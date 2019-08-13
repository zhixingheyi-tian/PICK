/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.oap.filecache

import java.io.File
import java.util.concurrent.atomic.AtomicLong

import org.apache.hadoop.fs.FSDataInputStream

import org.apache.spark.SparkEnv
import org.apache.spark.internal.Logging
import org.apache.spark.memory.MemoryMode
import org.apache.spark.sql.execution.datasources.OapException
import org.apache.spark.sql.execution.datasources.oap.utils.PersistentMemoryConfigUtils
import org.apache.spark.sql.internal.oap.OapConf
import org.apache.spark.storage.{BlockManager, TestBlockId}
import org.apache.spark.unsafe.{PersistentMemoryPlatform, Platform}
import org.apache.spark.util.Utils

object CacheEnum extends Enumeration {
  type CacheEnum = Value
  val INDEX, DATA, GENERAL = Value
}

/**
 * A memory block holder which contains the base object, offset, length and occupiedSize. For DRAM
 * type of memory, the field of occupiedSize should be same as the block length. For Intel Optane
 * DC persistent memory, the occupied size is typically larger than length because the
 * heap management is based on the jemalloc.
 * @param baseObject null for OFF_HEAP or Intel Optane DC persistent memory
 * @param length the requested size of the block
 * @param occupiedSize the actual occupied size of the memory block
 */
case class MemoryBlockHolder(
    var cacheType: CacheEnum.CacheEnum,
    baseObject: AnyRef,
    baseOffset: Long,
    length: Long,
    occupiedSize: Long)

private[sql] abstract class MemoryManager {
  /**
   * Return the total memory used until now.
   */
  def memoryUsed: Long

  /**
   * The memory size used for index cache.
   */
  def totalCacheMemory: Long = indexCacheMemory + dataCacheMemory

  /**
   * The memory size used for index cache.
   */
  def indexCacheMemory: Long

  /**
   * The memory size used for data cache.
   */
  def dataCacheMemory: Long

  /**
   * The memory size used for cache guardian.
   */
  def cacheGuardianMemory: Long

  /**
   * Allocate a block of memory with given size. The actual occupied size of memory maybe different
   * with the requested size, that's depends on the underlying implementation.
   * @param size requested size of memory block
   */
  private[filecache] def allocate(size: Long): MemoryBlockHolder
  private[filecache] def free(block: MemoryBlockHolder): Unit

  @inline protected def toFiberCache(bytes: Array[Byte]): FiberCache = {
    val block = allocate(bytes.length)
    Platform.copyMemory(
      bytes,
      Platform.BYTE_ARRAY_OFFSET,
      block.baseObject,
      block.baseOffset,
      bytes.length)
    FiberCache(block)
  }

  /**
   * Used by IndexFile
   */
  def toIndexFiberCache(in: FSDataInputStream, position: Long, length: Int): FiberCache = {
    val bytes = new Array[Byte](length)
    in.readFully(position, bytes)
    toFiberCache(bytes)
  }

  /**
   * Used by IndexFile. For decompressed data
   */
  def toIndexFiberCache(bytes: Array[Byte]): FiberCache = {
    toFiberCache(bytes)
  }

  /**
   * Used by OapDataFile since we need to parse the raw data in on-heap memory before put it into
   * off-heap memory
   */
  def toDataFiberCache(bytes: Array[Byte]): FiberCache = {
    toFiberCache(bytes)
  }

  def getEmptyDataFiberCache(length: Long): FiberCache = {
    FiberCache(allocate(length))
  }
  def stop(): Unit = {}
}

private[sql] object MemoryManager {
  /**
   * Dummy block id to acquire memory from [[org.apache.spark.memory.MemoryManager]]
   *
   * NOTE: We do acquire some memory from Spark without adding a Block into[[BlockManager]]
   * It may cause consistent problem.
   * (i.e. total size of blocks in BlockManager is not equal to Spark used storage memory)
   *
   * TODO should avoid using [[TestBlockId]]
   */
  private[filecache] val DUMMY_BLOCK_ID = TestBlockId("oap_memory_request_block")

  def apply(sparkEnv: SparkEnv): MemoryManager = {
    val conf = sparkEnv.conf
    val indexDataSeparationEnable = conf.getBoolean(
      OapConf.OAP_INDEX_DATA_SEPARATION_ENABLE.key,
      OapConf.OAP_INDEX_DATA_SEPARATION_ENABLE.defaultValue.get
    )
    val memoryManagerOpt =
      conf.get(OapConf.OAP_FIBERCACHE_MEMORY_MANAGER.key, "offheap").toLowerCase
    memoryManagerOpt match {
      case "offheap" => new OffHeapMemoryManager(sparkEnv)
      case "pm" => new PersistentMemoryManager(sparkEnv)
      case "mix" => if (indexDataSeparationEnable) {
        new MixMemoryManager(sparkEnv)
      } else {
        throw new UnsupportedOperationException("In order to enable MixMemoryManager," +
          "you need to set to spark.sql.oap.index.data.cache.separation.enable to true")
      }
      case _ => throw new UnsupportedOperationException(
        s"The memory manager: ${memoryManagerOpt} is not supported now")
    }
  }
}

/**
 * An memory manager which support allocate OFF_HEAP memory. It will acquire fixed amount of
 * memory from spark during initialization.
 */
private[filecache] class OffHeapMemoryManager(sparkEnv: SparkEnv)
  extends MemoryManager with Logging {

  private lazy val memoryManager = sparkEnv.memoryManager

  private lazy val oapMemory = {
    assert(memoryManager.maxOffHeapStorageMemory > 0, "Oap can't run without offHeap memory")
    val useOffHeapRatio = sparkEnv.conf.getDouble(
      OapConf.OAP_FIBERCACHE_USE_OFFHEAP_RATIO.key,
      OapConf.OAP_FIBERCACHE_USE_OFFHEAP_RATIO.defaultValue.get)
    logInfo(s"Oap use ${useOffHeapRatio * 100}% of 'spark.memory.offHeap.size' for fiber cache.")
    assert(useOffHeapRatio > 0 && useOffHeapRatio <1,
      "OapConf 'spark.sql.oap.fiberCache.use.offheap.ratio' must more than 0 and less than 1.")
    (memoryManager.maxOffHeapStorageMemory * useOffHeapRatio).toLong
  }

  // TODO: a config to control max memory size
  private val (_dataCacheMemory, _indexCacheMemory, _cacheGuardianMemory) = {
    if (memoryManager.acquireStorageMemory(
      MemoryManager.DUMMY_BLOCK_ID, oapMemory, MemoryMode.OFF_HEAP)) {
      val dataCacheRatio = sparkEnv.conf.getDouble(
        OapConf.OAP_DATAFIBER_USE_FIBERCACHE_RATIO.key,
        OapConf.OAP_DATAFIBER_USE_FIBERCACHE_RATIO.defaultValue.get)
      require(dataCacheRatio >= 0 && dataCacheRatio <= 1,
        "dataCacheRatio should be between 0 and 1")
      // TODO: make 0.9, 0.1 configurable
      ((oapMemory * 0.9 * dataCacheRatio).toLong,
        (oapMemory * 0.9 * (1 - dataCacheRatio)).toLong,
        (oapMemory * 0.1).toLong)
    } else {
      throw new OapException("Can't acquire memory from spark Memory Manager")
    }
  }

  // TODO: Atomic is really needed?
  private val _memoryUsed = new AtomicLong(0)

  override def memoryUsed: Long = _memoryUsed.get()

  override def dataCacheMemory: Long = _dataCacheMemory

  override def indexCacheMemory: Long = _indexCacheMemory

  override def cacheGuardianMemory: Long = _cacheGuardianMemory

  override private[filecache] def allocate(size: Long): MemoryBlockHolder = {
    val address = Platform.allocateMemory(size)
    _memoryUsed.getAndAdd(size)
    logDebug(s"request allocate $size memory, actual occupied size: " +
      s"${size}, used: $memoryUsed")
    // For OFF_HEAP, occupied size also equal to the size.
    MemoryBlockHolder(CacheEnum.GENERAL, null, address, size, size)
  }

  override private[filecache] def free(block: MemoryBlockHolder): Unit = {
    assert(block.baseObject == null)
    Platform.freeMemory(block.baseOffset)
    _memoryUsed.getAndAdd(-block.occupiedSize)
    logDebug(s"freed ${block.occupiedSize} memory, used: $memoryUsed")
  }

  override def stop(): Unit = {
    memoryManager.releaseStorageMemory(oapMemory, MemoryMode.OFF_HEAP)
  }
}

/**
 * A memory manager which supports allocate/free volatile memory from Intel Optane DC
 * persistent memory.
 */
private[filecache] class PersistentMemoryManager(sparkEnv: SparkEnv)
  extends MemoryManager with Logging {

  private val (_dataCacheMemory, _indexCacheMemory, _cacheGuardianMemory) = init()

  private val _memoryUsed = new AtomicLong(0)

  private def init(): (Long, Long, Long) = {
    val conf = sparkEnv.conf
    // The NUMA id should be set when the executor process start up. However, Spark don't
    // support NUMA binding currently.
    var numaId = conf.getInt("spark.executor.numa.id", -1)
    val executorId = sparkEnv.executorId.toInt
    val map = PersistentMemoryConfigUtils.parseConfig(conf)
    if (numaId == -1) {
      logWarning(s"Executor ${executorId} is not bind with NUMA. It would be better to bind " +
        s"executor with NUMA when cache data to Intel Optane DC persistent memory.")
      // Just round the executorId to the total NUMA number.
      // TODO: improve here
      numaId = executorId % PersistentMemoryConfigUtils.totalNumaNode(conf)
    }

    val initialPath = map.get(numaId).get
    val initialSizeStr = conf.get(OapConf.OAP_FIBERCACHE_PERSISTENT_MEMORY_INITIAL_SIZE).trim
    val initialSize = Utils.byteStringAsBytes(initialSizeStr)
    val reservedSizeStr = conf.get(OapConf.OAP_FIBERCACHE_PERSISTENT_MEMORY_RESERVED_SIZE).trim
    val reservedSize = Utils.byteStringAsBytes(reservedSizeStr)
    val fullPath = Utils.createTempDir(initialPath + File.separator + executorId)
    PersistentMemoryPlatform.initialize(fullPath.getCanonicalPath, initialSize)
    logInfo(s"Initialize Intel Optane DC persistent memory successfully, numaId: ${numaId}, " +
      s"initial path: ${fullPath.getCanonicalPath}, initial size: ${initialSize}, reserved size: " +
      s"${reservedSize}")
    require(reservedSize >= 0 && reservedSize < initialSize, s"Reserved size(${reservedSize}) " +
      s"should be larger than zero and smaller than initial size(${initialSize})")
    val totalUsableSize = initialSize - reservedSize

    val dataCacheRatio = sparkEnv.conf.getDouble(
      OapConf.OAP_DATAFIBER_USE_FIBERCACHE_RATIO.key,
      OapConf.OAP_DATAFIBER_USE_FIBERCACHE_RATIO.defaultValue.get)
    require(dataCacheRatio >= 0 && dataCacheRatio <= 1,
      "dataCacheRatio should be between 0 and 1")

    ((totalUsableSize * 0.9 * dataCacheRatio).toLong,
      (totalUsableSize * 0.9 * (1 - dataCacheRatio)).toLong,
      (totalUsableSize * 0.1).toLong)
  }

  override def memoryUsed: Long = _memoryUsed.get()

  override def dataCacheMemory: Long = _dataCacheMemory

  override def indexCacheMemory: Long = _indexCacheMemory

  override def cacheGuardianMemory: Long = _cacheGuardianMemory

  override private[filecache] def allocate(size: Long): MemoryBlockHolder = {
    val address = PersistentMemoryPlatform.allocateVolatileMemory(size)
    val occupiedSize = PersistentMemoryPlatform.getOccupiedSize(address)
    _memoryUsed.getAndAdd(occupiedSize)
    logDebug(s"request allocate $size memory, actual occupied size: " +
      s"${occupiedSize}, used: $memoryUsed")
    MemoryBlockHolder(CacheEnum.GENERAL, null, address, size, occupiedSize)
  }

  override private[filecache] def free(block: MemoryBlockHolder): Unit = {
    assert(block.baseObject == null)
    PersistentMemoryPlatform.freeMemory(block.baseOffset)
    _memoryUsed.getAndAdd(-block.occupiedSize)
    logDebug(s"freed ${block.occupiedSize} memory, used: $memoryUsed")
  }
}

/**
 * A memory manager contains different sub memory manager for index and data.
 */
private[filecache] class MixMemoryManager(sparkEnv: SparkEnv)
  extends MemoryManager with Logging {

  private val (indexMemoryManager, dataMemoryManager) = init()

  private val _memoryUsed = new AtomicLong(0)

  override def memoryUsed: Long = _memoryUsed.get()

  private def init(): (MemoryManager, MemoryManager) = {
    val indexManager =
      sparkEnv.conf.get(
        OapConf.OAP_MIX_INDEX_MEMORY_MANAGER.key,
        OapConf.OAP_MIX_INDEX_MEMORY_MANAGER.defaultValue.get)
        .toLowerCase match {
        case "offheap" => new OffHeapMemoryManager(sparkEnv)
        case "pm" => new PersistentMemoryManager(sparkEnv)
        case other => throw new UnsupportedOperationException(
          s"The memory manager: ${other} is not supported now")
      }

    val dataManager =
      sparkEnv.conf.get(
        OapConf.OAP_MIX_DATA_MEMORY_MANAGER.key,
        OapConf.OAP_MIX_DATA_MEMORY_MANAGER.defaultValue.get)
        .toLowerCase match {
        case "offheap" => new OffHeapMemoryManager(sparkEnv)
        case "pm" => new PersistentMemoryManager(sparkEnv)
        case other => throw new UnsupportedOperationException(
          s"The memory manager: ${other} is not supported now")
      }

    if (indexManager.getClass.equals(dataManager.getClass)) {
      throw new UnsupportedOperationException(
        "Index Cache type and Data Cache type need to be different in Mixed mode")
    }

    (indexManager, dataManager)
  }

  override private[filecache] def allocate(size: Long): MemoryBlockHolder = {
    throw new UnsupportedOperationException("Can't do direct allocate for MixedMemoryManager")
  }

  override def toIndexFiberCache(in: FSDataInputStream, position: Long, length: Int): FiberCache = {
    indexMemoryManager.toIndexFiberCache(in, position, length).setMemBlockCacheType(CacheEnum.INDEX)
  }

  override def toIndexFiberCache(bytes: Array[Byte]): FiberCache = {
    indexMemoryManager.toIndexFiberCache(bytes).setMemBlockCacheType(CacheEnum.INDEX)
  }

  override def toDataFiberCache(bytes: Array[Byte]): FiberCache = {
    dataMemoryManager.toDataFiberCache(bytes).setMemBlockCacheType(CacheEnum.DATA)
  }

  override def getEmptyDataFiberCache(length: Long): FiberCache = {
    dataMemoryManager.getEmptyDataFiberCache(length).setMemBlockCacheType(CacheEnum.DATA)
  }

  override private[filecache] def free(block: MemoryBlockHolder): Unit = {
    block.cacheType match {
      case CacheEnum.INDEX => indexMemoryManager.free(block)
      case CacheEnum.DATA => dataMemoryManager.free(block)
      case _ => throw new UnsupportedOperationException("Unsupported Memory Block type in Mix Mode")
    }
  }

  override def stop(): Unit = {
    indexMemoryManager.stop()
    dataMemoryManager.stop()
  }

  override def dataCacheMemory: Long = dataMemoryManager.totalCacheMemory

  override def indexCacheMemory: Long = indexMemoryManager.totalCacheMemory

  override def cacheGuardianMemory: Long =
    indexMemoryManager.cacheGuardianMemory + dataMemoryManager.cacheGuardianMemory
}

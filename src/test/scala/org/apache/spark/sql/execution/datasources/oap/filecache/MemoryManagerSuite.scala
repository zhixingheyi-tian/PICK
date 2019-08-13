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

import scala.util.Random

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FSDataInputStream, Path}

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.datasources.oap.utils.NonNullKeyWriter
import org.apache.spark.sql.oap.OapRuntime
import org.apache.spark.sql.test.oap.SharedOapContext
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark.util.{ByteBufferOutputStream, Utils}

class MemoryManagerSuite extends SharedOapContext {

  private var random: Random = _
  private var values: Seq[Any] = _
  private var fiberCache: FiberCache = _
  private lazy val memoryManager = OapRuntime.getOrCreate.memoryManager

  protected override def beforeAll(): Unit = {
    super.beforeAll()

    random = new Random(0)
    values = {
      val booleans: Seq[Boolean] = Seq(true, false)
      val bytes: Seq[Byte] = Seq(Byte.MinValue, 0, 10, 30, Byte.MaxValue)
      val shorts: Seq[Short] = Seq(Short.MinValue, -100, 0, 10, 200, Short.MaxValue)
      val ints: Seq[Int] = Seq(Int.MinValue, -100, 0, 100, 12346, Int.MaxValue)
      val longs: Seq[Long] = Seq(Long.MinValue, -10000, 0, 20, Long.MaxValue)
      val floats: Seq[Float] = Seq(Float.MinValue, Float.MinPositiveValue, Float.MaxValue)
      val doubles: Seq[Double] = Seq(Double.MinValue, Double.MinPositiveValue, Double.MaxValue)
      val strings: Seq[UTF8String] =
        Seq("", "test", "b plus tree", "MemoryManagerSuite").map(UTF8String.fromString)
      val binaries: Seq[Array[Byte]] = (0 until 20 by 5).map{ size =>
        val buf = new Array[Byte](size)
        random.nextBytes(buf)
        buf
      }
      booleans ++ bytes ++ shorts ++ ints ++ longs ++
        floats ++ doubles ++ strings ++ binaries ++ Nil
    }
    random.shuffle(values)

    def toSparkDataType(any: Any): DataType = {
      any match {
        case _: Boolean => BooleanType
        case _: Short => ShortType
        case _: Byte => ByteType
        case _: Int => IntegerType
        case _: Long => LongType
        case _: Float => FloatType
        case _: Double => DoubleType
        case _: UTF8String => StringType
        case _: Array[Byte] => BinaryType
      }
    }

    fiberCache = {
      val buf = new ByteBufferOutputStream()
      val schema = StructType(values.zipWithIndex.map {
        case (v, i) => StructField(s"col$i", toSparkDataType(v))
      })
      val nnkw = new NonNullKeyWriter(schema)
      nnkw.writeKey(buf, InternalRow.fromSeq(values))
      val fiberId = TestDataFiberId(null, "Test fiber for MemoryManagerSuite#0")
      val cache = memoryManager.toDataFiberCache(buf.toByteArray)
      cache.fiberId = fiberId
      cache
    }
  }

  protected override def afterAll(): Unit = {
    super.afterAll()
    fiberCache.realDispose()
  }

  // Override afterEach because we don't want to check open streams
  override def beforeEach(): Unit = {}
  override def afterEach(): Unit = {}

  test("test data in IndexFiberCache") {
    // TODO: find a nice way to create FSDataInputStream
    def createInputStreamFromBytes(bytes: Array[Byte]): FSDataInputStream = {
      val tempDir = Utils.createTempDir().getAbsolutePath
      val fileName = new Path(tempDir, "temp")
      val fs = fileName.getFileSystem(new Configuration())
      val writer = fs.create(fileName)
      writer.write(bytes)
      writer.close()
      fs.open(fileName)
    }
    val bytes = new Array[Byte](10240)
    random.nextBytes(bytes)
    val is = createInputStreamFromBytes(bytes)
    val indexFiberCache = memoryManager.toIndexFiberCache(is, 0, 10240)
    val fiberId = TestDataFiberId(null, "Test fiber for MemoryManagerSuite#1")
    indexFiberCache.fiberId = fiberId
    assert(bytes === indexFiberCache.toArray)
    indexFiberCache.realDispose()
  }

  test("test data in DataFiberCache") {
    val bytes = new Array[Byte](10240)
    random.nextBytes(bytes)
    val dataFiberCache = memoryManager.toDataFiberCache(bytes)
    val fiberId = TestDataFiberId(null, "Test fiber for MemoryManagerSuite#2")
    dataFiberCache.fiberId = fiberId
    assert(bytes === dataFiberCache.toArray)
    dataFiberCache.realDispose()
  }

  test("test FiberCache readInt") {
    var offset = 0
    values.foreach {
      case bool: Boolean =>
        assert(fiberCache.getBoolean(offset) === bool)
        offset += BooleanType.defaultSize
      case short: Short =>
        assert(fiberCache.getShort(offset) === short)
        offset += ShortType.defaultSize
      case byte: Byte =>
        assert(fiberCache.getByte(offset) === byte)
        offset += ByteType.defaultSize
      case int: Int =>
        assert(fiberCache.getInt(offset) === int)
        offset += IntegerType.defaultSize
      case long: Long =>
        assert(fiberCache.getLong(offset) === long)
        offset += LongType.defaultSize
      case float: Float =>
        assert(fiberCache.getFloat(offset) === float)
        offset += FloatType.defaultSize
      case double: Double =>
        assert(fiberCache.getDouble(offset) === double)
        offset += DoubleType.defaultSize
      case string: UTF8String =>
        val length = fiberCache.getInt(offset)
        assert(length === string.numBytes())
        offset += IntegerType.defaultSize
        assert(fiberCache.getUTF8String(offset, length) === string)
        offset += length
      case bytes: Array[Byte] =>
        val length = fiberCache.getInt(offset)
        assert(length === bytes.length)
        offset += IntegerType.defaultSize
        assert(fiberCache.getBytes(offset, length) === bytes)
        offset += length
    }
  }
}

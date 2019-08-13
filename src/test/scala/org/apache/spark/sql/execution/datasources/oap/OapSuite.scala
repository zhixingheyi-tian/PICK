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

package org.apache.spark.sql.execution.datasources.oap

import java.io.File

import org.apache.hadoop.fs.Path
import org.scalatest.BeforeAndAfter

import org.apache.spark.sql._
import org.apache.spark.sql.execution.datasources.oap.index.{IndexContext, ScannerBuilder}
import org.apache.spark.sql.execution.datasources.oap.io.{OapDataReaderV1, OapIndexInfo, OapIndexInfoStatus}
import org.apache.spark.sql.execution.datasources.oap.utils.OapIndexInfoStatusSerDe
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.internal.oap.OapConf
import org.apache.spark.sql.oap.OapRuntime
import org.apache.spark.sql.oap.listener.SparkListenerOapIndexInfoUpdate
import org.apache.spark.sql.sources._
import org.apache.spark.sql.test.oap.{SharedOapContext, TestIndex}
import org.apache.spark.sql.types.{IntegerType, StringType, StructType}
import org.apache.spark.util.Utils

class OapSuite extends QueryTest with SharedOapContext with BeforeAndAfter {
  import testImplicits._
  private var path: File = _
  private var parquetPath: File = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    path = Utils.createTempDir()
    path.delete()
    parquetPath = Utils.createTempDir()
    parquetPath.delete()

    val df = sparkContext.parallelize(1 to 100, 3)
      .map(i => (i, i + 100, s"this is row $i"))
      .toDF("a", "b", "c")

    df.write.format("oap").mode(SaveMode.Overwrite).save(path.getAbsolutePath)
    df.write.format("parquet").mode(SaveMode.Overwrite).save(parquetPath.getAbsolutePath)
  }

  override def afterAll(): Unit = {
    try {
      Utils.deleteRecursively(path)
    } finally {
      super.afterAll()
    }
  }

  // Override afterEach because we don't want to check open streams
  override def beforeEach(): Unit = {}
  override def afterEach(): Unit = {}

  test("reading oap file") {
    verifyFrame(sqlContext.read.format("oap").load(path.getAbsolutePath))
  }

  test("No Lease Exception on Parquet File Format in Index Building (#243)") {
    val df = sqlContext.read.format("parquet").load(parquetPath.getAbsolutePath)
    df.createOrReplaceTempView("parquet_table")
    val defaultMaxBytes = sqlContext.conf.getConf(SQLConf.FILES_MAX_PARTITION_BYTES)
    val maxPartitionBytes = 100L
    sqlContext.conf.setConf(SQLConf.FILES_MAX_PARTITION_BYTES, maxPartitionBytes)
    val numTasks = sql("select * from parquet_table").queryExecution.toRdd.partitions.length
    withIndex(TestIndex("parquet_table", "parquet_idx")) {
      sql("create oindex parquet_idx on parquet_table (a)")
      assert(numTasks == parquetPath.listFiles().filter(_.getName.endsWith(".parquet"))
        .map(f => Math.ceil(f.length().toDouble / maxPartitionBytes).toInt).sum)
      sqlContext.conf.setConf(SQLConf.FILES_MAX_PARTITION_BYTES, defaultMaxBytes)
    }
  }

  test("Add the corresponding compression type for the OAP data file name if any") {
    // Case insensitive.
    Seq(
      "GZIP",
      "Gzip",
      "SNAPPY",
      "snappy",
      "LZO",
      "lZo",
      "UNCOMPRESSED",
      "UnCompressed").foreach { codec =>
      sqlContext.conf.setConfString(OapConf.OAP_COMPRESSION.key, codec)
      val df = sqlContext.read.format("oap").load(path.getAbsolutePath)
      Utils.deleteRecursively(path)
      df.write.format("oap").mode(SaveMode.Overwrite).save(path.getAbsolutePath)
      val compressionType =
        sqlContext.conf.getConfString(OapConf.OAP_COMPRESSION.key).toLowerCase
      val fileNameIterator = path.listFiles()
      for (fileName <- fileNameIterator) {
        if (fileName.toString.endsWith(OapFileFormat.OAP_DATA_EXTENSION)) {
          // If the OAP data file is uncompressed, keep the original file name.
          if (!codec.toUpperCase.matches("UNCOMPRESSED")) {
            assert(fileName.toString.contains(compressionType))
          } else {
            assert(!fileName.toString.contains(compressionType))
          }
        }
      }
    }
    // Restore compression type back to default.
    sqlContext.conf.setConfString(
      OapConf.OAP_COMPRESSION.key, OapConf.OAP_COMPRESSION.defaultValueString)
  }

  test("Enable/disable using OAP index after the index is created already") {
    val dir = Utils.createTempDir()
    dir.delete()
    val data = sparkContext.parallelize(1 to 100, 1)
      .map(i => (i, s"this is row $i"))
    data.toDF("a", "b").write.format("oap").mode(SaveMode.Overwrite).save(dir.getAbsolutePath)
    val files = dir.listFiles
    var oapDataFile: File = null
    var oapMetaFile: File = null
    files.foreach { fileName =>
      if (fileName.toString.endsWith(OapFileFormat.OAP_DATA_EXTENSION)) {
        oapDataFile = fileName
      }
      if (fileName.toString.endsWith(OapFileFormat.OAP_META_FILE)) {
        oapMetaFile = fileName
      }
    }
    val df = sqlContext.read.format("oap").load(dir.getAbsolutePath)
    df.createOrReplaceTempView("oap_table")
    withIndex(TestIndex("oap_table", "oap_idx")) {
      sql("create oindex oap_idx on oap_table (a)")
      val conf = configuration
      val filePath = oapDataFile.toString
      val metaPath = new Path(oapMetaFile.toString)
      val dataSourceMeta = DataSourceMeta.initialize(metaPath, conf)
      val requiredIds = Array(0, 1)
      // No index scanner is used.
      val readerNoIndex = new OapDataReaderV1(filePath, dataSourceMeta, StructType(Seq()),
        StructType(Seq()), None, requiredIds, None, OapRuntime.getOrCreate.oapMetricsManager, conf)
      val itNoIndex = readerNoIndex.initialize()
      assert(itNoIndex.size == 100)
      val ic = new IndexContext(dataSourceMeta)
      val filters: Array[Filter] = Array(
        And(GreaterThan("a", 9), LessThan("a", 14)))
      ScannerBuilder.build(filters, ic)
      val filterScanners = ic.getScanners
      val readerIndex = new OapDataReaderV1(filePath, dataSourceMeta, StructType(Seq()),
        StructType(Seq()), filterScanners, requiredIds, None,
        OapRuntime.getOrCreate.oapMetricsManager, conf)
      val itIndex = readerIndex.initialize()
      assert(itIndex.size == 4)
      conf.setBoolean(OapConf.OAP_ENABLE_OINDEX.key, false)
      val itSetIgnoreIndex = readerIndex.initialize()
      assert(itSetIgnoreIndex.size == 100)
      conf.setBoolean(OapConf.OAP_ENABLE_OINDEX.key, true)
      val itSetUseIndex = readerIndex.initialize()
      assert(itSetUseIndex.size == 4)
      dir.delete()
    }
  }

  test("OapIndexInfo status and update") {
    val path1 = "partitionFile1"
    val useIndex1 = true
    val path2 = "partitionFile2"
    val useIndex2 = false
    val rawData1 = OapIndexInfoStatus(path1, useIndex1)
    val rawData2 = OapIndexInfoStatus(path2, useIndex2)
    OapIndexInfo.partitionOapIndex.clear()
    OapIndexInfo.partitionOapIndex.put(path1, useIndex1)
    OapIndexInfo.partitionOapIndex.put(path2, useIndex2)
    val indexInfoStatusSerializeStr = OapIndexInfo.status
    assert(Seq(Seq(rawData1, rawData2), Seq(rawData2, rawData1)).map(
      OapIndexInfoStatusSerDe.serialize).contains(indexInfoStatusSerializeStr))
    val host = "host1"
    val executorId = "executorId1"
    val oapIndexInfo =
      SparkListenerOapIndexInfoUpdate(host, executorId, indexInfoStatusSerializeStr)
    OapIndexInfo.update(oapIndexInfo)
    assert(oapIndexInfo.hostName == host)
    assert(oapIndexInfo.executorId == executorId)
    assert(oapIndexInfo.oapIndexInfo == indexInfoStatusSerializeStr)
  }

  /** Verifies data and schema. */
  private def verifyFrame(df: DataFrame): Unit = {
    // schema
    assert(df.schema == new StructType()
      .add("a", IntegerType).add("b", IntegerType).add("c", StringType))

    // verify content
    val data = df.collect().sortBy(_.getInt(0)) // sort locally
    assert(data.length == 100)
    assert(data(0) == Row(1, 101, "this is row 1"))
    assert(data(1) == Row(2, 102, "this is row 2"))
    assert(data(99) == Row(100, 200, "this is row 100"))
  }
}

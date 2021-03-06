/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.runtime.stream.table

import java.lang.{Boolean => JBool, Integer => JInt, Long => JLong}

import org.apache.calcite.runtime.SqlFunctions.{internalToTimestamp => toTimestamp}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.{GenericTypeInfo, RowTypeInfo}
import org.apache.flink.api.scala._
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.datastream.DataStream
import org.apache.flink.streaming.api.environment.{StreamExecutionEnvironment => JExecEnv}
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.streaming.util.StreamingMultipleProgramsTestBase
import org.apache.flink.table.api.{TableEnvironment, TableException, TableSchema, Types}
import org.apache.flink.table.api.scala._
import org.apache.flink.table.runtime.utils.{CommonTestData, StreamITCase}
import org.apache.flink.table.sources.StreamTableSource
import org.apache.flink.table.utils._
import org.apache.flink.types.Row
import org.junit.Assert._
import org.junit.Test

import scala.collection.JavaConverters._
import scala.collection.mutable

class TableSourceITCase extends StreamingMultipleProgramsTestBase {

  @Test(expected = classOf[TableException])
  def testInvalidDatastreamType(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)

    val tableSource = new StreamTableSource[Row]() {
      private val fieldNames: Array[String] = Array("name", "id", "value")
      private val fieldTypes: Array[TypeInformation[_]] = Array(Types.STRING, Types.LONG, Types.INT)
        .asInstanceOf[Array[TypeInformation[_]]]

      override def getDataStream(execEnv: JExecEnv): DataStream[Row] = {
        val data = List(Row.of("Mary", new JLong(1L), new JInt(1))).asJava
        // return DataStream[Row] with GenericTypeInfo
        execEnv.fromCollection(data, new GenericTypeInfo[Row](classOf[Row]))
      }
      override def getReturnType: TypeInformation[Row] = new RowTypeInfo(fieldTypes, fieldNames)
      override def getTableSchema: TableSchema = new TableSchema(fieldNames, fieldTypes)
    }
    tEnv.registerTableSource("T", tableSource)

    tEnv.scan("T")
      .select('value, 'name)
      .addSink(new StreamITCase.StringSink[Row])
    env.execute()

    // test should fail because type info of returned DataStream does not match type return type
    // info.
  }

  @Test
  def testCsvTableSource(): Unit = {

    val csvTable = CommonTestData.getCsvTableSource
    StreamITCase.testResults = mutable.MutableList()

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)

    tEnv.registerTableSource("csvTable", csvTable)
    tEnv.scan("csvTable")
      .where('id > 4)
      .select('last, 'score * 2)
      .toAppendStream[Row]
      .addSink(new StreamITCase.StringSink[Row])

    env.execute()

    val expected = Seq(
      "Williams,69.0",
      "Miller,13.56",
      "Smith,180.2",
      "Williams,4.68")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testCsvTableSourceWithFilterable(): Unit = {
    StreamITCase.testResults = mutable.MutableList()
    val tableName = "MyTable"
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    tEnv.registerTableSource(tableName, TestFilterableTableSource())
    tEnv.scan(tableName)
      .where("amount > 4 && price < 9")
      .select("id, name")
      .addSink(new StreamITCase.StringSink[Row])

    env.execute()

    val expected = Seq("5,Record_5", "6,Record_6", "7,Record_7", "8,Record_8")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testRowtimeRowTableSource(): Unit = {
    StreamITCase.testResults = mutable.MutableList()
    val tableName = "MyTable"
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    val tEnv = TableEnvironment.getTableEnvironment(env)

    val data = Seq(
      Row.of("Mary", new JLong(1L), new JInt(10)),
      Row.of("Bob", new JLong(2L), new JInt(20)),
      Row.of("Mary", new JLong(2L), new JInt(30)),
      Row.of("Liz", new JLong(2001L), new JInt(40)))

    val fieldNames = Array("name", "rtime", "amount")
    val schema = new TableSchema(fieldNames, Array(Types.STRING, Types.SQL_TIMESTAMP, Types.INT))
    val rowType = new RowTypeInfo(
      Array(Types.STRING, Types.LONG, Types.INT).asInstanceOf[Array[TypeInformation[_]]],
      fieldNames)

    val tableSource = new TestTableSourceWithTime(schema, rowType, data, "rtime", null)
    tEnv.registerTableSource(tableName, tableSource)

    tEnv.scan(tableName)
      .window(Tumble over 1.second on 'rtime as 'w)
      .groupBy('name, 'w)
      .select('name, 'w.start, 'amount.sum)
      .addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = Seq(
      "Mary,1970-01-01 00:00:00.0,40",
      "Bob,1970-01-01 00:00:00.0,20",
      "Liz,1970-01-01 00:00:02.0,40")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testProctimeRowTableSource(): Unit = {
    StreamITCase.testResults = mutable.MutableList()
    val tableName = "MyTable"
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    val tEnv = TableEnvironment.getTableEnvironment(env)

    val data = Seq(
      Row.of("Mary", new JLong(1L), new JInt(10)),
      Row.of("Bob", new JLong(2L), new JInt(20)),
      Row.of("Mary", new JLong(2L), new JInt(30)),
      Row.of("Liz", new JLong(2001L), new JInt(40)))

    val fieldNames = Array("name", "rtime", "amount")
    val schema = new TableSchema(
      fieldNames :+ "ptime",
      Array(Types.STRING, Types.LONG, Types.INT, Types.SQL_TIMESTAMP))
    val rowType = new RowTypeInfo(
      Array(Types.STRING, Types.LONG, Types.INT).asInstanceOf[Array[TypeInformation[_]]],
      fieldNames)

    val tableSource = new TestTableSourceWithTime(schema, rowType, data, null, "ptime")
    tEnv.registerTableSource(tableName, tableSource)

    tEnv.scan(tableName)
      .where('ptime.cast(Types.LONG) > 0L)
      .select('name, 'amount)
      .addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = Seq(
      "Mary,10",
      "Bob,20",
      "Mary,30",
      "Liz,40")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testRowtimeProctimeRowTableSource(): Unit = {
    StreamITCase.testResults = mutable.MutableList()
    val tableName = "MyTable"
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    val tEnv = TableEnvironment.getTableEnvironment(env)

    val data = Seq(
      Row.of("Mary", new JLong(1L), new JInt(10)),
      Row.of("Bob", new JLong(2L), new JInt(20)),
      Row.of("Mary", new JLong(2L), new JInt(30)),
      Row.of("Liz", new JLong(2001L), new JInt(40)))

    val fieldNames = Array("name", "rtime", "amount")
    val schema = new TableSchema(
      fieldNames :+ "ptime",
      Array(Types.STRING, Types.SQL_TIMESTAMP, Types.INT, Types.SQL_TIMESTAMP))
    val rowType = new RowTypeInfo(
      Array(Types.STRING, Types.LONG, Types.INT).asInstanceOf[Array[TypeInformation[_]]],
      fieldNames)

    val tableSource = new TestTableSourceWithTime(schema, rowType, data, "rtime", "ptime")
    tEnv.registerTableSource(tableName, tableSource)

    tEnv.scan(tableName)
      .window(Tumble over 1.second on 'rtime as 'w)
      .groupBy('name, 'w)
      .select('name, 'w.start, 'amount.sum)
      .addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = Seq(
      "Mary,1970-01-01 00:00:00.0,40",
      "Bob,1970-01-01 00:00:00.0,20",
      "Liz,1970-01-01 00:00:02.0,40")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testRowtimeAsTimestampRowTableSource(): Unit = {
    StreamITCase.testResults = mutable.MutableList()
    val tableName = "MyTable"
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    val tEnv = TableEnvironment.getTableEnvironment(env)

    val data = Seq(
      Row.of("Mary", toTimestamp(1L), new JInt(10)),
      Row.of("Bob", toTimestamp(2L), new JInt(20)),
      Row.of("Mary", toTimestamp(2L), new JInt(30)),
      Row.of("Liz", toTimestamp(2001L), new JInt(40)))

    val fieldNames = Array("name", "rtime", "amount")
    val schema = new TableSchema(fieldNames, Array(Types.STRING, Types.SQL_TIMESTAMP, Types.INT))
    val rowType = new RowTypeInfo(
      Array(Types.STRING, Types.SQL_TIMESTAMP, Types.INT).asInstanceOf[Array[TypeInformation[_]]],
      fieldNames)

    val tableSource = new TestTableSourceWithTime(schema, rowType, data, "rtime", null)
    tEnv.registerTableSource(tableName, tableSource)

    tEnv.scan(tableName)
      .window(Tumble over 1.second on 'rtime as 'w)
      .groupBy('name, 'w)
      .select('name, 'w.start, 'amount.sum)
      .addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = Seq(
      "Mary,1970-01-01 00:00:00.0,40",
      "Bob,1970-01-01 00:00:00.0,20",
      "Liz,1970-01-01 00:00:02.0,40")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testRowtimeLongTableSource(): Unit = {
    StreamITCase.testResults = mutable.MutableList()
    val tableName = "MyTable"
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    val tEnv = TableEnvironment.getTableEnvironment(env)

    val data = Seq(new JLong(1L), new JLong(2L), new JLong(2L), new JLong(2001L), new JLong(4001L))

    val schema = new TableSchema(Array("rtime"), Array(Types.SQL_TIMESTAMP))
    val returnType = Types.LONG

    val tableSource = new TestTableSourceWithTime(schema, returnType, data, "rtime", null)
    tEnv.registerTableSource(tableName, tableSource)

    tEnv.scan(tableName)
      .window(Tumble over 1.second on 'rtime as 'w)
      .groupBy('w)
      .select('w.start, 1.count)
      .addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = Seq(
      "1970-01-01 00:00:00.0,3",
      "1970-01-01 00:00:02.0,1",
      "1970-01-01 00:00:04.0,1")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testProctimeStringTableSource(): Unit = {
    StreamITCase.testResults = mutable.MutableList()
    val tableName = "MyTable"
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    val tEnv = TableEnvironment.getTableEnvironment(env)

    val data = Seq("Mary", "Peter", "Bob", "Liz")

    val schema = new TableSchema(Array("name", "ptime"), Array(Types.STRING, Types.SQL_TIMESTAMP))
    val returnType = Types.STRING

    val tableSource = new TestTableSourceWithTime(schema, returnType, data, null, "ptime")
    tEnv.registerTableSource(tableName, tableSource)

    tEnv.scan(tableName)
      .where('ptime.cast(Types.LONG) > 1)
      .select('name)
      .addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = Seq("Mary", "Peter", "Bob", "Liz")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testRowtimeProctimeLongTableSource(): Unit = {
    StreamITCase.testResults = mutable.MutableList()
    val tableName = "MyTable"
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    val tEnv = TableEnvironment.getTableEnvironment(env)

    val data = Seq(new JLong(1L), new JLong(2L), new JLong(2L), new JLong(2001L), new JLong(4001L))

    val schema = new TableSchema(
      Array("rtime", "ptime"),
      Array(Types.SQL_TIMESTAMP, Types.SQL_TIMESTAMP))
    val returnType = Types.LONG

    val tableSource = new TestTableSourceWithTime(schema, returnType, data, "rtime", "ptime")
    tEnv.registerTableSource(tableName, tableSource)

    tEnv.scan(tableName)
      .where('ptime.cast(Types.LONG) > 1)
      .window(Tumble over 1.second on 'rtime as 'w)
      .groupBy('w)
      .select('w.start, 1.count)
      .addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = Seq(
      "1970-01-01 00:00:00.0,3",
      "1970-01-01 00:00:02.0,1",
      "1970-01-01 00:00:04.0,1")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testFieldMappingTableSource(): Unit = {
    StreamITCase.testResults = mutable.MutableList()
    val tableName = "MyTable"
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    val tEnv = TableEnvironment.getTableEnvironment(env)

    val data = Seq(
      Row.of("Mary", new JLong(1L), new JInt(10)),
      Row.of("Bob", new JLong(2L), new JInt(20)),
      Row.of("Mary", new JLong(2L), new JInt(30)),
      Row.of("Liz", new JLong(2001L), new JInt(40)))

    val schema = new TableSchema(
      Array("ptime", "amount", "name", "rtime"),
      Array(Types.SQL_TIMESTAMP, Types.INT, Types.STRING, Types.SQL_TIMESTAMP))
    val returnType = new RowTypeInfo(Types.STRING, Types.LONG, Types.INT)
    val mapping = Map("amount" -> "f2", "name" -> "f0", "rtime" -> "f1")

    val source = new TestTableSourceWithTime(schema, returnType, data, "rtime", "ptime", mapping)
    tEnv.registerTableSource(tableName, source)

    tEnv.scan(tableName)
      .window(Tumble over 1.second on 'rtime as 'w)
      .groupBy('name, 'w)
      .select('name, 'w.start, 'amount.sum)
      .addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = Seq(
      "Mary,1970-01-01 00:00:00.0,40",
      "Bob,1970-01-01 00:00:00.0,20",
      "Liz,1970-01-01 00:00:02.0,40")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testProjectWithoutRowtimeProctime(): Unit = {
    StreamITCase.testResults = mutable.MutableList()
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    val tEnv = TableEnvironment.getTableEnvironment(env)

    val data = Seq(
      Row.of(new JInt(1), "Mary", new JLong(10L), new JLong(1)),
      Row.of(new JInt(2), "Bob", new JLong(20L), new JLong(2)),
      Row.of(new JInt(3), "Mike", new JLong(30L), new JLong(2)),
      Row.of(new JInt(4), "Liz", new JLong(40L), new JLong(2001)))

    val tableSchema = new TableSchema(
      Array("id", "rtime", "val", "ptime", "name"),
      Array(Types.INT, Types.SQL_TIMESTAMP, Types.LONG, Types.SQL_TIMESTAMP, Types.STRING))
    val returnType = new RowTypeInfo(
      Array(Types.INT, Types.STRING, Types.LONG, Types.LONG)
        .asInstanceOf[Array[TypeInformation[_]]],
      Array("id", "name", "val", "rtime"))

    tEnv.registerTableSource(
      "T",
      new TestProjectableTableSource(tableSchema, returnType, data, "rtime", "ptime"))

    tEnv.scan("T")
      .select('name, 'val, 'id)
      .addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = Seq(
      "Mary,10,1",
      "Bob,20,2",
      "Mike,30,3",
      "Liz,40,4")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testProjectWithoutProctime(): Unit = {
    StreamITCase.testResults = mutable.MutableList()
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    val tEnv = TableEnvironment.getTableEnvironment(env)

    val data = Seq(
      Row.of(new JInt(1), "Mary", new JLong(10L), new JLong(1)),
      Row.of(new JInt(2), "Bob", new JLong(20L), new JLong(2)),
      Row.of(new JInt(3), "Mike", new JLong(30L), new JLong(2)),
      Row.of(new JInt(4), "Liz", new JLong(40L), new JLong(2001)))

    val tableSchema = new TableSchema(
      Array("id", "rtime", "val", "ptime", "name"),
      Array(Types.INT, Types.SQL_TIMESTAMP, Types.LONG, Types.SQL_TIMESTAMP, Types.STRING))
    val returnType = new RowTypeInfo(
      Array(Types.INT, Types.STRING, Types.LONG, Types.LONG)
        .asInstanceOf[Array[TypeInformation[_]]],
      Array("id", "name", "val", "rtime"))

    tEnv.registerTableSource(
      "T",
      new TestProjectableTableSource(tableSchema, returnType, data, "rtime", "ptime"))

    tEnv.scan("T")
      .select('rtime, 'name, 'id)
      .addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = Seq(
      "1970-01-01 00:00:00.001,Mary,1",
      "1970-01-01 00:00:00.002,Bob,2",
      "1970-01-01 00:00:00.002,Mike,3",
      "1970-01-01 00:00:02.001,Liz,4")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testProjectWithoutRowtime(): Unit = {
    StreamITCase.testResults = mutable.MutableList()
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    val tEnv = TableEnvironment.getTableEnvironment(env)

    val data = Seq(
      Row.of(new JInt(1), "Mary", new JLong(10L), new JLong(1)),
      Row.of(new JInt(2), "Bob", new JLong(20L), new JLong(2)),
      Row.of(new JInt(3), "Mike", new JLong(30L), new JLong(2)),
      Row.of(new JInt(4), "Liz", new JLong(40L), new JLong(2001)))

    val tableSchema = new TableSchema(
      Array("id", "rtime", "val", "ptime", "name"),
      Array(Types.INT, Types.SQL_TIMESTAMP, Types.LONG, Types.SQL_TIMESTAMP, Types.STRING))
    val returnType = new RowTypeInfo(
      Array(Types.INT, Types.STRING, Types.LONG, Types.LONG)
        .asInstanceOf[Array[TypeInformation[_]]],
      Array("id", "name", "val", "rtime"))

    tEnv.registerTableSource(
      "T",
      new TestProjectableTableSource(tableSchema, returnType, data, "rtime", "ptime"))

    tEnv.scan("T")
      .filter('ptime.cast(Types.LONG) > 0)
      .select('name, 'id)
      .addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = Seq(
      "Mary,1",
      "Bob,2",
      "Mike,3",
      "Liz,4")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  def testProjectOnlyProctime(): Unit = {
    StreamITCase.testResults = mutable.MutableList()
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    val tEnv = TableEnvironment.getTableEnvironment(env)

    val data = Seq(
      Row.of(new JInt(1), new JLong(1), new JLong(10L), "Mary"),
      Row.of(new JInt(2), new JLong(2L), new JLong(20L), "Bob"),
      Row.of(new JInt(3), new JLong(2L), new JLong(30L), "Mike"),
      Row.of(new JInt(4), new JLong(2001L), new JLong(30L), "Liz"))

    val tableSchema = new TableSchema(
      Array("id", "rtime", "val", "ptime", "name"),
      Array(Types.INT, Types.SQL_TIMESTAMP, Types.LONG, Types.SQL_TIMESTAMP, Types.STRING))
    val returnType = new RowTypeInfo(
      Array(Types.INT, Types.LONG, Types.LONG, Types.STRING)
        .asInstanceOf[Array[TypeInformation[_]]],
      Array("id", "rtime", "val", "name"))

    tEnv.registerTableSource(
      "T",
      new TestProjectableTableSource(tableSchema, returnType, data, "rtime", "ptime"))

    tEnv.scan("T")
      .select('ptime > 0)
      .select(1.count)
      .addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = Seq("4")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  def testProjectOnlyRowtime(): Unit = {
    StreamITCase.testResults = mutable.MutableList()
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    val tEnv = TableEnvironment.getTableEnvironment(env)

    val data = Seq(
      Row.of(new JInt(1), new JLong(1), new JLong(10L), "Mary"),
      Row.of(new JInt(2), new JLong(2L), new JLong(20L), "Bob"),
      Row.of(new JInt(3), new JLong(2L), new JLong(30L), "Mike"),
      Row.of(new JInt(4), new JLong(2001L), new JLong(30L), "Liz"))

    val tableSchema = new TableSchema(
      Array("id", "rtime", "val", "ptime", "name"),
      Array(Types.INT, Types.SQL_TIMESTAMP, Types.LONG, Types.SQL_TIMESTAMP, Types.STRING))
    val returnType = new RowTypeInfo(
      Array(Types.INT, Types.LONG, Types.LONG, Types.STRING)
        .asInstanceOf[Array[TypeInformation[_]]],
      Array("id", "rtime", "val", "name"))

    tEnv.registerTableSource(
      "T",
      new TestProjectableTableSource(tableSchema, returnType, data, "rtime", "ptime"))

    tEnv.scan("T")
      .select('rtime)
      .addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = Seq(
      "1970-01-01 00:00:00.001",
      "1970-01-01 00:00:00.002",
      "1970-01-01 00:00:00.002",
      "1970-01-01 00:00:02.001")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testProjectWithMapping(): Unit = {
    StreamITCase.testResults = mutable.MutableList()
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    val tEnv = TableEnvironment.getTableEnvironment(env)

    val data = Seq(
      Row.of(new JLong(1), new JInt(1), "Mary", new JLong(10)),
      Row.of(new JLong(2), new JInt(2), "Bob", new JLong(20)),
      Row.of(new JLong(2), new JInt(3), "Mike", new JLong(30)),
      Row.of(new JLong(2001), new JInt(4), "Liz", new JLong(40)))

    val tableSchema = new TableSchema(
      Array("id", "rtime", "val", "ptime", "name"),
      Array(Types.INT, Types.SQL_TIMESTAMP, Types.LONG, Types.SQL_TIMESTAMP, Types.STRING))
    val returnType = new RowTypeInfo(
      Array(Types.LONG, Types.INT, Types.STRING, Types.LONG)
        .asInstanceOf[Array[TypeInformation[_]]],
      Array("p-rtime", "p-id", "p-name", "p-val"))
    val mapping = Map("rtime" -> "p-rtime", "id" -> "p-id", "val" -> "p-val", "name" -> "p-name")

    tEnv.registerTableSource(
      "T",
      new TestProjectableTableSource(tableSchema, returnType, data, "rtime", "ptime", mapping))

    tEnv.scan("T")
      .select('name, 'rtime, 'val)
      .addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = Seq(
      "Mary,1970-01-01 00:00:00.001,10",
      "Bob,1970-01-01 00:00:00.002,20",
      "Mike,1970-01-01 00:00:00.002,30",
      "Liz,1970-01-01 00:00:02.001,40")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testNestedProject(): Unit = {

    StreamITCase.testResults = mutable.MutableList()
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    val tEnv = TableEnvironment.getTableEnvironment(env)

    val data = Seq(
      Row.of(new JLong(1),
        Row.of(
          Row.of("Sarah", new JInt(100)),
          Row.of(new JInt(1000), new JBool(true))
        ),
        Row.of("Peter", new JInt(10000)),
        "Mary"),
      Row.of(new JLong(2),
        Row.of(
          Row.of("Rob", new JInt(200)),
          Row.of(new JInt(2000), new JBool(false))
        ),
        Row.of("Lucy", new JInt(20000)),
        "Bob"),
      Row.of(new JLong(3),
        Row.of(
          Row.of("Mike", new JInt(300)),
          Row.of(new JInt(3000), new JBool(true))
        ),
        Row.of("Betty", new JInt(30000)),
        "Liz"))

    val nested1 = new RowTypeInfo(
      Array(Types.STRING, Types.INT).asInstanceOf[Array[TypeInformation[_]]],
      Array("name", "value")
    )
    val nested2 = new RowTypeInfo(
      Array(Types.INT, Types.BOOLEAN).asInstanceOf[Array[TypeInformation[_]]],
      Array("num", "flag")
    )
    val deepNested = new RowTypeInfo(
      Array(nested1, nested2).asInstanceOf[Array[TypeInformation[_]]],
      Array("nested1", "nested2")
    )
    val tableSchema = new TableSchema(
      Array("id", "deepNested", "nested", "name"),
      Array(Types.LONG, deepNested, nested1, Types.STRING))

    val returnType = new RowTypeInfo(
      Array(Types.LONG, deepNested, nested1, Types.STRING).asInstanceOf[Array[TypeInformation[_]]],
      Array("id", "deepNested", "nested", "name"))

    tEnv.registerTableSource(
      "T",
      new TestNestedProjectableTableSource(tableSchema, returnType, data))

    tEnv
      .scan("T")
      .select('id,
        'deepNested.get("nested1").get("name") as 'nestedName,
        'nested.get("value") as 'nestedValue,
        'deepNested.get("nested2").get("flag") as 'nestedFlag,
        'deepNested.get("nested2").get("num") as 'nestedNum)
      .addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = Seq(
      "1,Sarah,10000,true,1000",
      "2,Rob,20000,false,2000",
      "3,Mike,30000,true,3000")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }
}

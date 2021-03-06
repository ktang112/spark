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

package org.apache.spark.sql

import org.apache.spark.sql.Dsl._
import org.apache.spark.sql.types._

/* Implicits */
import org.apache.spark.sql.test.TestSQLContext._

import scala.language.postfixOps

class DataFrameSuite extends QueryTest {
  import org.apache.spark.sql.TestData._

  test("analysis error should be eagerly reported") {
    intercept[Exception] { testData.select('nonExistentName) }
    intercept[Exception] {
      testData.groupBy('key).agg(Map("nonExistentName" -> "sum"))
    }
    intercept[Exception] {
      testData.groupBy("nonExistentName").agg(Map("key" -> "sum"))
    }
    intercept[Exception] {
      testData.groupBy($"abcd").agg(Map("key" -> "sum"))
    }
  }

  test("table scan") {
    checkAnswer(
      testData,
      testData.collect().toSeq)
  }

  test("repartition") {
    checkAnswer(
      testData.select('key).repartition(10).select('key),
      testData.select('key).collect().toSeq)
  }

  test("agg") {
    checkAnswer(
      testData2.groupBy("a").agg($"a", sum($"b")),
      Seq(Row(1,3), Row(2,3), Row(3,3))
    )
    checkAnswer(
      testData2.groupBy("a").agg($"a", sum($"b").as("totB")).agg(sum('totB)),
      Row(9)
    )
    checkAnswer(
      testData2.agg(sum('b)),
      Row(9)
    )
  }

  test("convert $\"attribute name\" into unresolved attribute") {
    checkAnswer(
      testData.where($"key" === lit(1)).select($"value"),
      Row("1"))
  }

  test("convert Scala Symbol 'attrname into unresolved attribute") {
    checkAnswer(
      testData.where('key === lit(1)).select('value),
      Row("1"))
  }

  test("select *") {
    checkAnswer(
      testData.select($"*"),
      testData.collect().toSeq)
  }

  test("simple select") {
    checkAnswer(
      testData.where('key === lit(1)).select('value),
      Row("1"))
  }

  test("select with functions") {
    checkAnswer(
      testData.select(sum('value), avg('value), count(lit(1))),
      Row(5050.0, 50.5, 100))

    checkAnswer(
      testData2.select('a + 'b, 'a < 'b),
      Seq(
        Row(2, false),
        Row(3, true),
        Row(3, false),
        Row(4, false),
        Row(4, false),
        Row(5, false)))

    checkAnswer(
      testData2.select(sumDistinct('a)),
      Row(6))
  }

  test("global sorting") {
    checkAnswer(
      testData2.orderBy('a.asc, 'b.asc),
      Seq(Row(1,1), Row(1,2), Row(2,1), Row(2,2), Row(3,1), Row(3,2)))

    checkAnswer(
      testData2.orderBy('a.asc, 'b.desc),
      Seq(Row(1,2), Row(1,1), Row(2,2), Row(2,1), Row(3,2), Row(3,1)))

    checkAnswer(
      testData2.orderBy('a.desc, 'b.desc),
      Seq(Row(3,2), Row(3,1), Row(2,2), Row(2,1), Row(1,2), Row(1,1)))

    checkAnswer(
      testData2.orderBy('a.desc, 'b.asc),
      Seq(Row(3,1), Row(3,2), Row(2,1), Row(2,2), Row(1,1), Row(1,2)))

    checkAnswer(
      arrayData.orderBy('data.getItem(0).asc),
      arrayData.toDataFrame.collect().sortBy(_.getAs[Seq[Int]](0)(0)).toSeq)

    checkAnswer(
      arrayData.orderBy('data.getItem(0).desc),
      arrayData.toDataFrame.collect().sortBy(_.getAs[Seq[Int]](0)(0)).reverse.toSeq)

    checkAnswer(
      arrayData.orderBy('data.getItem(1).asc),
      arrayData.toDataFrame.collect().sortBy(_.getAs[Seq[Int]](0)(1)).toSeq)

    checkAnswer(
      arrayData.orderBy('data.getItem(1).desc),
      arrayData.toDataFrame.collect().sortBy(_.getAs[Seq[Int]](0)(1)).reverse.toSeq)
  }

  test("limit") {
    checkAnswer(
      testData.limit(10),
      testData.take(10).toSeq)

    checkAnswer(
      arrayData.limit(1),
      arrayData.take(1).map(r => Row.fromSeq(r.productIterator.toSeq)))

    checkAnswer(
      mapData.limit(1),
      mapData.take(1).map(r => Row.fromSeq(r.productIterator.toSeq)))
  }

  test("average") {
    checkAnswer(
      testData2.agg(avg('a)),
      Row(2.0))

    checkAnswer(
      testData2.agg(avg('a), sumDistinct('a)), // non-partial
      Row(2.0, 6.0) :: Nil)

    checkAnswer(
      decimalData.agg(avg('a)),
      Row(new java.math.BigDecimal(2.0)))
    checkAnswer(
      decimalData.agg(avg('a), sumDistinct('a)), // non-partial
      Row(new java.math.BigDecimal(2.0), new java.math.BigDecimal(6)) :: Nil)

    checkAnswer(
      decimalData.agg(avg('a cast DecimalType(10, 2))),
      Row(new java.math.BigDecimal(2.0)))
    checkAnswer(
      decimalData.agg(avg('a cast DecimalType(10, 2)), sumDistinct('a cast DecimalType(10, 2))), // non-partial
      Row(new java.math.BigDecimal(2.0), new java.math.BigDecimal(6)) :: Nil)
  }

  test("null average") {
    checkAnswer(
      testData3.agg(avg('b)),
      Row(2.0))

    checkAnswer(
      testData3.agg(avg('b), countDistinct('b)),
      Row(2.0, 1))

    checkAnswer(
      testData3.agg(avg('b), sumDistinct('b)), // non-partial
      Row(2.0, 2.0))
  }

  test("zero average") {
    checkAnswer(
      emptyTableData.agg(avg('a)),
      Row(null))

    checkAnswer(
      emptyTableData.agg(avg('a), sumDistinct('b)), // non-partial
      Row(null, null))
  }

  test("count") {
    assert(testData2.count() === testData2.map(_ => 1).count())

    checkAnswer(
      testData2.agg(count('a), sumDistinct('a)), // non-partial
      Row(6, 6.0))
  }

  test("null count") {
    checkAnswer(
      testData3.groupBy('a).agg('a, count('b)),
      Seq(Row(1,0), Row(2, 1))
    )

    checkAnswer(
      testData3.groupBy('a).agg('a, count('a + 'b)),
      Seq(Row(1,0), Row(2, 1))
    )

    checkAnswer(
      testData3.agg(count('a), count('b), count(lit(1)), countDistinct('a), countDistinct('b)),
      Row(2, 1, 2, 2, 1)
    )

    checkAnswer(
      testData3.agg(count('b), countDistinct('b), sumDistinct('b)), // non-partial
      Row(1, 1, 2)
    )
  }

  test("zero count") {
    assert(emptyTableData.count() === 0)

    checkAnswer(
      emptyTableData.agg(count('a), sumDistinct('a)), // non-partial
      Row(0, null))
  }

  test("zero sum") {
    checkAnswer(
      emptyTableData.agg(sum('a)),
      Row(null))
  }

  test("zero sum distinct") {
    checkAnswer(
      emptyTableData.agg(sumDistinct('a)),
      Row(null))
  }

  test("except") {
    checkAnswer(
      lowerCaseData.except(upperCaseData),
      Row(1, "a") ::
      Row(2, "b") ::
      Row(3, "c") ::
      Row(4, "d") :: Nil)
    checkAnswer(lowerCaseData.except(lowerCaseData), Nil)
    checkAnswer(upperCaseData.except(upperCaseData), Nil)
  }

  test("intersect") {
    checkAnswer(
      lowerCaseData.intersect(lowerCaseData),
      Row(1, "a") ::
      Row(2, "b") ::
      Row(3, "c") ::
      Row(4, "d") :: Nil)
    checkAnswer(lowerCaseData.intersect(upperCaseData), Nil)
  }

  test("udf") {
    val foo = (a: Int, b: String) => a.toString + b

    checkAnswer(
      // SELECT *, foo(key, value) FROM testData
      testData.select($"*", callUDF(foo, 'key, 'value)).limit(3),
      Row(1, "1", "11") :: Row(2, "2", "22") :: Row(3, "3", "33") :: Nil
    )
  }

  test("apply on query results (SPARK-5462)") {
    val df = testData.sqlContext.sql("select key from testData")
    checkAnswer(df("key"), testData.select('key).collect().toSeq)
  }

}

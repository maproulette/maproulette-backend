/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.psql

import java.sql.SQLException

import anorm.NamedParameter
import org.joda.time.{DateTime, Months}
import org.joda.time.format.DateTimeFormat
import org.maproulette.framework.psql.filter._
import org.scalatestplus.play.PlaySpec

/**
  * @author mcuthbert
  */
class FilterParameterSpec extends PlaySpec {
  val KEY   = "KEY"
  val VALUE = "VALUE"

  "BaseParameter" should {
    "set correctly" in {
      val parameter = BaseParameter(KEY, VALUE)
      parameter.sql() mustEqual s"$KEY = {${parameter.getKey()}}"
      val params = parameter.parameters()
      params.size mustEqual 1
      params.head mustEqual NamedParameter(s"${parameter.getKey()}", VALUE)
    }

    "set operator correctly" in {
      val parameter = BaseParameter(KEY, VALUE, Operator.GT)
      parameter.sql() mustEqual s"$KEY > {${parameter.getKey()}}"
    }

    "set negation correctly" in {
      val parameter = BaseParameter(KEY, VALUE, negate = true)
      parameter.sql() mustEqual s"NOT $KEY = {${parameter.getKey()}}"
    }

    "set negation correctly on Custom operator" in {
      BaseParameter(KEY, VALUE, Operator.CUSTOM, true).sql() mustEqual s"NOT $KEY$VALUE"
    }

    "set value directly correctly" in {
      BaseParameter(KEY, s"'$VALUE'", useValueDirectly = true)
        .sql() mustEqual s"$KEY = '$VALUE'"
    }

    "fail on invalid provided column" in {
      intercept[SQLException] {
        BaseParameter(s"&$KEY", VALUE).sql()
      }
    }

    "set list correctly using IN operator" in {
      val parameter = BaseParameter(KEY, List(1, 2, 3), Operator.IN)
      parameter.sql() mustEqual s"$KEY IN ({${parameter.getKey()}})"
    }

    "do not set empty list" in {
      val parameter = BaseParameter(KEY, List.empty, Operator.IN)
      parameter.sql() mustEqual ""
      parameter.parameters().size mustEqual 0
    }

    "set implicit table" in {
      val parameter = BaseParameter(KEY, VALUE, table = Some("TEST"))
      parameter.sql() mustEqual s"TEST.$KEY = {${parameter.getKey()}}"
    }
  }

  "ConditionalFilter" should {
    "set correctly if condition true" in {
      val filter = FilterParameter.conditional(KEY, VALUE, includeOnlyIfTrue = true)
      filter.sql() mustEqual s"$KEY = {${filter.filter.getKey()}}"
      val params = filter.parameters()
      params.size mustEqual 1
      params.head mustEqual NamedParameter(filter.filter.getKey(), VALUE)
    }

    "not set if condition is false" in {
      FilterParameter.conditional(KEY, VALUE).sql() mustEqual ""
    }

    "set operator correctly" in {
      val parameter = FilterParameter.conditional(KEY, VALUE, Operator.LT, includeOnlyIfTrue = true)
      parameter.sql() mustEqual s"$KEY < {${parameter.filter.getKey()}}"
    }

    "set negation correctly" in {
      val parameter =
        FilterParameter.conditional(KEY, VALUE, negate = true, includeOnlyIfTrue = true)
      parameter.sql() mustEqual s"NOT $KEY = {${parameter.filter.getKey()}}"
    }

    "set value directly correctly" in {
      FilterParameter
        .conditional(KEY, VALUE, useValueDirectly = true, includeOnlyIfTrue = true)
        .sql() mustEqual s"$KEY = $VALUE"
    }

    "fail on invalid provided column" in {
      intercept[SQLException] {
        FilterParameter.conditional("$%$KEY", VALUE, includeOnlyIfTrue = true).sql()
      }
    }
  }

  "DateParameter" should {
    "set single start date correctly" in {
      val startDate = DateTime.now()
      val filter    = DateParameter(KEY, startDate, null)
      filter.sql() mustEqual s"$KEY = {${filter.getKey()}}"
      val params = filter.parameters()
      params.size mustEqual 1
      params.head mustEqual SQLUtils.buildNamedParameter(filter.getKey(), startDate)
    }

    "set two dates correctly" in {
      val endDate   = DateTime.now()
      val startDate = endDate.minus(Months.ONE)
      val filter    = DateParameter(KEY, startDate, endDate, Operator.BETWEEN)
      filter
        .sql() mustEqual s"$KEY::DATE BETWEEN {${filter.getKey()}_date1} AND {${filter.getKey()}_date2}"
      val params = filter.parameters()
      params.size mustEqual 2
      params.head mustEqual SQLUtils.buildNamedParameter(s"${filter.getKey()}_date1", startDate)
      params.tail.head mustEqual SQLUtils.buildNamedParameter(s"${filter.getKey()}_date2", endDate)
    }

    "set negate correctly on two dates" in {
      val parameter = DateParameter(
        KEY,
        DateTime.now(),
        DateTime.now(),
        Operator.BETWEEN,
        negate = true
      )
      parameter
        .sql() mustEqual s"$KEY::DATE NOT BETWEEN {${parameter.getKey()}_date1} AND {${parameter.getKey()}_date2}"
    }

    "fail on invalid provided column" in {
      intercept[SQLException] {
        DateParameter(s"432%$KEY", DateTime.now(), null).sql()
      }
    }

    "allow useValueDirectly" in {
      val dateFormat = DateTimeFormat.forPattern("yyyy-MM-dd")
      val endDate    = DateTime.now()
      val startDate  = endDate.minus(Months.ONE)
      val filter     = DateParameter(KEY, startDate, endDate, Operator.BETWEEN, useValueDirectly = true)
      filter
        .sql() mustEqual s"$KEY::DATE BETWEEN '${dateFormat.print(startDate)}' AND '${dateFormat.print(endDate)}'"
    }
  }

  "SubQueryFilter" should {
    "Set standard subquery filter" in {
      val parameter = BaseParameter("Key2", "value2")
      val filter = SubQueryFilter(
        KEY,
        Query.simple(List(parameter), "SELECT * FROM table")
      )
      filter
        .sql() mustEqual s"$KEY IN (SELECT * FROM table WHERE Key2 = {${parameter.getKey()}})"
      val params = filter.parameters()
      params.size mustEqual 1
      params.head mustEqual SQLUtils.buildNamedParameter(
        s"${parameter.getKey()}",
        "value2"
      )
    }

    "Set standard subquery filter with two parameters" in {
      val parameter1 = BaseParameter("key2", "value2")
      val parameter2 = BaseParameter("key3", "value3", Operator.LTE)
      val filter =
        SubQueryFilter(KEY, Query.simple(List(parameter1, parameter2), "SELECT * FROM table", OR()))
      filter
        .sql() mustEqual s"$KEY IN (SELECT * FROM table WHERE (key2 = {${parameter1
        .getKey()}} OR key3 <= {${parameter2.getKey()}}))"
      val params = filter.parameters()
      params.size mustEqual 2
      params.head mustEqual SQLUtils.buildNamedParameter(
        s"${parameter1.getKey()}",
        "value2"
      )
      params.tail.head mustEqual SQLUtils.buildNamedParameter(
        s"${parameter2.getKey()}",
        "value3"
      )
    }

    "Set exists subquery filter" in {
      val parameter = BaseParameter("key2", "value2")
      val filter = SubQueryFilter(
        KEY,
        Query.simple(List(parameter), "SELECT * FROM table"),
        operator = Operator.EXISTS
      )
      filter
        .sql() mustEqual s"EXISTS (SELECT * FROM table WHERE key2 = {${parameter.getKey()}})"
    }

    "Set equals subquery filter" in {
      val parameter = BaseParameter("key2", "value2")
      val filter = SubQueryFilter(
        KEY,
        Query.simple(List(parameter), "SELECT * FROM table"),
        operator = Operator.EQ
      )
      filter
        .sql() mustEqual s"$KEY = (SELECT * FROM table WHERE key2 = {${parameter.getKey()}})"
    }

    "fail on invalid provided column" in {
      intercept[SQLException] {
        SubQueryFilter(
          KEY,
          Query.simple(List(BaseParameter("$%Key2", "value2")), "SELECT * FROM table")
        ).sql()
      }
    }

    "set different base tables" in {
      val parameter = BaseParameter("key2", "value2")
      val filter = SubQueryFilter(
        KEY,
        Query.simple(List(parameter), "SELECT * FROM table"),
        table = Some("outerTable"),
        subQueryTable = Some("innerTable")
      )
      filter
        .sql() mustEqual s"outerTable.$KEY IN (SELECT * FROM table WHERE innerTable.key2 = {${parameter.getKey()}})"
    }
  }

  "CustomParameter" should {
    "set anything that is provided in the value" in {
      val filter = CustomParameter("THIS IS A TEST")
      filter.sql() mustEqual "THIS IS A TEST"
      filter.parameters().size mustEqual 0
    }
  }

  "FuzzySearchParameter" should {
    "sets all the correct values for fuzzy searches" in {
      val filter = FuzzySearchParameter(KEY, VALUE)
      filter
        .sql() mustEqual FilterParameterSpec.DEFAULT_FUZZY_SQL(KEY, keyPrefix = filter.randomPrefix)
      val params = filter.parameters()
      params.size mustEqual 1
      params.head mustEqual SQLUtils.buildNamedParameter(filter.getKey(), VALUE)
    }

    "sets fuzzy search with default levenshstein score if less than 1" in {
      val filter = FuzzySearchParameter(KEY, VALUE, 0)
      filter
        .sql() mustEqual FilterParameterSpec.DEFAULT_FUZZY_SQL(KEY, keyPrefix = filter.randomPrefix)
    }

    "sets fuzzy search with custom levenshstein score" in {
      val filter = FuzzySearchParameter(KEY, VALUE, 56)
      filter.sql() mustEqual FilterParameterSpec.DEFAULT_FUZZY_SQL(
        KEY,
        score = 56,
        keyPrefix = filter.randomPrefix
      )
    }

    "sets fuzzy search with custom metaphone size" in {
      val filter = FuzzySearchParameter(KEY, VALUE, metaphoneSize = 67)
      filter.sql() mustEqual FilterParameterSpec.DEFAULT_FUZZY_SQL(
        KEY,
        size = 67,
        keyPrefix = filter.randomPrefix
      )
    }

    "fail on invalid provided column" in {
      intercept[SQLException] {
        FuzzySearchParameter(s"&$KEY", VALUE).sql()
      }
    }
  }
}

object FilterParameterSpec {
  def DEFAULT_FUZZY_SQL(
      key: String,
      score: Int = FilterParameter.DEFAULT_LEVENSHSTEIN_SCORE,
      size: Int = FilterParameter.DEFAULT_METAPHONE_SIZE,
      keyPrefix: String = ""
  ) = s"""($key <> '' AND
      (LEVENSHTEIN(LOWER($key), LOWER({$keyPrefix$key})) < $score OR
      METAPHONE(LOWER($key), 4) = METAPHONE(LOWER({$keyPrefix$key}), $size) OR
      SOUNDEX(LOWER($key)) = SOUNDEX(LOWER({$keyPrefix$key})))
      )"""
}

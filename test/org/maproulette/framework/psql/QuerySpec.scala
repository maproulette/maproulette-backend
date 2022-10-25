/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.psql

import org.joda.time.DateTime
import org.maproulette.framework.psql.filter._
import org.scalatestplus.play.PlaySpec

/**
  * @author mcuthbert
  */
class QuerySpec extends PlaySpec {
  val KEY                          = "KEY"
  val VALUE                        = "VALUE"
  val parameter: Parameter[String] = BaseParameter(KEY, VALUE)
  val setKey: String               = s"${parameter.randomPrefix}$KEY"

  "Query" should {
    "build standard query" in {
      val query = Query.simple(List(parameter), "SELECT * FROM table")
      query.sql() mustEqual s"SELECT * FROM table WHERE KEY = {$setKey}"
      val params = query.parameters()
      params.size mustEqual 1
      params.head mustEqual SQLUtils.buildNamedParameter(setKey, VALUE)
    }

    "build standard query with paging" in {
      val paging       = Paging(10, 2)
      val pagingPrefix = paging.randomPrefix
      val query        = Query.simple(List(parameter), "SELECT * FROM table", paging = paging)
      query
        .sql() mustEqual s"SELECT * FROM table WHERE KEY = {$setKey} LIMIT {${pagingPrefix}limit} OFFSET {${pagingPrefix}offset}"
      val params = query.parameters()
      params.size mustEqual 3
      params.head mustEqual SQLUtils.buildNamedParameter(setKey, VALUE)
      params(1) mustEqual SQLUtils.buildNamedParameter(s"${pagingPrefix}limit", 10)
      params(2) mustEqual SQLUtils.buildNamedParameter(s"${pagingPrefix}offset", 20)
    }

    "build standard query with ordering" in {
      val query = Query.simple(
        List(parameter),
        "SELECT * FROM table",
        order = Order > ("test", Order.ASC)
      )
      query.sql() mustEqual s"SELECT * FROM table WHERE KEY = {$setKey} ORDER BY test ASC"
      query.parameters().size mustEqual 1
    }
  }

  "build standard query with grouping" in {
    val query = Query.simple(
      List(parameter),
      "SELECT * FROM table",
      grouping = Grouping > ("test1", "test2")
    )
    query.sql() mustEqual s"SELECT * FROM table WHERE KEY = {$setKey} GROUP BY test1,test2"
    query.parameters().size mustEqual 1
  }

  "build standard query with all extras" in {
    val paging       = Paging(10, 5)
    val pagingPrefix = paging.randomPrefix
    val query = Query.simple(
      List(parameter),
      "SELECT * FROM table",
      AND(),
      paging,
      Order > "order",
      Grouping > "test1"
    )
    query
      .sql() mustEqual s"SELECT * FROM table WHERE KEY = {$setKey} GROUP BY test1 ORDER BY order DESC LIMIT {${pagingPrefix}limit} OFFSET {${pagingPrefix}offset}"
    val params = query.parameters()
    params.size mustEqual 3
    params.head mustEqual SQLUtils.buildNamedParameter(setKey, VALUE)
    params(1) mustEqual SQLUtils.buildNamedParameter(s"${pagingPrefix}limit", 10)
    params(2) mustEqual SQLUtils.buildNamedParameter(s"${pagingPrefix}offset", 50)
  }

  "build standard query with final clause" in {
    val paging       = Paging(10, 5)
    val pagingPrefix = paging.randomPrefix
    val query = Query.simple(
      List(parameter),
      "SELECT * FROM table",
      AND(),
      paging,
      Order > "order",
      Grouping > "test1",
      finalClause = "RETURNING *"
    )
    query
      .sql() mustEqual s"SELECT * FROM table WHERE KEY = {$setKey} GROUP BY test1 ORDER BY order DESC LIMIT {${pagingPrefix}limit} OFFSET {${pagingPrefix}offset} RETURNING *"
    val params = query.parameters()
    params.size mustEqual 3
    params.head mustEqual SQLUtils.buildNamedParameter(setKey, VALUE)
    params(1) mustEqual SQLUtils.buildNamedParameter(s"${pagingPrefix}limit", 10)
    params(2) mustEqual SQLUtils.buildNamedParameter(s"${pagingPrefix}offset", 50)
  }

  "build complex query" in {
    implicit val parameterKey = ""
    val firstDate             = DateTime.now()
    val secondDate            = DateTime.now()
    val parameter2            = BaseParameter("key2", "value2")
    val parameter3            = CustomParameter("a.g = g.a")
    val parameter4            = DateParameter("dateField", firstDate, secondDate, Operator.BETWEEN)
    val parameter5            = FuzzySearchParameter("fuzzy", "fuzzyValue")
    val parameter6            = BaseParameter("subsubKey", "subsubValue")
    val paging1               = Paging(10)
    val paging2               = Paging(35, 15)
    val query = Query(
      Filter(
        List(
          FilterGroup(List(parameter, parameter2), OR()),
          FilterGroup(List(parameter3, parameter4), OR()),
          FilterGroup(
            List(
              parameter5,
              SubQueryFilter(
                "subQueryKey",
                Query.simple(
                  List(parameter6),
                  "SELECT * FROM subTable",
                  paging = paging1
                )
              )
            )
          )
        )
      ),
      "SELECT * FROM table",
      paging = paging2,
      order = Order > "oField"
    )

    query
      .sql() mustEqual s"""SELECT * FROM table WHERE ((KEY = {$setKey} OR key2 = {${parameter2
      .getKey()}})) AND ((a.g = g.a OR dateField::DATE BETWEEN {${parameter4
      .getKey()}_date1} AND {${parameter4.getKey()}_date2})) AND (${FilterParameterSpec
      .DEFAULT_FUZZY_SQL(
        "fuzzy",
        keyPrefix = parameter5.randomPrefix
      )} AND subQueryKey IN (SELECT * FROM subTable WHERE subsubKey = {${parameter6
      .getKey()}} LIMIT {${paging1.randomPrefix}limit} OFFSET {${paging1.randomPrefix}offset})) ORDER BY oField DESC LIMIT {${paging2.randomPrefix}limit} OFFSET {${paging2.randomPrefix}offset}"""
    val params = query.parameters()
    params.size mustEqual 10
    params.head mustEqual SQLUtils.buildNamedParameter(setKey, VALUE)
    params(1) mustEqual SQLUtils.buildNamedParameter(parameter2.getKey(), "value2")
    params(2) mustEqual SQLUtils.buildNamedParameter(s"${parameter4.getKey()}_date1", firstDate)
    params(3) mustEqual SQLUtils.buildNamedParameter(s"${parameter4.getKey()}_date2", secondDate)
    params(4) mustEqual SQLUtils.buildNamedParameter(
      s"${parameter5.randomPrefix}fuzzy",
      "fuzzyValue"
    )
    params(5) mustEqual SQLUtils.buildNamedParameter(
      s"${parameter6.getKey()}",
      "subsubValue"
    )
    params(6) mustEqual SQLUtils.buildNamedParameter(s"${paging1.randomPrefix}limit", 10)
    params(7) mustEqual SQLUtils.buildNamedParameter(s"${paging1.randomPrefix}offset", 0)
    params(8) mustEqual SQLUtils.buildNamedParameter(s"${paging2.randomPrefix}limit", 35)
    params(9) mustEqual SQLUtils.buildNamedParameter(s"${paging2.randomPrefix}offset", 525)
  }

  "handle input base query correctly when no base set" in {
    Query
      .simple(List(parameter))
      .sqlWithBaseQuery("SELECT * FROM projects") mustEqual s"SELECT * FROM projects WHERE $KEY = {$setKey}"
  }

  "handle input base query correctly when base is set" in {
    Query
      .simple(List(parameter), "SELECT * FROM challenges")
      .sqlWithBaseQuery("SELECT * FROM projects") mustEqual s"SELECT * FROM challenges WHERE $KEY = {$setKey}"
  }

  "handle input base query correctly when base is set and forced to base" in {
    Query
      .simple(List(parameter), "SELECT * FROM projects")
      .sqlWithBaseQuery("SELECT * FROM challenges") mustEqual s"SELECT * FROM projects WHERE $KEY = {$setKey}"
  }

  "allows filter groups to be added" in {
    val parameter2     = BaseParameter("key2", "value2")
    val query          = Query.simple(List(parameter))
    val augmentedQuery = query.addFilterGroup(FilterGroup(List(parameter2)))

    // Original query remains untouched
    query.parameters().size mustEqual 1

    // Augmented query gets additional parameters
    val params = augmentedQuery.parameters()
    params.size mustEqual 2
    params.head mustEqual SQLUtils.buildNamedParameter(parameter2.getKey(), "value2")
    params(1) mustEqual SQLUtils.buildNamedParameter(setKey, VALUE)
  }

  "supports creation of empty query" in {
    val query = Query.empty
    query.parameters().size mustEqual 0
  }
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.psql

import org.maproulette.framework.psql.filter.{BaseParameter, Filter, FilterGroup, Parameter}
import org.scalatestplus.play.PlaySpec

/**
  * @author mcuthbert
  */
class FilterSpec extends PlaySpec {
  val KEY                           = "KEY"
  val VALUE                         = "VALUE"
  val KEY2                          = "KEY2"
  val VALUE2                        = "VALUE2"
  implicit val parameterKey: String = ""
  val parameter1: Parameter[String] = BaseParameter(KEY, VALUE)
  val parameter2: Parameter[String] = BaseParameter(KEY2, VALUE2)
  val parameter3: Parameter[String] = BaseParameter(s"${KEY}_1", VALUE)
  val parameter4: Parameter[String] = BaseParameter(s"${KEY2}_2", VALUE2)

  "FilterGroup" should {
    "Create a group of AND filters parameters" in {
      val group = FilterGroup(List(parameter1, parameter2))
      group.sql() mustEqual s"KEY = {${parameter1.getKey()}} AND KEY2 = {${parameter2.getKey()}}"
      val params = group.parameters()
      params.size mustEqual 2
      params.head mustEqual SQLUtils.buildNamedParameter(parameter1.getKey(), VALUE)
      params.tail.head mustEqual SQLUtils.buildNamedParameter(parameter2.getKey(), VALUE2)
    }

    "Create a group of OR filter parameters" in {
      val group =
        FilterGroup(List(parameter1, parameter2), OR())
      group.sql() mustEqual s"(KEY = {${parameter1.getKey()}} OR KEY2 = {${parameter2.getKey()}})"
      val params = group.parameters()
      params.size mustEqual 2
      params.head mustEqual SQLUtils.buildNamedParameter(parameter1.getKey(), VALUE)
      params.tail.head mustEqual SQLUtils.buildNamedParameter(parameter2.getKey(), VALUE2)
    }
  }

  "Filter" should {
    "generate standard sql for AND parameters" in {
      val filter = Filter.simple(List(parameter1, parameter2), AND())
      filter.sql() mustEqual s"KEY = {${parameter1.getKey()}} AND KEY2 = {${parameter2.getKey()}}"
      val params = filter.parameters()
      params.size mustEqual 2
      params.head mustEqual SQLUtils.buildNamedParameter(parameter1.getKey(), VALUE)
      params.tail.head mustEqual SQLUtils.buildNamedParameter(parameter2.getKey(), VALUE2)
    }

    "generate standard sql for OR parameters" in {
      val filter = Filter.simple(List(parameter1, parameter2), OR())
      filter.sql() mustEqual s"(KEY = {${parameter1.getKey()}} OR KEY2 = {${parameter2.getKey()}})"
      val params = filter.parameters()
      params.size mustEqual 2
      params.head mustEqual SQLUtils.buildNamedParameter(parameter1.getKey(), VALUE)
      params.tail.head mustEqual SQLUtils.buildNamedParameter(parameter2.getKey(), VALUE2)
    }

    "generate filter groups for AND parameters" in {
      val filter = this.genericFilter(AND(), AND(), AND())
      filter
        .sql() mustEqual s"(KEY = {${parameter1.getKey()}} AND KEY_1 = {${parameter3
        .getKey()}}) AND (KEY2 = {${parameter2.getKey()}} AND KEY2_2 = {${parameter4.getKey()}})"
      val params = filter.parameters()
      params.size mustEqual 4
      params.head mustEqual SQLUtils.buildNamedParameter(parameter1.getKey(), VALUE)
      params(1) mustEqual SQLUtils.buildNamedParameter(parameter3.getKey(), VALUE)
      params(2) mustEqual SQLUtils.buildNamedParameter(parameter2.getKey(), VALUE2)
      params(3) mustEqual SQLUtils.buildNamedParameter(parameter4.getKey(), VALUE2)
    }

    "generate filter groups for OR parameters" in {
      val filter = this.genericFilter(OR(), OR(), OR())
      filter
        .sql() mustEqual s"((KEY = {${parameter1.getKey()}} OR KEY_1 = {${parameter3
        .getKey()}})) OR ((KEY2 = {${parameter2.getKey()}} OR KEY2_2 = {${parameter4.getKey()}}))"
      val params = filter.parameters()
      params.size mustEqual 4
    }

    "generate filter groups for AND/OR/AND parameters" in {
      val filter = this.genericFilter(OR(), AND(), AND())
      filter
        .sql() mustEqual s"(KEY = {${parameter1.getKey()}} AND KEY_1 = {${parameter3
        .getKey()}}) OR (KEY2 = {${parameter2.getKey()}} AND KEY2_2 = {${parameter4.getKey()}})"
      val params = filter.parameters()
      params.size mustEqual 4
    }

    "generate filter groups for OR/AND/OR parameters" in {
      val filter = this.genericFilter(AND(), OR(), OR())
      filter
        .sql() mustEqual s"((KEY = {${parameter1.getKey()}} OR KEY_1 = {${parameter3
        .getKey()}})) AND ((KEY2 = {${parameter2.getKey()}} OR KEY2_2 = {${parameter4.getKey()}}))"
      val params = filter.parameters()
      params.size mustEqual 4
    }

    "generate filter groups for OR/OR/AND parameters" in {
      val filter = this.genericFilter(OR(), OR(), AND())
      filter
        .sql() mustEqual s"((KEY = {${parameter1.getKey()}} OR KEY_1 = {${parameter3
        .getKey()}})) OR (KEY2 = {${parameter2.getKey()}} AND KEY2_2 = {${parameter4.getKey()}})"
      val params = filter.parameters()
      params.size mustEqual 4
    }
  }

  private def genericFilter(key: SQLKey, group1Key: SQLKey, group2Key: SQLKey): Filter = {
    Filter(
      List(
        FilterGroup(List(parameter1, parameter3), group1Key),
        FilterGroup(List(parameter2, parameter4), group2Key)
      ),
      key
    )
  }
}

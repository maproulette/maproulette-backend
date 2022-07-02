/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.psql.filter

import anorm.NamedParameter
import org.maproulette.framework.psql.{OR, AND, SQLKey}

/**
  * This class handles any filters that you want to add onto a query. Repositories only handle one
  * table, joins are special and would require special code and logic to handle correctly, however
  * joins are actively discouraged due to the complexity and the potential for large performance
  * costs.
  *
  * @author mcuthbert
  */
trait SQLClause {
  def sql()(implicit tableKey: String = ""): String
  def parameters(): List[NamedParameter] = List.empty

  // Simple function that makes sure I don't have a bunch of empty spaces at the end of the query.
  // It doesn't really matter, but makes it easier to look at.
  protected def format(value: String): String =
    if (value.isEmpty) {
      ""
    } else {
      s" $value"
    }
}

case class FilterGroup(params: List[Parameter[_]], key: SQLKey = AND(), condition: Boolean = true)
    extends SQLClause {
  override def sql()(implicit tableKey: String = ""): String =
    if (condition) {
      val result = params
        .flatMap(param =>
          param.sql() match {
            case ""  => None
            case sql => Some(sql)
          }
        )
        .mkString(s" ${key.getSQLKey()} ")

      // If we OR the results together they need to be wrapped in parens
      if (key == OR())
        s"(${result})"
      else result
    } else {
      ""
    }

  override def parameters(): List[NamedParameter] = params.flatMap(param => param.parameters())
}

case class Filter(groups: List[FilterGroup], key: SQLKey = AND()) extends SQLClause {
  def sql()(implicit tableKey: String = ""): String = {
    val groupSeparator = if (groups.size == 1) {
      ("", "")
    } else {
      ("(", ")")
    }
    groups
      .flatMap(group =>
        group.sql() match {
          case ""  => None
          case sql => Some(s"${groupSeparator._1}$sql${groupSeparator._2}")
        }
      )
      .mkString(s" ${key.getSQLKey()} ")
  }

  override def parameters(): List[NamedParameter] = groups.flatMap(param => param.parameters())
}

object Filter {
  def simple(parameters: List[Parameter[_]], key: SQLKey = AND()): Filter =
    Filter(List(FilterGroup(parameters, key)))
}

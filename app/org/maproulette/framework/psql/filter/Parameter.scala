/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.psql.filter

import anorm.{NamedParameter, ToParameterValue}
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.maproulette.framework.psql.filter.Operator.Operator
import org.maproulette.framework.psql.{Query, SQLUtils}
import org.maproulette.utils.Utils

/**
  * Filter Parameters are generally be used for filter select queries using some basic filtering. It
  * also can be used for updating objects, as the SET clause using a very similar structure to the
  * EQ operator.
  *
  * @author mcuthbert
  */
trait Parameter[T] extends SQLClause {
  val key: String
  val value: T
  val operator: Operator        = Operator.EQ
  val negate: Boolean           = false
  val useValueDirectly: Boolean = false
  val table: Option[String]     = None
  val randomPrefix: String      = Utils.randomStringFromCharList(5)

  def sql()(implicit tableKey: String = ""): String = {
    if (value.isInstanceOf[Iterable[_]] && value.asInstanceOf[Iterable[_]].isEmpty) {
      ""
    } else if (operator == Operator.CUSTOM) {
      SQLUtils.testColumnName(key)
      val negation = if (negate) {
        "NOT "
      } else {
        ""
      }
      s"$negation$key$value"
    } else {
      val directValue = if (useValueDirectly) {
        // if we are using the value directly, then we must at least try to convert to a string
        Some(value.toString)
      } else {
        None
      }
      Operator.format(this.getColumnKey(tableKey), this.getKey(), operator, negate, directValue)
    }
  }

  protected def getColumnKey(tableKey: String): String = {
    this.table.getOrElse(tableKey) match {
      case "" => key
      case v  => s"$v.$key"
    }
  }

  def getKey(): String = s"$randomPrefix$key"

  override def parameters(): List[NamedParameter] = {
    if ((value.isInstanceOf[Iterable[_]] && value
          .asInstanceOf[Iterable[_]]
          .isEmpty) || operator == Operator.CUSTOM || operator == Operator.NULL || operator == Operator.BOOL || useValueDirectly) {
      List.empty
    } else {
      List(SQLUtils.buildNamedParameter(this.getKey(), value))
    }
  }
}

/**
  * Conditional Filter that will only apply the filter if a condition is met
  *
  * @param key The column to filter on
  * @param value The value to use in the column filter
  * @param operator The operator to use for the equation
  * @param negate Whether to negate the operation
  * @param useValueDirectly use the value directly in the operation, instead of using a NamedParameter
  * @tparam T
  */
case class BaseParameter[T](
    override val key: String,
    override val value: T,
    override val operator: Operator = Operator.EQ,
    override val negate: Boolean = false,
    override val useValueDirectly: Boolean = false,
    override val table: Option[String] = None
) extends Parameter[T]

case class ConditionalFilterParameter[T](
    filter: Parameter[T],
    includeOnlyIfTrue: Boolean = false,
    override val table: Option[String] = None
) extends Parameter[T] {
  override val key: String = filter.key
  override val value: T    = filter.value

  override def sql()(implicit tableKey: String = ""): String = {
    if (includeOnlyIfTrue) {
      filter.sql()(table.getOrElse(tableKey))
    } else {
      ""
    }
  }

  override def parameters(): List[NamedParameter] =
    if (includeOnlyIfTrue) {
      filter.parameters()
    } else {
      List.empty
    }
}

case class DateParameter(
    override val key: String,
    override val value: DateTime,
    value2: DateTime,
    override val operator: Operator = Operator.EQ,
    override val negate: Boolean = false,
    override val table: Option[String] = None,
    override val useValueDirectly: Boolean = false
) extends Parameter[DateTime] {
  private val dateFormat = DateTimeFormat.forPattern("yyyy-MM-dd")

  override def sql()(implicit tableKey: String = ""): String = {
    if (operator == Operator.BETWEEN) {
      SQLUtils.testColumnName(key)
      val negation = if (negate) {
        "NOT "
      } else {
        ""
      }
      if (useValueDirectly) {
        s"${this.getColumnKey(tableKey)}::DATE ${negation}BETWEEN '${dateFormat.print(value)}' AND '${dateFormat
          .print(value2)}'"
      } else {
        s"${this.getColumnKey(tableKey)}::DATE ${negation}BETWEEN {${this.getKey()}_date1} AND {${this.getKey()}_date2}"
      }
    } else {
      super.sql()
    }
  }

  override def parameters(): List[NamedParameter] = {
    if (operator == Operator.BETWEEN) {
      List(
        Symbol(s"${this.getKey()}_date1") -> ToParameterValue
          .apply[DateTime](p = SQLUtils.toStatement)
          .apply(value),
        Symbol(s"${this.getKey()}_date2") -> ToParameterValue
          .apply[DateTime](p = SQLUtils.toStatement)
          .apply(value2)
      )
    } else {
      super.parameters()
    }
  }
}

/**
  * Allows you to do something like 'column' IN ( subQuery ), need to add support for exists later
  *
  * @param key The column that you are checking against
  * @param value The sub query to check against, the subQuery needs a base query included
  */
case class SubQueryFilter(
    override val key: String,
    override val value: Query,
    override val negate: Boolean = false,
    override val operator: Operator = Operator.IN,
    override val table: Option[String] = None,
    subQueryTable: Option[String] = Some("")
) extends Parameter[Query] {
  override def sql()(implicit tableKey: String = ""): String = {
    val innerSQL = value.sql()(subQueryTable.getOrElse(tableKey)) match {
      case ""                                                            => ""
      case sql if operator == Operator.IN || operator == Operator.EXISTS => sql
      case sql                                                           => s"($sql)"
    }
    if (operator == Operator.CUSTOM) {
      innerSQL
    } else {
      Operator.format(
        this.getColumnKey(tableKey),
        this.getKey(),
        operator,
        negate,
        Some(innerSQL)
      )
    }
  }

  override def parameters(): List[NamedParameter] = value.parameters()
}

/**
  * Custom filter that will simply use the sql included in the filter
  *
  * @param key
  */
case class CustomParameter(override val key: String) extends Parameter[String] {
  override val value: String = ""

  override def sql()(implicit tableKey: String = ""): String = key

  override def parameters(): List[NamedParameter] = List.empty
}

case class FuzzySearchParameter(
    override val key: String,
    override val value: String,
    levenshsteinScore: Int = FilterParameter.DEFAULT_LEVENSHSTEIN_SCORE,
    metaphoneSize: Int = FilterParameter.DEFAULT_METAPHONE_SIZE,
    override val table: Option[String] = None
) extends Parameter[String] {
  override def sql()(implicit tableKey: String = ""): String = {
    SQLUtils.testColumnName(key)
    val score = if (levenshsteinScore > 0) {
      levenshsteinScore
    } else {
      FilterParameter.DEFAULT_LEVENSHSTEIN_SCORE
    }
    val columnKey = this.getColumnKey(table.getOrElse(tableKey))
    s"""($columnKey <> '' AND
      (LEVENSHTEIN(LOWER($columnKey), LOWER({${this.getKey()}})) < $score OR
      METAPHONE(LOWER($columnKey), 4) = METAPHONE(LOWER({${this.getKey()}}), $metaphoneSize) OR
      SOUNDEX(LOWER($columnKey)) = SOUNDEX(LOWER({${this.getKey()}})))
      )"""
  }

  override def parameters(): List[NamedParameter] = List(Symbol(getKey()) -> value)
}

object FilterParameter {
  val DEFAULT_LEVENSHSTEIN_SCORE = 3
  val DEFAULT_METAPHONE_SIZE     = 4

  // Helper method to build a Conditional Filter
  def conditional[T](
      key: String,
      value: T,
      operator: Operator = Operator.EQ,
      negate: Boolean = false,
      useValueDirectly: Boolean = false,
      includeOnlyIfTrue: Boolean = false,
      table: Option[String] = None
  ): ConditionalFilterParameter[T] =
    ConditionalFilterParameter(
      BaseParameter(key, value, operator, negate, useValueDirectly, table),
      includeOnlyIfTrue
    )
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.psql

import java.sql.{PreparedStatement, SQLException}

import anorm.JodaParameterMetaData.JodaDateTimeMetaData
import anorm.{NamedParameter, ParameterValue, ToParameterValue, ToSql, ToStatement}
import org.joda.time.DateTime

/**
  * A Basic utils class to handle any common functionality
  *
  * @author mcuthbert
  */
sealed trait SQLKey {
  def getSQLKey(): String
}

case class AND() extends SQLKey {
  override def getSQLKey(): String = "AND"
}

case class OR() extends SQLKey {
  override def getSQLKey(): String = "OR"
}

object SQLUtils {
  // The set of characters that are allowed for column names, so that we can sanitize in unknown input
  // for protection against SQL injection
  private val ordinary =
    (('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9') ++ Seq('_') ++ Seq('.') ++ Seq('-')).toSet

  def testColumnName(columnName: String): Unit = {
    if (!columnName.forall(this.ordinary.contains)) {
      throw new SQLException(s"Invalid column name provided `$columnName`")
    }
  }

  /**
    * Normalizes a requested sort direction to either ASC or DESC. Anything that is not an explicit
    * (case-insensitive) ascending request is treated as DESC.
    */
  def orderDirection(value: String): String =
    if (value != null && value.equalsIgnoreCase("ASC")) "ASC" else "DESC"

  /**
    * Corrects the search string by adding % before and after string, so that it doesn't rely
    * on simply an exact match. If value not supplied, then will simply return %
    *
    * @param value The search string that you are using to match with
    * @return
    */
  def search(
      value: String,
      usePrefix: Boolean = false,
      escapeSingleQuote: Boolean = true
  ): String = {
    val firstChar = if (usePrefix) "" else "%"
    val searchString = escapeSingleQuote match {
      case true =>
        if (value.nonEmpty) value.replace("'", "''")
        else value
      case false => value
    }
    if (value.nonEmpty) s"$firstChar$searchString%" else "%"
  }

  def toParameterValue[T](value: T): ParameterValue =
    ToParameterValue.apply[T](p = toStatement).apply(value)

  def buildNamedParameter[T](key: String, value: T): NamedParameter = {
    val sequenceValue = value match {
      case value: List[_] => value.toSet
      case value: Seq[_]  => value.toSet
      case value          => value
    }

    val toSql = sequenceValue match {
      case value: Set[_] =>
        value.head match {
          case _: Int    => ToSql.setToSql[Int]
          case _: Long   => ToSql.setToSql[Long]
          case _: String => ToSql.setToSql[String]
        }
      case _ => ToSql.missing
    }
    NamedParameter.namedWithString(key, sequenceValue)(
      ToParameterValue(toSql.asInstanceOf[ToSql[Any]], toStatement[Any])
    )
  }

  /**
    * Handles setting the value in a SQL prepared statement
    *
    * @tparam Key The type of object that is being set
    * @return
    */
  def toStatement[Key]: ToStatement[Key] = {
    new ToStatement[Key] {
      def set(s: PreparedStatement, i: Int, identifier: Key) =
        identifier match {
          case value: String       => ToStatement.stringToStatement.set(s, i, value)
          case Some(value: String) => ToStatement.stringToStatement.set(s, i, value)
          case value: Long         => ToStatement.longToStatement.set(s, i, value)
          case Some(value: Long)   => ToStatement.longToStatement.set(s, i, value)
          case value: Integer      => ToStatement.integerToStatement.set(s, i, value)
          case value: Boolean      => ToStatement.booleanToStatement.set(s, i, value)
          case value: DateTime     => ToStatement.jodaDateTimeToStatement.set(s, i, value)
          case value: Set[_] =>
            value.head match {
              case _: Int =>
                ToStatement.setToStatement[Int].set(s, i, value.asInstanceOf[Set[Int]])
              case _: Long =>
                ToStatement.setToStatement[Long].set(s, i, value.asInstanceOf[Set[Long]])
              case _: String =>
                ToStatement.setToStatement[String].set(s, i, value.asInstanceOf[Set[String]])
              case _ =>
                throw new UnsupportedOperationException(
                  "Unsupported list type provided. Only support Int, Long and String."
                )
            }
        }
    }
  }
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.models.utils

import java.sql.{PreparedStatement, SQLException}

import anorm._
import org.apache.commons.lang3.StringUtils
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.maproulette.session.SearchParameters

import scala.collection.mutable.ListBuffer

sealed trait SQLKey {
  def getSQLKey(): String
}

case class AND() extends SQLKey {
  override def getSQLKey(): String = "AND"
}

case class OR() extends SQLKey {
  override def getSQLKey(): String = "OR"
}

case class WHERE() extends SQLKey {
  override def getSQLKey(): String = "WHERE"
}

/**
  * Helper functions for any Data Access Layer classes
  *
  * @author cuthbertm
  */
@deprecated
trait DALHelper {
  private val dateFormat = DateTimeFormat.forPattern("yyyy-MM-dd")
  // The set of characters that are allowed for column names, so that we can sanitize in unknown input
  // for protection against SQL injection
  private val ordinary =
    (('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9') ++ Seq('_') ++ Seq('.')).toSet

  /**
    * Function will return "ALL" if value is 0 otherwise the value itself. Postgres does not allow
    * using 0 for ALL
    * DEPRECATED: Use PsqlQuery instead
    *
    * @param value The limit used in the query
    * @return ALL if 0 otherwise the value
    */
  @Deprecated
  def sqlLimit(value: Int): String = if (value <= 0) "ALL" else s"$value"

  /**
    * Corrects the search string by adding % before and after string, so that it doesn't rely
    * on simply an exact match. If value not supplied, then will simply return %
    *
    * @param value The search string that you are using to match with
    * @return
    */
  def search(value: String): String = if (value.nonEmpty) s"%$value%" else "%"

  /**
    * Creates the ORDER functionality, with the column and direction
    * DEPRECATED: Use PsqlQuery instead
    *
    * @param orderColumn    The column that you are ordering with (or multiple comma separated columns)
    * @param orderDirection Direction of ordering ASC or DESC
    * @param tablePrefix    table alias if required
    * @param nameFix        The namefix really is just a way to force certain queries specific to MapRoulette
    *                       to use a much more efficient query plan. The difference in performance can be quite
    *                large. We don't do it by default because it relies on the "name" column which is
    *                       not guaranteed.
    * @return
    */
  @Deprecated
  def order(
      orderColumn: Option[String] = None,
      orderDirection: String = "ASC",
      tablePrefix: String = "",
      nameFix: Boolean = false,
      ignoreCase: Boolean = false
  ): String = orderColumn match {
    case Some(column) =>
      this.testColumnName(column)
      val direction = orderDirection match {
        case "DESC" => "DESC"
        case _      => "ASC"
      }
      // sanitize the column name to prevent sql injection. Only allow underscores and A-Za-z
      if (column.forall(this.ordinary.contains)) {
        val casedColumn = new StringBuilder()
        if (ignoreCase) {
          casedColumn ++= "LOWER("
        }
        casedColumn ++= this.getPrefix(tablePrefix) + column

        if (ignoreCase) {
          casedColumn ++= ")"
        }
        s"ORDER BY $casedColumn $direction ${if (nameFix) {
          "," + this.getPrefix(tablePrefix) + "name";
        } else {
          "";
        }}"
      } else {
        ""
      }
    case None => ""
  }

  @deprecated
  def sqlWithParameters(query: String, parameters: ListBuffer[NamedParameter]): SimpleSql[Row] = {
    if (parameters.nonEmpty) {
      SQL(query).on(parameters.toSeq: _*)
    } else {
      SQL(query).asSimple[Row]()
    }
  }

  def parentFilter(parentId: Long)(implicit conjunction: Option[SQLKey] = Some(AND())): String =
    if (parentId != -1) {
      s"${this.getSqlKey} parent_id = $parentId"
    } else {
      ""
    }

  def getLongListFilter(list: Option[List[Long]], columnName: String)(
      implicit conjunction: Option[SQLKey] = Some(AND())
  ): String = {
    this.testColumnName(columnName)
    list match {
      case Some(idList) if idList.nonEmpty =>
        s"${this.getSqlKey} $columnName IN (${idList.mkString(",")})"
      case _ => ""
    }
  }

  def getOptionalFilter(filterValue: Option[Any], columnName: String, key: String) = {
    filterValue match {
      case Some(value) => s"$columnName = {$key}"
      case None        => ""
    }
  }

  def getOptionalMatchFilter(filterValue: Option[Any], columnName: String, key: String) = {
    filterValue match {
      case Some(value) => s"LOWER($columnName) LIKE LOWER({$key})"
      case None        => ""
    }
  }

  def getIntListFilter(list: Option[List[Int]], columnName: String)(
      implicit conjunction: Option[SQLKey] = Some(AND())
  ): String = {
    this.testColumnName(columnName)
    list match {
      case Some(idList) if idList.nonEmpty =>
        s"${this.getSqlKey} $columnName IN (${idList.mkString(",")})"
      case _ => ""
    }
  }

  private def testColumnName(columnName: String): Unit = {
    if (!columnName.forall(this.ordinary.contains)) {
      throw new SQLException(s"Invalid column name provided `$columnName`")
    }
  }

  private def getSqlKey(implicit conjunction: Option[SQLKey]): String = {
    conjunction match {
      case Some(c) => c.getSQLKey()
      case None    => ""
    }
  }

  def getDateClause(column: String, start: Option[DateTime] = None, end: Option[DateTime] = None)(
      implicit sqlKey: Option[SQLKey] = None
  ): String = {
    this.testColumnName(column)
    val dates = getDates(start, end)
    s"${this.getSqlKey} $column::date BETWEEN '${dates._1}' AND '${dates._2}'"
  }

  def getDates(start: Option[DateTime] = None, end: Option[DateTime] = None): (String, String) = {
    val startDate = start match {
      case Some(s) => dateFormat.print(s)
      case None    => dateFormat.print(DateTime.now().minusWeeks(1))
    }
    val endDate = end match {
      case Some(e) => dateFormat.print(e)
      case None    => dateFormat.print(DateTime.now())
    }
    (startDate, endDate)
  }

  /**
    * All MapRoulette objects contain the enabled column that define whether it is enabled in the
    * system or not. This will create the WHERE part of the clause checking for enabled values in the
    * query
    *
    * @param value       If looking only for enabled elements this needs to be set to true
    * @param tablePrefix If used as part of a join or simply the table alias if required
    * @param key         Defaulted to "AND"
    * @return
    */
  @Deprecated
  def enabled(value: Boolean, tablePrefix: String = "")(
      implicit key: Option[SQLKey] = Some(AND())
  ): String = {
    if (value) {
      s"${this.getSqlKey} ${this.getPrefix(tablePrefix)}enabled = TRUE"
    } else {
      ""
    }
  }

  /**
    * Just appends the period at the end of the table prefix if the provided string is not empty
    *
    * @param prefix The table prefix that is being used in the query
    * @return
    */
  private def getPrefix(prefix: String): String =
    if (StringUtils.isEmpty(prefix) || !prefix.forall(this.ordinary.contains)) "" else s"$prefix."

  /**
    * This function will handle the conjunction in a where clause. So if you are you creating
    * a dynamic where clause this will handle adding the conjunction clause if required
    *
    * @param whereClause The StringBuilder where clause
    * @param value       The value that is being appended
    * @param conjunction The conjunction, by default AND
    */
  @deprecated
  def appendInWhereClause(whereClause: StringBuilder, value: String)(
      implicit conjunction: Option[SQLKey] = Some(AND())
  ): Unit = {
    if (whereClause.nonEmpty && value.nonEmpty) {
      whereClause ++= s" ${this.getSqlKey} $value"
    } else {
      whereClause ++= value
    }
  }

  /**
    * Set the search field in the where clause correctly, it will also surround the values
    * with LOWER to make sure that match is case insensitive
    *
    * @param column      The column that you are searching against
    * @param conjunction Default is AND, but can use AND or OR
    * @param key         The search string that you are testing against
    * @return
    */
  def searchField(column: String, key: String = "ss", invert: Boolean = false)(
      implicit conjunction: Option[SQLKey] = Some(AND())
  ): String =
    s" ${this.getSqlKey} LOWER($column) ${if (invert) "NOT" else ""} LIKE LOWER({$key})"

  /**
    * Adds fuzzy search to any query. This will include the Levenshtein, Metaphone and Soundex functions
    * that will search the string. On large datasets this could potentially decrease performance
    *
    * @param column            The column that we are comparing
    * @param key               The key used in anorm for the value to compare with
    * @param levenshsteinScore The levenshstein score, which is the difference between the strings
    * @param metaphoneSize     The maximum size of the metaphone code
    * @param conjunction       Default AND
    * @return A string with all the fuzzy search functions
    */
  def fuzzySearch(
      column: String,
      key: String = "ss",
      levenshsteinScore: Int = DALHelper.DEFAULT_LEVENSHSTEIN_SCORE,
      metaphoneSize: Int = DALHelper.DEFAULT_METAPHONE_SIZE
  )(implicit conjunction: Option[SQLKey] = Some(AND())): String = {
    val score = if (levenshsteinScore > 0) {
      levenshsteinScore
    } else {
      3
    }
    s""" ${this.getSqlKey} ($column <> '' AND
          (LEVENSHTEIN(LOWER($column), LOWER({$key})) < $score OR
            METAPHONE(LOWER($column), $metaphoneSize) = METAPHONE(LOWER({$key}), $metaphoneSize) OR
            SOUNDEX(LOWER($column)) = SOUNDEX(LOWER({$key})))
          )"""
  }

  def addChallengeTagMatchingToQuery(
      params: SearchParameters,
      whereClause: StringBuilder,
      joinClause: StringBuilder,
      challengePrefix: String = "c"
  ): ListBuffer[NamedParameter] = {
    val parameters = new ListBuffer[NamedParameter]()

    params.challengeParams.challengeTags match {
      case Some(ct) if ct.nonEmpty =>
        joinClause ++=
          s"""
                  INNER JOIN tags_on_challenges toc ON toc.challenge_id = $challengePrefix.id
                  INNER JOIN tags tgs ON tgs.id = toc.tag_id
                  """
        val tags = ListBuffer[String]()
        ct.zipWithIndex.foreach(tagWithIndex => {
          parameters += new NamedParameter(s"tag${tagWithIndex._2}", tagWithIndex._1)
          tags += s"{tag${tagWithIndex._2}}"
        })

        this.appendInWhereClause(whereClause, s"tgs.name IN (${tags.mkString(",")})")
      case _ => // ignore
    }
    parameters
  }

  /**
    * Our key for our objects are current Long, but can support String if need be. This function
    * handles transforming java objects to SQL for a specific set related to the object key
    *
    * @tparam Key The type of Key, this is currently always Long, but could be changed easily enough in the future
    * @return
    */
  def keyToStatement[Key]: ToStatement[Key] = {
    new ToStatement[Key] {
      def set(s: PreparedStatement, i: Int, identifier: Key) =
        identifier match {
          case id: String                  => ToStatement.stringToStatement.set(s, i, id)
          case Some(id: String)            => ToStatement.stringToStatement.set(s, i, id)
          case id: Long                    => ToStatement.longToStatement.set(s, i, id)
          case Some(id: Long)              => ToStatement.longToStatement.set(s, i, id)
          case intValue: Integer           => ToStatement.integerToStatement.set(s, i, intValue)
          case list: List[Long @unchecked] => ToStatement.listToStatement[Long].set(s, i, list)
        }
    }
  }
}

object DALHelper {
  private val DEFAULT_LEVENSHSTEIN_SCORE = 3
  private val DEFAULT_METAPHONE_SIZE     = 4
}

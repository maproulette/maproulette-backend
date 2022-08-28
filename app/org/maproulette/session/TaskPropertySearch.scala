/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.session

import org.maproulette.exception.InvalidException

/**
  * This holds the task property search parameters that are used for doing
  * complex nested task property searches on tasks.
  *
  * @author krotstan
  */
case class TaskPropertySearch(
    key: Option[String] = None,
    value: Option[String] = None,
    valueType: Option[String] = None,
    searchType: Option[String] = None,    // contains/not_equals/equals
    operationType: Option[String] = None, // and/or
    left: Option[TaskPropertySearch] = None,
    right: Option[TaskPropertySearch] = None
) {
  def toSQL: String = {
    this.validate

    val where = new StringBuilder
    left match {
      case Some(l) => where ++= "(" + l.toSQL + ") " + operationType.get
      case None    => // do nothing
    }

    right match {
      case Some(r) => where ++= " (" + r.toSQL + ")"
      case None    => // do nothing
    }

    valueType.getOrElse("") match {
      case SearchParameters.TASK_PROP_VALUE_TYPE_NUMBER =>
        where ++= s" CAST(features->'properties'->>'${key.get}' AS DOUBLE PRECISION)" +
          getSearchTypeSQL + value.getOrElse(0)
      case SearchParameters.TASK_PROP_VALUE_TYPE_STRING => {
        where ++= s" features->'properties'->>'${key.get.replaceAll("\'", "\'\'")}' " + getSearchTypeSQL
        searchType.get match {
          case SearchParameters.TASK_PROP_SEARCH_TYPE_CONTAINS =>
            where ++= "'%" + value.getOrElse("").replaceAll("\'", "\'\'") + "%' "
          case SearchParameters.TASK_PROP_SEARCH_TYPE_EXISTS  => // do nothing
          case SearchParameters.TASK_PROP_SEARCH_TYPE_MISSING => // do nothing
          case _ =>
            where ++= " '" + value.getOrElse("").replaceAll("\'", "\'\'") + "'"
        }
      }
      case _ => // do nothing
    }

    where.toString
  }

  def getSearchTypeSQL: String = {
    searchType.getOrElse("") match {
      case SearchParameters.TASK_PROP_SEARCH_TYPE_EQUALS       => "="
      case SearchParameters.TASK_PROP_SEARCH_TYPE_NOT_EQUAL    => "!="
      case SearchParameters.TASK_PROP_SEARCH_TYPE_CONTAINS     => " LIKE "
      case SearchParameters.TASK_PROP_SEARCH_TYPE_EXISTS       => " IS NOT NULL "
      case SearchParameters.TASK_PROP_SEARCH_TYPE_MISSING      => " IS NULL "
      case SearchParameters.TASK_PROP_SEARCH_TYPE_LESS_THAN    => "<"
      case SearchParameters.TASK_PROP_SEARCH_TYPE_GREATER_THAN => ">"
      case e                                                   => throw new InvalidException("Search Type: '" + e + "' not supported.")
    }
  }

  /**
    * Validates the TaskPropertySearch
    *
    * To be valid:
    * left/right/operationtype must all be present
    * OR key/value/searchType must be present
    * operation type must be "or" or "and"
    * search type must be "contains"/"equals"/"not_equal"/"less_than"/"greater_than"
    * key must not contain weird characters (')
    * value must not contain weird characters (')
    * value type must be "string"/"number"
    * value must be a number if value type is "number"
    * TASK_PROP_SEARCH_TYPE_CONTAINS only ok for value type "string"
    * TASK_PROP_SEARCH_TYPE_LESS_THAN or TASK_PROP_SEARCH_TYPE_GREATER_THAN ok for value type "number"
    */
  def validate: Unit = {
    var valid: Boolean =
      left match {
        case Some(l) => {
          l.validate
          right match {
            case Some(r) => {
              r.validate
              operationType match {
                case Some(SearchParameters.TASK_PROP_OPERATION_TYPE_AND) => true
                case Some(SearchParameters.TASK_PROP_OPERATION_TYPE_OR)  => true
                case _ =>
                  throw new InvalidException(
                    "TaskPropertySearch: A valid OperationType of 'or' or 'and' must be provided."
                  )
              }
            }
            case None =>
              throw new InvalidException(
                "TaskPropertySearch: Both a right and left side must be provided."
              )
          }
        }
        case None => true
      }

    if (right != None && left == None) {
      throw new InvalidException("TaskPropertySearch: Both a right and left side must be provided.")
    }

    valid = valid &&
      (key match {
        case Some(k) => {
          (valueType.getOrElse("") match {
            case SearchParameters.TASK_PROP_VALUE_TYPE_NUMBER => {
              if (!scala.util.Try(value.getOrElse("").toDouble).isSuccess)
                throw new InvalidException(
                  "TaskPropertySearch: Value " + value.getOrElse("") + " is not a number."
                )

              searchType.getOrElse("") match {
                case SearchParameters.TASK_PROP_SEARCH_TYPE_EQUALS       => true
                case SearchParameters.TASK_PROP_SEARCH_TYPE_NOT_EQUAL    => true
                case SearchParameters.TASK_PROP_SEARCH_TYPE_LESS_THAN    => true
                case SearchParameters.TASK_PROP_SEARCH_TYPE_GREATER_THAN => true
                case e =>
                  throw new InvalidException(
                    "Search Type: '" + e + "' not supported when comparing numerics."
                  )
              }
            }
            case SearchParameters.TASK_PROP_VALUE_TYPE_STRING => {
              searchType.getOrElse("") match {
                case SearchParameters.TASK_PROP_SEARCH_TYPE_EQUALS    => true
                case SearchParameters.TASK_PROP_SEARCH_TYPE_NOT_EQUAL => true
                case SearchParameters.TASK_PROP_SEARCH_TYPE_CONTAINS  => true
                case SearchParameters.TASK_PROP_SEARCH_TYPE_EXISTS    => true
                case SearchParameters.TASK_PROP_SEARCH_TYPE_MISSING   => true
                case e =>
                  throw new InvalidException(
                    "Search Type: '" + e + "' not supported when comparing strings."
                  )
              }
            }
            case _ =>
              throw new InvalidException(
                "TaskPropertySearch: A valid ValueType of 'string' or 'number' must be provided."
              )
          })
        }
        case None => {
          if (left == None)
            throw new InvalidException(
              "TaskPropertySearch: A key must be specified when not providing a right and left."
            )
          else true
        }
      })

    if (!valid) throw new InvalidException("Invalid task property search.")
  }
}

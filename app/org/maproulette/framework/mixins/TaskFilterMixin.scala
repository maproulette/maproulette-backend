/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.mixins

import org.maproulette.framework.model.{User, Challenge, Project}
import org.maproulette.framework.psql.{Query, _}
import org.maproulette.framework.psql.filter._
import org.maproulette.data.Actions

/**
  * TaskFilterMixin provides task related methods
  */
trait TaskFilterMixin {

  /**
    * Creates an Order from the sort and direction
    */
  def getOrder(sort: String = "", orderDirection: String = ""): Order = {
    val direction = orderDirection match {
      case "DESC" => Order.DESC
      case _      => Order.ASC
    }

    Order(
      List(
        sort match {
          case "reviewRequestedBy" =>
            OrderField("review_requested_by", direction, Some("task_review"))
          case "reviewedBy" =>
            OrderField("reviewed_by", direction, Some("task_review"))
          case "metaReviewedBy" =>
            OrderField("meta_reviewed_by", direction, Some("task_review"))
          case "reviewStatus" =>
            OrderField("review_status", direction, Some("task_review"))
          case "metaReviewStatus" =>
            OrderField("meta_review_status", direction, Some("task_review"))
          case "reviewedAt" =>
            OrderField("reviewed_at", direction, Some("task_review"))
          case "metaReviewedAt" =>
            OrderField("meta_reviewed_at", direction, Some("task_review"))
          case "mappedOn" =>
            OrderField("mapped_on", direction, Some("tasks"))
          case "completedBy" =>
            OrderField("completed_by", direction, Some("tasks"))
          case "completedTimeSpent" =>
            OrderField("completed_time_spent", direction, Some("tasks"))
          case "reviewDuration" =>
            OrderField("reviewDuration", direction, Some(""))
          case "" =>
            OrderField("RANDOM()", table = Some(""), isColumn = false)
          case _ =>
            OrderField(sort, direction, Some("tasks"))
        }
      )
    )
  }

  /**
    * Adds filter to exclude locked tasks
    *
    * @param user
    * @param query
    * @param ignoreLocked
    */
  def filterOutLocked(user: User, query: Query, ignoreLocked: Boolean = false): Query = {
    ignoreLocked match {
      case true => query
      case false =>
        query.addFilterGroup(
          FilterGroup(
            List(
              CustomParameter(
                s"""tasks.id NOT IN (select item_id from locked WHERE
                  item_id = tasks.id AND item_type = ${Actions.ITEM_TYPE_TASK}
                  AND user_id != ${user.id})"""
              )
            )
          )
        )
    }
  }

  /**
    * Adds filter on deleted projects and challenges to query
    */
  def filterOutDeletedParents(query: Query): Query = {
    query.addFilterGroup(
      FilterGroup(
        List(
          BaseParameter(
            Challenge.FIELD_DELETED,
            false,
            Operator.EQ,
            useValueDirectly = true,
            table = Some("c")
          ),
          BaseParameter(
            Project.FIELD_DELETED,
            false,
            Operator.EQ,
            useValueDirectly = true,
            table = Some("p")
          )
        )
      )
    )
  }
}

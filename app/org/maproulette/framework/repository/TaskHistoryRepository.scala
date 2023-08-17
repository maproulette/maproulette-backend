/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import org.joda.time.DateTime

import anorm.SqlParser.get
import anorm.{RowParser, ~, SQL}
import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model.{TaskLogEntry, TaskReview}
import play.api.db.Database

/**
  * @author krotstan
  */
@Singleton
class TaskHistoryRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = "actions"

  private val commentEntryParser: RowParser[TaskLogEntry] = {
    get[Long]("task_comments.task_id") ~
      get[DateTime]("task_comments.created") ~
      get[Int]("users.id") ~
      get[String]("task_comments.comment") map {
      case taskId ~ created ~ userId ~ comment =>
        new TaskLogEntry(
          taskId,
          created,
          TaskLogEntry.ACTION_COMMENT,
          Some(userId),
          None,
          None,
          None,
          None,
          None,
          None,
          Some(comment),
          None
        )
    }
  }

  private val reviewHistoryParser: RowParser[TaskReview] = {
    get[Long]("id") ~
      get[Long]("task_id") ~
      get[Option[DateTime]]("reviewed_at") ~
      get[Option[DateTime]]("review_started_at") ~
      get[Option[Int]]("review_status") ~
      get[Option[String]]("requested_by") ~
      get[Option[String]]("reviewed_by") ~
      get[Option[Int]]("meta_review_status") ~
      get[Option[String]]("meta_reviewed_by") ~
      get[Option[DateTime]]("meta_reviewed_at") ~
      get[Option[String]]("error_tags") map {
      case id ~ taskId ~ reviewedAt ~ reviewStartedAt ~ reviewStatus ~ requestedBy ~
            reviewedBy ~ metaReviewStatus ~ metaReviewedBy ~ metaReviewedAt ~ errorTags =>
        new TaskReview(
          id,
          taskId,
          reviewStatus,
          None,
          None,
          None,
          None,
          requestedBy,
          None,
          reviewedBy,
          reviewedAt,
          None,
          metaReviewStatus,
          metaReviewedAt,
          reviewStartedAt,
          None,
          None,
          None,
          None,
          None,
          metaReviewedBy,
          errorTags = errorTags.getOrElse("")
        )
    }
  }

  private val reviewEntryParser: RowParser[TaskLogEntry] = {
    get[Long]("task_id") ~
      get[Option[DateTime]]("reviewed_at") ~
      get[Option[DateTime]]("review_started_at") ~
      get[Option[Int]]("review_status") ~
      get[Int]("requested_by") ~
      get[Option[Int]]("reviewed_by") ~
      get[Option[Int]]("meta_review_status") ~
      get[Option[Int]]("meta_reviewed_by") ~
      get[Option[DateTime]]("meta_reviewed_at") ~
      get[Option[String]]("error_tags") map {
      case taskId ~ reviewedAt ~ reviewStartedAt ~ reviewStatus ~ requestedBy ~
            reviewedBy ~ metaReviewStatus ~ metaReviewedBy ~ metaReviewedAt ~ errorTags => {
        reviewStatus match {
          case None =>
            new TaskLogEntry(
              taskId,
              metaReviewedAt.get,
              TaskLogEntry.ACTION_META_REVIEW,
              None,
              None,
              None,
              reviewStartedAt,
              metaReviewStatus,
              Some(requestedBy),
              metaReviewedBy,
              None,
              errorTags = errorTags
            )
          case _ =>
            new TaskLogEntry(
              taskId,
              reviewedAt.getOrElse(reviewStartedAt.get),
              TaskLogEntry.ACTION_REVIEW,
              None,
              None,
              None,
              reviewStartedAt,
              reviewStatus,
              Some(requestedBy),
              reviewedBy,
              None,
              errorTags = errorTags
            )
        }
      }
    }
  }

  private val statusActionEntryParser: RowParser[TaskLogEntry] = {
    get[Long]("status_actions.task_id") ~
      get[DateTime]("status_actions.created") ~
      get[Option[Int]]("users.id") ~
      get[Int]("status_actions.old_status") ~
      get[Int]("status_actions.status") ~
      get[Option[DateTime]]("status_actions.started_at") map {
      case taskId ~ created ~ userId ~ oldStatus ~ status ~
            startedAt =>
        new TaskLogEntry(
          taskId,
          created,
          TaskLogEntry.ACTION_STATUS_CHANGE,
          userId,
          Some(oldStatus),
          Some(status),
          startedAt,
          None,
          None,
          None,
          None,
          None
        )
    }
  }

  private val actionEntryParser: RowParser[TaskLogEntry] = {
    get[Long]("actions.item_id") ~
      get[DateTime]("actions.created") ~
      get[Option[Int]]("users.id") map {
      case taskId ~ created ~ userId =>
        new TaskLogEntry(
          taskId,
          created,
          TaskLogEntry.ACTION_UPDATE,
          userId,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          None
        )
    }
  }

  /**
    * Returns comments
    * @param taskId
    * @return List of TaskLogEntry
    */
  def getComments(taskId: Long): List[TaskLogEntry] = {
    this.withMRConnection { implicit c =>
      SQL(s"""SELECT tc.task_id, tc.created, users.id, tc.comment FROM task_comments tc
            INNER JOIN users on users.osm_id=tc.osm_id
            WHERE task_id = $taskId""").as(this.commentEntryParser.*)
    }
  }

  /**
    * Returns reviews
    * @param taskId
    * @return List of TaskLogEntry
    */
  def getReviews(taskId: Long): List[TaskLogEntry] = {
    this.withMRConnection { implicit c =>
      SQL(s"""SELECT * FROM task_review_history trh WHERE task_id = $taskId""").as(
        this.reviewEntryParser.*
      )
    }
  }

  /**
    * Returns reviews with a review history specific format
    * @param taskId
    * @return List of TaskLogEntry
    */
  def getReviewLogs(taskId: Long): List[TaskReview] = {
    this.withMRConnection { implicit c =>
      SQL(s"""SELECT trh.id, trh.task_id, trh.reviewed_at, trh.review_started_at, trh.review_status,
           | (SELECT name as requested_by FROM users WHERE users.id = trh.requested_by),
           | (SELECT name as reviewed_by FROM users WHERE users.id = trh.reviewed_by),
           | trh.meta_review_status,
           | (SELECT name as meta_reviewed_by FROM users WHERE users.id = trh.meta_reviewed_by),
           | trh.meta_reviewed_at,
           | trh.error_tags
           |FROM task_review_history trh
           |WHERE task_id = $taskId""".stripMargin).as(
        this.reviewHistoryParser.*
      )
    }
  }

  /**
    * Returns statusActions
    * @param taskId
    * @return List of TaskLogEntry
    */
  def getStatusActions(taskId: Long): List[TaskLogEntry] = {
    this.withMRConnection { implicit c =>
      SQL(s"""SELECT sa.task_id, sa.created, users.id, sa.old_status, sa.status, sa.started_at
            FROM status_actions sa
            LEFT OUTER JOIN users on users.osm_id=sa.osm_user_id
            WHERE task_id = $taskId""").as(this.statusActionEntryParser.*)
    }
  }

  /**
    * Returns actions
    * @param taskId
    * @return List of TaskLogEntry
    */
  def getActions(taskId: Long, actionType: Int): List[TaskLogEntry] = {
    this.withMRConnection { implicit c =>
      SQL(s"""SELECT actions.item_id, actions.created, users.id
            FROM actions
            LEFT OUTER JOIN users on users.osm_id=actions.osm_user_id
            WHERE actions.item_id = $taskId AND actions.action = $actionType
         """).as(this.actionEntryParser.*)
    }
  }
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.mixins

import anorm.SqlParser.get
import anorm.{RowParser, ~}
import org.joda.time.DateTime

import org.maproulette.framework.model.{TaskReview, TaskReviewFields, TaskWithReview, Task}

/**
  * TaskParserMixin provides task parsers
  */
trait TaskParserMixin {
  // The columns to be retrieved for the task. Reason this is required is because one of the columns
  // "tasks.location" is a PostGIS object in the database and we want it returned in GeoJSON instead
  // so the ST_AsGeoJSON function is used to convert it to geoJSON
  val retrieveColumnsWithReview: String =
    "*, tasks.geojson::TEXT AS geo_json, " +
      "tasks.cooperative_work_json::TEXT AS cooperative_work, tasks.completion_responses::TEXT AS responses, " +
      "ST_AsGeoJSON(tasks.location) AS geo_location " +
      ", task_review.review_status, task_review.review_requested_by, " +
      "task_review.reviewed_by, task_review.reviewed_at, task_review.review_started_at, " +
      "task_review.meta_reviewed_by, task_review.meta_review_status, task_review.meta_reviewed_at, " +
      "task_review.review_claimed_by, task_review.review_claimed_at, task_review.additional_reviewers, task_review.error_tags "

  // The anorm row parser to convert records from the task table to task objects
  def getTaskParser(
      updateAndRetrieve: (Long, Option[String], Option[String], Option[String]) => (
          String,
          Option[String],
          Option[String]
      )
  ): RowParser[Task] = {
    get[Long]("tasks.id") ~
      get[String]("tasks.name") ~
      get[DateTime]("tasks.created") ~
      get[DateTime]("tasks.modified") ~
      get[Long]("tasks.parent_id") ~
      get[Option[String]]("tasks.instruction") ~
      get[Option[String]]("geo_location") ~
      get[Option[Int]]("tasks.status") ~
      get[Option[String]]("geo_json") ~
      get[Option[String]]("cooperative_work") ~
      get[Option[DateTime]]("tasks.mapped_on") ~
      get[Option[Long]]("tasks.completed_time_spent") ~
      get[Option[Long]]("tasks.completed_by") ~
      get[Option[Int]]("task_review.review_status") ~
      get[Option[Long]]("task_review.review_requested_by") ~
      get[Option[Long]]("task_review.reviewed_by") ~
      get[Option[DateTime]]("task_review.reviewed_at") ~
      get[Option[Long]]("task_review.meta_reviewed_by") ~
      get[Option[Int]]("task_review.meta_review_status") ~
      get[Option[DateTime]]("task_review.meta_reviewed_at") ~
      get[Option[DateTime]]("task_review.review_started_at") ~
      get[Option[Long]]("task_review.review_claimed_by") ~
      get[Option[DateTime]]("task_review.review_claimed_at") ~
      get[Option[List[Long]]]("task_review.additional_reviewers") ~
      get[Int]("tasks.priority") ~
      get[Option[Long]]("tasks.changeset_id") ~
      get[Option[String]]("responses") ~
      get[Option[Long]]("tasks.bundle_id") ~
      get[Option[Boolean]]("tasks.is_bundle_primary") ~
      get[Option[String]]("task_review.error_tags") map {
      case id ~ name ~ created ~ modified ~ parent_id ~ instruction ~ location ~ status ~ geojson ~
            cooperativeWork ~ mappedOn ~ completedTimeSpent ~ completedBy ~ reviewStatus ~
            reviewRequestedBy ~ reviewedBy ~ reviewedAt ~ metaReviewedBy ~
            metaReviewStatus ~ metaReviewedAt ~ reviewStartedAt ~ reviewClaimedBy ~ reviewClaimedAt ~
            additionalReviewers ~ priority ~ changesetId ~ responses ~ bundleId ~ isBundlePrimary ~ errorTags =>
        val values = updateAndRetrieve(id, geojson, location, cooperativeWork)
        Task(
          id,
          name,
          created,
          modified,
          parent_id,
          instruction,
          values._2,
          values._1,
          values._3,
          status,
          mappedOn,
          completedTimeSpent,
          completedBy,
          TaskReviewFields(
            reviewStatus,
            reviewRequestedBy,
            reviewedBy,
            reviewedAt,
            metaReviewedBy = metaReviewedBy,
            metaReviewStatus = metaReviewStatus,
            metaReviewedAt,
            reviewStartedAt,
            reviewClaimedBy,
            reviewClaimedAt,
            additionalReviewers
          ),
          priority,
          changesetId,
          responses,
          bundleId,
          isBundlePrimary,
          errorTags = errorTags.getOrElse("")
        )
    }
  }

    def getTaskReviewParser(
      updateAndRetrieve: (Long, Option[String], Option[String], Option[String]) => (
          String,
          Option[String],
          Option[String]
      )
  ): RowParser[TaskReview] = {
      get[Long]("id") ~
      get[Long]("task_id") ~
      get[Int]("review_status").? ~
      get[String]("challenge_name").? ~
      get[Long]("review_requested_by").? ~
      get[String]("review_requested_by_username").? ~
      get[Long]("reviewed_by").? ~
      get[String]("reviewed_by_username").? ~
      get[DateTime]("reviewed_at").? ~
      get[Long]("meta_reviewed_by").? ~
      get[Int]("meta_review_status").? ~
      get[DateTime]("meta_reviewed_at").? ~
      get[DateTime]("review_started_at").? ~
      get[List[Long]]("additional_reviewers").? ~
      get[Long]("review_claimed_by").? ~
      get[String]("review_claimed_by_username").? ~
      get[DateTime]("review_claimed_at").? ~
      get[Int]("original_reviewer").? ~
      get[String]("meta_reviewed_by_username").? ~
      get[String]("error_tags") map {
        case id ~ taskId ~ reviewStatus ~ challengeName ~ reviewRequestedBy ~ 
             reviewRequestedByUsername ~ reviewedBy ~ reviewedByUsername ~ reviewedAt ~ 
             metaReviewedBy ~ metaReviewStatus ~ metaReviewedAt ~ reviewStartedAt ~ 
             additionalReviewers ~ reviewClaimedBy ~ reviewClaimedByUsername ~ 
             reviewClaimedAt ~ originalReviewer ~ metaReviewedByUsername ~ errorTags => {
        new TaskReview(
            id = id,
            taskId,
            reviewStatus,
            challengeName,
            reviewRequestedBy,
            reviewRequestedByUsername,
            reviewedBy,
            reviewedByUsername,
            reviewedAt,
            metaReviewedBy,
            metaReviewStatus,
            metaReviewedAt,
            reviewStartedAt,
            additionalReviewers,
            reviewClaimedBy,
            reviewClaimedByUsername,
            reviewClaimedAt,
            None,
            None,
            errorTags = ""
        )
      }
    }
  }


  def getTaskWithReviewParser(
      updateAndRetrieve: (Long, Option[String], Option[String], Option[String]) => (
          String,
          Option[String],
          Option[String]
      )
  ): RowParser[TaskWithReview] = {
    get[Long]("tasks.id") ~
      get[String]("tasks.name") ~
      get[DateTime]("tasks.created") ~
      get[DateTime]("tasks.modified") ~
      get[Long]("tasks.parent_id") ~
      get[Option[String]]("tasks.instruction") ~
      get[Option[String]]("geo_location") ~
      get[Option[Int]]("tasks.status") ~
      get[Option[String]]("geo_json") ~
      get[Option[String]]("cooperative_work") ~
      get[Option[DateTime]]("tasks.mapped_on") ~
      get[Option[Long]]("tasks.completed_time_spent") ~
      get[Option[Long]]("tasks.completed_by") ~
      get[Option[Int]]("task_review.review_status") ~
      get[Option[Long]]("task_review.review_requested_by") ~
      get[Option[Long]]("task_review.reviewed_by") ~
      get[Option[DateTime]]("task_review.reviewed_at") ~
      get[Option[Long]]("task_review.meta_reviewed_by") ~
      get[Option[Int]]("task_review.meta_review_status") ~
      get[Option[DateTime]]("task_review.meta_reviewed_at") ~
      get[Option[DateTime]]("task_review.review_started_at") ~
      get[Option[Long]]("task_review.review_claimed_by") ~
      get[Option[DateTime]]("task_review.review_claimed_at") ~
      get[Option[List[Long]]]("task_review.additional_reviewers") ~
      get[Option[String]]("task_review.error_tags") ~
      get[Int]("tasks.priority") ~
      get[Option[Long]]("tasks.changeset_id") ~
      get[Option[Long]]("tasks.bundle_id") ~
      get[Option[Boolean]]("tasks.is_bundle_primary") ~
      get[Option[String]]("challenge_name") ~
      get[Option[String]]("review_requested_by_username") ~
      get[Option[String]]("reviewed_by_username") ~
      get[Option[String]]("responses") map {
      case id ~ name ~ created ~ modified ~ parent_id ~ instruction ~ location ~ status ~ geojson ~
            cooperativeWork ~ mappedOn ~ completedTimeSpent ~ completedBy ~ reviewStatus ~ reviewRequestedBy ~
            reviewedBy ~ reviewedAt ~ metaReviewedBy ~ metaReviewStatus ~ metaReviewedAt ~ reviewStartedAt ~
            reviewClaimedBy ~ reviewClaimedAt ~ additionalReviewers ~ errorTags ~
            priority ~ changesetId ~ bundleId ~ isBundlePrimary ~ challengeName ~ reviewRequestedByUsername ~
            reviewedByUsername ~ responses =>
        val values = updateAndRetrieve(id, geojson, location, cooperativeWork)
        TaskWithReview(
          Task(
            id,
            name,
            created,
            modified,
            parent_id,
            instruction,
            values._2,
            values._1,
            values._3,
            status,
            mappedOn,
            completedTimeSpent,
            completedBy,
            TaskReviewFields(
              reviewStatus,
              reviewRequestedBy,
              reviewedBy,
              reviewedAt,
              metaReviewedBy,
              metaReviewStatus,
              metaReviewedAt,
              reviewStartedAt,
              reviewClaimedBy,
              reviewClaimedAt,
              additionalReviewers
            ),
            priority,
            changesetId,
            responses,
            bundleId,
            isBundlePrimary,
            errorTags = errorTags.getOrElse("")
          ),
          TaskReview(
            -1,
            id,
            reviewStatus,
            challengeName,
            reviewRequestedBy,
            reviewRequestedByUsername,
            reviewedBy,
            reviewedByUsername,
            reviewedAt,
            metaReviewedBy,
            metaReviewStatus,
            metaReviewedAt,
            reviewStartedAt,
            additionalReviewers,
            reviewClaimedBy,
            None,
            None,
            errorTags = errorTags.getOrElse("")
          )
        )
    }
  }
}

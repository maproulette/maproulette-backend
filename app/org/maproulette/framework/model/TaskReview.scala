/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.joda.time.DateTime
import org.maproulette.framework.psql.CommonField
import org.maproulette.framework.model.Task
import play.api.libs.json.{Json, Reads, Writes}
import play.api.libs.json.JodaWrites._
import play.api.libs.json.JodaReads._

case class TaskReview(
    id: Long,
    taskId: Long,
    reviewStatus: Option[Int],
    challengeName: Option[String],
    projectName: Option[String],
    projectId: Option[Int],
    reviewRequestedBy: Option[Long],
    reviewRequestedByUsername: Option[String],
    reviewedBy: Option[Long],
    reviewedByUsername: Option[String],
    reviewedAt: Option[DateTime],
    metaReviewedBy: Option[Long],
    metaReviewStatus: Option[Int],
    metaReviewedAt: Option[DateTime],
    reviewStartedAt: Option[DateTime],
    additionalReviewers: Option[List[Long]],
    reviewClaimedBy: Option[Long],
    reviewClaimedByUsername: Option[String],
    reviewClaimedAt: Option[DateTime],
    originalReviewer: Option[Int] = None,
    metaReviewedByUsername: Option[String] = None,
    errorTags: String = ""
)
object TaskReview extends CommonField {
  implicit val writes: Writes[TaskReview] = Json.writes[TaskReview]
  implicit val reads: Reads[TaskReview]   = Json.reads[TaskReview]

  val TABLE                     = "task_review"
  val FIELD_TASK_ID             = "task_id"
  val FIELD_REVIEW_STATUS       = "review_status"
  val FIELD_REVIEW_REQUESTED_BY = "review_requested_by"
  val FIELD_REVIEWED_BY         = "reviewed_by"
  val FIELD_REVIEWED_AT         = "reviewed_at"
  val FIELD_META_REVIEWED_BY    = "meta_reviewed_by"
  val FIELD_META_REVIEWED_AT    = "meta_reviewed_at"
  val FIELD_META_REVIEW_STATUS  = "meta_review_status"
  val FIELD_REVIEW_CLAIMED_AT   = "review_claimed_at"
  val FIELD_REVIEW_CLAIMED_BY   = "review_claimed_by"
  val FIELD_REVIEW_STARTED_AT   = "review_started_at"
}

case class TaskWithReview(task: Task, review: TaskReview)
object TaskWithReview {
  implicit val taskWithReviewWrites: Writes[TaskWithReview] = Json.writes[TaskWithReview]
  implicit val taskWithReviewReads: Reads[TaskWithReview]   = Json.reads[TaskWithReview]
}

case class ReviewMetrics(
    total: Int,
    reviewRequested: Int,
    reviewApproved: Int,
    reviewRejected: Int,
    reviewAssisted: Int,
    reviewDisputed: Int,
    metaReviewRequested: Int,
    metaReviewApproved: Int,
    metaReviewRejected: Int,
    metaReviewAssisted: Int,
    fixed: Int,
    falsePositive: Int,
    skipped: Int,
    alreadyFixed: Int,
    tooHard: Int,
    avgReviewTime: Double,
    userId: Option[Long] = None,    // If these metrics apply to a particular user
    tagName: Option[String] = None, // If these metrics apply for a particular tag
    tagType: Option[String] = None  // If these metrics apply for a particular tag with tag type
)
object ReviewMetrics {
  implicit val reviewMetricsWrites = Json.writes[ReviewMetrics]
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.utils

import org.maproulette.framework.model._
import org.maproulette.models.utils.ChallengeReads
import play.api.libs.json.Reads
import play.api.libs.oauth.RequestToken

/**
  * @author mcuthbert
  */
trait Readers extends ChallengeReads {
  // User Readers
  implicit val tokenReads: Reads[RequestToken]    = User.tokenReads
  implicit val settingsReads: Reads[UserSettings] = User.settingsReads
  implicit val locationReads: Reads[Location]     = User.locationReads
  implicit val osmReads: Reads[OSMProfile]        = User.osmReads
  // Grant Readers
  implicit val grantReads: Reads[Grant] = Grant.reads
  // Point Readers
  implicit val pointReads: Reads[Point]                   = ClusteredPoint.pointReads
  implicit val clusteredPointReads: Reads[ClusteredPoint] = ClusteredPoint.clusteredPointReads
  // Comment Readers
  implicit val commentReads: Reads[Comment]                   = Comment.reads
  implicit val challengeCommentReads: Reads[ChallengeComment] = ChallengeComment.reads
  // Project Readers
  implicit val projectReads: Reads[Project] = Project.reads
  // Tag Readers
  implicit val tagReads: Reads[Tag] = Tag.tagReads
  // Task Readers
  implicit val taskReads                                  = Task.TaskFormat
  implicit val taskClusterReads: Reads[TaskCluster]       = TaskCluster.taskClusterReads
  implicit val taskLogEntryReads: Reads[TaskLogEntry]     = TaskLogEntry.taskLogEntryReads
  implicit val taskReviewReads: Reads[TaskReview]         = TaskReview.reads
  implicit val taskWithReviewReads: Reads[TaskWithReview] = TaskWithReview.taskWithReviewReads
  // UserNotification Readers
  implicit val userNotificationReads: Reads[UserNotification] = UserNotification.notificationReads
  implicit val userNotificationSubscriptionsReads: Reads[NotificationSubscriptions] =
    NotificationSubscriptions.notificationSubscriptionReads
}

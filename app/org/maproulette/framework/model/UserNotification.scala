/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.joda.time.DateTime
import org.maproulette.framework.psql.CommonField
import play.api.libs.json.{Json, Reads, Writes}
import play.api.libs.json.JodaWrites._
import play.api.libs.json.JodaReads._

/**
  * A user notification represents communication to a user about an event. It
  * can be associated with a task/challenge/project as well as other entities,
  * such as comments or reviews (referenced by targetId).
  *
  * @author nrotstan
  */
case class UserNotification(
    var id: Long,
    var userId: Long,
    var notificationType: Int,
    var created: DateTime = DateTime.now,
    var modified: DateTime = DateTime.now,
    var description: Option[String] = None,
    var fromUsername: Option[String] = None,
    var challengeName: Option[String] = None,
    var isRead: Boolean = false,
    var emailStatus: Int = 0,
    var taskId: Option[Long] = None,
    var challengeId: Option[Long] = None,
    var projectId: Option[Long] = None,
    var targetId: Option[Long] = None,
    var extra: Option[String] = None,
    var errorTags: String = ""
)

object UserNotification extends CommonField {
  implicit val notificationWrites: Writes[UserNotification] = Json.writes[UserNotification]
  implicit val notificationReads: Reads[UserNotification]   = Json.reads[UserNotification]

  val TABLE                   = "user_notifications"
  val FIELD_NOTIFICATION_TYPE = "notification_type"
  val FIELD_USER_ID           = "user_id"
  val FIELD_IS_READ           = "is_read"
  val FIELD_CHALLENGE_ID      = "challenge_id"
  val FIELD_FROM_USERNAME     = "from_username"
  val FIELD_EMAIL_STATUS      = "email_status"
  val TASK_TYPE_REVIEW        = "review"
  val TASK_TYPE_REVISION      = "revision"

  val NOTIFICATION_TYPE_SYSTEM                          = 0
  val NOTIFICATION_TYPE_SYSTEM_NAME                     = "System Message"
  val NOTIFICATION_TYPE_MENTION                         = 1
  val NOTIFICATION_TYPE_MENTION_NAME                    = "Comment Mention"
  val NOTIFICATION_TYPE_REVIEW_APPROVED                 = 2
  val NOTIFICATION_TYPE_REVIEW_APPROVED_NAME            = "Task Approved"
  val NOTIFICATION_TYPE_REVIEW_REJECTED                 = 3
  val NOTIFICATION_TYPE_REVIEW_REJECTED_NAME            = "Revision Requested"
  val NOTIFICATION_TYPE_REVIEW_AGAIN                    = 4
  val NOTIFICATION_TYPE_REVIEW_AGAIN_NAME               = "Review Requested"
  val NOTIFICATION_TYPE_CHALLENGE_COMPLETED             = 5
  val NOTIFICATION_TYPE_CHALLENGE_COMPLETED_NAME        = "Challenge Completed"
  val NOTIFICATION_TYPE_TEAM                            = 6
  val NOTIFICATION_TYPE_TEAM_NAME                       = "Team"
  val NOTIFICATION_TYPE_FOLLOW                          = 7
  val NOTIFICATION_TYPE_FOLLOW_NAME                     = "Follow"
  val NOTIFICATION_TYPE_MAPPER_CHALLENGE_COMPLETED      = 8
  val NOTIFICATION_TYPE_MAPPER_CHALLENGE_COMPLETED_NAME = "Mapper Challenge Completed"
  val NOTIFICATION_TYPE_REVIEW_REVISED                  = 9
  val NOTIFICATION_TYPE_REVIEW_REVISED_NAME             = "Review Revised"
  val NOTIFICATION_TYPE_META_REVIEW                     = 10
  val NOTIFICATION_TYPE_META_REVIEW_NAME                = "Meta-Review"
  val NOTIFICATION_TYPE_META_REVIEW_AGAIN               = 11
  val NOTIFICATION_TYPE_META_REVIEW_AGAIN_NAME          = "Meta-Review Again"
  val NOTIFICATION_TYPE_REVIEW_COUNT                    = 12
  val NOTIFICATION_TYPE_REVIEW_COUNT_NAME               = "Review Count"
  val NOTIFICATION_TYPE_REVISION_COUNT                  = 13
  val NOTIFICATION_TYPE_REVISION_COUNT_NAME             = "Revision Count"
  val NOTIFICATION_TYPE_CHALLENGE_COMMENT               = 14
  val NOTIFICATION_TYPE_CHALLENGE_COMMENT_NAME          = "Challenge Comment"

  val notificationTypeMap = Map(
    NOTIFICATION_TYPE_SYSTEM                     -> NOTIFICATION_TYPE_SYSTEM_NAME,
    NOTIFICATION_TYPE_MENTION                    -> NOTIFICATION_TYPE_MENTION_NAME,
    NOTIFICATION_TYPE_REVIEW_APPROVED            -> NOTIFICATION_TYPE_REVIEW_APPROVED_NAME,
    NOTIFICATION_TYPE_REVIEW_REJECTED            -> NOTIFICATION_TYPE_REVIEW_REJECTED_NAME,
    NOTIFICATION_TYPE_REVIEW_AGAIN               -> NOTIFICATION_TYPE_REVIEW_AGAIN_NAME,
    NOTIFICATION_TYPE_REVIEW_REVISED             -> NOTIFICATION_TYPE_REVIEW_REVISED_NAME,
    NOTIFICATION_TYPE_META_REVIEW                -> NOTIFICATION_TYPE_META_REVIEW_NAME,
    NOTIFICATION_TYPE_META_REVIEW_AGAIN          -> NOTIFICATION_TYPE_META_REVIEW_AGAIN_NAME,
    NOTIFICATION_TYPE_CHALLENGE_COMPLETED        -> NOTIFICATION_TYPE_CHALLENGE_COMPLETED_NAME,
    NOTIFICATION_TYPE_TEAM                       -> NOTIFICATION_TYPE_TEAM_NAME,
    NOTIFICATION_TYPE_FOLLOW                     -> NOTIFICATION_TYPE_FOLLOW_NAME,
    NOTIFICATION_TYPE_MAPPER_CHALLENGE_COMPLETED -> NOTIFICATION_TYPE_MAPPER_CHALLENGE_COMPLETED_NAME,
    NOTIFICATION_TYPE_CHALLENGE_COMMENT          -> NOTIFICATION_TYPE_CHALLENGE_COMMENT_NAME
  )

  val NOTIFICATION_IGNORE          = 0 // ignore notification
  val NOTIFICATION_EMAIL_NONE      = 1 // no email desired
  val NOTIFICATION_EMAIL_IMMEDIATE = 2 // send email immediately
  val NOTIFICATION_EMAIL_DIGEST    = 3 // include in daily digest
  val NOTIFICATION_EMAIL_SENT      = 4 // requested email sent
  val NOTIFICATION_EMAIL_DAILY     = 5 // daily email
  val NOTIFICATION_EMAIL_WEEKLY    = 6 // weekly email
}

case class NotificationSubscriptions(
    val id: Long,
    val userId: Long,
    val system: Int,
    val mention: Int,
    val reviewApproved: Int,
    val reviewRejected: Int,
    val reviewAgain: Int,
    val challengeCompleted: Int,
    val team: Int,
    val follow: Int,
    val metaReview: Int,
    val reviewCount: Int,
    val revisionCount: Int
)
object NotificationSubscriptions {
  implicit val notificationSubscriptionReads: Reads[NotificationSubscriptions] =
    Json.reads[NotificationSubscriptions]
  implicit val notificationSubscriptionWrites: Writes[NotificationSubscriptions] =
    Json.writes[NotificationSubscriptions]

  val TABLE         = "user_notification_subscriptions"
  val FIELD_USER_ID = "user_id"
}

case class UserNotificationEmail(
    val id: Long,
    val userId: Long,
    val notificationType: Int,
    val extra: Option[String],
    val created: DateTime,
    val emailStatus: Int
)

case class UserNotificationEmailDigest(
    val userId: Long,
    val notifications: List[UserNotificationEmail]
)

case class UserRevCount(
    val userId: Long,
    val name: String = "",
    val email: String = "",
    val tasks: List[Int],
    val reviewCountSubscriptionType: Int,
    val revisionCountSubscriptionType: Int
)

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.repository

import java.sql.Connection
import anorm.SqlParser._
import anorm._

import javax.inject.Inject
import scala.collection.mutable.ListBuffer
import org.joda.time.DateTime
import org.maproulette.framework.model.{UserNotification, UserNotificationEmail, UserRevCount}
import org.maproulette.framework.psql._
import org.maproulette.framework.psql.filter._
import play.api.db.Database

/**
  * @author nrotstan
  */
class NotificationRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = UserNotification.TABLE

  /**
    * Insert a new notification
    *
    * @param notification The notification to add
    * @return The notification that was inserted now with the generated id or None
    */
  def create(notification: UserNotification)(implicit c: Option[Connection] = None): Unit = {
    this.withMRTransaction { implicit c =>
      val errorTags = if (!notification.errorTags.isEmpty) notification.errorTags else null;
      SQL(
        """
          |INSERT INTO user_notifications (user_id, notification_type, description, from_username, is_read,
          |                                email_status, task_id, challenge_id, project_id, target_id, extra, error_tags)
          |VALUES ({userId}, {notificationType}, {description}, {fromUsername}, {isRead},
          |        {emailStatus}, {taskId}, {challengeId}, {projectId}, {targetId}, {extra}, {errorTags})
        """.stripMargin
      ).on(
          Symbol("userId")           -> notification.userId,
          Symbol("notificationType") -> notification.notificationType,
          Symbol("description")      -> notification.description,
          Symbol("fromUsername")     -> notification.fromUsername,
          Symbol("isRead")           -> false,
          Symbol("emailStatus")      -> notification.emailStatus,
          Symbol("taskId")           -> notification.taskId,
          Symbol("challengeId")      -> notification.challengeId,
          Symbol("projectId")        -> notification.projectId,
          Symbol("targetId")         -> notification.targetId,
          Symbol("extra")            -> notification.extra,
          Symbol("errorTags")        -> errorTags
        )
        .execute()
    }
  }

  /**
    * Retrieve notifications sent to the given userId and matching any of the
    * other optional filters provided
    *
    * @param userId           The user who received the notifications
    * @param notificationType Optional filter on the type of of notification
    * @param isRead           Optional filter on whether the notification has been read
    * @param fromUsername     Optional filter on who sent the notification
    * @param challengeId      Optional filter on challenge associated with notification
    * @param order            Desired order of results
    * @param page             Desired page of results
    */
  def getUserNotifications(
      userId: Long,
      notificationType: Option[Int] = None,
      isRead: Option[Boolean] = None,
      fromUsername: Option[String] = None,
      challengeId: Option[Long] = None,
      order: OrderField = OrderField(UserNotification.FIELD_IS_READ, Order.ASC),
      page: Paging = Paging()
  )(implicit c: Option[Connection] = None): List[UserNotification] = {
    withMRConnection { implicit c =>
      // In addition to the requested sort, we always add a sort by created desc
      // (unless created was the requested sort column)
      val orderFields = ListBuffer[OrderField](order)
      if (order.name != UserNotification.FIELD_CREATED) {
        orderFields += OrderField(UserNotification.FIELD_CREATED, Order.DESC)
      }

      val data = Query
        .simple(
          List(
            BaseParameter(UserNotification.FIELD_USER_ID, userId),
            FilterParameter.conditional(
              UserNotification.FIELD_NOTIFICATION_TYPE,
              notificationType.getOrElse(-1),
              includeOnlyIfTrue = notificationType.isDefined
            ),
            FilterParameter.conditional(
              UserNotification.FIELD_IS_READ,
              isRead.getOrElse(false),
              includeOnlyIfTrue = isRead.isDefined
            ),
            FilterParameter.conditional(
              UserNotification.FIELD_CHALLENGE_ID,
              challengeId.getOrElse(-1),
              includeOnlyIfTrue = challengeId.isDefined
            ),
            FilterParameter.conditional(
              UserNotification.FIELD_FROM_USERNAME,
              fromUsername.getOrElse(""),
              includeOnlyIfTrue = fromUsername.isDefined
            )
          ),
          order = Order(orderFields.toList)
        )
        .build(
          """
            |SELECT user_notifications.*, challenges.name
            |FROM user_notifications
            |LEFT OUTER JOIN challenges on user_notifications.challenge_id = challenges.id
        """.stripMargin
        )
        .as(NotificationRepository.parser.*)

      data
    }
  }

  /**
    * Marks as read the given notifications owned by the given userId
    *
    * @param userId          The id of the user that owns the notifications
    * @param notificationIds The ids of the notifications to be marked read
    */
  def markNotificationsRead(userId: Long, notificationIds: List[Long])(
      implicit c: Option[Connection] = None
  ): Boolean = {
    withMRConnection { implicit c =>
      Query
        .simple(
          List(
            BaseParameter(UserNotification.FIELD_USER_ID, userId),
            BaseParameter(UserNotification.FIELD_ID, notificationIds, Operator.IN)
          )
        )
        .build("UPDATE user_notifications SET is_read=TRUE")
        .execute()
    }
  }

  /**
    * Deletes the given notifications owned by the given userId
    *
    * @param userId          The id of the user that owns the notifications
    * @param notificationIds The ids of the notifications to delete
    * @return
    */
  def deleteNotifications(userId: Long, notificationIds: List[Long])(
      implicit c: Option[Connection] = None
  ): Boolean = {
    withMRConnection { implicit c =>
      Query
        .simple(
          List(
            BaseParameter(UserNotification.FIELD_USER_ID, userId),
            BaseParameter(UserNotification.FIELD_ID, notificationIds, Operator.IN)
          )
        )
        .build("DELETE FROM user_notifications")
        .execute()
    }
  }

  /**
    * Prepare for emailing any pending notifications matching the given email
    * status, up to the maximum given limit, by marking them as emailed and
    * returning them for processing (by marking first we err on the side of not
    * emailing in the event of downstream failure rather than potentially
    * emailing notifications multiple times). An optional userId can be given to
    * restrict notifications to those received by a user
    *
    * @param emailStatus The targeted email status
    * @param userId      Optionally filter by owner of the notifications
    * @param limit       The maximum number of notifications to process
    */
  def prepareNotificationsForEmail(emailStatus: Int, userId: Option[Long], limit: Int)(
      implicit c: Option[Connection] = None
  ): List[UserNotificationEmail] = {
    withMRConnection { implicit c =>
      Query
        .simple(
          List(
            SubQueryFilter(
              UserNotification.FIELD_ID,
              Query.simple(
                List(
                  BaseParameter(UserNotification.FIELD_EMAIL_STATUS, emailStatus),
                  FilterParameter.conditional(
                    UserNotification.FIELD_USER_ID,
                    userId.getOrElse(-1),
                    includeOnlyIfTrue = userId.isDefined
                  )
                ),
                "SELECT id FROM user_notifications",
                paging = Paging(limit),
                order = Order(List(OrderField(UserNotification.FIELD_CREATED, Order.ASC)))
              ),
              operator = Operator.IN
            )
          ),
          finalClause = "RETURNING *"
        )
        .build(
          s"""
             |UPDATE user_notifications
             |SET email_status = ${UserNotification.NOTIFICATION_EMAIL_SENT}
        """.stripMargin
        )
        .as(NotificationRepository.userNotificationEmailParser.*)
    }
  }

  /**
    * Retrieve a list of user ids for users who have notifications that are in
    * the given email status
    */
  def usersWithNotificationEmails(
      emailStatus: Int
  )(implicit c: Option[Connection] = None): List[Long] = {
    withMRConnection { implicit c =>
      Query
        .simple(
          List(BaseParameter(UserNotification.FIELD_EMAIL_STATUS, emailStatus))
        )
        .build("SELECT distinct(user_id) from user_notifications")
        .as(SqlParser.long("user_id").*)
    }
  }

  /**
    * Retrieve a list of users and their associated revision tasks
    */
  def usersWithTasksToBeRevised()(implicit c: Option[Connection] = None): List[UserRevCount] = {
    withMRConnection { implicit c =>
      SQL(
        """
          |SELECT a.id, name, email, u.revision_count, u.review_count, ARRAY_AGG (b.task_id) tasks
          | FROM users a
          | inner join task_review as b on a.id = b.review_requested_by and b.review_status = 2
          | inner join user_notification_subscriptions as u on a.id = u.user_id
          | group by a.id, u.revision_count, u.review_count;
        """.stripMargin
      ).as(NotificationRepository.userRevisionCountParser.*)
    }
  }

  /**
    * Retrieve a list of users and their task review count
    */
  def usersWithTasksToBeReviewed()(implicit c: Option[Connection] = None): List[UserRevCount] = {
    withMRConnection { implicit c =>
      SQL(
        """
          |SELECT a.id, name, email, u.revision_count, u.review_count, ARRAY_AGG (b.task_id) tasks
          |	FROM users a
          |	inner join task_review as b on a.id = b.reviewed_by and b.review_status = 0
          | inner join user_notification_subscriptions as u on a.id = u.user_id
          | group by a.id, u.revision_count, u.review_count;
        """.stripMargin
      ).as(NotificationRepository.userRevisionCountParser.*)
    }
  }
}

object NotificationRepository {
  // The anorm row parser for user notifications
  val parser: RowParser[UserNotification] = {
    get[Long]("user_notifications.id") ~
      get[Long]("user_notifications.user_id") ~
      get[Int]("user_notifications.notification_type") ~
      get[DateTime]("user_notifications.created") ~
      get[DateTime]("user_notifications.modified") ~
      get[Option[String]]("user_notifications.description") ~
      get[Option[String]]("user_notifications.from_username") ~
      get[Option[String]]("challenges.name") ~
      get[Boolean]("user_notifications.is_read") ~
      get[Int]("user_notifications.email_status") ~
      get[Option[Long]]("user_notifications.task_id") ~
      get[Option[Long]]("user_notifications.challenge_id") ~
      get[Option[Long]]("user_notifications.project_id") ~
      get[Option[Long]]("user_notifications.target_id") ~
      get[Option[String]]("user_notifications.extra") ~
      get[Option[String]]("error_tags") map {
      case id ~ userId ~ notificationType ~ created ~ modified ~ description ~ fromUsername ~ challengeName ~ isRead ~ emailStatus ~ taskId ~ challengeId ~ projectId ~ targetId ~ extra ~ errorTags =>
        new UserNotification(
          id,
          userId,
          notificationType,
          created,
          modified,
          description,
          fromUsername,
          challengeName,
          isRead,
          emailStatus,
          taskId,
          challengeId,
          projectId,
          targetId,
          extra,
          errorTags.getOrElse("")
        )
    }
  }

  val userNotificationEmailParser: RowParser[UserNotificationEmail] = {
    get[Long]("user_notifications.id") ~
      get[Long]("user_notifications.user_id") ~
      get[Int]("user_notifications.notification_type") ~
      get[Option[String]]("user_notifications.extra") ~
      get[DateTime]("user_notifications.created") ~
      get[Int]("user_notifications.email_status") map {
      case id ~ userId ~ notificationType ~ extra ~ created ~ emailStatus =>
        new UserNotificationEmail(id, userId, notificationType, extra, created, emailStatus)
    }
  }

  val userRevisionCountParser: RowParser[UserRevCount] = {
    get[Long]("id") ~
      get[String]("name") ~
      get[String]("email") ~
      get[List[Int]]("tasks") ~
      get[Int]("review_count") ~
      get[Int]("revision_count") map {
      case id ~ name ~ email ~ tasks ~ reviewCount ~ revisionCount =>
        new UserRevCount(id, name, email, tasks, reviewCount, revisionCount)
    }
  }
}

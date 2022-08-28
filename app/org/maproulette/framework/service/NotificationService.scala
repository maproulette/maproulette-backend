/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.maproulette.exception.{InvalidException, NotFoundException}
import org.maproulette.data.{UserType}
import org.maproulette.framework.model._
import org.maproulette.framework.model.{UserRevCount}
import org.maproulette.framework.psql._
import org.maproulette.framework.repository.{
  NotificationRepository,
  NotificationSubscriptionRepository
}
import org.maproulette.permissions.Permission
import org.maproulette.provider.websockets.{WebSocketMessages, WebSocketProvider}

/**
  * Service for handling Notification requests
  *
  * @author nrotstan
  */
@Singleton
class NotificationService @Inject() (
    repository: NotificationRepository,
    subscriptionsRepository: NotificationSubscriptionRepository,
    serviceManager: ServiceManager,
    webSocketProvider: WebSocketProvider,
    permission: Permission
) {

  /**
    * Retrieve notifications sent to the given userId and matching any of the
    * other optional filters provided
    *
    * @param userId           The user who received the notifications
    * @param user             The user making the request
    * @param notificationType Optional filter on the type of of notification
    * @param isRead           Optional filter on whether the notification has been read
    * @param fromUsername     Optional filter on who sent the notification
    * @param challengeId      Optional filter on challenge associated with notification
    * @param order            Desired order of results
    * @param page             Desired page of results
    */
  def getUserNotifications(
      userId: Long,
      user: User,
      notificationType: Option[Int] = None,
      isRead: Option[Boolean] = None,
      fromUsername: Option[String] = None,
      challengeId: Option[Long] = None,
      order: OrderField = OrderField(UserNotification.FIELD_IS_READ, Order.ASC),
      page: Paging = Paging()
  ): List[UserNotification] = {
    permission.hasReadAccess(UserType(), user)(userId)
    this.repository.getUserNotifications(
      userId,
      notificationType,
      isRead,
      fromUsername,
      challengeId,
      order,
      page
    )
  }

  /**
    * Retrieve notification subscriptions for a user
    *
    * @param userId The id of the subscribing user
    * @param user   The user making the request
    */
  def getNotificationSubscriptions(userId: Long, user: User): NotificationSubscriptions = {
    permission.hasReadAccess(UserType(), user)(userId)
    this.subscriptionsRepository.getNotificationSubscriptions(userId) match {
      case Some(subscription) => subscription
      case None               =>
        // Default to subscribing to all notifications, but with no emails
        NotificationSubscriptions(
          -1,
          userId,
          UserNotification.NOTIFICATION_EMAIL_NONE,
          UserNotification.NOTIFICATION_EMAIL_NONE,
          UserNotification.NOTIFICATION_EMAIL_NONE,
          UserNotification.NOTIFICATION_EMAIL_NONE,
          UserNotification.NOTIFICATION_EMAIL_NONE,
          UserNotification.NOTIFICATION_EMAIL_NONE,
          UserNotification.NOTIFICATION_EMAIL_NONE,
          UserNotification.NOTIFICATION_EMAIL_NONE,
          UserNotification.NOTIFICATION_EMAIL_NONE,
          UserNotification.NOTIFICATION_EMAIL_NONE,
          UserNotification.NOTIFICATION_EMAIL_NONE
        )
    }
  }

  /**
    * Updates notification subscriptions for a user
    *
    * @param userId        The id of the subscribing user
    * @param user          The user making the request
    * @param subscriptions The updated subscriptions
    */
  def updateNotificationSubscriptions(
      userId: Long,
      user: User,
      subscriptions: NotificationSubscriptions
  ): Unit = {
    permission.hasWriteAccess(UserType(), user)(userId)
    this.subscriptionsRepository.updateNotificationSubscriptions(userId, subscriptions)
  }

  /**
    * Marks as read the given notifications owned by the given userId
    *
    * @param userId          The id of the user that owns the notifications
    * @param user            The user making the request
    * @param notificationIds The ids of the notifications to be marked read
    */
  def markNotificationsRead(userId: Long, user: User, notificationIds: List[Long]): Boolean = {
    permission.hasWriteAccess(UserType(), user)(userId)
    this.repository.markNotificationsRead(userId, notificationIds)
  }

  /**
    * Deletes the given notifications owned by the given userId
    *
    * @param userId          The id of the user that owns the notifications
    * @param user            The user making the request
    * @param notificationIds The ids of the notifications to delete
    * @return
    */
  def deleteNotifications(userId: Long, user: User, notificationIds: List[Long]): Boolean = {
    permission.hasWriteAccess(UserType(), user)(userId)
    this.repository.deleteNotifications(userId, notificationIds)
  }

  /**
    * Create one or more new notifications for users mentioned by name in a
    * comment. The recipient user(s) will be extracted from the comment
    *
    * @param fromUser The author of the comment
    * @param comment  The comment mentioning the user
    * @param task     The task on which the comment was added
    */
  def createMentionNotifications(fromUser: User, comment: Comment, task: Task): Unit = {
    // match [@username] (username may contain spaces) or @username (no spaces allowed)
    val mentionRegex = """\[@([^\]]+)\]|@([\w\d_-]+)""".r.unanchored

    for (m <- mentionRegex.findAllMatchIn(comment.comment)) {
      // use first non-null group
      val username = m.subgroups.filter(_ != null).head

      // Retrieve and notify mentioned user
      this.serviceManager.user.retrieveByOSMUsername(username, User.superUser) match {
        case Some(mentionedUser) =>
          this.addNotification(
            UserNotification(
              -1,
              userId = mentionedUser.id,
              notificationType = UserNotification.NOTIFICATION_TYPE_MENTION,
              fromUsername = Some(fromUser.osmProfile.displayName),
              taskId = Some(task.id),
              challengeId = Some(task.parent),
              targetId = Some(comment.id),
              extra = Some(comment.comment),
              errorTags = task.errorTags
            ),
            User.superUser
          )
        case None => None
      }
    }
  }

  /**
    * Create one or more new notifications for users mentioned by name in a
    * comment. The recipient user(s) will be extracted from the comment
    *
    * @param fromUser The author of the comment
    * @param comment  The comment mentioning the user
    * @param challenge     The task on which the comment was added
    */
  def createChallengeMentionNotifications(
      fromUser: User,
      comment: ChallengeComment,
      challenge: Challenge
  ): Unit = {
    // match [@username] (username may contain spaces) or @username (no spaces allowed)
    val mentionRegex = """\[@([^\]]+)\]|@([\w\d_-]+)""".r.unanchored

    for (m <- mentionRegex.findAllMatchIn(comment.comment)) {
      // use first non-null group
      val username = m.subgroups.filter(_ != null).head

      // Retrieve and notify mentioned user
      this.serviceManager.user.retrieveByOSMUsername(username, User.superUser) match {
        case Some(mentionedUser) =>
          this.addNotification(
            UserNotification(
              -1,
              userId = mentionedUser.id,
              notificationType = UserNotification.NOTIFICATION_TYPE_MENTION,
              fromUsername = Some(fromUser.osmProfile.displayName),
              taskId = None,
              challengeId = Some(challenge.id),
              targetId = Some(comment.id),
              extra = Some(comment.comment)
            ),
            User.superUser
          )
        case None => None
      }
    }
  }

  /**
    * Create new notification indicating that a task review status has been updated
    *
    * @param fromUser     The user who caused the review status to be updated
    * @param forUserId    The recipient user id
    * @param reviewStatus The updated review status
    * @param task         The task on which the review status was updated
    * @param comment      An optional comment associated with the status update
    */
  def createReviewNotification(
      fromUser: User,
      forUserId: Long,
      reviewStatus: Int,
      task: Task,
      comment: Option[Comment],
      isMetaReview: Boolean = false,
      errorTags: String = ""
  ): Unit = {
    val notificationType = isMetaReview match {
      case true => UserNotification.NOTIFICATION_TYPE_META_REVIEW
      case false =>
        reviewStatus match {
          case Task.REVIEW_STATUS_REQUESTED => UserNotification.NOTIFICATION_TYPE_REVIEW_AGAIN
          case Task.REVIEW_STATUS_APPROVED  => UserNotification.NOTIFICATION_TYPE_REVIEW_APPROVED
          case Task.REVIEW_STATUS_APPROVED_WITH_REVISIONS =>
            UserNotification.NOTIFICATION_TYPE_REVIEW_APPROVED
          case Task.REVIEW_STATUS_APPROVED_WITH_FIXES_AFTER_REVISIONS =>
            UserNotification.NOTIFICATION_TYPE_REVIEW_APPROVED
          case Task.REVIEW_STATUS_ASSISTED => UserNotification.NOTIFICATION_TYPE_REVIEW_APPROVED
          case Task.REVIEW_STATUS_REJECTED => UserNotification.NOTIFICATION_TYPE_REVIEW_REJECTED
          case Task.REVIEW_STATUS_DISPUTED => UserNotification.NOTIFICATION_TYPE_REVIEW_AGAIN
        }
    }

    this.addNotification(
      UserNotification(
        -1,
        userId = forUserId,
        notificationType = notificationType,
        fromUsername = Some(fromUser.osmProfile.displayName),
        description = Some(reviewStatus.toString()),
        taskId = Some(task.id),
        challengeId = Some(task.parent),
        extra = comment match {
          case Some(c) => Some(c.comment)
          case None    => None
        },
        errorTags = errorTags
      ),
      User.superUser
    )
  }

  /**
    * Create new notification indicating that a task review status has been revised
    *
    * @param fromUser     The user who caused the review status to be updated
    * @param forUserId    The original reviewer user id
    * @param reviewStatus The updated review status
    * @param task         The task on which the review status was updated
    * @param comment      An optional comment associated with the status update
    */
  def createReviewRevisedNotification(
      fromUser: User,
      forUserId: Long,
      reviewStatus: Int,
      task: Task,
      comment: Option[Comment],
      isMetaReview: Boolean = false
  ): Unit = {
    this.addNotification(
      UserNotification(
        -1,
        userId = forUserId,
        notificationType =
          if (isMetaReview) UserNotification.NOTIFICATION_TYPE_META_REVIEW_AGAIN
          else UserNotification.NOTIFICATION_TYPE_REVIEW_REVISED,
        fromUsername = Some(fromUser.osmProfile.displayName),
        description = Some(reviewStatus.toString()),
        taskId = Some(task.id),
        challengeId = Some(task.parent),
        extra = comment match {
          case Some(c) => Some(c.comment)
          case None    => None
        }
      ),
      User.superUser
    )
  }

  /**
    * Create new notifications for challenge managers indicating that a
    * challenge they manage has been completed
    *
    * @param challenge The challenge that was completed
    */
  def createChallengeCompletionNotification(challenge: Challenge): Unit = {
    this.serviceManager.project.retrieve(challenge.general.parent) match {
      case Some(parentProject) =>
        val buildNotification = (userId: Long, isManager: Boolean) => {
          UserNotification(
            -1,
            userId = userId,
            notificationType =
              if (isManager) UserNotification.NOTIFICATION_TYPE_CHALLENGE_COMPLETED
              else UserNotification.NOTIFICATION_TYPE_MAPPER_CHALLENGE_COMPLETED,
            challengeId = Some(challenge.id),
            projectId = Some(parentProject.id),
            description = Some(challenge.name),
            extra = Some(s""""${challenge.name}" from project "${parentProject.displayName
              .getOrElse(parentProject.name)}"""")
          )
        }

        this.serviceManager.user
          .getUsersManagingProject(parentProject.id, None, User.superUser)
          .foreach { manager =>
            this.addNotification(
              buildNotification(manager.userId, true),
              User.superUser
            )
          }
        this.serviceManager.user
          .getChallengeMappers(challenge.id)
          .foreach { mapper =>
            this.addNotification(
              buildNotification(mapper.id, false),
              User.superUser
            )
          }
      case None =>
        throw new NotFoundException(
          s"Parent project ${challenge.general.parent} not found for challenge ${challenge.id}"
        )
    }
  }

  /**
    * Create new notification indicating that a user has been invited to join a team
    *
    * @param fromUser  The user who issued the invitation
    * @param forUserId The recipient user id
    * @param team      The team to which the user is invited
    */
  def createTeamInviteNotification(
      fromUser: User,
      forUserId: Long,
      team: Group
  ): Unit = {
    this.addNotification(
      UserNotification(
        -1,
        userId = forUserId,
        notificationType = UserNotification.NOTIFICATION_TYPE_TEAM,
        fromUsername = Some(fromUser.osmProfile.displayName),
        description = Some("invited"),
        targetId = Some(team.id),
        extra = Some(team.name)
      ),
      User.superUser
    )
  }

  /**
    * Create new notification indicating that a user is now being followed
    *
    * @param follower   The user who is following
    * @param followedId The id of the user being followed
    */
  def createFollowedNotification(follower: User, followedId: Long): Unit = {
    this.addNotification(
      UserNotification(
        -1,
        userId = followedId,
        notificationType = UserNotification.NOTIFICATION_TYPE_FOLLOW,
        fromUsername = Some(follower.osmProfile.displayName),
        description = Some("followed")
      ),
      User.superUser
    )
  }

  /**
    * Add/insert a notification. The email setting of the notification will be automatically
    * set based on the recipient's email settings. If the recipient is not subscribed to
    * the type of notification given then it is simply ignored
    *
    * @param notification The notification to add
    * @param user         The user making the request
    * @return
    */
  def addNotification(notification: UserNotification, user: User): Unit = {
    permission.hasWriteAccess(UserType(), user)(notification.userId)
    val subscriptions = this.getNotificationSubscriptions(notification.userId, user)
    val subscriptionType = notification.notificationType match {
      case UserNotification.NOTIFICATION_TYPE_SYSTEM            => subscriptions.system
      case UserNotification.NOTIFICATION_TYPE_MENTION           => subscriptions.mention
      case UserNotification.NOTIFICATION_TYPE_REVIEW_APPROVED   => subscriptions.reviewApproved
      case UserNotification.NOTIFICATION_TYPE_REVIEW_REJECTED   => subscriptions.reviewRejected
      case UserNotification.NOTIFICATION_TYPE_REVIEW_AGAIN      => subscriptions.reviewAgain
      case UserNotification.NOTIFICATION_TYPE_REVIEW_REVISED    => subscriptions.reviewAgain
      case UserNotification.NOTIFICATION_TYPE_META_REVIEW       => subscriptions.metaReview
      case UserNotification.NOTIFICATION_TYPE_META_REVIEW_AGAIN => subscriptions.metaReview
      case UserNotification.NOTIFICATION_TYPE_CHALLENGE_COMPLETED =>
        subscriptions.challengeCompleted
      case UserNotification.NOTIFICATION_TYPE_MAPPER_CHALLENGE_COMPLETED =>
        subscriptions.challengeCompleted
      case UserNotification.NOTIFICATION_TYPE_TEAM   => subscriptions.team
      case UserNotification.NOTIFICATION_TYPE_FOLLOW => subscriptions.follow
      case _                                         => throw new InvalidException("Invalid notification type")
    }

    // Guard against ignored notification type
    subscriptionType match {
      case UserNotification.NOTIFICATION_IGNORE => None // nothing to do
      case _ =>
        notification.emailStatus = subscriptionType
        notification.isRead = false
        this.repository.create(notification)

        webSocketProvider.sendMessage(
          WebSocketMessages.notificationNew(
            WebSocketMessages.NotificationData(notification.userId, notification.notificationType)
          )
        )
    }
  }

  /**
    * Prepare for emailing any pending notifications set to be emailed
    * immediately, up to the maximum given limit, by marking them as emailed and
    * returning them for processing (by marking first we err on the side of not
    * emailing in the event of downstream failure rather than potentially
    * emailing notifications multiple times)
    *
    * @param user  The user making the request (must be a superuser)
    * @param limit The maximum number of notifications to process
    */
  def prepareNotificationsForImmediateEmail(user: User, limit: Int): List[UserNotificationEmail] = {
    permission.hasSuperAccess(user)
    this.repository.prepareNotificationsForEmail(
      UserNotification.NOTIFICATION_EMAIL_IMMEDIATE,
      None,
      limit
    )
  }

  /**
    * Prepare for emailing any pending notifications set to be emailed
    * immediately, up to the maximum given limit, by marking them as emailed and
    * returning them for processing (by marking first we err on the side of not
    * emailing in the event of downstream failure rather than potentially
    * emailing notifications multiple times)
    *
    * @param userId id of owner of notifications
    * @param user  The user making the request (must be a superuser)
    * @param limit The maximum number of notifications to process
    */
  def prepareNotificationsForDigestEmail(
      userId: Long,
      user: User,
      limit: Int = 0
  ): List[UserNotificationEmail] = {
    permission.hasSuperAccess(user)
    this.repository.prepareNotificationsForEmail(
      UserNotification.NOTIFICATION_EMAIL_DIGEST,
      Some(userId),
      limit
    )
  }

  /**
    * Retrieve a list of user ids for users who have notifications that are in
    * the given email status
    *
    * @param user        The user making the request (must be superuser)
    * @param emailStatus The targeted email status
    */
  def usersWithNotificationEmails(user: User, emailStatus: Int): List[Long] = {
    permission.hasSuperAccess(user)
    this.repository.usersWithNotificationEmails(emailStatus)
  }

  /**
    * Retrieve a list of users and their count of tasks to be revised
    *
    * @param user The user making the request (must be superuser)
    */
  def usersWithTasksToBeRevised(user: User): List[UserRevCount] = {
    permission.hasSuperAccess(user)
    this.repository.usersWithTasksToBeRevised()
  }

  /**
    * Retrieve a list of users and their count of tasks to be reviewed
    *
    * @param user The user making the request (must be superuser)
    */
  def usersWithTasksToBeReviewed(user: User): List[UserRevCount] = {
    permission.hasSuperAccess(user)
    this.repository.usersWithTasksToBeReviewed()
  }
}

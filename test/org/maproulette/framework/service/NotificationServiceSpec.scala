/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework

import org.joda.time.DateTime
import org.maproulette.framework.model._
import org.maproulette.framework.repository.NotificationRepository
import org.maproulette.framework.service.NotificationService
import org.maproulette.framework.util.{FrameworkHelper, NotificationTag}
import play.api.Application

/**
  * @author nrotstan
  */
class NotificationServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val repository: NotificationRepository =
    this.application.injector.instanceOf(classOf[NotificationRepository])
  val service: NotificationService = this.serviceManager.notification

  "NotificationService" should {
    "retrieve a user's notifications" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911111, "Service_retrieveUserNotificationsOUser"),
        User.superUser
      )

      this.repository.create(
        this.getTestNotification(
          freshUser.id,
          UserNotification.NOTIFICATION_TYPE_SYSTEM,
          fromUsername = Some(s"User_${this.projectTestName}")
        )
      )

      this.service
        .getUserNotifications(
          freshUser.id,
          freshUser,
          Some(UserNotification.NOTIFICATION_TYPE_SYSTEM)
        )
        .size mustEqual 1

      this.service
        .getUserNotifications(
          freshUser.id,
          freshUser,
          Some(UserNotification.NOTIFICATION_TYPE_MENTION)
        )
        .isEmpty mustEqual true

      this.service
        .getUserNotifications(
          freshUser.id,
          freshUser,
          isRead = Some(false)
        )
        .size mustEqual 1

      this.service
        .getUserNotifications(
          freshUser.id,
          freshUser,
          isRead = Some(true)
        )
        .isEmpty mustEqual true

      this.service
        .getUserNotifications(
          freshUser.id,
          freshUser,
          fromUsername = Some(s"User_${this.projectTestName}")
        )
        .size mustEqual 1

      this.service
        .getUserNotifications(
          freshUser.id,
          freshUser,
          fromUsername = Some("Nonexistent User")
        )
        .isEmpty mustBe true
    }

    "get subscriptions for user" taggedAs (NotificationTag) in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911112, "Service_getNotificationSubscriptionsOUser"),
        User.superUser
      )

      this.service.updateNotificationSubscriptions(
        freshUser.id,
        freshUser,
        this.getTestSubscription(
          freshUser.id,
          system = UserNotification.NOTIFICATION_EMAIL_DIGEST,
          mention = UserNotification.NOTIFICATION_IGNORE
        )
      )
      val subs = this.service.getNotificationSubscriptions(freshUser.id, freshUser)
      subs.userId mustEqual freshUser.id
      subs.system mustEqual UserNotification.NOTIFICATION_EMAIL_DIGEST
      subs.mention mustEqual UserNotification.NOTIFICATION_IGNORE
    }

    "returns default subscriptions if none set for user" taggedAs (NotificationTag) in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911113, "Service_getDefaultSubscriptionsOUser"),
        User.superUser
      )
      val subs = this.service.getNotificationSubscriptions(freshUser.id, freshUser)
      subs.system mustEqual UserNotification.NOTIFICATION_EMAIL_NONE
      subs.mention mustEqual UserNotification.NOTIFICATION_EMAIL_NONE
      subs.reviewApproved mustEqual UserNotification.NOTIFICATION_EMAIL_NONE
      subs.reviewRejected mustEqual UserNotification.NOTIFICATION_EMAIL_NONE
      subs.reviewAgain mustEqual UserNotification.NOTIFICATION_EMAIL_NONE
      subs.challengeCompleted mustEqual UserNotification.NOTIFICATION_EMAIL_NONE
    }

    "requires permission to get subscriptions" taggedAs (NotificationTag) in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911114, "Service_getNotificationSubscriptionsFailureOUser"),
        User.superUser
      )

      an[IllegalAccessException] should be thrownBy
        this.service.getNotificationSubscriptions(this.defaultUser.id, freshUser)
    }

    "upserts a new set of subscription values" taggedAs (NotificationTag) in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911115, "Service_insertNotificationSubscriptionOUser"),
        User.superUser
      )

      this.service.updateNotificationSubscriptions(
        freshUser.id,
        freshUser,
        this.getTestSubscription(
          freshUser.id,
          system = UserNotification.NOTIFICATION_EMAIL_DIGEST,
          mention = UserNotification.NOTIFICATION_IGNORE
        )
      )

      val subs = this.service.getNotificationSubscriptions(freshUser.id, freshUser)
      subs.userId mustEqual freshUser.id
      subs.system mustEqual UserNotification.NOTIFICATION_EMAIL_DIGEST
      subs.mention mustEqual UserNotification.NOTIFICATION_IGNORE
    }

    "updates existing subscription values" taggedAs (NotificationTag) in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911116, "Service_updateNotificationSubscriptionOUser"),
        User.superUser
      )

      this.service.updateNotificationSubscriptions(
        freshUser.id,
        freshUser,
        this.getTestSubscription(
          freshUser.id,
          system = UserNotification.NOTIFICATION_EMAIL_DIGEST,
          mention = UserNotification.NOTIFICATION_IGNORE
        )
      )

      val subs = this.service.getNotificationSubscriptions(freshUser.id, freshUser)
      this.service.updateNotificationSubscriptions(
        freshUser.id,
        freshUser,
        subs.copy(system = UserNotification.NOTIFICATION_EMAIL_IMMEDIATE)
      )

      val updatedSubs = this.service.getNotificationSubscriptions(freshUser.id, freshUser)
      updatedSubs.userId mustEqual freshUser.id
      updatedSubs.system mustEqual UserNotification.NOTIFICATION_EMAIL_IMMEDIATE
      updatedSubs.mention mustEqual UserNotification.NOTIFICATION_IGNORE
    }

    "requires permission to update subscriptions" taggedAs (NotificationTag) in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911117, "Service_getNotificationSubscriptionsFailureOUser"),
        User.superUser
      )

      an[IllegalAccessException] should be thrownBy
        this.service.updateNotificationSubscriptions(
          freshUser.id,
          this.defaultUser,
          this.getTestSubscription(freshUser.id)
        )
    }

    "mark notifications as read" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911118, "Service_markNotificationReadOUser"),
        User.superUser
      )
      this.repository.create(this.getTestNotification(freshUser.id))

      val unreadNotifications = this.service.getUserNotifications(freshUser.id, freshUser)
      unreadNotifications.size mustEqual 1
      unreadNotifications.head.isRead mustEqual false

      this.service.markNotificationsRead(freshUser.id, freshUser, unreadNotifications.map(_.id))

      val readNotifications =
        this.service.getUserNotifications(freshUser.id, freshUser, isRead = Some(true))
      readNotifications.size mustEqual 1
      readNotifications.head.id mustEqual unreadNotifications.head.id
      readNotifications.head.isRead mustEqual true
    }

    "requires permission to mark notifications as read" taggedAs (NotificationTag) in {
      val notifications = this.service.getUserNotifications(this.defaultUser.id, this.defaultUser)

      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911119, "Service_markNotificationsReadFailureOUser"),
        User.superUser
      )

      an[IllegalAccessException] should be thrownBy
        this.service.markNotificationsRead(this.defaultUser.id, freshUser, notifications.map(_.id))
    }

    "delete notifications" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911120, "Service_deleteNotificationsOUser"),
        User.superUser
      )
      this.repository.create(this.getTestNotification(freshUser.id))

      val notifications = this.service.getUserNotifications(freshUser.id, freshUser)
      notifications.size mustEqual 1

      this.service.deleteNotifications(freshUser.id, freshUser, notifications.map(_.id))
      this.service.getUserNotifications(freshUser.id, freshUser).isEmpty mustEqual true
    }

    "requires permission to delete notifications" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911121, "Service_deleteNotificationsFailureOUser"),
        User.superUser
      )
      this.repository.create(this.getTestNotification(freshUser.id))

      val notifications = this.service.getUserNotifications(freshUser.id, freshUser)
      notifications.size mustEqual 1

      an[IllegalAccessException] should be thrownBy
        this.service.deleteNotifications(freshUser.id, this.defaultUser, notifications.map(_.id))
    }

    "add a notification" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911122, "Service_addNotificationOUser"),
        User.superUser
      )

      this.service.addNotification(
        this.getTestNotification(freshUser.id, description = Some("AddNotificationTest")),
        freshUser
      )

      val notifications = this.service.getUserNotifications(freshUser.id, freshUser)
      notifications.size mustEqual 1
      notifications.head.description.get mustEqual "AddNotificationTest"
    }

    "skips notifications user has chosen to ignore" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911123, "Service_addIgnoredNotificationOUser"),
        User.superUser
      )

      this.service.updateNotificationSubscriptions(
        freshUser.id,
        freshUser,
        this.getTestSubscription(
          freshUser.id,
          system = UserNotification.NOTIFICATION_IGNORE
        )
      )

      this.service.addNotification(
        this.getTestNotification(
          freshUser.id,
          notificationType = UserNotification.NOTIFICATION_TYPE_SYSTEM
        ),
        freshUser
      )
      this.service.getUserNotifications(freshUser.id, freshUser).isEmpty mustEqual true
    }

    "requires permission to add a notification" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911124, "Service_addNotificationFailureOUser"),
        User.superUser
      )

      an[IllegalAccessException] should be thrownBy
        this.service.addNotification(this.getTestNotification(freshUser.id), this.defaultUser)
    }

    "add a comment (mention) notification" taggedAs NotificationTag in {
      val firstUser = this.serviceManager.user.create(
        this.getTestUser(299911125, "Service_addCommentNotificationOUser1"),
        User.superUser
      )

      val secondUser = this.serviceManager.user.create(
        this.getTestUser(299911126, "Service addCommentNotificationOUser2"),
        User.superUser
      )

      val comment = Comment(
        12345,
        this.defaultUser.osmProfile.id,
        this.defaultUser.osmProfile.displayName,
        this.defaultUser.osmProfile.avatarURL,
        this.defaultTask.id,
        this.defaultChallenge.id,
        this.defaultProject.id,
        new DateTime(),
        s"Comment mentioning @Service_addCommentNotificationOUser1 and [@Service addCommentNotificationOUser2]"
      )

      // Notifications should be generated for both mentioned users
      this.service.createMentionNotifications(this.defaultUser, comment, this.defaultTask)
      val firstNotifications = this.service.getUserNotifications(firstUser.id, firstUser)
      firstNotifications.size mustEqual 1
      firstNotifications.head.userId mustEqual firstUser.id
      firstNotifications.head.notificationType mustEqual UserNotification.NOTIFICATION_TYPE_MENTION
      firstNotifications.head.targetId.get mustEqual comment.id
      firstNotifications.head.extra.get mustEqual comment.comment
      firstNotifications.head.fromUsername.get mustEqual this.defaultUser.osmProfile.displayName
      firstNotifications.head.taskId.get mustEqual this.defaultTask.id
      firstNotifications.head.challengeId.get mustEqual this.defaultChallenge.id

      val secondNotifications = this.service.getUserNotifications(secondUser.id, secondUser)
      secondNotifications.size mustEqual 1
      secondNotifications.head.userId mustEqual secondUser.id
      secondNotifications.head.notificationType mustEqual UserNotification.NOTIFICATION_TYPE_MENTION
      secondNotifications.head.targetId.get mustEqual comment.id
      secondNotifications.head.extra.get mustEqual comment.comment
      secondNotifications.head.fromUsername.get mustEqual this.defaultUser.osmProfile.displayName
      secondNotifications.head.taskId.get mustEqual this.defaultTask.id
      secondNotifications.head.challengeId.get mustEqual this.defaultChallenge.id
    }

    "add a task-review notification" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911127, "Service_addTaskReviewNotificationOUser1"),
        User.superUser
      )

      val comment = Comment(
        98765,
        this.defaultUser.osmProfile.id,
        this.defaultUser.osmProfile.displayName,
        this.defaultUser.osmProfile.avatarURL,
        this.defaultTask.id,
        this.defaultChallenge.id,
        this.defaultProject.id,
        new DateTime(),
        s"Some review comment"
      )

      // Notifications should be generated for both mentioned users
      this.service.createReviewNotification(
        this.defaultUser,
        freshUser.id,
        Task.REVIEW_STATUS_REJECTED,
        this.defaultTask,
        Some(comment)
      )

      val notifications = this.service.getUserNotifications(freshUser.id, freshUser)
      notifications.size mustEqual 1
      notifications.head.userId mustEqual freshUser.id
      notifications.head.notificationType mustEqual UserNotification.NOTIFICATION_TYPE_REVIEW_REJECTED
      notifications.head.description.get mustEqual Task.REVIEW_STATUS_REJECTED.toString()
      notifications.head.extra.get mustEqual comment.comment
      notifications.head.fromUsername.get mustEqual this.defaultUser.osmProfile.displayName
      notifications.head.taskId.get mustEqual this.defaultTask.id
      notifications.head.challengeId.get mustEqual this.defaultTask.parent
    }

    "add a task-review revised notification" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(399911127, "Service_addTaskReviewRevisedNotificationOUser1"),
        User.superUser
      )

      val comment = Comment(
        98765,
        this.defaultUser.osmProfile.id,
        this.defaultUser.osmProfile.displayName,
        this.defaultUser.osmProfile.avatarURL,
        this.defaultTask.id,
        this.defaultChallenge.id,
        this.defaultProject.id,
        new DateTime(),
        s"Some review comment"
      )

      // Notifications should be generated for both mentioned users
      this.service.createReviewRevisedNotification(
        this.defaultUser,
        freshUser.id,
        Task.REVIEW_STATUS_REJECTED,
        this.defaultTask,
        Some(comment)
      )

      val notifications = this.service.getUserNotifications(freshUser.id, freshUser)
      notifications.size mustEqual 1
      notifications.head.userId mustEqual freshUser.id
      notifications.head.notificationType mustEqual UserNotification.NOTIFICATION_TYPE_REVIEW_REVISED
      notifications.head.description.get mustEqual Task.REVIEW_STATUS_REJECTED.toString()
      notifications.head.extra.get mustEqual comment.comment
      notifications.head.fromUsername.get mustEqual this.defaultUser.osmProfile.displayName
      notifications.head.taskId.get mustEqual this.defaultTask.id
      notifications.head.challengeId.get mustEqual this.defaultTask.parent
    }

    "add a challenge-complete notification" taggedAs NotificationTag in {
      this.service.createChallengeCompletionNotification(this.defaultChallenge)
      val notifications = this.service.getUserNotifications(this.defaultUser.id, this.defaultUser)

      val completed = notifications.find(
        _.notificationType == UserNotification.NOTIFICATION_TYPE_CHALLENGE_COMPLETED
      )
      completed.isDefined mustEqual true
      completed.get.challengeId.get mustEqual this.defaultChallenge.id
      completed.get.projectId.get mustEqual this.defaultChallenge.general.parent
      completed.get.description.get mustEqual this.defaultChallenge.name
    }

    "add a mapper challenge-complete notification" taggedAs NotificationTag in {
      val task = this.taskDAL.insert(
        this.getTestTask("mapperTestTask", this.defaultChallenge.id),
        User.superUser
      )

      val randomUser = this.serviceManager.user.create(
        this.getTestUser(52345, "RandomMapperUser"),
        User.superUser
      )

      this.taskDAL.setTaskStatus(List(task), Task.STATUS_FIXED, randomUser, Some(true))

      this.service.createChallengeCompletionNotification(this.defaultChallenge)
      val notifications = this.service.getUserNotifications(randomUser.id, randomUser)

      val completed = notifications.find(
        _.notificationType == UserNotification.NOTIFICATION_TYPE_MAPPER_CHALLENGE_COMPLETED
      )
      completed.isDefined mustEqual true
      completed.get.challengeId.get mustEqual this.defaultChallenge.id
      completed.get.projectId.get mustEqual this.defaultChallenge.general.parent
      completed.get.description.get mustEqual this.defaultChallenge.name
    }

    "add a team invite notification" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911128, "Service_AddTeamNotificationUser"),
        User.superUser
      )
      val team = this.serviceManager.team
        .create(
          this.getTestTeam("NotificationService_addTeamNotificationTest Team"),
          MemberObject.user(freshUser.id),
          freshUser
        )
        .get

      this.service.createTeamInviteNotification(freshUser, this.defaultUser.id, team)
      val notifications = this.service.getUserNotifications(this.defaultUser.id, this.defaultUser)
      val invited = notifications.find(
        _.notificationType == UserNotification.NOTIFICATION_TYPE_TEAM
      )

      invited.isDefined mustEqual true
      invited.get.fromUsername.get mustEqual freshUser.osmProfile.displayName
      invited.get.description.get mustEqual "invited"
      invited.get.targetId.get mustEqual team.id
      invited.get.extra.get mustEqual team.name
    }

    "add a followed notification" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911129, "Service_addFollowNotificationUser1"),
        User.superUser
      )

      this.service.createFollowedNotification(freshUser, this.defaultUser.id)
      val notifications = this.service.getUserNotifications(this.defaultUser.id, this.defaultUser)
      val followed = notifications.find(
        _.notificationType == UserNotification.NOTIFICATION_TYPE_FOLLOW
      )

      followed.isDefined mustEqual true
      followed.get.fromUsername.get mustEqual freshUser.osmProfile.displayName
      followed.get.description.get mustEqual "followed"
    }

    "prepare notifications for immediate email" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911130, "Service_PrepareNotificationsForImmediateEmailUser"),
        User.superUser
      )
      this.repository.create(
        this.getTestNotification(
          freshUser.id,
          emailStatus = UserNotification.NOTIFICATION_EMAIL_IMMEDIATE
        )
      )
      this.repository.create(
        this.getTestNotification(
          freshUser.id,
          emailStatus = UserNotification.NOTIFICATION_EMAIL_IMMEDIATE
        )
      )
      this.repository.create(
        this.getTestNotification(
          freshUser.id,
          emailStatus = UserNotification.NOTIFICATION_EMAIL_DIGEST
        )
      )

      val notificationsToEmail =
        this.service.prepareNotificationsForImmediateEmail(User.superUser, 50)

      // Notifications created by other unit tests could get picked up, so we
      // only know the minimum number we should have
      (notificationsToEmail.size >= 2) mustEqual true
      notificationsToEmail.head.emailStatus mustEqual UserNotification.NOTIFICATION_EMAIL_SENT
      notificationsToEmail(1).emailStatus mustEqual UserNotification.NOTIFICATION_EMAIL_SENT

      this.service.prepareNotificationsForImmediateEmail(User.superUser, 50).isEmpty mustEqual true
    }

    "superuser required to prepare notifications for immediate email" taggedAs NotificationTag in {
      an[IllegalAccessException] should be thrownBy
        this.service.prepareNotificationsForImmediateEmail(this.defaultUser, 50)
    }

    "prepare notifications for user's digest emails" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911131, "Service_PrepareNotificationsForDigestEmailsUser"),
        User.superUser
      )
      this.repository.create(
        this.getTestNotification(
          freshUser.id,
          emailStatus = UserNotification.NOTIFICATION_EMAIL_IMMEDIATE
        )
      )
      this.repository.create(
        this.getTestNotification(
          freshUser.id,
          emailStatus = UserNotification.NOTIFICATION_EMAIL_DIGEST
        )
      )
      this.repository.create(
        this.getTestNotification(
          freshUser.id,
          emailStatus = UserNotification.NOTIFICATION_EMAIL_DIGEST
        )
      )

      val notificationsToEmail =
        this.service.prepareNotificationsForDigestEmail(freshUser.id, User.superUser, 50)

      notificationsToEmail.size mustEqual 2
      notificationsToEmail.head.emailStatus mustEqual UserNotification.NOTIFICATION_EMAIL_SENT
      notificationsToEmail(1).emailStatus mustEqual UserNotification.NOTIFICATION_EMAIL_SENT

      this.service
        .prepareNotificationsForDigestEmail(freshUser.id, User.superUser, 50)
        .isEmpty mustEqual true
    }

    "superuser required to prepare notifications for digest email" taggedAs NotificationTag in {
      an[IllegalAccessException] should be thrownBy
        this.service.prepareNotificationsForDigestEmail(this.defaultUser.id, this.defaultUser, 50)
    }

    "users with notification emails" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(299911132, "Service_UsersWithNotificationEmailsUser"),
        User.superUser
      )
      this.repository.create(
        this.getTestNotification(
          freshUser.id,
          emailStatus = UserNotification.NOTIFICATION_EMAIL_IMMEDIATE
        )
      )

      val usersAwaitingImmediateEmails = this.service.usersWithNotificationEmails(
        User.superUser,
        UserNotification.NOTIFICATION_EMAIL_IMMEDIATE
      )
      usersAwaitingImmediateEmails.contains(freshUser.id) mustEqual true

      val usersAwaitingDigestEmails = this.service.usersWithNotificationEmails(
        User.superUser,
        UserNotification.NOTIFICATION_EMAIL_DIGEST
      )
      usersAwaitingDigestEmails.contains(freshUser.id) mustEqual false
    }

    "superuser required to get users with notification emails" taggedAs NotificationTag in {
      an[IllegalAccessException] should be thrownBy
        this.service
          .usersWithNotificationEmails(this.defaultUser, UserNotification.NOTIFICATION_EMAIL_DIGEST)
    }
  }

  override implicit val projectTestName: String = "NotificationServiceSpecProject"

  protected def getTestNotification(
      userId: Long,
      notificationType: Int = UserNotification.NOTIFICATION_TYPE_SYSTEM,
      description: Option[String] = None,
      fromUsername: Option[String] = None,
      isRead: Boolean = false,
      emailStatus: Int = UserNotification.NOTIFICATION_EMAIL_NONE
  ): UserNotification = UserNotification(
    -1,
    userId,
    notificationType,
    description = description,
    fromUsername = fromUsername,
    isRead = isRead,
    emailStatus = emailStatus
  )

  protected def getTestSubscription(
      userId: Long,
      system: Int = UserNotification.NOTIFICATION_EMAIL_NONE,
      mention: Int = UserNotification.NOTIFICATION_EMAIL_NONE
  ): NotificationSubscriptions = {
    NotificationSubscriptions(
      -1,
      userId,
      system,
      mention,
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

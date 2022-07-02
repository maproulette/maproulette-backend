/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework

import org.maproulette.framework.model.{User, UserNotification}
import org.maproulette.framework.repository.NotificationRepository
import org.maproulette.framework.service.NotificationService
import org.maproulette.framework.util.{FrameworkHelper, NotificationTag}
import play.api.Application

/**
  * @author nrotstan
  */
class NotificationRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  val repository: NotificationRepository =
    this.application.injector.instanceOf(classOf[NotificationRepository])
  val service: NotificationService = this.serviceManager.notification

  "NotificationRepository" should {
    "create a notification" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(199911111, "CreateNotificationOUser"),
        User.superUser
      )
      this.repository.create(
        this.getTestNotification(freshUser.id, description = Some("CreateNotificationTest"))
      )

      val notifications = this.repository.getUserNotifications(freshUser.id)
      notifications.size mustEqual 1
      notifications.head.description.get mustEqual "CreateNotificationTest"
    }

    "retrieve a user's notifications" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(199911112, "RetrieveUserNotificationsOUser"),
        User.superUser
      )

      this.repository.create(
        this.getTestNotification(
          freshUser.id,
          UserNotification.NOTIFICATION_TYPE_SYSTEM,
          fromUsername = Some(s"User_${this.projectTestName}")
        )
      )

      this.repository
        .getUserNotifications(
          freshUser.id,
          Some(UserNotification.NOTIFICATION_TYPE_SYSTEM)
        )
        .size mustEqual 1

      this.repository
        .getUserNotifications(
          freshUser.id,
          Some(UserNotification.NOTIFICATION_TYPE_MENTION)
        )
        .isEmpty mustEqual true

      this.repository
        .getUserNotifications(
          freshUser.id,
          isRead = Some(false)
        )
        .size mustEqual 1

      this.repository
        .getUserNotifications(
          freshUser.id,
          isRead = Some(true)
        )
        .isEmpty mustEqual true

      this.repository
        .getUserNotifications(
          freshUser.id,
          fromUsername = Some(s"User_${this.projectTestName}")
        )
        .size mustEqual 1

      this.repository
        .getUserNotifications(
          freshUser.id,
          fromUsername = Some("Nonexistent User")
        )
        .isEmpty mustBe true
    }

    "mark notifications as read" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(199911113, "MarkNotificationReadOUser"),
        User.superUser
      )
      this.repository.create(this.getTestNotification(freshUser.id))

      val unreadNotifications = this.repository.getUserNotifications(freshUser.id)
      unreadNotifications.size mustEqual 1
      unreadNotifications.head.isRead mustEqual false

      this.repository.markNotificationsRead(freshUser.id, unreadNotifications.map(_.id))

      val readNotifications =
        this.repository.getUserNotifications(freshUser.id, isRead = Some(true))
      readNotifications.size mustEqual 1
      readNotifications.head.id mustEqual unreadNotifications.head.id
      readNotifications.head.isRead mustEqual true
    }

    "delete notifications" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(199911114, "DeleteNotificationsOUser"),
        User.superUser
      )
      this.repository.create(this.getTestNotification(freshUser.id))

      val notifications = this.repository.getUserNotifications(freshUser.id)
      notifications.size mustEqual 1

      this.repository.deleteNotifications(freshUser.id, notifications.map(_.id))
      this.repository.getUserNotifications(freshUser.id).isEmpty mustEqual true
    }

    "prepare notifications for email" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(199911115, "PrepareNotificationsForEmailUser"),
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
        this
          .getTestNotification(freshUser.id, emailStatus = UserNotification.NOTIFICATION_EMAIL_NONE)
      )

      val notificationsToEmail = this.repository.prepareNotificationsForEmail(
        UserNotification.NOTIFICATION_EMAIL_IMMEDIATE,
        Some(freshUser.id),
        50
      )

      notificationsToEmail.size mustEqual 2
      notificationsToEmail.head.emailStatus mustEqual UserNotification.NOTIFICATION_EMAIL_SENT
      notificationsToEmail(1).emailStatus mustEqual UserNotification.NOTIFICATION_EMAIL_SENT

      this.repository
        .prepareNotificationsForEmail(
          UserNotification.NOTIFICATION_EMAIL_IMMEDIATE,
          Some(freshUser.id),
          50
        )
        .isEmpty mustEqual true
    }

    "users with notification emails" taggedAs NotificationTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(199911116, "UsersWithNotificationEmailsUser"),
        User.superUser
      )
      this.repository.create(
        this.getTestNotification(
          freshUser.id,
          emailStatus = UserNotification.NOTIFICATION_EMAIL_IMMEDIATE
        )
      )

      val usersAwaitingImmediateEmails = this.repository.usersWithNotificationEmails(
        UserNotification.NOTIFICATION_EMAIL_IMMEDIATE
      )
      usersAwaitingImmediateEmails.contains(freshUser.id) mustEqual true

      val usersAwaitingDigestEmails = this.repository.usersWithNotificationEmails(
        UserNotification.NOTIFICATION_EMAIL_DIGEST
      )
      usersAwaitingDigestEmails.contains(freshUser.id) mustEqual false
    }
  }

  override implicit val projectTestName: String = "NotificationRepositorySpecProject"

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
}

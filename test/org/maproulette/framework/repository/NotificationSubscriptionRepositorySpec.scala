/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework

import org.maproulette.framework.model.{User, UserNotification, NotificationSubscriptions}
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.BaseParameter
import org.maproulette.framework.repository.{NotificationSubscriptionRepository}
import org.maproulette.framework.service.NotificationService
import org.maproulette.framework.util.{FrameworkHelper, NotificationTag}
import play.api.Application

/**
  * @author nrotstan
  */
class NotificationSubscriptionRepositorySpec(implicit val application: Application)
    extends FrameworkHelper {
  val repository: NotificationSubscriptionRepository =
    this.application.injector.instanceOf(classOf[NotificationSubscriptionRepository])
  val service: NotificationService = this.serviceManager.notification

  var defaultSubscription: NotificationSubscriptions = null

  "NotificationSubscriptionRepository" should {
    "perform a basic query" taggedAs (NotificationTag) in {
      val subs =
        this.repository.query(
          Query.simple(
            List(BaseParameter(NotificationSubscriptions.FIELD_USER_ID, this.defaultUser.id))
          )
        )
      subs.size mustEqual 1
      subs.head.id mustEqual this.defaultSubscription.id
    }

    "get subscriptions for user" taggedAs (NotificationTag) in {
      val subs = this.repository.getNotificationSubscriptions(this.defaultUser.id)
      subs.isDefined mustEqual true
      subs.get.id mustEqual this.defaultSubscription.id

      val freshUser = this.serviceManager.user.create(
        this.getTestUser(888811111, "getNotificationSubscriptionsOUser"),
        User.superUser
      )
      this.repository.getNotificationSubscriptions(freshUser.id).isDefined mustEqual false
    }

    "upserts a new set of subscription values" taggedAs (NotificationTag) in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(888811112, "insertNotificationSubscriptionOUser"),
        User.superUser
      )

      this.repository.updateNotificationSubscriptions(
        freshUser.id,
        this.getTestSubscription(
          freshUser.id,
          system = UserNotification.NOTIFICATION_EMAIL_DIGEST,
          mention = UserNotification.NOTIFICATION_IGNORE
        )
      )

      val subs = this.repository.getNotificationSubscriptions(freshUser.id).get
      subs.userId mustEqual freshUser.id
      subs.system mustEqual UserNotification.NOTIFICATION_EMAIL_DIGEST
      subs.mention mustEqual UserNotification.NOTIFICATION_IGNORE
    }

    "updates existing subscription values" taggedAs (NotificationTag) in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(888811113, "updateNotificationSubscriptionOUser"),
        User.superUser
      )

      this.repository.updateNotificationSubscriptions(
        freshUser.id,
        this.getTestSubscription(
          freshUser.id,
          system = UserNotification.NOTIFICATION_EMAIL_DIGEST,
          mention = UserNotification.NOTIFICATION_IGNORE
        )
      )

      val subs = this.repository.getNotificationSubscriptions(freshUser.id).get
      this.repository.updateNotificationSubscriptions(
        freshUser.id,
        subs.copy(system = UserNotification.NOTIFICATION_EMAIL_IMMEDIATE)
      )

      val updatedSubs = this.repository.getNotificationSubscriptions(freshUser.id).get
      updatedSubs.userId mustEqual freshUser.id
      updatedSubs.system mustEqual UserNotification.NOTIFICATION_EMAIL_IMMEDIATE
      updatedSubs.mention mustEqual UserNotification.NOTIFICATION_IGNORE
    }
  }

  override implicit val projectTestName: String = "NotificationSubscriptionRepositorySpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    this.service.updateNotificationSubscriptions(
      this.defaultUser.id,
      this.defaultUser,
      this.getTestSubscription(this.defaultUser.id)
    )
    defaultSubscription =
      this.service.getNotificationSubscriptions(this.defaultUser.id, this.defaultUser)
  }

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
      UserNotification.NOTIFICATION_EMAIL_NONE
    )
  }
}

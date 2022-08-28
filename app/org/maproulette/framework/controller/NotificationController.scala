/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.controller

import javax.inject.Inject
import org.apache.commons.lang3.StringUtils
import org.maproulette.exception.StatusMessage
import org.maproulette.framework.service.{NotificationService}
import org.maproulette.framework.model.{NotificationSubscriptions}
import org.maproulette.framework.psql.{Order, OrderField, Paging}
import org.maproulette.session.SessionManager
import org.maproulette.utils.{Crypto, Utils}
import play.api.libs.json._
import play.api.mvc._

/**
  * @author nrotstan
  */
class NotificationController @Inject() (
    service: NotificationService,
    sessionManager: SessionManager,
    components: ControllerComponents,
    bodyParsers: PlayBodyParsers,
    crypto: Crypto
) extends AbstractController(components)
    with DefaultWrites {

  implicit val notificationSubscriptionReads =
    NotificationSubscriptions.notificationSubscriptionReads
  implicit val notificationSubscriptionWrites =
    NotificationSubscriptions.notificationSubscriptionWrites

  def getUserNotifications(
      userId: Long,
      limit: Int,
      page: Int,
      sort: String,
      order: String,
      notificationType: Option[Int] = None,
      isRead: Option[Int] = None,
      fromUsername: Option[String] = None,
      challengeId: Option[Long] = None
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      // Routes don't allow Boolean options, so convert isRead from Int option (zero false, non-zero true)
      val readFilter: Option[Boolean] = isRead match {
        case Some(value) => Some(value != 0)
        case None        => None
      }

      Ok(
        Json.toJson(
          this.service.getUserNotifications(
            userId,
            user,
            notificationType,
            readFilter,
            fromUsername,
            challengeId,
            OrderField(sort, if (order == "ASC") Order.ASC else Order.DESC),
            Paging(limit, page)
          )
        )
      )
    }
  }

  def markNotificationsRead(userId: Long, notificationIds: String): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        if (!StringUtils.isEmpty(notificationIds)) {
          val parsedNotificationIds = Utils.split(notificationIds).map(_.toLong)
          this.service.markNotificationsRead(userId, user, parsedNotificationIds)
        }
        Ok(Json.toJson(StatusMessage("OK", JsString(s"Notifications marked as read"))))
      }
    }

  def deleteNotifications(userId: Long, notificationIds: String): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        if (!StringUtils.isEmpty(notificationIds)) {
          val parsedNotificationIds = Utils.split(notificationIds).map(_.toLong)
          this.service.deleteNotifications(userId, user, parsedNotificationIds)
        }
        Ok(Json.toJson(StatusMessage("OK", JsString(s"Notifications deleted"))))
      }
    }

  def getNotificationSubscriptions(userId: Long): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        Ok(Json.toJson(this.service.getNotificationSubscriptions(userId, user)))
      }
  }

  def updateNotificationSubscriptions(userId: Long): Action[JsValue] =
    Action.async(bodyParsers.json) { implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        this.service.updateNotificationSubscriptions(
          userId,
          user,
          request.body.as[NotificationSubscriptions]
        )
        Ok(Json.toJson(StatusMessage("OK", JsString(s"Subscriptions updated"))))
      }
    }
}

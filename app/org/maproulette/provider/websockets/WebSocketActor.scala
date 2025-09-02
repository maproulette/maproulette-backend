/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.provider.websockets

import play.api.libs.json._
import akka.actor._
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Subscribe, Unsubscribe}
import org.maproulette.framework.model.{Challenge, TaskReview, TaskWithReview, Task}

/**
  * WebSocketActors are responsible for bi-directional communication with client
  * websockets. A new instance is instantiated by the WebSocketController when a
  * new websocket connection is established, and that WebSocketActor instance
  * manages communication with that websocket.
  *
  * When a WebSocketActor instance receives a message from the client websocket,
  * it performs initial processing of the message. When it receives a message
  * from the server, it simply transmits a JSON representation of that message
  * to the client websocket.
  *
  * Messages from the server will typically come by way of an Akka mediator that
  * manages the publish/subscribe of the various message types. The client can
  * send subscribe and unsubscribe messages to determine which message types it
  * is currently interested in receiving (e.g. task-review messages), and this
  * class will inform the mediator that it then wishes to subscribe or
  * unsubscribe to/from those types of messages.
  *
  * Note that this class is not responsible for sending server-initiated
  * messages to the mediator for distribution to clients -- the
  * WebSocketPublisher does that, though server code should use the
  * WebSocketProvider.sendMessage method rather than trying to access the
  * WebSocketPublisher actor directly.
  *
  * @author nrotstan
  */
class WebSocketActor(out: ActorRef) extends Actor {
  implicit val pingMessageReads                   = WebSocketMessages.pingMessageReads
  implicit val subscriptionDataReads              = WebSocketMessages.subscriptionDataReads
  implicit val serverMetaWrites                   = WebSocketMessages.serverMetaWrites
  implicit val pongMessageWrites                  = WebSocketMessages.pongMessageWrites
  implicit val notificationDataWrites             = WebSocketMessages.notificationDataWrites
  implicit val notificationMessageWrites          = WebSocketMessages.notificationMessageWrites
  implicit val achievementDataWrites              = WebSocketMessages.achievementDataWrites
  implicit val achievementMessageWrites           = WebSocketMessages.achievementMessageWrites
  implicit val reviewDataWrites                   = WebSocketMessages.reviewDataWrites
  implicit val reviewMessageWrites                = WebSocketMessages.reviewMessageWrites
  implicit val taskWithReviewWrites               = TaskWithReview.taskWithReviewWrites
  implicit val taskReviewWrites                   = TaskReview.writes
  implicit val taskWrites: Writes[Task]           = Task.TaskFormat
  implicit val challengeWrites: Writes[Challenge] = Challenge.writes.challengeWrites
  implicit val teamUpdateDataWrites               = WebSocketMessages.teamUpdateDataWrites
  implicit val teamMessageWrites                  = WebSocketMessages.teamMessageWrites
  implicit val followUpdateDataWrites             = WebSocketMessages.followUpdateDataWrites
  implicit val followMessageWrites                = WebSocketMessages.followMessageWrites

  def receive = {
    case serverMessage: WebSocketMessages.NotificationMessage =>
      out ! Json.toJson(serverMessage)
    case serverMessage: WebSocketMessages.AchievementMessage =>
      out ! Json.toJson(serverMessage)
    case serverMessage: WebSocketMessages.ReviewMessage =>
      out ! Json.toJson(serverMessage)
    case serverMessage: WebSocketMessages.TaskMessage =>
      out ! Json.toJson(serverMessage)
    case serverMessage: WebSocketMessages.TasksMessage =>
      out ! Json.toJson(serverMessage)
    case serverMessage: WebSocketMessages.TeamMessage =>
      out ! Json.toJson(serverMessage)
    case serverMessage: WebSocketMessages.FollowMessage =>
      out ! Json.toJson(serverMessage)
    case clientMessage: WebSocketMessages.ClientMessage =>
      clientMessage.messageType match {
        case "subscribe" =>
          val mediator         = DistributedPubSub(context.system).mediator
          val subscriptionData = clientMessage.data.get.as[WebSocketMessages.SubscriptionData]
          mediator ! Subscribe(subscriptionData.subscriptionName, self)
        case "unsubscribe" =>
          val mediator         = DistributedPubSub(context.system).mediator
          val subscriptionData = clientMessage.data.get.as[WebSocketMessages.SubscriptionData]
          mediator ! Unsubscribe(subscriptionData.subscriptionName, self)
        case "ping" =>
          // Immediately send back pong
          out ! Json.toJson(WebSocketMessages.pong())
        case _ => None
      }
  }
}

object WebSocketActor {
  def props(out: ActorRef) = Props(new WebSocketActor(out))
}

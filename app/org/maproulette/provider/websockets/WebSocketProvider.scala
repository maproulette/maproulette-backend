/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.provider.websockets

import javax.inject.{Inject, Singleton}
import akka.actor._

/**
  * WebSocketProvider provides convenience methods for interacting with the
  * WebSocketPublisher to publish messages to the various client websockets by
  * way of an Akka mediator. Server code should interact with this class to send
  * messages to clients rather than trying to access actors or the mediator
  * directly.
  *
  * Note that the mediator is responsible for determining which client
  * websockets receive which messages based on each client's communicated
  * subscription preferences, so server code need not worry about trying to
  * address messages to particular clients when using the sendMessage method.
  *
  * The various supported types of messages and data formats, along with helper
  * methods to easily and correctly construct them, can be found in
  * WebSocketMessage
  *
  * @author nrotstan
  */
@Singleton
class WebSocketProvider @Inject() (implicit system: ActorSystem) {
  val publisher = system.actorOf(Props[WebSocketPublisher](), "publisher")

  def sendMessage(message: WebSocketMessages.ServerMessage): Unit = {
    publisher ! message
  }

  def sendMessage(messages: List[WebSocketMessages.ServerMessage]): Unit = {
    messages.foreach { publisher ! _ }
  }
}

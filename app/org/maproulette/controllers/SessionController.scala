package org.maproulette.controllers

import org.maproulette.data.ActionManager
import org.maproulette.exception.StatusMessages
import org.maproulette.session.SessionManager
import org.slf4j.LoggerFactory
import play.api.libs.json.DefaultWrites
import play.api.mvc.{AbstractController, BaseController, PlayBodyParsers}

/**
  * @author mcuthbert
  */
trait SessionController extends BaseController with DefaultWrites with StatusMessages {
  this: AbstractController =>

  // The session manager which should be injected into the implementing class using @Inject
  val sessionManager: SessionManager
  // The action manager which should be injected into the implementing class using @Inject
  val actionManager: ActionManager
  val bodyParsers: PlayBodyParsers
  protected val logger = LoggerFactory.getLogger(this.getClass)
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package controllers

import java.util.Calendar

import akka.actor.ActorRef
import javax.inject.{Inject, Named}
import org.maproulette.exception.StatusMessages
import org.maproulette.jobs.SchedulerActor.RunJob
import org.maproulette.session.SessionManager
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

class Application @Inject() (
    components: ControllerComponents,
    sessionManager: SessionManager,
    @Named("scheduler-actor") schedulerActor: ActorRef
) extends AbstractController(components)
    with StatusMessages {
  def untrail(path: String): Action[AnyContent] = Action {
    MovedPermanently(s"/$path")
  }

  def runJob(name: String, action: String): Action[AnyContent] = Action.async { implicit request =>
    implicit val requireSuperUser = true
    this.sessionManager.authenticatedRequest { implicit user =>
      schedulerActor ! RunJob(name, action)
      Ok
    }
  }

  def ping(): Action[AnyContent] = Action.async { implicit request =>
    import ExecutionContext.Implicits.global
    Future {
      Ok(s"Pong - ${Calendar.getInstance().getTime()}")
    }
  }

  /**
    * Convenience method for users who visit '/docs' to be redirected to the swagger docs
    */
  def redirectDocs(): Action[AnyContent] = Action {
    Redirect(url = "/docs/swagger-ui/index.html")
  }
}

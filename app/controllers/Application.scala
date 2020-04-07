/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package controllers

import java.util.Calendar

import akka.actor.ActorRef
import javax.inject.{Inject, Named}
import org.maproulette.exception.{StatusMessage, StatusMessages}
import org.maproulette.framework.service.{ServiceManager, UserService}
import org.maproulette.jobs.SchedulerActor.RunJob
import org.maproulette.models.dal._
import org.maproulette.session.SessionManager
import play.api.libs.json.{JsString, Json}
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

class Application @Inject() (
    components: ControllerComponents,
    sessionManager: SessionManager,
    dalManager: DALManager,
    serviceManager: ServiceManager,
    @Named("scheduler-actor") schedulerActor: ActorRef
) extends AbstractController(components)
    with StatusMessages {
  def untrail(path: String): Action[AnyContent] = Action {
    MovedPermanently(s"/$path")
  }

  def clearCaches: Action[AnyContent] = Action.async { implicit request =>
    implicit val requireSuperUser = true
    sessionManager.authenticatedRequest { implicit user =>
      this.serviceManager.user.clearCache()
      this.serviceManager.project.clearCache()
      this.dalManager.challenge.clearCaches
      this.dalManager.task.clearCaches
      this.dalManager.tag.clearCaches
      Ok(Json.toJson(StatusMessage("OK", JsString("All caches cleared."))))
    }
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
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.controller

import javax.inject.Inject
import org.maproulette.data.ActionManager
import org.maproulette.framework.model.{Challenge, Project}
import org.maproulette.framework.service.ProjectService
import org.maproulette.models.dal.{ChallengeDAL, TaskDAL}
import org.maproulette.session.SessionManager
import play.api.libs.json._
import play.api.mvc._

class SearchController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val bodyParsers: PlayBodyParsers,
    projectService: ProjectService,
    challengeDAL: ChallengeDAL,
    taskDAL: TaskDAL,
    components: ControllerComponents
) extends AbstractController(components)
    with MapRouletteController {

  implicit val challengeWrites: Writes[Challenge] = Challenge.writes.challengeWrites
  implicit val projectWrites: Writes[Project]     = Project.writes

  def search(q: String, limit: Int = 25): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      val projects   = Json.toJson(projectService.search(q))
      val challenges = Json.toJson(challengeDAL.search(q))
      val tasks      = Json.toJson(taskDAL.search(q, limit))
      Ok(
        Json.obj(
          "projects"   -> projects,
          "challenges" -> challenges,
          "tasks"      -> tasks
        )
      )
    }
  }

  def searchById(id: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      val project = projectService.retrieve(id) match {
        case Some(p) =>
          Json.obj(
            "id"          -> p.id,
            "name"        -> p.name,
            "displayName" -> p.displayName,
            "description" -> p.description
          )
        case None => JsNull
      }

      val challenge = challengeDAL.retrieveById(id) match {
        case Some(c) =>
          Json.obj(
            "id"          -> c.id,
            "name"        -> c.name,
            "description" -> c.description
          )
        case None => JsNull
      }

      val task = taskDAL.retrieveById(id) match {
        case Some(t) =>
          Json.obj(
            "id"     -> t.id,
            "name"   -> t.name,
            "status" -> t.status,
            "parent" -> t.parent
          )
        case None => JsNull
      }

      Ok(
        Json.obj(
          "project"   -> project,
          "challenge" -> challenge,
          "task"      -> task
        )
      )
    }
  }
}

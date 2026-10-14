/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.controller

import javax.inject.Inject
import org.maproulette.data.ActionManager
import org.maproulette.framework.service.{ServiceManager, TaskHistoryService}
import org.maproulette.framework.model.{SessionHistory, TaskLogEntry}
import org.maproulette.framework.repository.SessionHistoryRepository
import org.maproulette.session.SessionManager
import org.maproulette.utils.Utils
import play.api.mvc._
import play.api.libs.json._

/**
  * TaskHistoryController is responsible for fetching the history of a task.
  *
  * @author krotstan
  */
class TaskHistoryController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val bodyParsers: PlayBodyParsers,
    service: TaskHistoryService,
    sessionHistoryRepository: SessionHistoryRepository,
    serviceManager: ServiceManager,
    components: ControllerComponents
) extends AbstractController(components)
    with MapRouletteController {

  /**
    * Gets the history for a task. This includes commments, status_actions, and review_actions.
    *
    * @param taskId      The id of the task being viewed
    * @return
    */
  def getTaskHistoryLog(taskId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(_insertExtraJSON(service.getTaskHistoryLog(taskId)))
    }
  }

  /**
    * Lock/unlock session events for a task, separate from comments/status/review
    * history. The frontend merges this feed into the history widget by timestamp.
    *
    * @param taskId The id of the task being viewed
    * @return
    */
  def getSessionHistory(taskId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      val entries = sessionHistoryRepository.listForTask(taskId)
      val usersById =
        this.serviceManager.user
          .retrieveListById(entries.map(_.userId).distinct)
          .map(u => u.id -> Json.obj("id" -> u.id, "username" -> u.name))
          .toMap
      Ok(SessionHistory.toJsonWithUsers(entries, usersById))
    }
  }

  /**
    * Fetches and inserts usernames for 'osm_user_id', 'reviewRequestedBy' and 'reviewBy'
    */
  private def _insertExtraJSON(entries: List[TaskLogEntry]): JsValue = {
    if (entries.isEmpty) {
      Json.toJson(List[JsValue]())
    } else {
      val users = Some(
        this.serviceManager.user
          .retrieveListById(entries.map(t => t.user.getOrElse(0).toLong))
          .map(u => u.id -> Json.obj("username" -> u.name, "id" -> u.id))
          .toMap
      )

      val mappers = Some(
        this.serviceManager.user
          .retrieveListById(entries.map(t => t.reviewRequestedBy.getOrElse(0).toLong))
          .map(u => u.id -> Json.obj("username" -> u.name, "id" -> u.id))
          .toMap
      )

      val reviewers = Some(
        this.serviceManager.user
          .retrieveListById(entries.map(t => t.reviewedBy.getOrElse(0).toLong))
          .map(u => u.id -> Json.obj("username" -> u.name, "id" -> u.id))
          .toMap
      )

      val metaReviewers = Some(
        this.serviceManager.user
          .retrieveListById(entries.map(t => t.metaReviewRequestedBy.getOrElse(0).toLong))
          .map(u => u.id -> Json.obj("username" -> u.name, "id" -> u.id))
          .toMap
      )

      val jsonList = entries.map { entry =>
        var updated = Json.toJson(entry)
        if (entry.user.getOrElse(0) != 0) {
          val usersJson = Json.toJson(users.get(entry.user.get.toLong)).as[JsObject]
          updated = Utils.insertIntoJson(updated, "user", usersJson, true)
        }
        if (entry.reviewRequestedBy.getOrElse(0) != 0) {
          val mapperJson = Json.toJson(mappers.get(entry.reviewRequestedBy.get.toLong)).as[JsObject]
          updated = Utils.insertIntoJson(updated, "reviewRequestedBy", mapperJson, true)
        }
        if (entry.reviewedBy.getOrElse(0) != 0) {
          val reviewerJson = Json.toJson(reviewers.get(entry.reviewedBy.get.toLong)).as[JsObject]
          updated = Utils.insertIntoJson(updated, "reviewedBy", reviewerJson, true)
        }
        if (entry.metaReviewRequestedBy.getOrElse(0) != 0) {
          val reviewerJson =
            Json.toJson(reviewers.get(entry.metaReviewRequestedBy.get.toLong)).as[JsObject]
          updated = Utils.insertIntoJson(updated, "metaReviewRequestedBy", reviewerJson, true)
        }

        updated
      }
      Json.toJson(jsonList)
    }
  }
}

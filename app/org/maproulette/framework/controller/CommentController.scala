/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.controller

import java.net.URLDecoder

import javax.inject.Inject
import org.maproulette.data.ActionManager
import org.maproulette.framework.service.{CommentService, ServiceManager}
import org.maproulette.session.SessionManager
import play.api.libs.json.Json
import play.api.mvc._

/**
  * @author mcuthbert
  */
class CommentController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val bodyParsers: PlayBodyParsers,
    commentService: CommentService,
    components: ControllerComponents,
    serviceManager: ServiceManager
) extends AbstractController(components)
    with MapRouletteController {

  /**
    * Retrieves a specific comment for the user
    *
    * @param commentId The id of the comment to retrieve
    * @return The comment
    */
  def retrieve(commentId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      this.commentService.retrieve(commentId) match {
        case Some(comment) => Ok(Json.toJson(comment))
        case None          => NotFound
      }
    }
  }

  /**
    * Retrieves all the comments for a Task
    *
    * @param taskId The task to retrieve the comments for
    * @return A list of comments
    */
  def find(taskId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(
        Json.toJson(this.commentService.find(List.empty, List.empty, List(taskId)))
      )
    }
  }

  /**
    * Retrieves all the challenge comments for a Challenge
    *
    * @param challengeId The challenge to retrieve the comments for
    * @return A list of comments
    */
  def findChallengeComments(challengeId: Long): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        Ok(
          Json.toJson(this.commentService.findChallengeComments(challengeId))
        )
      }
  }

  /**
    * Retrieves all the task comments sent by a user
    *
    * @param id The id of the user who sent the comments
    * @return A list of comments
    */
  def findUserComments(
      id: Long,
      sort: String = "created",
      order: String = "DESC",
      limit: Int = 25,
      page: Int = 0
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(
        Json.toJson(this.commentService.findUserComments(id, sort, order, limit, page))
      )
    }
  }

  /**
    * Retrieves all the challenge comments sent by a user
    *
    * @param id The id of the user who sent the comments
    * @return A list of comments
    */
  def findUserChallengeComments(
      id: Long,
      sort: String = "created",
      order: String = "DESC",
      limit: Int = 25,
      page: Int = 0
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(
        Json.toJson(this.commentService.findUserChallengeComments(id, sort, order, limit, page))
      )
    }
  }

  /**
    * Adds a comment for a specific task
    *
    * @param taskId   The id for a task
    * @param comment  The comment the user is leaving
    * @param actionId The action if any associated with the comment
    * @return Ok if successful.
    */
  def add(taskId: Long, comment: String, actionId: Option[Long]): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        Created(
          Json.toJson(
            this.commentService.create(user, taskId, URLDecoder.decode(comment, "UTF-8"), actionId)
          )
        )
      }
    }

  /**
    * Adds a comment for a specific challenge
    *
    * @param challengeId   The id for a challenge
    * @param comment  The comment the user is leaving
    * @return Ok if successful.
    */
  def addChallengeComment(challengeId: Long, comment: String): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        Created(
          Json.toJson(
            this.commentService
              .createChallengeComment(user, challengeId, URLDecoder.decode(comment, "UTF-8"))
          )
        )
      }
    }

  /**
    * Adds a comment for tasks in a bundle
    *
    * @param bundleId   The id for the bundle
    * @param comment  The comment the user is leaving
    * @param actionId The action if any associated with the comment
    * @return Ok if successful.
    */
  def addToBundleTasks(
      bundleId: Long,
      comment: String,
      actionId: Option[Long]
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.commentService.addToBundle(user, bundleId, comment, actionId)

      Ok(Json.toJson(this.serviceManager.taskBundle.getTaskBundle(user, bundleId)))
    }
  }

  /**
    * Updates the original comment
    *
    * @param commentId The ID of the comment to update
    * @param comment   The comment to update
    * @return
    */
  def update(commentId: Long, comment: String): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        Ok(
          Json.toJson(
            this.commentService.update(commentId, URLDecoder.decode(comment, "UTF-8"), user)
          )
        )
      }
  }

  /**
    * Deletes a comment from a task
    *
    * @param taskId    The id of the task that the comment is associated with
    * @param commentId The id of the comment that is being deleted
    * @return Ok if successful,
    */
  def delete(taskId: Long, commentId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.commentService.delete(taskId, commentId, user)
      Ok
    }
  }
}

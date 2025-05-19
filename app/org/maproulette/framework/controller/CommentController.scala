/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.controller

import javax.inject.Inject
import org.maproulette.exception.StatusMessage
import org.maproulette.data.ActionManager
import org.maproulette.framework.service.{CommentService, ServiceManager}
import org.maproulette.session.SessionManager
import play.api.libs.json.{JsValue, JsString, Json}
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
    * @param searchTerm An optional term to search within the comments
    * @param sort The field by which to sort the comments (default is "created")
    * @param order The order of sorting, either "ASC" or "DESC" (default is "DESC")
    * @param limit The maximum number of comments to return (default is 25)
    * @param page The page number for pagination (default is 0)
    * @return A list of comments
    */
  def findUserComments(
      id: Long,
      searchTerm: Option[String] = None,
      sort: String = "created",
      order: String = "DESC",
      limit: Int = 25,
      page: Int = 0
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(
        Json.toJson(this.commentService.findUserComments(id, searchTerm, sort, order, limit, page))
      )
    }
  }

  /**
    * Retrieves all the challenge comments sent by a user
    *
    * @param id The id of the user who sent the comments
    * @param searchTerm An optional term to search within the comments
    * @param sort The field by which to sort the comments (default is "created")
    * @param order The order of sorting, either "ASC" or "DESC" (default is "DESC")
    * @param limit The maximum number of comments to return (default is 25)
    * @param page The page number for pagination (default is 0)
    * @return A list of comments
    */
  def findUserChallengeComments(
      id: Long,
      searchTerm: Option[String] = None,
      sort: String = "created",
      order: String = "DESC",
      limit: Int = 25,
      page: Int = 0
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(
        Json.toJson(
          this.commentService.findUserChallengeComments(id, searchTerm, sort, order, limit, page)
        )
      )
    }
  }

  /**
    * Adds a comment for a specific task
    *
    * @param taskId   The id for a task
    * @param actionId The action if any associated with the comment
    * @return Ok if successful.
    */
  def add(taskId: Long, actionId: Option[Long]): Action[JsValue] =
    Action.async(bodyParsers.json) { implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        val commentResult = (request.body \ "comment").asOpt[String].map(_.trim)

        commentResult match {
          case Some(comment) if comment.nonEmpty =>
            try {
              val createdComment = this.commentService.create(user, taskId, comment, actionId)
              Created(Json.toJson(createdComment))
            } catch {
              case _: Throwable =>
                // Handle other unexpected errors
                BadRequest(Json.toJson(StatusMessage("KO", JsString("Comment couldn't be saved"))))
            }

          case Some(comment) =>
            // Empty comment is not allowed
            BadRequest(Json.toJson(StatusMessage("KO", JsString("Comment cannot be empty"))))

          case None =>
            // "comment" field is missing in the request body
            BadRequest(
              Json.toJson(
                StatusMessage("KO", JsString("Required comment object in request body not found."))
              )
            )
        }
      }
    }

  /**
    * Adds a comment for a specific challenge
    *
    * @param challengeId   The id for a challenge
    * @return Ok if successful.
    */
  def addChallengeComment(challengeId: Long): Action[JsValue] =
    Action.async(bodyParsers.json) { implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        val commentResult = (request.body \ "comment").asOpt[String].map(_.trim)

        commentResult match {
          case Some(comment) if comment.nonEmpty =>
            try {
              Created(
                Json.toJson(this.commentService.createChallengeComment(user, challengeId, comment))
              )
            } catch {
              case _: Throwable =>
                // Handle other unexpected errors
                BadRequest(Json.toJson(StatusMessage("KO", JsString("Comment couldn't be saved"))))
            }

          case Some(comment) =>
            // Empty comment is not allowed
            BadRequest(Json.toJson(StatusMessage("KO", JsString("Comment cannot be empty"))))

          case None =>
            // "comment" field is missing in the request body
            BadRequest(
              Json.toJson(
                StatusMessage("KO", JsString("Required comment object in request body not found."))
              )
            )
        }
      }
    }

  /**
    * Adds a comment for tasks in a bundle
    *
    * @param bundleId   The id for the bundle
    * @param actionId The action if any associated with the comment
    * @return Ok if successful.
    */
  def addToBundleTasks(
      bundleId: Long,
      actionId: Option[Long]
  ): Action[JsValue] = Action.async(bodyParsers.json) { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val commentResult = (request.body \ "comment").asOpt[String].map(_.trim)

      commentResult match {
        case Some(comment) if comment.nonEmpty =>
          try {
            this.commentService.addToBundle(user, bundleId, comment, actionId)
            Ok(Json.toJson(this.serviceManager.taskBundle.getTaskBundle(user, bundleId)))
          } catch {
            case _: Throwable =>
              // Handle other unexpected errors
              BadRequest(Json.toJson(StatusMessage("KO", JsString("Comment couldn't be saved"))))
          }

        case Some(comment) =>
          // Empty comment is not allowed
          BadRequest(Json.toJson(StatusMessage("KO", JsString("Comment cannot be empty"))))

        case None =>
          // "comment" field is missing in the request body
          BadRequest(
            Json.toJson(
              StatusMessage("KO", JsString("Required comment object in request body no found"))
            )
          )
      }
    }
  }

  /**
    * Updates the original comment
    *
    * @param commentId The ID of the comment to update
    * @return
    */
  def update(commentId: Long): Action[JsValue] = Action.async(bodyParsers.json) {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        val commentResult = (request.body \ "comment").asOpt[String].map(_.trim)

        commentResult match {
          case Some(comment) if comment.nonEmpty =>
            try {
              Ok(Json.toJson(this.commentService.update(commentId, comment, user)))
            } catch {
              case _: Throwable =>
                // Handle other unexpected errors
                BadRequest(Json.toJson(StatusMessage("KO", JsString("Comment couldn't be saved"))))
            }

          case Some(comment) =>
            // Empty comment is not allowed
            BadRequest(Json.toJson(StatusMessage("KO", JsString("Comment cannot be empty"))))

          case None =>
            // "comment" field is missing in the request body
            BadRequest(
              Json.toJson(
                StatusMessage("KO", JsString("Required comment object in request body not found."))
              )
            )
        }
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

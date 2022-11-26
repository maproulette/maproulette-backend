/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.controller

import javax.inject.Inject
import org.maproulette.exception.InvalidException
import org.maproulette.data._
import org.maproulette.framework.model.{Task, Tag}
import org.maproulette.framework.service.{TaskBundleService, ServiceManager, TagService}
import org.maproulette.framework.mixins.TagsControllerMixin
import org.maproulette.session.SessionManager
import play.api.libs.json._
import play.api.mvc._

//// deprecated and to be removed after conversion
import org.maproulette.models.dal.TaskDAL
import org.maproulette.models.dal.mixin.TagDALMixin

class TaskBundleController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val bodyParsers: PlayBodyParsers,
    service: TaskBundleService,
    val tagService: TagService,
    components: ControllerComponents,
    serviceManager: ServiceManager,
    taskDAL: TaskDAL
) extends AbstractController(components)
    with MapRouletteController
    with TagsControllerMixin[Task] {

  // json reads for automatically reading Tasks from a posted json body
  override implicit val tReads: Reads[Task] = Task.TaskFormat
  // json writes for automatically writing Tasks to a json body response
  override implicit val tWrites: Writes[Task] = Task.TaskFormat

  // For Tags
  implicit val tagType                        = Task.TABLE
  implicit val itemType: ItemType             = TaskType()
  override def dalWithTags: TagDALMixin[Task] = this.taskDAL

  /**
    * This performs setTaskStatus on a bundle of tasks.
    *
    * @param bundleId  The id of the task bundle
    * @param primaryId The id of the primary task for this bundle
    * @param status    The status id to set the task's status to
    * @param comment   An optional comment to add to the task
    * @param tags      Optional tags to add to the task
    * @return 400 BadRequest if status id is invalid or task with supplied id not found.
    *         If successful then 200 NoContent
    */
  def setBundleTaskStatus(
      bundleId: Long,
      primaryId: Long,
      status: Int,
      comment: String = "",
      tags: String = ""
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val requestReview = request.getQueryString("requestReview") match {
        case Some(v) => Some(v.toBoolean)
        case None    => None
      }

      val tasks = this.serviceManager.taskBundle.getTaskBundle(user, bundleId).tasks match {
        case Some(t) => t
        case None    => throw new InvalidException("No tasks found in this bundle.")
      }

      val completionResponses = request.body.asJson
      this.taskDAL.setTaskStatus(
        tasks,
        status,
        user,
        requestReview,
        completionResponses,
        Some(bundleId),
        Some(primaryId)
      )

      for (task <- tasks) {
        val action = this.actionManager
          .setAction(Some(user), new TaskItem(task.id), TaskStatusSet(status), task.name)
        // add comment to each task if any provided
        if (comment.nonEmpty) {
          val actionId = action match {
            case Some(a) => Some(a.id)
            case None    => None
          }
          this.serviceManager.comment.create(user, task.id, comment, actionId)
        }

        // Add tags to each task
        val tagList = tags.split(",").toList
        if (tagList.nonEmpty) {
          this.addTagstoItem(task.id, tagList.map(new Tag(-1, _, tagType = this.tagType)), user)
        }
      }

      // Refetch to get updated data
      Ok(Json.toJson(this.serviceManager.taskBundle.getTaskBundle(user, bundleId)))
    }
  }

  /**
    * This function sets the task review status.
    * Must be authenticated to perform operation and marked as a reviewer.
    *
    * @param id           The id of the task
    * @param reviewStatus The review status id to set the task's review status to
    * @param comment      An optional comment to add to the task
    * @param tags         Optional tags to add to the task
    * @param newTaskStatus  Optional new taskStatus to change on all tasks in bundle
    * @return 400 BadRequest if task with supplied id not found.
    *         If successful then 200 NoContent
    */
  def setBundleTaskReviewStatus(
      id: Long,
      reviewStatus: Int,
      comment: String = "",
      tags: String = "",
      newTaskStatus: String = "",
      errorTags: String = ""
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val tasks = this.serviceManager.taskBundle.getTaskBundle(user, id).tasks match {
        case Some(t) => {
          // If the mapper wants to change the task status while revising the task after review
          if (!newTaskStatus.isEmpty) {
            val taskStatus = newTaskStatus.toInt

            for (task <- t) {
              // Make sure to remove user's score credit for the prior task status first.
              this.serviceManager.userMetrics.rollbackUserScore(task.status.get, user.id)
            }

            // Change task status. This will also credit user's score for new task status.
            this.taskDAL.setTaskStatus(t, taskStatus, user, Some(false))
            val updatedTasks = this.serviceManager.taskBundle.getTaskBundle(user, id).tasks match {
              case Some(t) => t
              case None    => throw new InvalidException("No tasks found in this bundle.")
            }

            for (task <- t) {
              this.actionManager
                .setAction(Some(user), new TaskItem(task.id), TaskStatusSet(taskStatus), task.name)
            }
            updatedTasks
          } else t
        }
        case None => throw new InvalidException("No tasks found in this bundle.")
      }

      var notify = true
      for (task <- tasks) {
        val action = this.actionManager.setAction(
          Some(user),
          new TaskItem(task.id),
          TaskReviewStatusSet(reviewStatus),
          task.name
        )
        val actionId = action match {
          case Some(a) => Some(a.id)
          case None    => None
        }

        this.serviceManager.taskReview
          .setTaskReviewStatus(task, reviewStatus, user, actionId, comment, errorTags, notify)

        //disable notifications after the first task to prevent duplicates
        notify = false

        if (tags.nonEmpty) {
          val tagList = tags.split(",").toList
          if (tagList.nonEmpty) {
            this.addTagstoItem(task.id, tagList.map(new Tag(-1, _, tagType = this.tagType)), user)
          }
        }
      }

      // Refetch to get updated data
      Ok(Json.toJson(this.serviceManager.taskBundle.getTaskBundle(user, id)))
    }
  }

  /**
    * This function sets the meta review status.
    * Must be authenticated to perform operation and marked as a reviewer.
    *
    * @param id           The id of the task
    * @param reviewStatus The review status id to set the task's review status to
    * @param comment      An optional comment to add to the task
    * @param tags         Optional tags to add to the task
    * @return 400 BadRequest if task with supplied id not found.
    *         If successful then 200 NoContent
    */
  def setBundleMetaReviewStatus(
      id: Long,
      reviewStatus: Int,
      comment: String = "",
      tags: String = "",
      errorTags: String = ""
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val tasks = this.serviceManager.taskBundle.getTaskBundle(user, id).tasks match {
        case Some(t) => t
        case None    => throw new InvalidException("No tasks found in this bundle.")
      }

      for (task <- tasks) {
        val action = this.actionManager.setAction(
          Some(user),
          new TaskItem(task.id),
          MetaReviewStatusSet(reviewStatus),
          task.name
        )
        val actionId = action match {
          case Some(a) => Some(a.id)
          case None    => None
        }

        this.serviceManager.taskReview
          .setMetaReviewStatus(task, reviewStatus, user, actionId, comment, errorTags)

        if (tags.nonEmpty) {
          val tagList = tags.split(",").toList
          if (tagList.nonEmpty) {
            this.addTagstoItem(id, tagList.map(new Tag(-1, _, tagType = this.tagType)), user)
          }
        }
      }

      // Refetch to get updated data
      Ok(Json.toJson(this.serviceManager.taskBundle.getTaskBundle(user, id)))
    }
  }

  /**
    * Creates a new task bundle with the task ids in the json body, assigning
    * ownership of the bundle to the logged-in user
    *
    * @return A TaskBundle representing the new bundle
    */
  def createTaskBundle(): Action[JsValue] = Action.async(bodyParsers.json) { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val name = (request.body \ "name").asOpt[String].getOrElse("")
      val taskIds = (request.body \ "taskIds").asOpt[List[Long]] match {
        case Some(tasks) => tasks
        case None        => throw new InvalidException("No task ids provided for task bundle")
      }
      val bundle = this.serviceManager.taskBundle.createTaskBundle(user, name, taskIds)
      Created(Json.toJson(bundle))
    }
  }

  /**
    * Gets the tasks in the given Bundle
    *
    * @param id The id for the bundle
    * @return Task Bundle
    */
  def getTaskBundle(id: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      Ok(Json.toJson(this.serviceManager.taskBundle.getTaskBundle(user, id)))
    }
  }

  /**
    * Remove tasks from a bundle.
    *
    * @param id      The id for the bundle
    * @param taskIds List of task ids to remove
    * @return Task Bundle
    */
  def unbundleTasks(id: Long, taskIds: List[Long]): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        this.serviceManager.taskBundle.unbundleTasks(user, id, taskIds)
        Ok(Json.toJson(this.serviceManager.taskBundle.getTaskBundle(user, id)))
      }
  }

  /**
    * Delete bundle.
    *
    * @param id        The id for the bundle
    * @param primaryId optional task id to no unlock after deleting this bundle
    */
  def deleteTaskBundle(id: Long, primaryId: Option[Long] = None): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        this.serviceManager.taskBundle.deleteTaskBundle(user, id, primaryId)
        Ok
      }
    }
}

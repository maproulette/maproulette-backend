/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.controller

import javax.inject.Inject
import org.maproulette.exception.{InvalidException, LockConflictException}
import org.maproulette.data._
import org.maproulette.framework.model.{Task, TaskBundle, Tag, User}
import org.maproulette.framework.service.{TaskBundleService, ServiceManager, TagService}
import org.maproulette.framework.mixins.TagsControllerMixin
import org.maproulette.provider.websockets.{WebSocketMessages, WebSocketProvider}
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
    taskDAL: TaskDAL,
    webSocketProvider: WebSocketProvider
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
    * @param tags      Optional tags to add to the task
    * @return 400 BadRequest if status id is invalid or task with supplied id not found.
    *         If successful then 200 NoContent
    */
  def setBundleTaskStatus(
      bundleId: Long,
      primaryId: Long,
      status: Int,
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

      val primaryTask = tasks.find(_.id == primaryId).getOrElse(tasks.head)
      // Resolve the lock's bundle membership before setTaskStatus releases it as a side
      // effect (TaskDAL#setTaskStatus), so we can broadcast the release the same way
      // unbundleTasks/deleteTaskBundle do below - otherwise other tabs/sessions never
      // learn these tasks were unlocked and keep showing them as locked-by-you.
      val releasedTasks = this.taskDAL.resolveLockReleaseTasks(primaryTask)

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

        // Add tags to each task
        val tagList = tags.split(",").toList
        if (tagList.nonEmpty) {
          this.addTagstoItem(task.id, tagList.map(new Tag(-1, _, tagType = this.tagType)), user)
        }
      }

      try {
        if (releasedTasks.length > 1) {
          webSocketProvider.sendMessage(
            WebSocketMessages.tasksReleased(releasedTasks, Some(WebSocketMessages.userSummary(user)))
          )
        } else {
          webSocketProvider.sendMessage(
            WebSocketMessages.taskReleased(releasedTasks.head, Some(WebSocketMessages.userSummary(user)))
          )
        }
      } catch {
        case e: Exception => logger.warn(e.getMessage)
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
    * @param tags         Optional tags to add to the task
    * @param newTaskStatus  Optional new taskStatus to change on all tasks in bundle
    * @return 400 BadRequest if task with supplied id not found.
    *         If successful then 200 NoContent
    */
  def setBundleTaskReviewStatus(
      id: Long,
      reviewStatus: Int,
      tags: String = "",
      newTaskStatus: String = "",
      errorTags: String = ""
  ): Action[JsValue] = Action.async(bodyParsers.json) { implicit request =>
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

        val comment = (request.body \ "comment").asOpt[String].map(_.trim).getOrElse("")

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
    * @param tags         Optional tags to add to the task
    * @return 400 BadRequest if task with supplied id not found.
    *         If successful then 200 NoContent
    */
  def setBundleMetaReviewStatus(
      id: Long,
      reviewStatus: Int,
      tags: String = "",
      errorTags: String = ""
  ): Action[JsValue] = Action.async(bodyParsers.json) { implicit request =>
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

        val comment = (request.body \ "comment").asOpt[String].map(_.trim).getOrElse("")

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
    * ownership of the bundle to the logged-in user. Locks the bundle's primary task for
    * the user, recording the other member task ids in that lock's bundledTasks.
    *
    * @return A TaskBundle representing the new bundle
    */
  def createTaskBundle(): Action[JsValue] = Action.async(bodyParsers.json) { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val name      = (request.body \ "name").asOpt[String].getOrElse("")
      val primaryId = (request.body \ "primaryId").asOpt[Long]
      val taskIds = (request.body \ "taskIds").asOpt[List[Long]] match {
        case Some(tasks) => tasks
        case None        => throw new InvalidException("No task ids provided for task bundle")
      }
      try {
        val bundle = this.serviceManager.taskBundle.createTaskBundle(user, name, primaryId, taskIds)
        this.broadcastBundleClaimed(bundle, user)
        Created(Json.toJson(bundle))
      } catch {
        case e: LockConflictException => this.lockConflictResponse(e)
      }
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
    *  Sets the bundle to the tasks provided, and unlock all tasks removed from current bundle.
    *  Re-locks the bundle's primary task with the updated bundledTasks membership.
    *
    * @param bundleId The id of the bundle
    * @param taskIds The task ids the bundle will reset to
    */
  def updateTaskBundle(
      id: Long,
      taskIds: List[Long]
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      try {
        val previousTaskIds = this.serviceManager.taskBundle.getTaskBundle(user, id).taskIds
        this.serviceManager.taskBundle.updateTaskBundle(user, id, taskIds)
        val updatedBundle = this.serviceManager.taskBundle.getTaskBundle(user, id)

        val removedTaskIds = previousTaskIds.filterNot(taskIds.contains)
        if (removedTaskIds.nonEmpty) {
          webSocketProvider.sendMessage(
            WebSocketMessages.tasksReleased(
              this.taskDAL.retrieveListById(-1, 0)(removedTaskIds),
              Some(WebSocketMessages.userSummary(user))
            )
          )
        }
        this.broadcastBundleClaimed(updatedBundle, user)

        Ok(Json.toJson(updatedBundle))
      } catch {
        case e: LockConflictException => this.lockConflictResponse(e)
      }
    }
  }

  /**
    * Builds a 409 Conflict response describing the lock the user already holds elsewhere,
    * so the client can release it (task release endpoint) and retry.
    */
  private def lockConflictResponse(e: LockConflictException): Result = {
    val conflictingTaskId = e.conflictingLock.itemId
    val conflictingParentName =
      this.taskDAL
        .retrieveById(conflictingTaskId)
        .flatMap(t => this.serviceManager.challenge.retrieve(t.parent))
        .map(_.name)
    Conflict(
      Json.obj(
        "status"       -> "Conflict",
        "message"      -> e.getMessage,
        "lockedTaskId" -> conflictingTaskId,
        "parentName"   -> conflictingParentName,
        "bundledTasks" -> e.conflictingLock.bundledTasks,
        "startedAt"    -> e.conflictingLock.lockedTime.map(_.toString)
      )
    )
  }

  /**
    * Broadcasts a tasks-claimed websocket message for the bundle's current task
    * membership, so other tabs/sessions of the same user (or anyone else subscribed
    * to task/challenge updates) can resync their view of which tasks are bundled and
    * locked together. Mirrors TaskController#startOnTask's single-task broadcast.
    */
  private def broadcastBundleClaimed(bundle: TaskBundle, user: User): Unit = {
    val tasks = bundle.tasks.getOrElse(List())
    if (tasks.nonEmpty) {
      val challengeAndProject = for {
        challenge <- this.serviceManager.challenge.retrieve(tasks.head.parent)
        project   <- this.serviceManager.project.retrieve(challenge.general.parent)
      } yield (challenge, project)

      challengeAndProject.foreach {
        case (challenge, project) =>
          webSocketProvider.sendMessage(
            WebSocketMessages.tasksClaimed(
              tasks,
              WebSocketMessages.challengeSummary(challenge),
              WebSocketMessages.projectSummary(project),
              WebSocketMessages.userSummary(user)
            )
          )
      }
    }
  }

  /**
    * Remove tasks from a bundle.
    *
    * @param id      The id for the bundle
    * @param taskIds List of task ids to remove
    * @return Task Bundle
    */
  def unbundleTasks(
      id: Long,
      taskIds: List[Long]
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val removedTasks = this.taskDAL.retrieveListById(-1, 0)(taskIds)
      this.serviceManager.taskBundle.unbundleTasks(user, id, taskIds)
      if (removedTasks.nonEmpty) {
        webSocketProvider.sendMessage(
          WebSocketMessages.tasksReleased(removedTasks, Some(WebSocketMessages.userSummary(user)))
        )
      }
      Ok(Json.toJson(this.serviceManager.taskBundle.getTaskBundle(user, id)))
    }
  }

  /**
    * Delete bundle.
    *
    * @param id        The id for the bundle
    */
  def deleteTaskBundle(id: Long): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        val tasks = this.serviceManager.taskBundle.getTaskBundle(user, id).tasks.getOrElse(List())
        this.serviceManager.taskBundle.deleteTaskBundle(user, id)
        if (tasks.nonEmpty) {
          webSocketProvider.sendMessage(
            WebSocketMessages.tasksReleased(tasks, Some(WebSocketMessages.userSummary(user)))
          )
        }
        Ok
      }
    }
}

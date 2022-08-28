/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.controller

import javax.inject.Inject
import org.maproulette.data.ActionManager
import org.maproulette.exception.NotFoundException
import org.maproulette.framework.service.{TaskReviewService, TagService, ServiceManager}
import org.maproulette.framework.psql.Paging
import org.maproulette.framework.model.{Challenge, ChallengeListing, Project, User, Tag, Task}
import org.maproulette.framework.mixins.{ParentMixin, TagsControllerMixin}
import org.maproulette.framework.repository.TaskRepository
import org.maproulette.session.{SessionManager, SearchParameters, SearchLocation}
import org.maproulette.utils.Utils
import play.api.mvc._
import play.api.libs.json._

import org.maproulette.data.{
  TaskItem,
  TaskStatusSet,
  TaskReviewStatusSet,
  TaskType,
  MetaReviewStatusSet
}

import org.maproulette.models.dal.TaskDAL
import org.maproulette.models.dal.mixin.TagDALMixin

/**
  * TaskReviewController is responsible for handling functionality related to
  * task reviews.
  *
  * @author krotstan
  */
class TaskReviewController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val bodyParsers: PlayBodyParsers,
    service: TaskReviewService,
    taskRepository: TaskRepository,
    components: ControllerComponents,
    val serviceManager: ServiceManager,
    val taskDAL: TaskDAL
) extends AbstractController(components)
    with MapRouletteController
    with ParentMixin
    with TagsControllerMixin[Task] {

  implicit val challengeListingWrites: Writes[ChallengeListing] = Json.writes[ChallengeListing]

  // For TagsMixin
  override def dalWithTags: TagDALMixin[Task] = this.taskDAL
  def tagService: TagService                  = this.serviceManager.tag
  override implicit val itemType              = TaskType()
  override implicit val tagType               = Task.TABLE
  override implicit val tReads: Reads[Task]   = Task.TaskFormat
  override implicit val tWrites: Writes[Task] = Task.TaskFormat

  /**
    * Gets and claims a task that needs to be reviewed.
    *
    * @param id Task id to work on
    * @return
    */
  def startTaskReview(id: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val task = this.taskRepository.retrieve(id) match {
        case Some(t) => t
        case None    => throw new NotFoundException(s"Task with $id not found, cannot start review.")
      }

      val result = this.service.startTaskReview(user, task)
      Ok(Json.toJson(result))
    }
  }

  /**
    * Releases a claim on a task that needs to be reviewed.
    *
    * @param id Task id to work on
    * @return
    */
  def cancelTaskReview(id: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val task = this.taskRepository.retrieve(id) match {
        case Some(t) => t
        case None    => throw new NotFoundException(s"Task with $id not found, cannot cancel review.")
      }

      val result = this.service.cancelTaskReview(user, task)
      Ok(Json.toJson(result))
    }
  }

  /**
    * Gets and claims the next task that needs to be reviewed.
    *
    * Valid search parameters include:
    * cs => "my challenge name"
    * o => "mapper's name"
    * r => "reviewer's name"
    *
    * @return Task
    */
  def nextTaskReview(
      onlySaved: Boolean = false,
      sort: String,
      order: String,
      lastTaskId: Long = -1,
      excludeOtherReviewers: Boolean = false,
      asMetaReview: Boolean = false
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        // Cancel review claim of last task
        try {
          val lastTask = this.taskRepository.retrieve(lastTaskId) match {
            case Some(t) => t
            case None =>
              throw new NotFoundException(s"Task with $lastTaskId not found, cannot cancel review.")
          }
          this.service.cancelTaskReview(user, lastTask)
        } catch {
          case _: Throwable => // do nothing, it's okay if the user didn't lock the prior task
        }

        val result = this.service.nextTaskReview(
          user,
          params,
          onlySaved,
          sort,
          order,
          (if (lastTaskId == -1) None else Some(lastTaskId)),
          excludeOtherReviewers,
          asMetaReview
        )
        val nextTask = result match {
          case Some(task) =>
            Ok(Json.toJson(this.service.startTaskReview(user, task)))
          case None =>
            throw new NotFoundException("No tasks found to review.")
        }

        nextTask
      }
    }
  }

  /**
    * Gets tasks where a review is requested
    *
    * @param limit The number of tasks to return
    * @param page The page number for the results
    * @param sort The column to sort
    * @param order The order direction to sort
    * @param excludeOtherReviewers exclude tasks that have been reviewed by someone else
    * @return
    */
  def getReviewRequestedTasks(
      onlySaved: Boolean = false,
      limit: Int,
      page: Int,
      sort: String,
      order: String,
      excludeOtherReviewers: Boolean = false,
      includeTags: Boolean = false
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        //cs => "my challenge name"
        //o => "mapper's name"
        //r => "reviewer's name"
        val (count, result) = this.service.getReviewRequestedTasks(
          User.userOrMocked(user),
          params,
          onlySaved,
          Paging(limit, page),
          sort,
          order,
          true,
          excludeOtherReviewers
        )
        Ok(
          Json.obj(
            "total" -> count,
            "tasks" -> insertChallengeJSON(
              result,
              includeTags
            )
          )
        )
      }
    }
  }

  /**
    * Gets reviewed tasks where the user has reviewed or requested review
    *
    * @param allowReviewNeeded Whether we should return tasks where status is review requested also
    * @param limit The number of tasks to return
    * @param page The page number for the results
    * @param sort The column to sort
    * @param order The order direction to sort
    * @return
    */
  def getReviewedTasks(
      allowReviewNeeded: Boolean = false,
      limit: Int,
      page: Int,
      sort: String,
      order: String,
      includeTags: Boolean = false,
      asMetaReview: Boolean = false
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        val (count, result) = this.serviceManager.taskReview.getReviewedTasks(
          User.userOrMocked(user),
          params,
          allowReviewNeeded,
          limit,
          page,
          sort,
          order,
          asMetaReview
        )
        Ok(
          Json.obj(
            "total" -> count,
            "tasks" -> insertChallengeJSON(
              result,
              includeTags
            )
          )
        )
      }
    }
  }

  /**
    * Gets clusters of review tasks. Uses kmeans method in postgis.
    *
    * @param reviewTasksType Type of review tasks (1: To Be Reviewed 2: User's reviewed Tasks 3: All reviewed by users 4:Meta Review)
    * @param numberOfPoints Number of clustered points you wish to have returned
    * @param onlySaved include challenges that have been saved
    * @param excludeOtherReviewers exclude tasks that have been reviewed by someone else
    *
    * @return A list of ClusteredPoint's that represent clusters of tasks
    */
  def getReviewTaskClusters(
      reviewTasksType: Int,
      numberOfPoints: Int,
      onlySaved: Boolean = false,
      excludeOtherReviewers: Boolean = false
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        Ok(
          Json.toJson(
            this.serviceManager.taskReview.getReviewTaskClusters(
              User.userOrMocked(user),
              reviewTasksType,
              params,
              numberOfPoints,
              onlySaved,
              excludeOtherReviewers
            )
          )
        )
      }
    }
  }

  /**
    * This function sets the task review status.
    * Must be authenticated to perform operation and marked as a reviewer.
    *
    * @param id The id of the task
    * @param reviewStatus The review status id to set the task's review status to
    * @param comment An optional comment to add to the task
    * @param tags Optional tags to add to the task
    * @param newTaskStatus Optional new taskStatus to change the task's status
    * @param errorTags Optional string for error tags
    * @return 400 BadRequest if task with supplied id not found.
    *         If successful then 200 NoContent
    */
  def setTaskReviewStatus(
      id: Long,
      reviewStatus: Int,
      comment: String = "",
      tags: String = "",
      newTaskStatus: String = "",
      errorTags: String = ""
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val task = this.taskRepository.retrieve(id) match {
        case Some(t) => {
          // If the mapper wants to change the task status while revising the task after review
          if (!newTaskStatus.isEmpty) {
            val taskStatus = newTaskStatus.toInt

            // Make sure to remove user's score credit for the prior task status first.
            this.serviceManager.userMetrics.rollbackUserScore(t.status.get, user.id)

            // Change task status. This will also credit user's score for new task status.
            this.taskDAL.setTaskStatus(List(t), taskStatus, user, Some(false))
            this.actionManager
              .setAction(Some(user), new TaskItem(t.id), TaskStatusSet(taskStatus), t.name)
            // Refetch Task
            this.taskRepository.retrieve(id).get
          } else t
        }
        case None =>
          throw new NotFoundException(s"Task with $id not found, cannot set review status.")
      }

      val action = this.actionManager
        .setAction(Some(user), new TaskItem(task.id), TaskReviewStatusSet(reviewStatus), task.name)
      val actionId = action match {
        case Some(a) => Some(a.id)
        case None    => None
      }

      this.service.setTaskReviewStatus(task, reviewStatus, user, actionId, comment, errorTags)

      val tagList = tags.split(",").toList
      if (tagList.nonEmpty) {
        this.addTagstoItem(id, tagList.map(new Tag(-1, _, tagType = "review")), user)
      }

      NoContent
    }
  }

  /**
    * This function sets the task meta review status.
    * Must be authenticated to perform operation and marked as a reviewer.
    *
    * @param id The id of the task
    * @param reviewStatus The review status id to set the task's meta review status to
    * @param comment An optional comment to add to the task
    * @param tags Optional tags to add to the task
    * @return 400 BadRequest if task with supplied id not found.
    *         If successful then 200 NoContent
    */
  def setMetaReviewStatus(
      id: Long,
      reviewStatus: Int,
      comment: String = "",
      tags: String = "",
      errorTags: String = ""
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val task = this.taskRepository.retrieve(id) match {
        case Some(t) => t
        case None =>
          throw new NotFoundException(s"Task with $id not found, cannot set review status.")
      }

      val action = this.actionManager
        .setAction(Some(user), new TaskItem(task.id), MetaReviewStatusSet(reviewStatus), task.name)
      val actionId = action match {
        case Some(a) => Some(a.id)
        case None    => None
      }

      this.service.setMetaReviewStatus(task, reviewStatus, user, actionId, comment, errorTags)

      val tagList = tags.split(",").toList
      if (tagList.nonEmpty) {
        this.addTagstoItem(id, tagList.map(new Tag(-1, _, tagType = "review")), user)
      }

      NoContent
    }
  }

  /**
    * This function will set the review status to "Unnecessary", essentially removing the
    * review request.
    *
    * User must have write access to parent challenge(s).
    *
    * @param ids The ids of the tasks to update
    * @return The number of tasks updated.
    */
  def removeReviewRequest(ids: String, asMetaReview: Boolean = false): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        SearchParameters.withSearch { p =>
          implicit val taskIds = Utils.toLongList(ids) match {
            case Some(l) if !l.isEmpty => l
            case None => {
              val params = p.location match {
                case Some(l) => p
                case None    =>
                  // No bounding box, so search everything
                  p.copy(location = Some(SearchLocation(-180, -90, 180, 90)))
              }
              val (count, tasks) =
                this.serviceManager.taskCluster.getTasksInBoundingBox(user, params, Paging(-1))
              tasks.map(task => task.id)
            }
          }

          // set the taskIds variable to `implicit` above
          val updatedTasks = this.taskDAL
            .retrieveListById()
            .foldLeft(0)((updatedCount, t) =>
              t.review.reviewStatus match {
                case Some(r) =>
                  if (asMetaReview) {
                    updatedCount +
                      this.service
                        .setMetaReviewStatus(t, Task.REVIEW_STATUS_UNNECESSARY, user, None, "")
                  } else {
                    updatedCount +
                      this.service
                        .setTaskReviewStatus(t, Task.REVIEW_STATUS_UNNECESSARY, user, None, "")
                  }
                case None => updatedCount
              }
            )

          Ok(Json.toJson(updatedTasks))
        }
      }
    }

  /**
    * Returns a list of challenges that have reviews/review requests.
    *
    * @param reviewTasksType  The type of reviews (1: To Be Reviewed,  2: User's reviewed Tasks, 3: All reviewed by users 4: meta review tasks)
    * @param tStatus The task statuses to include
    * @param excludeOtherReviewers Whether tasks completed by other reviewers should be included
    * @return JSON challenge list
    */
  def listChallenges(
      reviewTasksType: Int,
      tStatus: String,
      excludeOtherReviewers: Boolean = false,
      limit: Int,
      page: Int
  ): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        val taskStatus = tStatus match {
          case v if v.nonEmpty => Utils.toIntList(v)
          case _               => None
        }

        val challenges = this.serviceManager.challengeListing.withReviewList(
          reviewTasksType,
          user,
          taskStatus,
          excludeOtherReviewers,
          Paging(limit, page)
        )

        // Populate some parent/virtual parent project data
        val projects = Some(
          this.serviceManager.project
            .list(challenges.map(c => c.parent))
            .map(p => p.id -> p)
            .toMap
        )

        var vpIds = scala.collection.mutable.Set[Long]()
        challenges.map(c => {
          c.virtualParents match {
            case Some(vps) =>
              vps.map(vp => vpIds += vp)
            case _ => // do nothing
          }
        })
        val vpObjects =
          this.serviceManager.project.list(vpIds.toList).map(p => p.id -> p).toMap

        val jsonList = challenges.map { c =>
          val projectJson = Json
            .toJson(projects.get(c.parent))
            .as[JsObject] - Project.KEY_GRANTS

          var updated =
            Utils.insertIntoJson(Json.toJson(c), Challenge.KEY_PARENT, projectJson, true)
          c.virtualParents match {
            case Some(vps) =>
              val vpJson =
                Some(
                  vps.map(vp => Json.toJson(vpObjects.get(vp)).as[JsObject] - Project.KEY_GRANTS)
                )
              updated = Utils.insertIntoJson(updated, Challenge.KEY_VIRTUAL_PARENTS, vpJson, true)
            case _ => // do nothing
          }
          updated
        }

        Ok(
          Json.toJson(jsonList)
        )
      }
    }

  /**
    * Gets tasks near the given task id within the given challenge
    *
    * @param challengeId  The challenge id that is the parent of the tasks that you would be searching for
    * @param proximityId  Id of task for which nearby tasks are desired
    * @param excludeSelfLocked Also exclude tasks locked by requesting user
    * @param limit        The maximum number of nearby tasks to return
    * @return
    */
  def getNearbyReviewTasks(
      proximityId: Long,
      limit: Int,
      excludeOtherReviewers: Boolean = false,
      onlySaved: Boolean = false
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { params =>
        val results = this.service
          .getNearbyReviewTasks(
            User.userOrMocked(user),
            params,
            proximityId,
            limit,
            excludeOtherReviewers,
            onlySaved
          )
        Ok(Json.toJson(results))
      }
    }
  }
}

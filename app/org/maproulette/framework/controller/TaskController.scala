/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.controller

import akka.util.ByteString

import javax.inject.Inject
import org.maproulette.data.ActionManager
import org.maproulette.framework.service.{
  NotificationService,
  ServiceManager,
  TaskClusterService,
  TaskService
}
import org.maproulette.framework.psql.Paging
import org.maproulette.framework.model.User
import org.maproulette.framework.mixins.TaskJSONMixin
import org.maproulette.session.{SearchLocation, SearchParameters, SessionManager}
import play.api.mvc._
import play.api.libs.json._
import play.api.http.HttpEntity
import org.maproulette.exception.{InvalidException, NotFoundException}
import org.maproulette.models.dal.TaskDAL

/**
  * TaskController is responsible for handling functionality related to
  * tasks.
  *
  * @author krotstan
  */
class TaskController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val bodyParsers: PlayBodyParsers,
    taskService: TaskService,
    taskClusterService: TaskClusterService,
    components: ControllerComponents,
    val taskDAL: TaskDAL,
    val serviceManager: ServiceManager,
    val notificationService: NotificationService
) extends AbstractController(components)
    with MapRouletteController
    with TaskJSONMixin {

  /**
    * Gets clusters of tasks for the challenge. Uses kmeans method in postgis.
    *
    * @param numberOfPoints Number of clustered points you wish to have returned
    * @return A list of ClusteredPoint's that represent clusters of tasks
    */
  def getTaskClusters(numberOfPoints: Int): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { implicit p =>
        val location = p.location.get
        val challengeIds = if (p.challengeParams.challengeIds.isEmpty && location != null) {
          if ((location.top, location.bottom, location.right, location.left) match {
                case (t, b, r, l) => (t - b > 20 || r - l > 20)
              }) {
            throw new InvalidException(
              "Location exceeds the maximum allowed size of 20 latitude by 20 longitude."
            )
          }
          None
        } else {
          p.challengeParams.challengeIds
        }

        val params = p.copy(
          challengeParams = p.challengeParams.copy(challengeIds = None)
        )

        Ok(
          Json.toJson(this.taskClusterService.getTaskClusters(params, numberOfPoints, challengeIds))
        )
      }
    }
  }

  /**
    * Gets the list of tasks that are contained within the single cluster
    *
    * @param clusterId      The cluster id, when "getTaskClusters" is executed it will return single point clusters
    *                       representing all the tasks in the cluster. Each cluster will contain an id, supplying
    *                       that id to this method will allow you to retrieve all the tasks in the cluster
    * @param numberOfPoints Number of clustered points that was originally used to get all the clusters
    * @return A list of ClusteredPoint's that represent each of the tasks within a single cluster
    */
  def getTasksInCluster(clusterId: Int, numberOfPoints: Int): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        SearchParameters.withSearch { implicit params =>
          Ok(
            Json.toJson(
              this.taskClusterService.getTasksInCluster(clusterId, params, numberOfPoints)
            )
          )
        }
      }
  }

  /**
    * Gets all the tasks within a bounding box
    *
    * @param left   The minimum longitude for the bounding box
    * @param bottom The minimum latitude for the bounding box
    * @param right  The maximum longitude for the bounding box
    * @param top    The maximum latitude for the bounding box
    * @param limit  Limit for the number of returned tasks
    * @param offset The offset used for paging
    * @return
    */
  def getTasksInBoundingBox(
      left: Double,
      bottom: Double,
      right: Double,
      top: Double,
      limit: Int,
      page: Int,
      excludeLocked: Boolean,
      sort: String = "",
      order: String = "ASC",
      includeTotal: Boolean = false,
      includeGeometries: Boolean = false,
      includeTags: Boolean = false
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { p =>
        val challengeIds = if (p.challengeParams.challengeIds.isEmpty) {
          if ((left, bottom, right, top) match {
                case (l, b, r, t) => (t - b > 20 || r - l > 20)
              }) {
            throw new InvalidException(
              "Location exceeds the maximum allowed size of 20 latitude by 20 longitude."
            )
          }
          None // or some default value if needed
        } else {
          p.challengeParams.challengeIds
        }

        val params = p.copy(
          challengeParams = p.challengeParams.copy(challengeIds = None),
          location = Some(SearchLocation(left, bottom, right, top))
        )
        val (count, result) = this.taskClusterService.getTasksInBoundingBox(
          User.userOrMocked(user),
          params,
          Paging(limit, page),
          excludeLocked,
          sort,
          order,
          challengeIds
        )

        val resultJson = this.insertExtraTaskJSON(result, includeGeometries, includeTags)

        if (includeTotal) {
          Ok(Json.obj("total" -> count, "tasks" -> resultJson))
        } else {
          Ok(resultJson)
        }
      }
    }
  }

  /**
    * Gets all the task markers within a bounding box
    *
    * @param left   The minimum longitude for the bounding box
    * @param bottom The minimum latitude for the bounding box
    * @param right  The maximum longitude for the bounding box
    * @param top    The maximum latitude for the bounding box
    * @param limit  Limit for the number of returned tasks
    * @return
    */
  def getTaskMarkerDataInBoundingBox(
      left: Double,
      bottom: Double,
      right: Double,
      top: Double,
      limit: Int,
      excludeLocked: Boolean,
      includeGeometries: Boolean,
      includeTags: Boolean
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { p =>
        val challengeIds = if (p.challengeParams.challengeIds.isEmpty) {
          if ((left, bottom, right, top) match {
                case (l, b, r, t) => (t - b > 20 || r - l > 20)
              }) {
            throw new InvalidException(
              "Location exceeds the maximum allowed size of 20 latitude by 20 longitude."
            )
          }
          None // No challenge IDs
        } else {
          p.challengeParams.challengeIds
        }

        // Create params without challengeIds
        val params = p.copy(
          challengeParams = p.challengeParams.copy(challengeIds = None),
          location = Some(SearchLocation(left, bottom, right, top))
        )
        val result = this.taskClusterService.getTaskMarkerDataInBoundingBox(
          User.userOrMocked(user),
          params,
          limit,
          excludeLocked,
          challengeIds
        )

        val resultJson = this.insertExtraTaskJSON(result, includeGeometries, includeTags)

        Ok(resultJson)
      }
    }
  }

  /**
    * Updates the completion responses asked in the task instructions. Request
    * body should include the reponse JSON.
    *
    * @param id    The id of the task
    */
  def updateCompletionResponses(id: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val responses = request.body.asJson match {
        case Some(r) => r
        case None =>
          throw new NotFoundException(s"Completion responses not found in request body.")
      }

      this.taskService.updateCompletionResponses(id, user, responses)
      NoContent
    }
  }

  /**
    * Retrieve a task attachment
    */
  def attachment(id: Long, attachmentId: String): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        this.taskService.getTaskAttachment(id, attachmentId) match {
          case Some(attachment) => Ok(attachment)
          case None             => throw new NotFoundException(s"Attachment not found.")
        }
      }
  }

  /**
    * Download the data from a task attachment as a file, decoded if necessary,
    * and with the correct mime type
    */
  def attachmentData(id: Long, attachmentId: String, filename: String): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        this.taskService.getTaskAttachment(id, attachmentId) match {
          case Some(attachment) =>
            var mimeType: Option[String] = None
            val fileContent: String = (attachment \ "type").asOpt[String] match {
              case Some(dataType) if dataType == "geojson" || dataType == "json" =>
                mimeType = Some("application/json")
                Json.stringify((attachment \ "data").get)
              case _ =>
                (attachment \ "format").asOpt[String] match {
                  case Some(format) if format == "json" =>
                    mimeType = Some("application/json")
                    Json.stringify((attachment \ "data").get)
                  case Some(format) if format == "xml" =>
                    mimeType = Some("text/xml")
                    (attachment \ "encoding").asOpt[String] match {
                      case Some(encoding) if encoding == "base64" =>
                        new String(
                          java.util.Base64.getDecoder.decode((attachment \ "data").as[String])
                        )
                      case Some(encoding) =>
                        throw new UnsupportedOperationException("Data encoding not supported")
                      case None => (attachment \ "data").as[String]
                    }
                  case _ => throw new UnsupportedOperationException("Data format not supported")
                }
            }

            Result(
              header = ResponseHeader(
                OK,
                Map(CONTENT_DISPOSITION -> s"attachment; filename=${filename}")
              ),
              body = HttpEntity.Strict(
                ByteString.fromString(fileContent),
                mimeType
              )
            )
          case None =>
            throw new NotFoundException(s"Attachment not found.")
        }
      }
    }

  /**
    * Request that a task be unlocked by sending a notification to the user who has it locked
    *
    * @param taskId The id of the task that is locked
    * @return 200 OK with success message, or error status
    */
  def requestTaskUnlock(taskId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val task = taskDAL.retrieveById(taskId)

      task match {
        case Some(t) => {
          val lockerId = taskDAL.lockItem(user, t)
          if (lockerId != user.id) {
            this.serviceManager.user.retrieve(lockerId) match {
              case Some(lockUser) =>
                this.serviceManager.notification
                  .createTaskUnlockRequestNotification(user, lockUser, t)
                Ok(Json.obj("message" -> "Unlock request notification has been sent successfully"))
              case None =>
                throw new IllegalAccessException(s"Your notification request encountered an error")
            }
          } else {
            throw new IllegalAccessException(s"Your notification request encountered an error")
          }
        }
        case None =>
          throw new NotFoundException(
            s"Task with $taskId not found, unable to process unlock notification request."
          )
      }
    }
  }
}

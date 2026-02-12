/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.controller

import akka.util.ByteString
import javax.inject.Inject
import org.maproulette.data.ActionManager
import org.maproulette.framework.service.{
  ServiceManager,
  TaskService,
  TaskClusterService,
  NotificationService
}
import org.maproulette.framework.psql.Paging
import org.maproulette.framework.model.{
  User,
  TaskMarker,
  TaskMarkerLocation,
  TaskMarkerResponse,
  OverlappingTaskMarker
}
import org.maproulette.framework.mixins.TaskJSONMixin
import org.maproulette.session.{SessionManager, SearchParameters, SearchLocation}
import play.api.mvc._
import play.api.libs.json._
import play.api.http.HttpEntity
import org.maproulette.exception.NotFoundException

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
      SearchParameters.withSearch { implicit params =>
        Ok(Json.toJson(this.taskClusterService.getTaskClusters(params, numberOfPoints)))
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
        val params = p.copy(location = Some(SearchLocation(left, bottom, right, top)))
        val (count, result) = this.taskClusterService.getTasksInBoundingBox(
          User.userOrMocked(user),
          params,
          Paging(limit, page),
          excludeLocked,
          sort,
          order
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
    * Gets challenge tasks within a bounding box (simplified endpoint)
    *
    * @param bounds       Comma-separated bounding box coordinates: "left,bottom,right,top"
    * @param challengeIds Comma-separated list of challenge IDs to filter by
    * @param limit        Maximum number of tasks to return per page
    * @param page         Page number for pagination (0-indexed)
    * @return Paginated list of tasks with total count
    */
  def getChallengeTasksInBounds(
      bounds: String,
      challengeIds: String,
      limit: Int,
      page: Int
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      // Parse bounds string (format: "left,bottom,right,top")
      val location = if (bounds.nonEmpty) {
        try {
          val coords = bounds.split(",").map(_.trim.toDouble)
          if (coords.length == 4) {
            Some(SearchLocation(coords(0), coords(1), coords(2), coords(3)))
          } else {
            None
          }
        } catch {
          case _: Exception => None
        }
      } else {
        None
      }

      // Parse challenge IDs
      val challenges = if (challengeIds.nonEmpty) {
        try {
          Some(challengeIds.split(",").map(_.trim.toLong).toList)
        } catch {
          case _: Exception => None
        }
      } else {
        None
      }

      // Get tasks using simplified method
      val (count, result) = this.taskClusterService.getChallengeTasksInBounds(
        location,
        challenges,
        Paging(limit, page)
      )

      // Convert result to JSON (without extra geometries/tags for performance)
      val resultJson =
        this.insertExtraTaskJSON(result, includeGeometries = false, includeTags = false)

      // Return paginated response
      Ok(
        Json.obj(
          "data"  -> resultJson,
          "total" -> count,
          "page"  -> page,
          "limit" -> limit
        )
      )
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
        val params = p.copy(location = Some(SearchLocation(left, bottom, right, top)))
        val result = this.taskClusterService.getTaskMarkerDataInBoundingBox(
          User.userOrMocked(user),
          params,
          limit,
          excludeLocked
        )

        val resultJson = this.insertExtraTaskJSON(result, includeGeometries, includeTags)

        Ok(resultJson)
      }
    }
  }

  def getTaskMarkers(
      statuses: String,
      global: Boolean,
      cluster: Boolean,
      bounds: Option[String],
      location_id: Option[Long],
      keywords: Option[String],
      difficulty: Option[Int]
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { p =>
        val statusList = if (statuses.isEmpty) {
          List.empty[Int]
        } else {
          statuses.split(",").map(_.trim.toInt).toList
        }

        val boundingBox = bounds match {
          case Some(b) =>
            b.split(",").map(_.trim.toDouble).toList match {
              case List(left, bottom, right, top) =>
                SearchLocation(left, bottom, right, top)
              case _ => SearchLocation(-180.0, -90.0, 180.0, 90.0)
            }
          case None => SearchLocation(-180.0, -90.0, 180.0, 90.0)
        }

        val taskCount = this.taskClusterService.countTaskMarkers(
          statusList,
          global,
          boundingBox,
          location_id,
          keywords,
          difficulty
        )

        if (taskCount > 5000) {
          Ok(
            Json.toJson(
              TaskMarkerResponse(
                totalCount = taskCount,
                tasks = None,
                clusters = None
              )
            )
          )
        } else if ((cluster || taskCount > 500) && !(taskCount < 100)) {
          val clusters = this.taskClusterService.getTaskMarkersClustered(
            statusList,
            global,
            boundingBox,
            location_id,
            keywords,
            difficulty
          )
          Ok(
            Json.toJson(
              TaskMarkerResponse(
                totalCount = taskCount,
                tasks = None,
                clusters = Some(clusters)
              )
            )
          )
        } else {
          val (singleMarkers, overlappingMarkers) =
            this.taskClusterService.getTaskMarkersWithOverlaps(
              statusList,
              global,
              boundingBox,
              location_id,
              keywords,
              difficulty
            )
          Ok(
            Json.toJson(
              TaskMarkerResponse(
                totalCount = taskCount,
                tasks = Some(singleMarkers),
                overlappingTasks =
                  if (overlappingMarkers.nonEmpty) Some(overlappingMarkers) else None,
                clusters = None
              )
            )
          )
        }
      }
    }
  }

  /**
    * Gets task data using pre-computed tile aggregates for efficient map display at scale.
    * Uses a tile pyramid system with pre-computed counts broken down by difficulty × global.
    *
    * Behavior by filter:
    * - difficulty & global: Filtered from pre-computed tile data (fast)
    * - location_id: Recursive tile drilling until within polygon or < 2000 tasks
    * - keywords: Falls back to dynamic query (challenge-level filter, not pre-computed)
    *
    * All fetched data is re-clustered into ~80 clusters for display.
    * When total tasks < 2000, returns individual task markers instead of clusters.
    *
    * @param z           Zoom level (0-14 for pre-computed tiles)
    * @param bounds      Comma-separated bounding box: left,bottom,right,top
    * @param global      Whether to include global challenges
    * @param location_id Optional Nominatim place_id for polygon filtering
    * @param keywords    Optional keywords filter (triggers fallback to dynamic query)
    * @param difficulty  Optional difficulty filter (1=Easy, 2=Normal, 3=Expert)
    * @return TaskMarkerResponse with totalCount and either clusters or tasks (with overlaps)
    */
  def getTaskTiles(
      z: Int,
      bounds: String,
      global: Boolean,
      location_id: Option[Long],
      keywords: Option[String],
      difficulty: Option[Int]
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      // Clamp zoom to valid range
      val validZoom = math.max(0, math.min(20, z))

      // Only allow valid difficulty values (1, 2, 3)
      val validDifficulty = difficulty.filter(d => d >= 1 && d <= 3)

      // Parse and validate bounding box
      val boundingBox = try {
        bounds.split(",").map(_.trim.toDouble).toList match {
          case List(left, bottom, right, top) =>
            // Clamp coordinates to valid ranges
            val clampedLeft   = math.max(-180.0, math.min(180.0, left))
            val clampedRight  = math.max(-180.0, math.min(180.0, right))
            val clampedBottom = math.max(-90.0, math.min(90.0, bottom))
            val clampedTop    = math.max(-90.0, math.min(90.0, top))
            SearchLocation(clampedLeft, clampedBottom, clampedRight, clampedTop)
          case _ =>
            SearchLocation(-180.0, -90.0, 180.0, 90.0)
        }
      } catch {
        case _: NumberFormatException =>
          SearchLocation(-180.0, -90.0, 180.0, 90.0)
      }

      val response = this.serviceManager.tileAggregate.getTileData(
        validZoom,
        boundingBox,
        validDifficulty,
        global,
        location_id,
        keywords
      )
      Ok(Json.toJson(response))
    }
  }

// for getting more detailed task marker data on individul makrers
  // def getTaskMarkerData(id: Long): Action[AnyContent] = Action.async { implicit request =>
  //   this.sessionManager.userAwareRequest { implicit user =>
  //     val task = this.taskService.getTask(id)
  //     Ok(Json.toJson(task))
  //   }
  // }

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

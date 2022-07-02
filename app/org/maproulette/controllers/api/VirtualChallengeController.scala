/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.controllers.api

import javax.inject.Inject
import org.apache.commons.lang3.StringUtils
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.controllers.CRUDController
import org.maproulette.data.{ActionManager, TaskViewed, VirtualChallengeType}
import org.maproulette.exception.NotFoundException
import org.maproulette.framework.model.{User, ClusteredPoint, Task}
import org.maproulette.models.dal.{TaskDAL, VirtualChallengeDAL}
import org.maproulette.models.VirtualChallenge
import org.maproulette.session.{
  SearchChallengeParameters,
  SearchTaskParameters,
  SearchReviewParameters,
  SearchLeaderboardParameters,
  SearchLocation,
  SearchParameters,
  SessionManager,
  TaskPropertySearch
}
import org.maproulette.utils.Utils
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.duration.Duration

/**
  * @author mcuthbert
  */
class VirtualChallengeController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val dal: VirtualChallengeDAL,
    taskDAL: TaskDAL,
    config: Config,
    components: ControllerComponents,
    override val bodyParsers: PlayBodyParsers
) extends AbstractController(components)
    with CRUDController[VirtualChallenge] {

  override implicit val tReads: Reads[VirtualChallenge]   = VirtualChallenge.virtualChallengeReads
  override implicit val tWrites: Writes[VirtualChallenge] = VirtualChallenge.virtualChallengeWrites
  override implicit val itemType                          = VirtualChallengeType()

  implicit val jodaDateWrite: Writes[DateTime] =
    JodaWrites.jodaDateWrites("yyyy-MM-dd'T'HH:mm:ss'Z")
  implicit val jodaDateReads: Reads[DateTime] = JodaReads.jodaDateReads("yyyy-MM-dd'T'HH:mm:ss'Z")

  //writes for tasks
  implicit val taskWrites: Writes[Task] = Task.TaskFormat
  implicit val taskReads: Reads[Task]   = Task.TaskFormat
  //reads and writes for Search Parameters
  implicit val locationWrites           = Json.writes[SearchLocation]
  implicit val locationReads            = Json.reads[SearchLocation]
  implicit val taskPropertySearchWrites = Json.writes[TaskPropertySearch]
  implicit val taskPropertySearchReads  = Json.reads[TaskPropertySearch]
  implicit val challengeParamsWrites    = Json.writes[SearchChallengeParameters]
  implicit val challengeParamsReads     = Json.reads[SearchChallengeParameters]
  implicit val taskParamsWrites         = Json.writes[SearchTaskParameters]
  implicit val taskParamsReads          = Json.reads[SearchTaskParameters]
  implicit val reviewParamsWrites       = Json.writes[SearchReviewParameters]
  implicit val reviewParamsReads        = Json.reads[SearchReviewParameters]
  implicit val leaderboardParamsWrites  = Json.writes[SearchLeaderboardParameters]
  implicit val leaderboardParamsReads   = Json.reads[SearchLeaderboardParameters]
  implicit val paramsWrites             = Json.writes[SearchParameters]
  implicit val paramsReads              = Json.reads[SearchParameters]

  /**
    * This function allows sub classes to modify the body, primarily this would be used for inserting
    * default elements into the body that shouldn't have to be required to create an object.
    *
    * @param body The incoming body from the request
    * @param user The user executing the request
    * @return
    */
  override def updateCreateBody(body: JsValue, user: User): JsValue = {
    val jsonBody = super.updateCreateBody(body, user)
    // if expiry is set as a hours from now, pull it out and convert to timestamp.
    val expiryValue = (jsonBody \ "expiry").asOpt[String] match {
      case Some(ex) => Duration(ex).toMillis.toInt
      case None     => config.virtualChallengeExpiry.toMillis.toInt
    }
    val expiryUpdate =
      Utils.insertIntoJson(jsonBody, "expiry", DateTime.now().plusMillis(expiryValue), true)(
        JodaWrites.JodaDateTimeNumberWrites
      )
    val searchUpdate = Utils.insertIntoJson(expiryUpdate, "searchParameters", SearchParameters())
    Utils.insertIntoJson(searchUpdate, "ownerId", user.osmProfile.id)
  }

  /**
    * Lists all the tasks for the virtual challenge
    *
    * @param id     The id of the virtual challenge that you are listing the tasks for
    * @param limit  Limit the number of tasks returned
    * @param offset paging offset
    * @return
    */
  def listTasks(id: Long, limit: Int, offset: Int): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        Ok(Json.toJson(this.dal.listTasks(id, User.userOrMocked(user), limit, offset)))
      }
  }

  /**
    * Rebuilds the challenge to take into account any new tasks that can be found in from the original
    * search parameters
    *
    * @param id The id of the virtual challenge
    * @return
    */
  def rebuildVirtualChallenge(id: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { p =>
        this.dal.rebuildVirtualChallenge(id, p, User.userOrMocked(user))
        Ok
      }
    }
  }

  /**
    * Gets a random task from the list of tasks within the virtual challenge
    *
    * @param id
    * @return
    */
  def getRandomTask(id: Long, proximity: Long): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        SearchParameters.withSearch { p =>
          val proximityOption = if (proximity < 0) {
            None
          } else {
            Some(proximity)
          }
          Ok(Json.toJson(this.getRandomTask(id, p, user, proximityOption)))
        }
      }
  }

  /**
    * Gets a random task from the list of tasks within the virtual challenge
    *
    * @param name
    * @return
    */
  def getRandomTask(name: String, proximity: Long): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        SearchParameters.withSearch { p =>
          this.dal.retrieveByName(name) match {
            case Some(vc) =>
              val proximityOption = if (proximity < 0) {
                None
              } else {
                Some(proximity)
              }
              Ok(Json.toJson(this.getRandomTask(vc.id, p, user, proximityOption)))
            case None =>
              throw new NotFoundException("No Virtual Challenge found with that challenge name.")
          }
        }
      }
  }

  private def getRandomTask(
      id: Long,
      params: SearchParameters,
      user: Option[User],
      proximity: Option[Long] = None
  ): Option[Task] = {
    val results = this.dal.getRandomTask(id, params, User.userOrMocked(user), proximity)
    results.foreach(task =>
      this.actionManager.setAction(user, this.itemType.convertToItem(task.id), TaskViewed(), "")
    )
    results
  }

  /**
    * Gets the next task in sequential order for the specified virtual challenge
    *
    * @param challengeId   The current virtual challenge id
    * @param currentTaskId The current task id that is being viewed
    * @return The next task in the list
    */
  def getSequentialNextTask(challengeId: Long, currentTaskId: Long): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        Ok(
          Utils.getResponseJSON(
            this.dal.getSequentialNextTask(challengeId, currentTaskId),
            this.taskDAL.getLastModifiedUser
          )
        );
      }
    }

  /**
    * Gets the previous task in sequential order for the specified challenge
    *
    * @param challengeId   The current virtual challenge id
    * @param currentTaskId The current task id that is being viewed
    * @return The previous task in the list
    */
  def getSequentialPreviousTask(challengeId: Long, currentTaskId: Long): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        Ok(
          Utils.getResponseJSON(
            this.dal.getSequentialPreviousTask(challengeId, currentTaskId),
            this.taskDAL.getLastModifiedUser
          )
        );
      }
    }

  /**
    * Gets tasks near the given task id within the given virtual challenge
    *
    * @param challengeId  The current virtual challenge id
    * @param proximityId  Id of task for which nearby tasks are desired
    * @param limit        The maximum number of nearby tasks to return
    * @return
    */
  def getNearbyTasks(challengeId: Long, proximityId: Long, limit: Int): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        val results =
          this.dal.getNearbyTasks(User.userOrMocked(user), challengeId, proximityId, limit)
        Ok(Json.toJson(results))
      }
    }

  /**
    * Gets the geo json for all the tasks associated with the challenge
    *
    * @param challengeId  The challenge with the geojson
    * @param statusFilter Filtering by status of the tasks
    * @return
    */
  def getVirtualChallengeGeoJSON(challengeId: Long, statusFilter: String): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        this.dal.retrieveById(challengeId) match {
          case Some(c) =>
            val filter = if (StringUtils.isEmpty(statusFilter)) {
              None
            } else {
              Some(Utils.split(statusFilter).map(_.toInt))
            }
            Ok(Json.parse(this.dal.getChallengeGeometry(challengeId, filter)))
          case None =>
            throw new NotFoundException(s"No virtual challenge with id $challengeId found.")
        }
      }
    }

  def getClusteredPoints(challengeId: Long, statusFilter: String): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        implicit val writes = ClusteredPoint.clusteredPointWrites
        val filter = if (StringUtils.isEmpty(statusFilter)) {
          None
        } else {
          Some(Utils.split(statusFilter).map(_.toInt))
        }
        Ok(Json.toJson(this.dal.getClusteredPoints(challengeId, filter)))
      }
    }
}

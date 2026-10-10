/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.controllers.api

import javax.inject.Inject
import org.maproulette.Config
import org.maproulette.data._
import org.maproulette.framework.model.Challenge
import org.maproulette.models.dal.ChallengeDAL
import org.maproulette.session.{SearchParameters, SessionManager}
import org.maproulette.utils.Utils
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json._
import play.api.mvc._

import scala.collection.mutable

/**
  * @author cuthbertm
  */
class DataController @Inject() (
    sessionManager: SessionManager,
    challengeDAL: ChallengeDAL,
    dataManager: DataManager,
    config: Config,
    actionManager: ActionManager,
    components: ControllerComponents,
    statusActionManager: StatusActionManager
) extends AbstractController(components) {

  implicit val actionWrites              = actionManager.actionItemWrites
  implicit val dateWrites                = Writes.dateWrites("yyyy-MM-dd")
  implicit val dateTimeWrites            = JodaWrites.jodaDateWrites("yyyy-MM-dd'T'HH:mm:ss")
  implicit val actionSummaryWrites       = Json.writes[ActionSummary]
  implicit val challengeSummaryWrites    = Json.writes[ChallengeSummary]
  implicit val challengeActivityWrites   = Json.writes[ChallengeActivity]
  implicit val rawActivityWrites         = Json.writes[RawActivity]
  implicit val statusActionItemWrites    = Json.writes[StatusActionItem]
  implicit val statusActionSummaryWrites = Json.writes[DailyStatusActionSummary]

  implicit val stringIntMap: Writes[Map[String, Int]] = new Writes[Map[String, Int]] {
    def writes(map: Map[String, Int]): JsValue =
      Json.obj(map.map {
        case (s, i) =>
          val ret: (String, JsValueWrapper) = s.toString -> JsNumber(i)
          ret
      }.toSeq: _*)
  }

  /**
    * Gets the recent contributions of the logged-in user
    *
    * @param limit the limit on the number of contributions returned
    * @return List of the user's recent task actions, newest first
    */
  def getUserContributions(limit: Int): Action[AnyContent] =
    Action.async { implicit request =>
      val actualLimit = if (limit == -1) {
        this.config.numberOfActivities
      } else {
        limit
      }

      this.sessionManager.authenticatedRequest { user =>
        Ok(
          Json.toJson(this.actionManager.getRecentActivity(List(user.osmProfile.id), actualLimit))
        )
      }
    }

  def getChallengeSummary(
      id: Long,
      priority: String,
      includeByPriority: Boolean = false
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { _ =>
      SearchParameters.withSearch { implicit params =>
        val response = this.dataManager.getChallengeSummary(
          challengeId = Some(id),
          priority = Utils.toIntList(priority),
          params = Some(params)
        )

        if (includeByPriority && response.nonEmpty) {
          val priorityMap = this._fetchPrioritySummaries(Some(id), Some(params))
          val updated = Utils.insertIntoJson(
            Json.toJson(response).as[JsArray].head.as[JsValue],
            "priorityActions",
            Json.toJson(priorityMap),
            false
          )
          Ok(Json.toJson(List(updated)))
        } else {
          Ok(Json.toJson(response))
        }
      }
    }
  }

  private def _fetchPrioritySummaries(
      challengeId: Option[Long],
      params: Option[SearchParameters],
      onlyEnabled: Boolean = false
  ): mutable.Map[String, JsValue] = {
    val prioritiesToFetch =
      List(Challenge.PRIORITY_HIGH, Challenge.PRIORITY_MEDIUM, Challenge.PRIORITY_LOW)

    val priorityMap = mutable.Map[String, JsValue]()

    prioritiesToFetch.foreach(p => {
      val pResult = this.dataManager.getChallengeSummary(
        challengeId = challengeId,
        priority = Some(List(p)),
        params = params,
        onlyEnabled = onlyEnabled
      )
      if (pResult.nonEmpty) {
        priorityMap.put(p.toString, Json.toJson(pResult.head.actions))
      } else {
        priorityMap
          .put(p.toString, Json.toJson(ActionSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
      }
    })

    priorityMap
  }

  def getProjectSummary(
      projects: String,
      onlyEnabled: Boolean = true,
      includeByPriority: Boolean = false
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { _ =>
      val response =
        this.dataManager.getChallengeSummary(Utils.toLongList(projects), onlyEnabled = onlyEnabled)

      if (includeByPriority) {
        val allUpdated =
          response.map(challenge => {
            val priorityMap = this._fetchPrioritySummaries(Some(challenge.id), None, onlyEnabled)
            Utils.insertIntoJson(Json.toJson(challenge), "priorityActions", priorityMap, false)
          })

        Ok(Json.toJson(allUpdated))
      } else {
        Ok(Json.toJson(response))
      }
    }
  }

  def getChallengeActivity(
      challengeId: Long,
      start: String,
      end: String,
      priority: Int
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      Ok(
        Json.toJson(
          this.dataManager.getChallengeActivity(
            None,
            Some(challengeId),
            Utils.getDate(start),
            Utils.getDate(end),
            this.getPriority(priority)
          )
        )
      )
    }
  }

  private def getPriority(priority: Int): Option[Int] = {
    priority match {
      case x if x >= Challenge.PRIORITY_HIGH & x <= Challenge.PRIORITY_LOW => Some(x)
      case _                                                               => None
    }
  }

  def getProjectActivity(projects: String, start: String, end: String): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        Ok(
          Json.toJson(
            this.dataManager.getChallengeActivity(
              Utils.toLongList(projects),
              None,
              Utils.getDate(start),
              Utils.getDate(end)
            )
          )
        )
      }
    }

  /**
    * Gets every task status change matching the filters. Each row includes
    * the name of the user who made the change, so this is restricted to
    * superusers. userIds are OSM user ids.
    */
  def getRawActivity(
      userIds: String,
      projectIds: String,
      challengeIds: String,
      start: String,
      end: String
  ): Action[AnyContent] = Action.async { implicit request =>
    implicit val requireSuperUser = true
    this.sessionManager.authenticatedRequest { implicit user =>
      Ok(
        Json.toJson(
          dataManager.getRawActivity(
            Utils.toLongList(userIds),
            Utils.toLongList(projectIds),
            Utils.toLongList(challengeIds),
            Utils.getDate(start),
            Utils.getDate(end)
          )
        )
      )
    }
  }

  /**
    * Gets a page of task status changes matching the filters. Each row includes
    * the name of the user who made the change, so this is restricted to
    * superusers. userIds are OSM user ids.
    */
  def getStatusActivity(
      userIds: String,
      projectIds: String,
      challengeIds: String,
      start: String,
      end: String,
      newStatus: String,
      oldStatus: String,
      limit: Int,
      offset: Int
  ): Action[AnyContent] = Action.async { implicit request =>
    implicit val requireSuperUser = true
    this.sessionManager.authenticatedRequest { implicit user =>
      val statusActionLimits = StatusActionLimits(
        Utils.getDate(start),
        Utils.getDate(end),
        Utils.toLongList(userIds).getOrElse(List.empty),
        Utils.toLongList(projectIds).getOrElse(List.empty),
        Utils.toLongList(challengeIds).getOrElse(List.empty),
        List.empty,
        Utils.toIntList(newStatus).getOrElse(List.empty),
        Utils.toIntList(oldStatus).getOrElse(List.empty)
      )
      Ok(
        Json.toJson(
          statusActionManager.getStatusUpdates(user, statusActionLimits, limit, offset)
        )
      )
    }
  }

  /**
    * Gets daily counts of task status changes for each user. Since this reveals
    * individual users' activity, it is restricted to superusers. userIds are
    * OSM user ids.
    */
  def getStatusSummary(
      userIds: String,
      projectIds: String,
      challengeIds: String,
      start: String,
      end: String,
      limit: Int,
      offset: Int
  ): Action[AnyContent] = Action.async { implicit request =>
    implicit val requireSuperUser = true
    this.sessionManager.authenticatedRequest { implicit user =>
      val statusActionLimits = StatusActionLimits(
        Utils.getDate(start),
        Utils.getDate(end),
        Utils.toLongList(userIds).getOrElse(List.empty),
        Utils.toLongList(projectIds).getOrElse(List.empty),
        Utils.toLongList(challengeIds).getOrElse(List.empty),
        List.empty,
        List.empty,
        List.empty
      )
      Ok(
        Json.toJson(
          statusActionManager.getStatusSummary(user, statusActionLimits, limit, offset)
        )
      )
    }
  }

  def getPropertyKeys(challengeId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { _ =>
      Ok(Json.toJson(Map("keys" -> dataManager.getPropertyKeys(challengeId))))
    }
  }
}

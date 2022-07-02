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
import org.maproulette.permissions.Permission
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
    statusActionManager: StatusActionManager,
    permission: Permission
) extends AbstractController(components) {

  implicit val actionWrites              = actionManager.actionItemWrites
  implicit val dateWrites                = Writes.dateWrites("yyyy-MM-dd")
  implicit val dateTimeWrites            = JodaWrites.jodaDateWrites("yyyy-MM-dd'T'HH:mm:ss")
  implicit val actionSummaryWrites       = Json.writes[ActionSummary]
  implicit val userSummaryWrites         = Json.writes[UserSummary]
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
    * Gets the recent activity for one or more users
    *
    * @param osmUserIds OSM user ids for which activity is desired
    * @param limit  the limit on the number of activities return
    * @param offset paging, starting at 0
    * @return List of action summaries associated with the user
    */
  def getRecentUserActivity(osmUserIds: String, limit: Int, offset: Int): Action[AnyContent] =
    Action.async { implicit request =>
      val actualLimit = if (limit == -1) {
        this.config.numberOfActivities
      } else {
        limit
      }

      this.sessionManager.authenticatedRequest { user =>
        // If no users were explicitly specified, use the current user. If -1 is
        // given, do not limit by user
        val osmIds = Utils.toLongList(osmUserIds) match {
          case Some(ids) if ids.contains(-1) => List.empty
          case Some(ids)                     => ids
          case None                          => List(user.osmProfile.id)
        }

        Ok(Json.toJson(this.actionManager.getRecentActivity(osmIds, actualLimit, offset)))
      }
    }

  def getUserChallengeSummary(
      challengeId: Long,
      start: String,
      end: String,
      priority: Int
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      Ok(
        Json.toJson(
          this.dataManager.getUserChallengeSummary(
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

  def getUserSummary(
      projects: String,
      start: String,
      end: String,
      priority: Int
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      Ok(
        Json.toJson(
          this.dataManager.getUserChallengeSummary(
            Utils.toLongList(projects),
            None,
            Utils.getDate(start),
            Utils.getDate(end),
            this.getPriority(priority)
          )
        )
      )
    }
  }

  def getChallengeSummary(
      id: Long,
      priority: String,
      includeByPriority: Boolean = false
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
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
    this.sessionManager.userAwareRequest { implicit user =>
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
    * Special API for handling data table API requests for challenge summary table
    *
    * @param projectIds A comma separated list of projects to filter by
    * @return
    *
    * @deprecated("This method does not support virtual projects.", "05-23-2019")
    */
  def getChallengeSummaries(
      projectIds: String,
      priority: String,
      onlyEnabled: Boolean = true
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      val postData        = request.body.asInstanceOf[AnyContentAsFormUrlEncoded].data
      val draw            = postData.get("draw").head.head.toInt
      val start           = postData.get("start").head.head.toInt
      val length          = postData.get("length").head.head.toInt
      val search          = postData.get("search[value]").head.head
      val orderDirection  = postData.get("order[0][dir]").head.head.toUpperCase
      val orderColumnID   = postData.get("order[0][column]").head.head.toInt
      val orderColumnName = postData.get(s"columns[$orderColumnID][name]").head.headOption
      val projectList     = Utils.toLongList(projectIds)
      val challengeSummaries =
        this.dataManager.getChallengeSummary(
          projectList,
          None,
          length,
          start,
          orderColumnName,
          orderDirection,
          search,
          Utils.toIntList(priority),
          onlyEnabled
        )

      val summaryMap = challengeSummaries.map(summary =>
        Map(
          "id"                  -> summary.id.toString,
          "name"                -> summary.name,
          "complete_percentage" -> summary.actions.percentComplete.toString,
          "available"           -> summary.actions.trueAvailable.toString,
          "available_perc"      -> summary.actions.percentage(summary.actions.available).toString,
          "fixed"               -> summary.actions.fixed.toString,
          "fixed_perc"          -> summary.actions.percentage(summary.actions.fixed).toString,
          "false_positive"      -> summary.actions.falsePositive.toString,
          "false_positive_perc" -> summary.actions
            .percentage(summary.actions.falsePositive)
            .toString,
          "skipped"            -> summary.actions.skipped.toString,
          "skipped_perc"       -> summary.actions.percentage(summary.actions.skipped).toString,
          "already_fixed"      -> summary.actions.alreadyFixed.toString,
          "already_fixed_perc" -> summary.actions.percentage(summary.actions.alreadyFixed).toString,
          "too_hard"           -> summary.actions.tooHard.toString,
          "too_hard_perc"      -> summary.actions.percentage(summary.actions.tooHard).toString
        )
      )
      Ok(
        Json.obj(
          "draw"         -> JsNumber(draw),
          "recordsTotal" -> JsNumber(dataManager.getTotalSummaryCount(projectList, None)),
          "recordsFiltered" -> JsNumber(
            dataManager.getTotalSummaryCount(projectList, None, search)
          ),
          "data" -> Json.toJson(summaryMap)
        )
      )
    }
  }

  def getRawActivity(
      userIds: String,
      projectIds: String,
      challengeIds: String,
      start: String,
      end: String
  ): Action[AnyContent] = Action.async { implicit request =>
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
    this.sessionManager.authenticatedRequest { implicit user =>
      val users = if (permission.isSuperUser(user)) {
        Utils.toLongList(userIds).getOrElse(List.empty)
      } else {
        List(user.id)
      }
      val statusActionLimits = StatusActionLimits(
        Utils.getDate(start),
        Utils.getDate(end),
        users,
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
    * Gets the most recent activity entries for each challenge, regardless of date.
    *
    * @param projectIds   restrict to specified projects
    * @param challengeIds restrict to specified challenges
    * @param entries      the number of most recent activity entries per challenge. Defaults to 1.
    * @return most recent activity entries for each challenge
    */
  def getLatestChallengeActivity(
      projectIds: String,
      challengeIds: String,
      entries: Int
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      Ok(
        Json.toJson(
          this.dataManager.getLatestChallengeActivity(
            Utils.toLongList(projectIds),
            Utils.toLongList(challengeIds),
            entries
          )
        )
      )
    }
  }

  def getStatusSummary(
      userIds: String,
      projectIds: String,
      challengeIds: String,
      start: String,
      end: String,
      limit: Int,
      offset: Int
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val users = if (permission.isSuperUser(user)) {
        Utils.toLongList(userIds).getOrElse(List.empty)
      } else {
        List(user.id)
      }
      val statusActionLimits = StatusActionLimits(
        Utils.getDate(start),
        Utils.getDate(end),
        users,
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
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(Json.toJson(Map("keys" -> dataManager.getPropertyKeys(challengeId))))
    }
  }
}

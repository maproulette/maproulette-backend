/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.controller

import javax.inject.Inject
import akka.util.ByteString
import org.maproulette.data.ActionManager
import org.maproulette.framework.service.{
  ChallengeService,
  ProjectService,
  TaskReviewMetricsService,
  UserService
}
import org.maproulette.framework.psql.Paging
import org.maproulette.framework.model.{Challenge, User, ReviewMetrics, Task}
import org.maproulette.session.{SessionManager, SearchParameters, SearchTaskParameters}
import org.maproulette.utils.Utils
import play.api.mvc._
import play.api.libs.json._
import play.api.http.HttpEntity

/**
  * TaskReviewController is responsible for handling functionality related to
  * task reviews.
  *
  * @author krotstan
  */
class TaskReviewMetricsController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val bodyParsers: PlayBodyParsers,
    service: TaskReviewMetricsService,
    challengeService: ChallengeService,
    projectService: ProjectService,
    userService: UserService,
    components: ControllerComponents
) extends AbstractController(components)
    with MapRouletteController {

  /**
    * Gets reviewed tasks where the user has reviewed or requested review
    *
    * @param reviewTasksType - 1: To Be Reviewed 2: User's reviewed Tasks 3: All reviewed by users
    * @param onlySaved Only include saved challenges
    * @param excludeOtherReviewers exclude tasks that have been reviewed by someone else
    * @return
    */
  def getReviewMetrics(
      reviewTasksType: Int,
      onlySaved: Boolean = false,
      excludeOtherReviewers: Boolean = false,
      includeByPriority: Boolean = false,
      includeByTaskStatus: Boolean = false
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        val result = this.service.getReviewMetrics(
          User.userOrMocked(user),
          reviewTasksType,
          params,
          onlySaved,
          excludeOtherReviewers
        )

        if (includeByPriority || includeByTaskStatus) {
          var resultJson: JsValue = Json.obj(
            "reviewActions" -> Json.toJson(result)
          )

          if (includeByPriority) {
            val priorityMap = this.fetchPriorityReviewMetrics(
              User.userOrMocked(user),
              reviewTasksType,
              params,
              onlySaved,
              excludeOtherReviewers
            )

            resultJson =
              Utils.insertIntoJson(resultJson, "priorityReviewActions", Json.toJson(priorityMap))
          }

          if (includeByTaskStatus) {
            val statusMap = this.fetchByTaskStatusReviewMetrics(
              User.userOrMocked(user),
              reviewTasksType,
              params,
              onlySaved,
              excludeOtherReviewers
            )

            resultJson =
              Utils.insertIntoJson(resultJson, "statusReviewActions", Json.toJson(statusMap))
          }

          Ok(resultJson)
        } else {
          Ok(Json.toJson(List(result)))
        }
      }
    }
  }

  private def fetchPriorityReviewMetrics(
      user: User,
      reviewTasksType: Int,
      params: SearchParameters,
      onlySaved: Boolean,
      excludeOtherReviewers: Boolean
  ): scala.collection.mutable.Map[String, JsValue] = {
    val prioritiesToFetch =
      List(Challenge.PRIORITY_HIGH, Challenge.PRIORITY_MEDIUM, Challenge.PRIORITY_LOW)

    val priorityMap = scala.collection.mutable.Map[String, JsValue]()

    prioritiesToFetch.foreach(p => {
      val newParams =
        params.copy(taskParams = params.taskParams.copy(taskPriorities = Some(List(p))))

      val pResult = this.service.getReviewMetrics(
        user,
        reviewTasksType,
        newParams,
        onlySaved,
        excludeOtherReviewers
      )

      priorityMap.put(p.toString, Json.toJson(pResult))
    })

    priorityMap
  }

  private def fetchByTaskStatusReviewMetrics(
      user: User,
      reviewTasksType: Int,
      params: SearchParameters,
      onlySaved: Boolean,
      excludeOtherReviewers: Boolean
  ): scala.collection.mutable.Map[String, JsValue] = {
    val statusesToFetch =
      List(
        Task.STATUS_FIXED,
        Task.STATUS_FALSE_POSITIVE,
        Task.STATUS_ALREADY_FIXED,
        Task.STATUS_TOO_HARD
      )

    val statusMap = scala.collection.mutable.Map[String, JsValue]()

    statusesToFetch.foreach(m => {
      val newParams = params.copy(
        taskParams = params.taskParams.copy(taskStatus = Some(List(m)))
      )

      val mResult = this.service.getReviewMetrics(
        user,
        reviewTasksType,
        newParams,
        onlySaved,
        excludeOtherReviewers
      )

      statusMap.put(m.toString, Json.toJson(mResult))
    })

    statusMap
  }

  /**
    * Returns a CSV export of review metrics per mapper.
    *
    * SearchParameters:
    *   mappers Optional limit to reviews of tasks by specific mappers
    *   reviewers Optional limit reviews done by specific reviewers
    *   priorities Optional limit to only these priorities
    *   startDate Optional start date to filter by reviewedAt date
    *   endDate Optional end date to filter by reviewedAt date
    * @param onlySaved Only include saved challenges
    * @return
    */
  def extractMapperMetrics(
      onlySaved: Boolean = false
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        val allReviewStatuses = List(
          Task.REVIEW_STATUS_REQUESTED,
          Task.REVIEW_STATUS_APPROVED,
          Task.REVIEW_STATUS_APPROVED_WITH_REVISIONS,
          Task.REVIEW_STATUS_APPROVED_WITH_FIXES_AFTER_REVISIONS,
          Task.REVIEW_STATUS_REJECTED,
          Task.REVIEW_STATUS_ASSISTED,
          Task.REVIEW_STATUS_DISPUTED
        )

        val metrics = this.service.getMapperMetrics(
          User.userOrMocked(user),
          params.copy(
            taskParams = SearchTaskParameters(
              taskReviewStatus = Some(allReviewStatuses)
            )
          ),
          onlySaved
        )

        val (projectName, challengeName, mapperNames) = retrieveObjectNames(params, metrics)

        val byReviewStatusMetrics =
          allReviewStatuses
            .map(reviewStatus => {
              val reviewStatusMetrics = this.service.getMapperMetrics(
                User.userOrMocked(user),
                params.copy(
                  taskParams = SearchTaskParameters(
                    taskReviewStatus = Some(List(reviewStatus))
                  )
                ),
                onlySaved
              )

              reviewStatus -> (reviewStatusMetrics.map(m => m.userId.get -> m).toMap)
            })
            .toMap

        val seqString = metrics.map(row => {
          var mapper = mapperNames.get(row.userId.get)

          val result = new StringBuilder(
            s"${mapper.get},${projectName},${if (challengeName == "") "" else s"${challengeName},"}" +
              s",${row.total},,,${row.reviewRequested}," +
              s"${row.reviewApproved},${row.reviewRejected},${row.reviewAssisted}," +
              s"${row.reviewDisputed},${row.fixed},${row.falsePositive},${row.alreadyFixed}," +
              s"${row.tooHard}"
          )

          allReviewStatuses.foreach(rs => {
            if (byReviewStatusMetrics.get(rs).get.contains(row.userId.get)) {
              val rsRow         = byReviewStatusMetrics.get(rs).get(row.userId.get)
              val rsTimeSeconds = Math.round(rsRow.avgReviewTime / 1000)
              val rsPercent     = Math.round(rsRow.total * 100 / row.total)
              result ++=
                s"\n${mapper.get},${projectName},${if (challengeName == "") ""
                else s"${challengeName},"}" +
                  s"${Task.reviewStatusMap.get(rs).get},${rsRow.total}," +
                  s"${rsPercent},${rsTimeSeconds},,,,," +
                  s",${rsRow.fixed},${rsRow.falsePositive},${rsRow.alreadyFixed}," +
                  s"${rsRow.tooHard}"
            }
          })
          result.toString
        })

        Result(
          header = ResponseHeader(
            OK,
            Map(CONTENT_DISPOSITION -> s"attachment; filename=mapper_review_metrics.csv")
          ),
          body = HttpEntity.Strict(
            ByteString(
              s"Mapper,Project,${if (challengeName == "") "" else "Challenge,"}" +
                s"Review Status,Total Review Tasks,Coverage %,Avg Review Time (seconds)," +
                s"Review Requested,Approved,Needs Revision,Approved w/Fixes,Contested," +
                s"${Task.STATUS_FIXED_NAME},${Task.STATUS_FALSE_POSITIVE_NAME}," +
                s"${Task.STATUS_ALREADY_FIXED_NAME},${Task.STATUS_TOO_HARD_NAME}\n"
            ).concat(ByteString(seqString.mkString("\n"))),
            Some("text/csv; header=present")
          )
        )
      }
    }
  }

  /**
    * Returns a CSV export of meta-review metrics per reviewer.
    *
    * SearchParameters:
    *   reviewers Optional limit reviews done by specific reviewers
    *   priorities Optional limit to only these priorities
    *   startDate Optional start date to filter by reviewedAt date
    *   endDate Optional end date to filter by reviewedAt date
    * @param onlySaved Only include saved challenges
    * @return
    */
  def extractMetaReviewCoverage(
      onlySaved: Boolean = false
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        val allReviewStatuses = List(
          Task.REVIEW_STATUS_REQUESTED,
          Task.REVIEW_STATUS_APPROVED,
          Task.REVIEW_STATUS_APPROVED_WITH_REVISIONS,
          Task.REVIEW_STATUS_APPROVED_WITH_FIXES_AFTER_REVISIONS,
          Task.REVIEW_STATUS_REJECTED,
          Task.REVIEW_STATUS_ASSISTED,
          Task.REVIEW_STATUS_DISPUTED
        )

        val metaReviewStatuses = List(
          Task.REVIEW_STATUS_REQUESTED,
          Task.REVIEW_STATUS_APPROVED,
          Task.REVIEW_STATUS_APPROVED_WITH_REVISIONS,
          Task.REVIEW_STATUS_APPROVED_WITH_FIXES_AFTER_REVISIONS,
          Task.REVIEW_STATUS_REJECTED,
          Task.REVIEW_STATUS_ASSISTED
        )

        val metrics = this.service.getMetaReviewMetrics(
          User.userOrMocked(user),
          params.copy(
            taskParams = SearchTaskParameters(
              taskReviewStatus = Some(allReviewStatuses)
            )
          ),
          onlySaved
        )

        val (projectName, challengeName, reviewerNames) = retrieveObjectNames(params, metrics)

        // One row will not have a userId because it's tasks that are awaiting reviews
        // and have not reviewer assigned.
        val seqString = metrics
          .filter(_.userId != None)
          .map(row => {
            var reviewer =
              reviewerNames.get(row.userId.getOrElse(-1)) match {
                case None    => "Not Yet Reviewed"
                case Some(r) => r
              }

            val metaTotal = row.metaReviewRequested + row.metaReviewApproved +
              row.metaReviewRejected + row.metaReviewAssisted

            val metaPercent =
              if (row.total != 0) Math.round(metaTotal * 100 / row.total)
              else 0

            val result = new StringBuilder(
              s"${reviewer},${projectName},${if (challengeName == "") "" else s"${challengeName},"}" +
                s"${row.total},${metaTotal},${metaPercent},${row.metaReviewRequested}," +
                s"${row.metaReviewApproved},${row.metaReviewRejected},${row.metaReviewAssisted}," +
                s"${row.reviewRequested},${row.reviewApproved},${row.reviewRejected},${row.reviewAssisted},${row.reviewDisputed}," +
                s"${row.fixed},${row.falsePositive},${row.alreadyFixed},${row.tooHard}"
            )
            result.toString
          })

        Result(
          header = ResponseHeader(
            OK,
            Map(CONTENT_DISPOSITION -> s"attachment; filename=metareview_coverage.csv")
          ),
          body = HttpEntity.Strict(
            ByteString(
              s"Reviewer,Project,${if (challengeName == "") "" else "Challenge,"}" +
                s"Total Tasks, Total Meta-Reviewed Tasks, % Meta-Reviewed," +
                s"Meta Re-review Needed,Meta Approved,Meta Rejected,Meta-Approved w/Fixes," +
                s"Review Requested, Approved,Needs Revision,Approved w/Fixes,Contested," +
                s"${Task.STATUS_FIXED_NAME},${Task.STATUS_FALSE_POSITIVE_NAME}," +
                s"${Task.STATUS_ALREADY_FIXED_NAME},${Task.STATUS_TOO_HARD_NAME}\n"
            ).concat(ByteString(seqString.mkString("\n"))),
            Some("text/csv; header=present")
          )
        )
      }
    }
  }

  /**
    * Returns a breakdown of tag metrics
    *
    * @return
    */
  def getReviewTagMetrics(
      reviewTasksType: Int,
      onlySaved: Boolean,
      excludeOtherReviewers: Boolean
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        val result = this.service.getReviewTagMetrics(
          User.userOrMocked(user),
          reviewTasksType,
          params,
          onlySaved,
          excludeOtherReviewers
        )
        Ok(Json.toJson(result))
      }
    }
  }

  /**
    * Retrieves projectName, challengeName and userNames from params and metrics
    */
  private def retrieveObjectNames(
      params: SearchParameters,
      metrics: List[ReviewMetrics]
  ): (String, String, Map[Long, String]) = {
    val projectName =
      params.getProjectIds match {
        case Some(pId) =>
          // Searching by project, just fetch name
          pId
            .map(
              this.projectService.retrieve(_) match {
                case Some(p) => p.displayName.get
                case None    => ""
              }
            )
            .mkString("|")
        case None =>
          params.getChallengeIds match {
            case Some(cId) =>
              // We have a list of challenges. If these challenges all belong
              // to the same project we can use the parent project name.
              val challengeList = this.challengeService.list(cId)
              val parentProject: Option[Long] =
                if (challengeList.forall(_.general.parent == challengeList.head.general.parent))
                  Some(challengeList.head.general.parent)
                else None
              parentProject match {
                case Some(pp) =>
                  this.projectService.retrieve(pp) match {
                    case Some(p) => p.displayName.get
                    case None    => ""
                  }
                case None => ""
              }
            case None => ""
          }
      }

    val challengeName =
      params.getChallengeIds match {
        case Some(cIds) =>
          this.challengeService.list(cIds).map(_.name).mkString("|")
        case None => ""
      }

    val userNames =
      this.userService
        .retrieveListById(metrics.map(m => m.userId.getOrElse(-1)), Paging())
        .map(u => u.id -> u.name)
        .toMap

    (projectName, challengeName, userNames)
  }
}

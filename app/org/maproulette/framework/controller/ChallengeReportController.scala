/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.controller

import javax.inject.Inject
import org.maproulette.data.ActionManager
import org.maproulette.exception.InvalidException
import org.maproulette.framework.model.ChallengeReport
import org.maproulette.framework.service.ChallengeReportService
import org.maproulette.session.SessionManager
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._

/**
  * Endpoints for reports filed against a challenge's design. Any authenticated
  * user can file one; only superusers can list or triage them.
  */
class ChallengeReportController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val bodyParsers: PlayBodyParsers,
    challengeReportService: ChallengeReportService,
    components: ControllerComponents
) extends AbstractController(components)
    with MapRouletteController {

  /**
    * Files a report against a challenge. The reporter is taken from the session
    * rather than the request body, so a report cannot be attributed to someone
    * else.
    *
    * @param challengeId The challenge being reported
    * @return 201 Created with the new report
    */
  def create(challengeId: Long): Action[JsValue] = Action.async(bodyParsers.json) {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        val comment = (request.body \ "comment")
          .asOpt[String]
          .getOrElse(
            throw new InvalidException("Required 'comment' field in request body not found.")
          )
        val email = (request.body \ "email").asOpt[String]

        Created(
          Json.toJson(this.challengeReportService.create(user, challengeId, comment, email))
        )
      }
  }

  /**
    * Reports whether the requesting user already has an open report against a
    * challenge, so the report button can explain itself rather than letting the
    * user write a duplicate and be rejected on submit. Returns only the
    * caller's own report, so it needs no elevated permission.
    *
    * @param challengeId The challenge in question
    * @return The user's open report, or 204 if they have none
    */
  def retrieveOwnOpenReport(challengeId: Long): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        this.challengeReportService.retrieveOwnOpenReport(user, challengeId) match {
          case Some(report) => Ok(Json.toJson(report))
          case None         => NoContent
        }
      }
  }

  /**
    * Lists reports, newest first. Superusers only.
    *
    * @param status Restrict to one triage status, by name ("open", "actioned", "dismissed")
    * @param challengeId Restrict to a single challenge
    * @param activeOnly Only reports on challenges that are neither deleted nor archived
    * @param limit Page size
    * @param page Zero-based page number
    * @return A list of reports
    */
  def list(
      status: Option[String],
      challengeId: Option[Long],
      activeOnly: Boolean,
      limit: Int,
      page: Int
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val statusValue = status.map(_.trim).filter(_.nonEmpty).map { name =>
        ChallengeReport
          .statusFromName(name)
          .getOrElse(
            throw new InvalidException(
              s"'$name' is not a valid report status. Expected one of ${ChallengeReport.statusNames.values
                .mkString(", ")}."
            )
          )
      }

      Ok(
        Json.toJson(
          this.challengeReportService
            .list(user, statusValue, challengeId, activeOnly, limit, page)
        )
      )
    }
  }

  /**
    * Retrieves a single report. Superusers only.
    *
    * @param id The id of the report
    * @return The report, or 404
    */
  def retrieve(id: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.challengeReportService.retrieve(user, id) match {
        case Some(report) => Ok(Json.toJson(report))
        case None         => NotFound
      }
    }
  }

  /**
    * Records a triage decision on a report, so an admin can mark it actioned
    * (after archiving the challenge, say) or dismissed. Superusers only.
    *
    * @param id The report being resolved
    * @return The updated report
    */
  def updateStatus(id: Long): Action[JsValue] = Action.async(bodyParsers.json) { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val statusName = (request.body \ "status")
        .asOpt[String]
        .getOrElse(
          throw new InvalidException("Required 'status' field in request body not found.")
        )
      val status = ChallengeReport
        .statusFromName(statusName)
        .getOrElse(
          throw new InvalidException(
            s"'$statusName' is not a valid report status. Expected one of ${ChallengeReport.statusNames.values
              .mkString(", ")}."
          )
        )
      val reviewComment = (request.body \ "reviewComment").asOpt[String]

      Ok(Json.toJson(this.challengeReportService.updateStatus(user, id, status, reviewComment)))
    }
  }
}

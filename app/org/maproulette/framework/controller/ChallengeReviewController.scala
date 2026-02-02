/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.controller

import javax.inject.Inject
import org.maproulette.framework.model.{ChallengeReview, ChallengeReviewSummary}
import org.maproulette.framework.service.ServiceManager
import org.maproulette.session.SessionManager
import play.api.libs.json._
import play.api.mvc._

class ChallengeReviewController @Inject() (
    override val controllerComponents: ControllerComponents,
    sessionManager: SessionManager,
    serviceManager: ServiceManager
) extends AbstractController(controllerComponents) {

  implicit val reviewWrites: Writes[ChallengeReview]         = ChallengeReview.challengeReviewWrites
  implicit val reviewReads: Reads[ChallengeReview]           = ChallengeReview.challengeReviewReads
  implicit val summaryWrites: Writes[ChallengeReviewSummary] = ChallengeReview.summaryWrites

  def submitReview(challengeId: Long): Action[JsValue] = Action.async(parse.json) {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        request.body
          .validate[ChallengeReview]
          .fold(
            errors => BadRequest(JsError.toJson(errors)),
            review => {
              this.serviceManager.challengeReview.submitReview(user, challengeId, review) match {
                case Some(r) => Created(Json.toJson(r))
                case None    => InternalServerError(Json.toJson(Map("status" -> "error")))
              }
            }
          )
      }
  }

  def removeReview(challengeId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.serviceManager.challengeReview.removeReview(user, challengeId)
      Ok(Json.toJson(Map("deleted" -> "true")))
    }
  }

  def getUserReview(challengeId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.serviceManager.challengeReview.getUserReview(user, challengeId) match {
        case Some(r) => Ok(Json.toJson(r))
        case None    => NotFound
      }
    }
  }

  def getReviewsForChallenge(challengeId: Long, limit: Int, offset: Int): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        Ok(
          Json.toJson(
            this.serviceManager.challengeReview.getReviewsForChallenge(challengeId, limit, offset)
          )
        )
      }
    }

  def getReviewSummary(challengeId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      Ok(Json.toJson(this.serviceManager.challengeReview.getReviewSummary(challengeId)))
    }
  }

  def getRecommended(
      lon: Option[Double],
      lat: Option[Double],
      difficulty: Option[String],
      limit: Int,
      offset: Int
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val results = this.serviceManager.challengeReview.getRecommended(
        user,
        lon,
        lat,
        difficulty,
        limit,
        offset
      )
      Ok(
        Json.toJson(
          results.map {
            case (id, score) =>
              Json.obj("challengeId" -> id, "relevanceScore" -> score)
          }
        )
      )
    }
  }
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.controller

import javax.inject.Inject
import org.maproulette.data.{Created => _, _}
import org.maproulette.framework.service.{LeaderboardService}
import org.maproulette.session.{SessionManager, SearchParameters}
import play.api.libs.json._
import play.api.mvc._

/**
  * @author krotstan
  */
class LeaderboardController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val bodyParsers: PlayBodyParsers,
    service: LeaderboardService,
    components: ControllerComponents
) extends AbstractController(components)
    with MapRouletteController {

  /**
    * Gets the top scoring users, based on task completion, over the given
    * number of montns (or using start and end dates). Included with each user is their top challenges
    * (by amount of activity).
    *
    * @param limit         the limit on the number of users returned
    * @param offset        paging, starting at 0
    * @return Top-ranked users with scores based on task completion activity
    */
  def getMapperLeaderboard(
      limit: Int,
      offset: Int
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        Ok(
          Json.toJson(
            this.service.getMapperLeaderboard(
              params.leaderboardParams,
              limit,
              offset
            )
          )
        )
      }
    }
  }

  /**
    * Gets the leaderboard ranking for a user, based on task completion, over
    * the given number of months (or start and end dates). Included with the user is their top challenges
    * (by amount of activity). Also a bracketing number of users above and below
    * the user in the rankings.
    *
    * @param userId        user Id for user
    * @param bracket       the number of users to return above and below the given user (0 returns just the user)
    * @return User with score and ranking based on task completion activity
    */
  def getLeaderboardForUser(
      userId: Long,
      bracket: Int
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        Ok(
          Json.toJson(
            this.service.getLeaderboardForUser(
              userId,
              params.leaderboardParams,
              bracket
            )
          )
        )
      }
    }
  }

  /**
    * Gets the user's top challenges, based on activity, over the given number of months
    * (or start and end dates).
    *
    * @param userId        the id of the user
    * @param limit         the limit on the number of challenges returned
    * @param offset        paging, starting at 0
    * @return Top challenges based on user's activity
    */
  def getUserTopChallenges(
      userId: Long,
      limit: Int,
      offset: Int
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        Ok(
          Json.toJson(
            this.service.getUserTopChallenges(
              userId,
              params.leaderboardParams,
              limit,
              offset
            )
          )
        )
      }
    }
  }

  /**
    * Gets the top ranking reviewers over the given number of months (or
    * using start and end dates).
    *
    * @param limit         the limit on the number of reviewers returned
    * @param offset        paging, starting at 0
    * @return Top-ranked reviewers
    */
  def getReviewerLeaderboard(
      limit: Int,
      offset: Int
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        Ok(
          Json.toJson(
            this.service.getReviewerLeaderboard(
              params.leaderboardParams,
              limit,
              offset
            )
          )
        )
      }
    }
  }
}

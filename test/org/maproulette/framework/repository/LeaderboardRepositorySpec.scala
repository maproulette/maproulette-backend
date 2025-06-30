/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import org.maproulette.framework.model._
import org.maproulette.framework.psql._
import org.maproulette.framework.psql.filter._
import org.maproulette.framework.service.LeaderboardServiceSpec
import org.maproulette.framework.util.{LeaderboardRepoTag, FrameworkHelper}
import play.api.Application

/**
  * @author krotstan
  */
class LeaderboardRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  val repository: LeaderboardRepository =
    this.application.injector.instanceOf(classOf[LeaderboardRepository])
  var randomUser: User = null

  "LeaderboardRepository" should {
    "queries user leaderboard table" taggedAs LeaderboardRepoTag in {
      // Check for everything
      val results = this.repository.queryUserLeaderboard(
        Query.simple(List()),
        userId => List(new LeaderboardChallenge(userId, "name", 1, 1))
      )
      results.size mustEqual 4

      // Check for month duration = 6 and no country code
      val results2 = this.repository.queryUserLeaderboard(
        Query.simple(
          List(
            BaseParameter(
              "month_duration",
              6,
              Operator.EQ,
              useValueDirectly = true,
              table = Some("")
            ),
            FilterParameter.conditional(
              "country_code",
              None,
              Operator.NULL,
              useValueDirectly = true,
              table = Some("")
            )
          )
        ),
        userId => List(new LeaderboardChallenge(userId, "name", 1, 1))
      )
      results2.size mustEqual 2

      // Check for month duration = 3 and country code AR
      val results3 = this.repository.queryUserLeaderboard(
        Query.simple(
          List(
            BaseParameter(
              "month_duration",
              3,
              Operator.EQ,
              useValueDirectly = true,
              table = Some("")
            ),
            FilterParameter.conditional(
              "country_code",
              s"'AR'",
              Operator.EQ,
              useValueDirectly = true,
              table = Some("")
            )
          )
        ),
        userId => List(new LeaderboardChallenge(userId, "name", 1, 1))
      )
      results3.size mustEqual 2
    }
  }

  override implicit val projectTestName: String = "LeaderboardRepositorySpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val userRepository = this.application.injector.instanceOf(classOf[UserRepository])
    LeaderboardServiceSpec.setup(
      this.challengeDAL,
      this.taskDAL,
      this.serviceManager,
      this.defaultProject.id,
      this.getTestTask,
      this.getTestUser,
      userRepository
    )
  }
}

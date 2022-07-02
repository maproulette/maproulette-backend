/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import org.maproulette.framework.model._
import org.maproulette.framework.util.{LeaderboardTag, FrameworkHelper}
import org.maproulette.framework.repository.UserRepository
import org.maproulette.models.dal.{ChallengeDAL, TaskDAL}
import org.maproulette.session.SearchLeaderboardParameters
import play.api.Application
import org.joda.time.DateTime

import play.api.db.Database
import org.maproulette.jobs.utils.LeaderboardHelper
import anorm._
import org.maproulette.Config

/**
  * @author krotstan
  */
class LeaderboardServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val service: LeaderboardService = this.serviceManager.leaderboard
  var randomUser: User            = null
  var challenge: Challenge        = null

  "LeaderboardService" should {
    "get mapper leaderboard" taggedAs (LeaderboardTag) in {
      val params  = SearchLeaderboardParameters(onlyEnabled = false)
      val results = this.service.getMapperLeaderboard(params)
      results.size mustEqual 2

      // Verify top challenges were populated
      results.head.topChallenges.size mustEqual 1
      results.head.topChallenges.head.id mustEqual challenge.id

      // Verify top challenges were not populated when onlyEnabled
      val params2  = SearchLeaderboardParameters(onlyEnabled = true)
      val results2 = this.service.getMapperLeaderboard(params2)
      results2.size mustEqual 2
      results2.head.topChallenges.size mustEqual 0

      // With Challenge Filter
      val cParams  = SearchLeaderboardParameters(challengeFilter = Some(List(challenge.id)))
      val cResults = this.service.getMapperLeaderboard(cParams)
      cResults.size mustEqual 2
      val cParams2  = SearchLeaderboardParameters(challengeFilter = Some(List(987654)))
      val cResults2 = this.service.getMapperLeaderboard(cParams2)
      cResults2.size mustEqual 0

      // With Project Filter
      val pParams =
        SearchLeaderboardParameters(projectFilter = Some(List(challenge.general.parent)))
      val pResults =
        this.service.getMapperLeaderboard(pParams)
      pResults.size mustEqual 2
      val pParams2  = SearchLeaderboardParameters(projectFilter = Some(List(987654)))
      val pResults2 = this.service.getMapperLeaderboard(pParams2)
      pResults2.size mustEqual 2

      // With User Filter
      val uParams  = SearchLeaderboardParameters(userFilter = Some(List(randomUser.id)))
      val uResults = this.service.getMapperLeaderboard(uParams)
      uResults.size mustEqual 1

      // By start and end date
      val dParams = SearchLeaderboardParameters(
        onlyEnabled = false,
        start = Some(new DateTime().minusMonths(2)),
        end = Some(new DateTime)
      )
      val dateResults = this.service.getMapperLeaderboard(dParams)
      dateResults.size mustEqual 2

      // By Country code
      val ccParams =
        SearchLeaderboardParameters(countryCodeFilter = Some(List("AR")), monthDuration = Some(3))
      val ccResults = this.service
        .getMapperLeaderboard(ccParams)
      ccResults.size mustEqual 2
    }

    "get leaderboard for user" taggedAs (LeaderboardTag) in {
      val results = this.service.getLeaderboardForUser(randomUser.id, SearchLeaderboardParameters())
      results.size mustEqual 1
      results.head.userId mustEqual randomUser.id
    }

    "get leaderboard for user with bracketing" taggedAs (LeaderboardTag) in {
      val results = this.service
        .getLeaderboardForUser(randomUser.id, SearchLeaderboardParameters(), bracket = 1)
      results.size mustEqual 2
      results.head.userId mustEqual randomUser.id
    }

    "get reviewer leaderboard" taggedAs (LeaderboardTag) in {
      val cParams  = SearchLeaderboardParameters(challengeFilter = Some(List(challenge.id)))
      val cResults = this.service.getReviewerLeaderboard(cParams)
      cResults.size mustEqual 1

      val cParams2  = SearchLeaderboardParameters(challengeFilter = Some(List(987654)))
      val cResults2 = this.service.getReviewerLeaderboard(cParams2)
      cResults2.size mustEqual 0

      // With Project Filter
      val pParams =
        SearchLeaderboardParameters(projectFilter = Some(List(challenge.general.parent)))
      val pResults = this.service.getReviewerLeaderboard(pParams)
      pResults.size mustEqual 1
    }
  }

  override implicit val projectTestName: String = "LeaderboardServiceSpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val userRepository = this.application.injector.instanceOf(classOf[UserRepository])
    val (u, c) = LeaderboardServiceSpec.setup(
      this.challengeDAL,
      this.taskDAL,
      this.serviceManager,
      this.defaultProject.id,
      this.getTestTask,
      this.getTestUser,
      userRepository
    )
    randomUser = u
    challenge = c

    val db     = this.application.injector.instanceOf(classOf[Database])
    val config = this.application.injector.instanceOf(classOf[Config])

    db.withConnection { implicit c =>
      // Past 6 Months
      SQL(LeaderboardHelper.rebuildChallengesLeaderboardSQL(6, config)).executeUpdate()
      SQL(
        LeaderboardHelper
          .rebuildChallengesLeaderboardSQLCountry(3, "AR", "-73.42, -55.25, -53.63, -21.83", config)
      ).executeUpdate()
    }
  }
}

object LeaderboardServiceSpec {
  var challengeDAL: ChallengeDAL       = null
  var taskDAL: TaskDAL                 = null
  var serviceManager: ServiceManager   = null
  var taskFunc: (String, Long) => Task = null
  var userFunc: (Long, String) => User = null
  var userRepository: UserRepository   = null

  def setup(
      challengeDAL: ChallengeDAL,
      taskDAL: TaskDAL,
      serviceManager: ServiceManager,
      projectId: Long,
      taskFunc: (String, Long) => Task,
      userFunc: (Long, String) => User,
      userRepository: UserRepository
  ): (User, Challenge) = {
    this.challengeDAL = challengeDAL
    this.taskDAL = taskDAL
    this.serviceManager = serviceManager
    this.taskFunc = taskFunc
    this.userFunc = userFunc
    this.userRepository = userRepository

    val createdChallenge = challengeDAL
      .insert(
        Challenge(
          -1,
          "leaderboardChallenge",
          null,
          null,
          general = ChallengeGeneral(
            User.superUser.osmProfile.id,
            projectId,
            "TestChallengeInstruction"
          ),
          creation = ChallengeCreation(),
          priority = ChallengePriority(),
          extra = ChallengeExtra()
        ),
        User.superUser
      )

    val randomUser = completeTask(
      createdChallenge.id,
      Task.STATUS_FIXED,
      "randomUser"
    )
    completeTask(
      createdChallenge.id,
      Task.STATUS_ALREADY_FIXED,
      "randomUser2",
      false,
      true
    )

    // User that has opted out.
    completeTask(
      createdChallenge.id,
      Task.STATUS_FIXED,
      "hiddenUser",
      true
    )

    (randomUser, createdChallenge)
  }

  private val counter = new AtomicInteger(9000)

  private def completeTask(
      challengeId: Long,
      taskStatus: Int,
      username: String,
      optOut: Boolean = false,
      addReview: Boolean = false
  ): User = {
    val task = this.taskDAL
      .insert(
        this.taskFunc(UUID.randomUUID().toString, challengeId),
        User.superUser
      )

    var randomUser = serviceManager.user.create(
      this.userFunc(counter.getAndIncrement(), username),
      User.superUser
    )

    if (optOut) {
      randomUser =
        randomUser.copy(settings = randomUser.settings.copy(leaderboardOptOut = Some(true)))
      this.userRepository.update(randomUser, "POINT (14.0 22.0)")
    }

    this.taskDAL.setTaskStatus(List(task), taskStatus, randomUser, Some(true))

    if (addReview) {
      val refreshedTask = taskDAL.retrieveById(task.id).get
      serviceManager.taskReview.setTaskReviewStatus(
        refreshedTask,
        Task.REVIEW_STATUS_APPROVED,
        User.superUser,
        None
      )
    }

    randomUser
  }
}

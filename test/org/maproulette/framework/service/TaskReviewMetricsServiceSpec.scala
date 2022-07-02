/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import java.util.UUID

import org.maproulette.session.{SearchParameters, SearchChallengeParameters, SearchReviewParameters}
import org.maproulette.framework.model._
import org.maproulette.framework.util.{TaskReviewTag, FrameworkHelper}
import org.maproulette.models.dal.{ChallengeDAL, TaskDAL}
import play.api.Application

/**
  * @author krotstan
  */
class TaskReviewMetricsServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val service: TaskReviewMetricsService = this.serviceManager.taskReviewMetrics
  var randomChallenge: Challenge        = null
  var randomUser: User                  = null

  "TaskReviewMetricsService" should {
    "get Review Metrics" taggedAs (TaskReviewTag) in {
      val result = this.service.getReviewMetrics(User.superUser, 4, new SearchParameters())
      result.total mustEqual 2
      result.reviewRequested mustEqual 1
      result.reviewApproved mustEqual 1
    }

    "get Review Metrics filter on challenge" taggedAs (TaskReviewTag) in {
      val params = new SearchParameters(
        challengeParams = new SearchChallengeParameters(
          challengeIds = Some(List(randomChallenge.id))
        )
      )
      val result = this.service.getReviewMetrics(User.superUser, 4, params)
      result.total mustEqual 1
      result.reviewRequested mustEqual 1
    }

    "get Review Metrics filter by ReviewTasksType" taggedAs (TaskReviewTag) in {
      // Limit to only requested tasks (exclude approved)
      val result = this.service.getReviewMetrics(User.superUser, 1, new SearchParameters())
      result.total mustEqual 1
      result.reviewRequested mustEqual 1
      result.reviewApproved mustEqual 0
    }

    "get Mapper Metrics" taggedAs (TaskReviewTag) in {
      val result = this.service.getMapperMetrics(User.superUser, new SearchParameters())

      // Expecting review metrics for 2 users
      result.length mustEqual 2

      // Each user expected to have one task they want reviewed
      result(0).total mustEqual 1
      result(1).total mustEqual 1
    }

    "get Mapper Metrics filter by mappers" taggedAs (TaskReviewTag) in {
      val params = new SearchParameters(
        reviewParams = new SearchReviewParameters(
          mappers = Some(List(randomUser.id))
        )
      )
      val result = this.service.getMapperMetrics(User.superUser, params)

      // Expecting review metrics for only 1 user
      result.length mustEqual 1

      // User expected to have one task they want reviewed
      result(0).total mustEqual 1
      result(0).userId mustEqual Some(randomUser.id)
    }

    "get Tag Metrics" taggedAs (TaskReviewTag) in {
      val result = this.service.getReviewTagMetrics(User.superUser, 4, new SearchParameters())
      result.length mustEqual 1
      result.head.tagName.get mustEqual "testreviewmetricstag"
      result.head.total mustEqual 1
      result.head.fixed mustEqual 1
    }
  }

  override implicit val projectTestName: String = "TaskReviewMetricsSpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val (c, u) =
      TaskReviewMetricsServiceSpec.setup(
        this.challengeDAL,
        this.taskDAL,
        this.serviceManager,
        this.defaultProject.id,
        this.getTestTask,
        this.getTestUser
      )
    randomChallenge = c
    randomUser = u
  }
}

object TaskReviewMetricsServiceSpec {
  def setup(
      challengeDAL: ChallengeDAL,
      taskDAL: TaskDAL,
      serviceManager: ServiceManager,
      projectId: Long,
      taskFunc: (String, Long) => Task,
      userFunc: (Long, String) => User
  ): (Challenge, User) = {
    val createdReviewChallenge = challengeDAL
      .insert(
        Challenge(
          -1,
          "reviewChallenge",
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
    val task = taskDAL
      .insert(
        taskFunc(UUID.randomUUID().toString, createdReviewChallenge.id),
        User.superUser
      )

    val createdReviewChallenge2 = challengeDAL
      .insert(
        Challenge(
          -1,
          "reviewChallenge2",
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
    var task2 = taskDAL
      .insert(
        taskFunc(UUID.randomUUID().toString, createdReviewChallenge2.id),
        User.superUser
      )

    var newTag = serviceManager.tag.create(
      Tag(id = -1, tagType = "tasks", name = "testReviewMetricsTag"),
      User.superUser
    )
    taskDAL.updateItemTags(task2.id, List(newTag.id), User.superUser, true)

    val randomUser = serviceManager.user.create(
      userFunc(12345, "RandomOUser"),
      User.superUser
    )

    taskDAL.setTaskStatus(List(task), Task.STATUS_FIXED, randomUser, Some(true))
    taskDAL.setTaskStatus(List(task2), Task.STATUS_FIXED, User.superUser, Some(true))

    task2 = taskDAL.retrieveById(task2.id).get
    serviceManager.taskReview.setTaskReviewStatus(
      task2,
      Task.REVIEW_STATUS_APPROVED,
      User.superUser,
      None
    )

    (createdReviewChallenge, randomUser)
  }
}

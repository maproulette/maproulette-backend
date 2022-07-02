/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import java.util.UUID

import org.maproulette.session.SearchParameters
import org.maproulette.framework.model._
import org.maproulette.framework.util.{DataTag, FrameworkHelper}
import org.maproulette.models.dal.{ChallengeDAL, TaskDAL}
import play.api.Application

/**
  * @author krotstan
  */
class DataServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val service: DataService       = this.serviceManager.data
  var randomChallenge: Challenge = null
  var randomUser: User           = null

  "DataService" should {
    "get Tag Metrics" taggedAs (DataTag) in {
      val result = this.service.getTagMetrics(new SearchParameters())
      result.length mustEqual 1
      result.head.tagName.get mustEqual "datatesttag"
      result.head.total mustEqual 1
      result.head.fixed mustEqual 1
    }
  }

  override implicit val projectTestName: String = "DataServiceSpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val (c, u) =
      DataServiceSpec.setup(
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

object DataServiceSpec {
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
      Tag(id = -1, tagType = "tasks", name = "dataTestTag"),
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

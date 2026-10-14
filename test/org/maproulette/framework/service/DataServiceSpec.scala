/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import java.util.UUID

import org.maproulette.data.DataManager
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

    "ignore user filters in Tag Metrics" taggedAs (DataTag) in {
      // RandomOUser mapped only the untagged task, so honouring the mapper filter would return
      // no tag metrics at all.
      val unfiltered = this.service.getTagMetrics(new SearchParameters())
      List(
        SearchParameters(mapper = Some("RandomOUser")),
        SearchParameters(owner = Some("RandomOUser")),
        SearchParameters(reviewer = Some("RandomOUser")),
        SearchParameters(metaReviewer = Some("RandomOUser"))
      ).foreach { params =>
        this.service.getTagMetrics(params) mustEqual unfiltered
      }
    }

    "ignore user filters in the challenge summary" taggedAs (DataTag) in {
      val dataManager = application.injector.instanceOf(classOf[DataManager])
      val unfiltered  = dataManager.getChallengeSummary(challengeId = Some(randomChallenge.id))
      unfiltered.head.actions.fixed mustEqual 1
      List(
        SearchParameters(mapper = Some("nobody")),
        SearchParameters(owner = Some("nobody")),
        SearchParameters(reviewer = Some("nobody"))
      ).foreach { params =>
        dataManager.getChallengeSummary(
          challengeId = Some(randomChallenge.id),
          params = Some(params)
        ) mustEqual unfiltered
      }
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

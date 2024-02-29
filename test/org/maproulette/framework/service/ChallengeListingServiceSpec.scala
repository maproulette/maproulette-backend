/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import java.util.UUID

import org.maproulette.framework.model._
import org.maproulette.framework.psql.{GroupField, Grouping, Query}
import org.maproulette.framework.util.{ChallengeListingTag, FrameworkHelper}
import org.maproulette.models.dal.{ChallengeDAL, TaskDAL}
import play.api.Application

/**
  * @author krotstan
  */
class ChallengeListingServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val service: ChallengeListingService = this.serviceManager.challengeListing
  var randomUser: User                 = null

  "ChallengeListingService" should {
    "make a basic query" taggedAs (ChallengeListingTag) in {
      val challenges = this.service.query(
        Query.simple(
          List(),
          grouping = Grouping(GroupField(Challenge.FIELD_ID, Some(Challenge.TABLE)))
        )
      )
      challenges.size mustEqual 11
    }

    "fetch challenges with reviews" taggedAs (ChallengeListingTag) in {
    val challenges = this.service.withReviewList(1, User.superUser)
    challenges.size mustEqual 1
}
  }

  override implicit val projectTestName: String = "ChallengeListingSpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    ChallengeListingServiceSpec.setup(
      this.challengeDAL,
      this.taskDAL,
      this.serviceManager,
      this.defaultProject.id,
      this.getTestTask,
      this.getTestUser
    )
  }
}

object ChallengeListingServiceSpec {
  def setup(
      challengeDAL: ChallengeDAL,
      taskDAL: TaskDAL,
      serviceManager: ServiceManager,
      projectId: Long,
      taskFunc: (String, Long) => Task,
      userFunc: (Long, String) => User
  ): User = {
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

    val randomUser = serviceManager.user.create(
      userFunc(12345, "RandomOUser"),
      User.superUser
    )

    taskDAL.setTaskStatus(List(task), Task.STATUS_FIXED, randomUser, Some(true))
    randomUser
  }
}

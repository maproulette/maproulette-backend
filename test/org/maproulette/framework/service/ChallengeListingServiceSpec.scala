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
      challenges.map(_.id) must contain allElementsOf this.defaultChallenges.map(_.id)
    }

    "fetch challenges with reviews" taggedAs (ChallengeListingTag) in {
      val challenges = this.service.withReviewList(1, User.superUser)
      challenges.size mustEqual 1
    }

    "page the listing in name order with the project name" taggedAs (ChallengeListingTag) in {
      val ids        = Some(this.defaultChallenges.map(_.id))
      val firstPage  = this.challengeDAL.listing(limit = 2, offset = 0, challengeIds = ids)
      val secondPage = this.challengeDAL.listing(limit = 2, offset = 2, challengeIds = ids)
      firstPage.map(_.name) mustEqual List("default_1", "default_2")
      secondPage.map(_.name) mustEqual List("default_3")
      (firstPage ++ secondPage).flatMap(_.parentName).distinct mustEqual List(projectTestName)
    }

    "include challenges without tasks in the listing" taggedAs (ChallengeListingTag) in {
      val emptyChallenge = this.challengeDAL
        .insert(this.getTestChallenge("listingEmptyChallenge"), User.superUser)
      this.challengeDAL
        .listing(Some(List(this.defaultProject.id)), limit = -1)
        .map(_.id) must contain(emptyChallenge.id)
    }

    "filter the listing by name, owner and archived" taggedAs (ChallengeListingTag) in {
      val ids = Some(this.defaultChallenges.map(_.id))
      this.challengeDAL
        .listing(searchString = "AULT_2", challengeIds = ids)
        .map(_.name) mustEqual List("default_2")
      this.challengeDAL
        .listing(ownerId = Some(User.superUser.osmProfile.id), challengeIds = ids)
        .size mustEqual 3
      this.challengeDAL
        .listing(ownerId = Some(this.defaultUser.osmProfile.id), challengeIds = ids) mustEqual List()
      this.challengeDAL.listing(onlyArchived = true, challengeIds = ids) mustEqual List()
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

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import org.maproulette.framework.model._
import org.maproulette.framework.psql.{Grouping, Query}
import org.maproulette.framework.service.ChallengeListingServiceSpec
import org.maproulette.framework.util.{ChallengeListingRepoTag, FrameworkHelper}
import play.api.Application

/**
  * @author mcuthbert
  */
class ChallengeListingRepositorySpec(implicit val application: Application)
    extends FrameworkHelper {
  val repository: ChallengeListingRepository =
    this.application.injector.instanceOf(classOf[ChallengeListingRepository])
  var randomUser: User = null

  "ChallengeListingRepository" should {
    "make a basic query" taggedAs ChallengeListingRepoTag in {
      val challenges = this.repository.query(Query.simple(List(), grouping = Grouping > "c.id"))
      challenges.map(_.id) must contain allElementsOf this.defaultChallenges.map(_.id)
    }
  }

  override implicit val projectTestName: String = "ChallengeListingRepositorySpecProject"

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

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import org.maproulette.framework.model.{Project, User, Challenge}
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.BaseParameter
import org.maproulette.framework.util.{ChallengeRepoTag, FrameworkHelper}

import play.api.Application

/**
  * @author mcuthbert
  */
class ChallengeRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  val repository: ChallengeRepository =
    this.application.injector.instanceOf(classOf[ChallengeRepository])

  val projectRepository: ProjectRepository =
    this.application.injector.instanceOf(classOf[ProjectRepository])
  val vpRepository: VirtualProjectRepository =
    this.application.injector.instanceOf(classOf[VirtualProjectRepository])

  "ChallengeRepository" should {
    "make a basic query" taggedAs ChallengeRepoTag in {
      val challenges = this.repository.query(
        Query.simple(List(BaseParameter(Challenge.FIELD_ID, this.defaultChallenge.id)))
      )
      challenges.size mustEqual 1
      challenges.head.id mustEqual this.defaultChallenge.id
    }

    "findRelevantChallenges" taggedAs ChallengeRepoTag in {
      // Setup new project and new challenge
      val newProject =
        this.projectRepository.create(Project(-1, User.superUser.osmProfile.id, "frcNewProject"))
      val newChallenge =
        this.challengeDAL
          .insert(this.getTestChallenge("frcNewChallenge", newProject.id), User.superUser)

      // Add new challenge to a virtual project
      val vpProject =
        this.serviceManager.project.create(
          Project(
            -1,
            this.defaultUser.osmProfile.id,
            "frcVPProject",
            isVirtual = Some(true)
          ),
          this.defaultUser
        )
      this.vpRepository.addChallenge(vpProject.id, newChallenge.id)

      // Ask for virtual project challenges and default project challenges
      val challengeIds = this.repository
        .findRelevantChallenges(Some(List(this.defaultChallenge.general.parent, vpProject.id)))

      // Should include the virtual project challenge
      challengeIds.get.size mustEqual 11
      challengeIds.get contains newChallenge.id
    }
  }

  override implicit val projectTestName: String = "ChallengeRepositorySpecProject"
}

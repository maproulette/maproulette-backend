/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import org.maproulette.framework.model.{Project, User}
import org.maproulette.framework.service.VirtualProjectService
import org.maproulette.framework.util.{FrameworkHelper, VirtualProjectRepoTag}
import play.api.Application

/**
  * @author mcuthbert
  */
class VirtualProjectRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  val repository: VirtualProjectRepository =
    this.application.injector.instanceOf(classOf[VirtualProjectRepository])
  val service: VirtualProjectService = this.serviceManager.virtualProject

  "VirtualProjectRepository" should {
    "add challenges to virtual project" taggedAs VirtualProjectRepoTag in {
      val challenge =
        this.challengeDAL
          .insert(this.getTestChallenge("VP_ChallengeRepTest"), User.superUser)

      val project = this.getDefaultVirtualProject
      this.repository.addChallenge(project.id, challenge.id)
      val challenges = this.service.listVirtualChildren(project.id, User.superUser)
      challenges.size mustEqual 1
      challenges.head.id mustEqual challenge.id

      this.repository.removeChallenge(project.id, challenge.id)
      val challenges2 = this.service.listVirtualChildren(project.id, User.superUser)
      challenges2.size mustEqual 0

      // cleanup
      this.challengeDAL.delete(challenge.id, User.superUser, true)
    }
  }

  override implicit val projectTestName: String = "VirtualProjectRepositorySpecProject"

  def getDefaultVirtualProject: Project =
    this.serviceManager.project.retrieveByName("ActualVirtualProjectRepositorySpecProject").get

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    // update default project to be a virtual project
    this.serviceManager.project.create(
      Project(
        -1,
        this.defaultUser.osmProfile.id,
        "ActualVirtualProjectRepositorySpecProject",
        isVirtual = Some(true)
      ),
      this.defaultUser
    )
  }
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import org.maproulette.framework.model.{Project, User, Grant}
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.{BaseParameter, Operator}
import org.maproulette.framework.util.{FrameworkHelper, ProjectTag}
import play.api.Application
import play.api.libs.json.Json

/**
  * @author mcuthbert
  */
class ProjectServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val service: ProjectService = this.application.injector.instanceOf(classOf[ProjectService])

  "ProjectService" should {
    "get all the children of a project" taggedAs ProjectTag in {
      val challenges = this.service.children(this.defaultProject.id)
      challenges.map(_.id) must contain theSameElementsAs this.defaultChallenges.map(_.id)
    }

    "create a new project" taggedAs ProjectTag in {
      val createdProject = this.service
        .create(
          Project(-1, this.defaultUser.osmProfile.id, "ServiceCreateProject"),
          this.defaultUser
        )
      val retrievedProject = this.service.retrieve(createdProject.id)
      retrievedProject.get mustEqual createdProject

      // make sure that project creator is granted admin role on new project
      val createdGrants = retrievedProject.get.grants
      createdGrants.size mustEqual 1
      createdGrants.head.grantee.granteeId mustEqual defaultUser.id
      createdGrants.head.role mustEqual Grant.ROLE_ADMIN
      createdGrants.head.target.objectId mustEqual createdProject.id
    }

    // This
    "get featured projects" taggedAs ProjectTag in {
      val featuredProject = this.service.create(
        Project(-1, User.superUser.osmProfile.id, "FeaturedProject", featured = true),
        User.superUser
      )
      val projects1 = this.service.getFeaturedProjects(false)
      projects1.head mustEqual featuredProject
      val projects2 = this.service.getFeaturedProjects()
      projects2.size mustEqual 0
      this.service.update(featuredProject.id, Json.obj("enabled" -> true), User.superUser)
      val projects3 = this.service.getFeaturedProjects()
      projects3.head.id mustEqual featuredProject.id
      projects3.head.featured mustEqual true
      projects3.head.enabled mustEqual true
    }

    "only update featured if super user" taggedAs ProjectTag in {
      val randomUser = this.serviceManager.user
        .create(this.getTestUser(575438, "RandomFeatureUser"), User.superUser)
      val project = this.service.create(
        Project(-1, randomUser.osmProfile.id, "ServiceFeaturedUpdateProject"),
        randomUser
      )
      project.featured mustEqual false
      val updatedProject =
        this.service.update(project.id, Json.obj("featured" -> true), User.superUser)
      updatedProject.get.featured mustEqual true
      val notUpdatedProject =
        this.service.update(project.id, Json.obj("featured" -> false), randomUser)
      notUpdatedProject.get.featured mustEqual true
    }

    "update a project" taggedAs ProjectTag in {
      val randomUser =
        this.serviceManager.user.create(this.getTestUser(9876, "RANDOM_USER"), User.superUser)
      val updateProject = this.service
        .create(Project(-1, User.superUser.osmProfile.id, "UpdateProject"), User.superUser)
      val updates = Json.obj(
        "name"        -> "SERVICE_UPDATE_NAME",
        "displayName" -> "UPDATE_DISPLAY_NAME",
        "ownerId"     -> randomUser.osmProfile.id,
        "description" -> "UPDATE_DESCRIPTION",
        "enabled"     -> false,
        "featured"    -> false
      )
      this.service.update(updateProject.id, updates, User.superUser)
      val updated = this.service.retrieve(updateProject.id)
      updated.get.name mustEqual "SERVICE_UPDATE_NAME"
      updated.get.displayName.get mustEqual "UPDATE_DISPLAY_NAME"
      updated.get.owner mustEqual randomUser.osmProfile.id
      updated.get.description.get mustEqual "UPDATE_DESCRIPTION"
      updated.get.enabled mustEqual false
      updated.get.featured mustEqual false
    }

    "update cannot change enabled or feature unless SuperUser" taggedAs ProjectTag in {
      val randomUser =
        this.serviceManager.user.create(this.getTestUser(9876, "RANDOM_USER"), User.superUser)
      val updateProject = this.service
        .create(Project(-1, randomUser.osmProfile.id, "EnabledFeaturedProject"), randomUser)
      val update1 =
        this.service.update(updateProject.id, Json.obj("featured" -> true), User.superUser)
      update1.get.featured mustEqual true
      val update2 = this.service.update(updateProject.id, Json.obj("featured" -> false), randomUser)
      update2.get.featured mustEqual true
      val update3 =
        this.service.update(updateProject.id, Json.obj("enabled" -> true), User.superUser)
      update3.get.enabled mustEqual true
      val update4 = this.service.update(updateProject.id, Json.obj("enabled" -> false), randomUser)
      update4.get.enabled mustEqual true
    }

    "update cannot change virtual status" taggedAs ProjectTag in {
      val randomUser =
        this.serviceManager.user.create(this.getTestUser(9876, "RANDOM_USER"), User.superUser)
      val updateProject = this.service
        .create(
          Project(
            -1,
            randomUser.osmProfile.id,
            "ChangeVirtualStatusProject",
            isVirtual = Some(true)
          ),
          randomUser
        )
      // Update that omits isVirtual
      val update1 =
        this.service.update(updateProject.id, Json.obj("featured" -> true), randomUser)
      update1.get.isVirtual.get mustEqual true
      // Update that explicitly tries to change isVirtual
      val update2 = this.service
        .update(updateProject.id, Json.obj("featured" -> false, "isVirtual" -> false), randomUser)
      update2.get.isVirtual.get mustEqual true
    }

    "delete a project" taggedAs ProjectTag in {
      val createdProject = this.service
        .create(Project(-1, User.superUser.osmProfile.id, "DeleteTestProject"), User.superUser)
      val retrievedProject = this.service.retrieve(createdProject.id)
      retrievedProject.get mustEqual createdProject

      this.service.delete(createdProject.id, User.superUser)
      val retrievedProject2 = this.service.retrieve(createdProject.id)
      retrievedProject2.get.id mustEqual createdProject.id
      retrievedProject2.get.deleted mustEqual true

      this.service.delete(createdProject.id, User.superUser, true)
      val retrievedProject3 = this.service.retrieve(createdProject.id)
      retrievedProject3.isEmpty mustEqual true
    }
  }

  "retrieve a project by it's name" taggedAs ProjectTag in {
    val createdProject = this.service
      .create(Project(-1, User.superUser.osmProfile.id, "RetrieveByNameTest"), User.superUser)
    val retrievedProject = this.service.retrieveByName("RetrieveByNameTest")
    retrievedProject.get mustEqual createdProject
    val retrievedProject2 = this.service.retrieveByName("RetrieveByNameTes")
    retrievedProject2.isEmpty mustEqual true
  }

  "list projects by id's" taggedAs ProjectTag in {
    val createdProject = this.service
      .create(Project(-1, User.superUser.osmProfile.id, "ListingProject"), User.superUser)
    val projects = this.service.list(List(createdProject.id, this.defaultProject.id))
    projects.size mustEqual 2
    projects.foreach(project => {
      if (project.id == createdProject.id) {
        project mustEqual createdProject
      } else if (project.id == this.defaultProject.id) {
        project mustEqual this.defaultProject
      } else {
        throw new RuntimeException("Invalid project returned")
      }
    })
  }

  "list projects using a custom query" taggedAs ProjectTag in {
    val results = this.service
      .query(Query.simple(List(BaseParameter("id", List(1259, 3898, 217, 217, 217), Operator.IN))))
    results.size mustEqual 0
  }

  "list managed projects" taggedAs ProjectTag in {
    val createdUser =
      this.serviceManager.user.create(this.getTestUser(678, "ManagedListingUser"), User.superUser)
    // make sure the home project is created for the user
    this.serviceManager.user.initializeHomeProject(createdUser)
    val randomUser = this.serviceManager.user.retrieveByOSMId(678).get
    // get all the managed projects, for this user should just be their initialized project
    val projects = this.service.getManagedProjects(randomUser)
    projects.size mustEqual 1
    val projects2 = this.service.getManagedProjects(randomUser, searchString = "Home_")
    projects2.size mustEqual 1
    val projects3 = this.service.getManagedProjects(randomUser, searchString = "DUMMY")
    projects3.size mustEqual 0
  }

  override implicit val projectTestName: String = "ProjectServiceSpecProject"
}

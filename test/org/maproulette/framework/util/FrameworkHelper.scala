/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.util

import java.util.UUID

import org.joda.time.DateTime
import org.maproulette.framework.model._
import org.maproulette.framework.service.ServiceManager
import org.maproulette.models.dal.{ChallengeDAL, TaskDAL}
import org.maproulette.permissions.Permission
import org.scalatest.{BeforeAndAfterAll, Tag}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Application

import org.maproulette.data.SnapshotManager

/**
  * @author mcuthbert
  */
trait FrameworkHelper extends PlaySpec with BeforeAndAfterAll with MockitoSugar {
  implicit val application: Application
  val serviceManager: ServiceManager = application.injector.instanceOf(classOf[ServiceManager])
  val challengeDAL: ChallengeDAL     = application.injector.instanceOf(classOf[ChallengeDAL])
  val taskDAL: TaskDAL               = application.injector.instanceOf(classOf[TaskDAL])
  val permission: Permission         = application.injector.instanceOf(classOf[Permission])

  // To be removed when all of SnapshotManager has been converted
  val snapshotManager: SnapshotManager = application.injector.instanceOf(classOf[SnapshotManager])

  implicit val projectTestName: String

  var defaultChallenge: Challenge = null
  var defaultTask: Task           = null

  def defaultProject: Project = this.serviceManager.project.retrieveByName(projectTestName).get
  def defaultUser: User       = this.serviceManager.user.retrieveByOSMId(134567788).get

  override protected def beforeAll(): Unit = {
    this.serviceManager.user
      .create(this.getTestUser(134567788, s"User_$projectTestName"), User.superUser)
    this.createProjectStructure(projectTestName, "default")
  }

  protected def createProjectStructure(
      projectName: String,
      challengePrefix: String,
      numberOfChallenges: Int = 10,
      numberOfTasksPerChallenge: Int = 50,
      ownerId: Long = this.defaultUser.osmProfile.id
  ): Project = {
    val createdProject = this.serviceManager.project
      .create(Project(-1, ownerId, projectName), this.defaultUser)
    1 to numberOfChallenges foreach { cid =>
      {
        val challenge = this.createChallengeStructure(s"${challengePrefix}_$cid", createdProject.id)
        if (this.defaultChallenge == null) {
          this.defaultChallenge = challenge
        }
      }
    }
    createdProject
  }

  protected def createChallengeStructure(
      challengeName: String,
      projectId: Long,
      numberOfTasks: Int = 50
  ): Challenge = {
    val createdChallenge = this.challengeDAL
      .insert(this.getTestChallenge(challengeName, projectId), User.superUser)
    this.createTasks(createdChallenge.id, numberOfTasks)
    createdChallenge
  }

  protected def getTestChallenge(
      name: String,
      parentId: Long = this.defaultProject.id
  ): Challenge = {
    Challenge(
      -1,
      name,
      null,
      null,
      general = ChallengeGeneral(
        User.superUser.osmProfile.id,
        parentId,
        "TestChallengeInstruction"
      ),
      creation = ChallengeCreation(),
      priority = ChallengePriority(),
      extra = ChallengeExtra()
    )
  }

  protected def createTasks(challengeId: Long, numberOfTasks: Int = 50): Unit = {
    1 to numberOfTasks foreach { _ =>
      {
        val task = this.taskDAL
          .insert(this.getTestTask(UUID.randomUUID().toString, challengeId), User.superUser)
        if (this.defaultTask == null) {
          this.defaultTask = task
        }
      }
    }
  }

  protected def getTestTask(name: String, parentId: Long = this.defaultChallenge.id): Task = {
    Task(
      -1,
      name,
      null,
      null,
      parentId,
      geometries =
        "{\"features\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[-60.811801,-32.9199812],[-60.8117804,-32.9199856],[-60.8117816,-32.9199896],[-60.8117873,-32.919984]]},\"properties\":{\"osm_id\":\"OSM_W_378169283_000000_000\",\"pbfHistory\":[\"20200110-043000\"]}}]}",
      status = Some(0)
    )
  }

  protected def getTestUser(osmId: Long, osmName: String): User = {
    User(
      -1,
      null,
      null,
      OSMProfile(
        osmId,
        osmName,
        "Test User",
        "",
        Location(1.0, 2.0),
        DateTime.now(),
        "token"
      ),
      List.empty,
      Some(UUID.randomUUID().toString)
    )
  }

  protected def getTestTeam(name: String): Group = {
    Group(
      -1,
      name,
      Some("A test team"),
      Some("http://www.gravatar.com/avatar/?d=identicon")
    )
  }

  override protected def afterAll(): Unit = {
    val testProject = this.serviceManager.project.retrieveByName(projectTestName)
    this.serviceManager.project.delete(testProject.get.id, User.superUser, true)
  }
}

// Test tags so that you only have to run specific tests
object ChallengeTag            extends Tag("challenge")
object ChallengeRepoTag        extends Tag("challengerepo")
object ChallengeListingTag     extends Tag("challengelisting")
object ChallengeListingRepoTag extends Tag("challengelistingrepo")
object ChallengeSnapshotTag    extends Tag("challengesnapshot")
object ProjectTag              extends Tag("project")
object ProjectRepoTag          extends Tag("projectrepo")
object CommentTag              extends Tag("comment")
object CommentRepoTag          extends Tag("commentrepo")
object DataTag                 extends Tag("datatag")
object GrantTag                extends Tag("grant")
object UserMetricsTag          extends Tag("usermetrics")
object UserSavedObjectsTag     extends Tag("usersavedobjects")
object UserSavedObjectsRepoTag extends Tag("usersavedobjectsrepo")
object UserTag                 extends Tag("user")
object GroupTag                extends Tag("group")
object UserRepoTag             extends Tag("userRepo")
object VirtualProjectTag       extends Tag("virtualproject")
object VirtualProjectRepoTag   extends Tag("virtualprojectrepo")
object KeywordTag              extends Tag("keyword")
object KeywordRepoTag          extends Tag("keywordrepo")
object TaskReviewTag           extends Tag("taskreviewtag")
object TaskTag                 extends Tag("tasktag")
object TeamTag                 extends Tag("teamtag")
object NotificationTag         extends Tag("notificationtag")
object LeaderboardTag          extends Tag("leaderboardtag")
object LeaderboardRepoTag      extends Tag("leaderboardrepotag")

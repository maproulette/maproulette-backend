/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.utils

import com.google.inject.util.Providers
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.data.{ActionManager, DataManager, StatusActionManager}
import org.maproulette.framework.model._
import org.maproulette.framework.service._
import org.maproulette.models._
import org.maproulette.models.dal._
import org.maproulette.permissions.Permission
import org.maproulette.session._
import org.mockito.ArgumentMatchers.{eq => eqM, _}
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.db.Databases

/**
  * @author mcuthbert
  */
trait TestSpec extends PlaySpec with MockitoSugar {
  implicit val configuration = Configuration.from(
    Map(Config.KEY_CACHING_CACHE_LIMIT -> 6, Config.KEY_CACHING_CACHE_EXPIRY -> 5)
  )

  val testDb = Databases.inMemory()

  //projects
  val project1 = Project(
    1,
    101,
    "Mocked_1",
    DateTime.now(),
    DateTime.now(),
    None,
    List(adminGrant(1, 1), writeGrant(1, 2), readGrant(1, 3))
  )

  //challenges
  val challenge1 = Challenge(
    1,
    "Challenge1",
    DateTime.now(),
    DateTime.now(),
    None,
    false,
    false,
    None,
    ChallengeGeneral(101, 1, ""),
    ChallengeCreation(),
    ChallengePriority(),
    ChallengeExtra()
  )

  // Grants (Admin, Write, Read)
  def adminGrant(projectId: Long, userId: Long): Grant =
    Grant(
      projectId,
      s"Mocked_${projectId}/${userId}_Admin",
      Grantee.user(userId),
      Grant.ROLE_ADMIN,
      GrantTarget.project(1)
    )

  def writeGrant(projectId: Long, userId: Long): Grant =
    Grant(
      projectId,
      s"Mocked_${projectId}/${userId}_Write",
      Grantee.user(userId),
      Grant.ROLE_WRITE_ACCESS,
      GrantTarget.project(1)
    )

  def readGrant(projectId: Long, userId: Long): Grant =
    Grant(
      projectId,
      s"Mocked_${projectId}/${userId}_Read",
      Grantee.user(userId),
      Grant.ROLE_READ_ONLY,
      GrantTarget.project(1)
    )

  //tasks
  val task1               = Task(1, "Task1", DateTime.now(), DateTime.now(), 1, None, None, "")
  val taskDAL             = mock[TaskDAL]
  val challengeDAL        = mock[ChallengeDAL]
  val virtualChallengeDAL = mock[VirtualChallengeDAL]
  val virtualChallenge = VirtualChallenge(
    1,
    "VChallenge",
    DateTime.now(),
    DateTime.now(),
    None,
    101,
    SearchParameters(),
    DateTime.now()
  )
  val projectService = mock[ProjectService]
  val userService    = mock[UserService]
  val adminUser = User(
    1,
    DateTime.now(),
    DateTime.now(),
    OSMProfile(1, "AdminUser", "", "", Location(0, 0), DateTime.now(), ""),
    List(adminGrant(1, 1))
  )
  val actionManager = mock[ActionManager]
  val dataManager   = mock[DataManager]

  val statusActionManager = mock[StatusActionManager]
  val dalManager = new DALManager(
    taskDAL,
    challengeDAL,
    virtualChallengeDAL,
    actionManager,
    dataManager,
    statusActionManager
  )
  val followService            = mock[FollowService]
  val grantService             = mock[GrantService]
  val commentService           = mock[CommentService]
  val challengeService         = mock[ChallengeService]
  val challengeListingService  = mock[ChallengeListingService]
  val challengeSnapshotService = mock[ChallengeSnapshotService]
  val dataService              = mock[DataService]
  val userMetricService        = mock[UserMetricService]
  val achievementService       = mock[AchievementService]
  val virtualProjectService    = mock[VirtualProjectService]
  val tagService               = mock[TagService]
  val taskBundleService        = mock[TaskBundleService]
  val taskClusterService       = mock[TaskClusterService]
  val taskReviewService        = mock[TaskReviewService]
  val taskReviewMetricsService = mock[TaskReviewMetricsService]
  val taskService              = mock[TaskService]
  val groupService             = mock[GroupService]
  val teamService              = mock[TeamService]
  val notificationService      = mock[NotificationService]
  val leaderboardService       = mock[LeaderboardService]
  val taskHistoryService       = mock[TaskHistoryService]
  val serviceManager = new ServiceManager(
    Providers.of[ProjectService](projectService),
    Providers.of[GrantService](grantService),
    Providers.of[UserService](userService),
    Providers.of[FollowService](followService),
    Providers.of[GroupService](groupService),
    Providers.of[CommentService](commentService),
    Providers.of[TagService](tagService),
    Providers.of[DataService](dataService),
    Providers.of[ChallengeService](challengeService),
    Providers.of[ChallengeListingService](challengeListingService),
    Providers.of[ChallengeSnapshotService](challengeSnapshotService),
    Providers.of[UserMetricService](userMetricService),
    Providers.of[AchievementService](achievementService),
    Providers.of[VirtualProjectService](virtualProjectService),
    Providers.of[TaskBundleService](taskBundleService),
    Providers.of[TaskClusterService](taskClusterService),
    Providers.of[TaskReviewService](taskReviewService),
    Providers.of[TaskReviewMetricsService](taskReviewMetricsService),
    Providers.of[TaskService](taskService),
    Providers.of[TeamService](teamService),
    Providers.of[NotificationService](notificationService),
    Providers.of[LeaderboardService](leaderboardService),
    Providers.of[TaskHistoryService](taskHistoryService)
  )
  val permission =
    new Permission(Providers.of[DALManager](dalManager), serviceManager, new Config())
  var writeUser = User(
    2,
    DateTime.now(),
    DateTime.now(),
    OSMProfile(2, "WriteUser", "", "", Location(0, 0), DateTime.now(), ""),
    List(writeGrant(1, 2))
  )
  var readUser = User(
    3,
    DateTime.now(),
    DateTime.now(),
    OSMProfile(3, "ReadUser", "", "", Location(0, 0), DateTime.now(), ""),
    List(readGrant(1, 3))
  )
  var owner = User(
    100,
    DateTime.now(),
    DateTime.now(),
    OSMProfile(101, "DefaultOwner", "", "", Location(0, 0), DateTime.now(), ""),
    List.empty // even an owner needs to be granted the proper role
  )

  def setupMocks(): Unit = {
    // Mocks for Tags
    when(this.tagService.retrieve(0)).thenReturn(None)
    when(this.tagService.retrieve(1)).thenReturn(Some(Tag(1, "Tag")))

    // Mocks for Tasks
    when(taskDAL.retrieveById(0L, None)).thenReturn(None)
    doAnswer(_ => None).when(taskDAL.asInstanceOf[BaseDAL[Long, Task]]).retrieveById(0L, None)
    when(taskDAL.retrieveById(1L, None)).thenReturn(Some(task1))
    doAnswer(_ => Some(task1))
      .when(taskDAL.asInstanceOf[BaseDAL[Long, Task]])
      .retrieveById(1L, None)
    when(taskDAL.retrieveRootObject(eqM(Right(task1)), any())(any())).thenReturn(Some(project1))

    // Mocks for Challenge DAL
    when(challengeDAL.retrieveById(0L, None)).thenReturn(None)
    doAnswer(_ => None)
      .when(challengeDAL.asInstanceOf[BaseDAL[Long, Challenge]])
      .retrieveById(0L, None)
    when(challengeDAL.retrieveById(1L, None)).thenReturn(Some(challenge1))
    doAnswer(_ => Some(challenge1))
      .when(challengeDAL.asInstanceOf[BaseDAL[Long, Challenge]])
      .retrieveById(1L, None)
    when(challengeDAL.retrieveRootObject(eqM(Right(challenge1)), any())(any()))
      .thenReturn(Some(project1))
    // Mocks for Challenge Service
    when(challengeService.retrieve(0L)).thenReturn(None)
    when(challengeService.retrieve(1L)).thenReturn(Some(challenge1))

    // Mocks for Virtual Challenges
    when(virtualChallengeDAL.retrieveById(0, None)).thenReturn(None)
    doAnswer(_ => None)
      .when(virtualChallengeDAL.asInstanceOf[BaseDAL[Long, VirtualChallenge]])
      .retrieveById(0L, None)
    when(virtualChallengeDAL.retrieveById(1, None)).thenReturn(Some(virtualChallenge))
    doAnswer(_ => Some(virtualChallenge))
      .when(virtualChallengeDAL.asInstanceOf[BaseDAL[Long, VirtualChallenge]])
      .retrieveById(1L, None)

    // Mocks for Projects
    when(this.projectService.retrieve(0)).thenReturn(None)
    when(this.projectService.retrieve(1)).thenReturn(Some(project1))

    // Mocks for users
    when(this.userService.retrieve(-999L)).thenReturn(Some(User.superUser))
    when(this.userService.isSuperUser(-999L)).thenReturn(true)
    when(this.userService.retrieve(-998L)).thenReturn(Some(User.guestUser))
    doAnswer(_ => Some(User.superUser)).when(this.userService).retrieve(-999L)
    when(this.userService.retrieve(-1L)).thenReturn(Some(User.guestUser))
    doAnswer(_ => Some(User.guestUser)).when(this.userService).retrieve(-1L)
    when(this.userService.retrieve(0L)).thenReturn(None)
    doAnswer(_ => None).when(this.userService).retrieve(0L)
    when(this.userService.retrieve(1L)).thenReturn(Some(adminUser))
    doAnswer(_ => Some(adminUser)).when(this.userService).retrieve(1L)
    when(this.userService.retrieve(2L)).thenReturn(Some(writeUser))
    doAnswer(_ => Some(writeUser)).when(this.userService).retrieve(2L)
    when(this.userService.retrieve(3L)).thenReturn(Some(readUser))
    doAnswer(_ => Some(readUser)).when(this.userService).retrieve(3L)
    when(this.userService.retrieve(100L)).thenReturn(Some(owner))
    doAnswer(_ => Some(owner)).when(this.userService).retrieve(100L)

    // Mocks for Grants
    when(this.grantService.retrieve(1)).thenReturn(Some(this.adminGrant(1, 1).copy(id = 1)))
  }
}

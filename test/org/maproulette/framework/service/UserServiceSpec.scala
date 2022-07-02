/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import org.maproulette.framework.model._
import org.maproulette.framework.psql.filter._
import org.maproulette.framework.psql.{OR, Paging, Query}
import org.maproulette.framework.util.{FrameworkHelper, UserTag}
import org.maproulette.exception.{InvalidException}
import org.maproulette.session.{SearchParameters, SearchTaskParameters}
import org.maproulette.data.{ProjectType}
import org.maproulette.models.dal.{ChallengeDAL, TaskDAL}
import org.scalatest.matchers.should.Matchers._
import play.api.Application

/**
  * @author mcuthbert
  */
class UserServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val userService: UserService   = this.serviceManager.user
  var randomChallenge: Challenge = null
  var randomUser: User           = null

  val commentService: CommentService = this.application.injector.instanceOf(classOf[CommentService])

  "UserService" should {
    "not allow retrieval of user by API key if not actual user" taggedAs UserTag in {
      val firstUser =
        this.userService.create(this.getTestUser(6, "FailedUserTest"), User.superUser)
      val secondUser =
        this.userService.create(this.getTestUser(7, "FailedUserTest2"), User.superUser)
      intercept[IllegalAccessException] {
        this.userService.retrieveByAPIKey(firstUser.id, firstUser.apiKey.get, secondUser)
      }
    }

    "retrieve Users" taggedAs UserTag in {
      val insertedUser =
        this.userService.create(this.getTestUser(8, "InsertUserServiceTest"), User.superUser)
      // get the user by their API Key and id
      val retrievedUser1 =
        this.userService.retrieveByAPIKey(insertedUser.id, insertedUser.apiKey.get, User.superUser)
      retrievedUser1.get mustEqual insertedUser
      // get the user by their API Key and OSM user id
      val retrievedUser2 = this.userService
        .retrieveByAPIKey(insertedUser.osmProfile.id, insertedUser.apiKey.get, User.superUser)
      retrievedUser2.get mustEqual insertedUser
      // get the user by their username and API Key
      val retrievedUser3 = this.userService
        .retrieveByUsernameAndAPIKey(insertedUser.osmProfile.displayName, insertedUser.apiKey.get)
      retrievedUser3.get mustEqual insertedUser
      // get the user by their OSM username
      val retrievedUser4 =
        this.userService.retrieveByOSMUsername(insertedUser.osmProfile.displayName, User.superUser)
      retrievedUser4.get mustEqual insertedUser
      // get the user based off of the requestToken
      val retrievedUser5 = this.userService.matchByRequestToken(
        insertedUser.id,
        insertedUser.osmProfile.requestToken,
        User.superUser
      )
      retrievedUser5.get mustEqual insertedUser
      // get the user simply based off their id
      val retrievedUser6 = this.userService.retrieve(insertedUser.id)
      retrievedUser6.get mustEqual insertedUser
      // get the user by their OSM user id
      val retrievedUser7 = this.userService.retrieveByOSMId(insertedUser.osmProfile.id)
      retrievedUser7
    }

    "not allow retrieval of user by OSM username if not super user" taggedAs UserTag in {
      val firstUser =
        this.userService.create(this.getTestUser(9, "FailedORetrievalUser1"), User.superUser)
      val secondUser =
        this.userService.create(this.getTestUser(10, "FailedORetrievalUser2"), User.superUser)
      intercept[IllegalAccessException] {
        this.userService.retrieveByOSMUsername(firstUser.osmProfile.displayName, secondUser)
      }
    }

    "retrieve includes grants conferred by teams" taggedAs UserTag in {
      val insertedUser =
        this.userService
          .create(this.getTestUser(21, "retrieveConferredGrantsUser1"), User.superUser)

      val team = this.serviceManager.team
        .create(
          this.getTestTeam("UserRepository_conferredGrantsTest Team"),
          MemberObject.user(this.defaultUser.id),
          this.defaultUser
        )
        .get

      val project = this.serviceManager.project
        .create(
          Project(-1, this.defaultUser.osmProfile.id, "retrieveConferredGrantsProject"),
          this.defaultUser
        )

      this.serviceManager.team
        .addTeamMember(
          team,
          MemberObject.user(insertedUser.id),
          Grant.ROLE_ADMIN,
          TeamMember.STATUS_MEMBER,
          User.superUser
        )

      this.serviceManager.team
        .addTeamToProject(
          team.id,
          project.id,
          Grant.ROLE_WRITE_ACCESS,
          User.superUser
        )

      // Work off a fresh copy of the user
      val user = this.userService.retrieve(insertedUser.id).get
      user.grants
        .filter(g =>
          g.target.objectType == ProjectType() &&
            g.target.objectId == project.id &&
            g.role == Grant.ROLE_WRITE_ACCESS
        )
        .size mustEqual 1

      user.managedProjectIds().contains(project.id) mustEqual true
      user.grantsForProject(project.id).size mustEqual 1
    }

    "generate new API key" taggedAs UserTag in {
      val insertedUser =
        this.userService.create(this.getTestUser(11, "APIGenerationTestUser"), User.superUser)
      val newAPIUser = this.userService.generateAPIKey(insertedUser, User.superUser)
      newAPIUser.get.apiKey must not be insertedUser.apiKey
    }

    "add a user's Following group" taggedAs UserTag in {
      val insertedUser =
        this.userService.create(this.getTestUser(21, "AddFollowingGroupTest"), User.superUser)

      insertedUser.followingGroupId mustEqual None
      val followingGroupId = this.userService.addFollowingGroup(insertedUser, User.superUser)
      followingGroupId.isDefined mustEqual true

      val groupAgain = this.userService.addFollowingGroup(insertedUser, User.superUser)
      groupAgain.get mustEqual followingGroupId.get
    }

    "add a user's Followers group" taggedAs UserTag in {
      val insertedUser =
        this.userService.create(this.getTestUser(22, "AddFollowersGroupTest"), User.superUser)

      insertedUser.followersGroupId mustEqual None
      val followersGroupId = this.userService.addFollowersGroup(insertedUser, User.superUser)
      followersGroupId.isDefined mustEqual true

      val groupAgain = this.userService.addFollowersGroup(insertedUser, User.superUser)
      groupAgain.get mustEqual followersGroupId.get
    }

    "retrieve list of users" taggedAs UserTag in {
      val user1 =
        this.userService.create(this.getTestUser(12, "ListRetrievalTestUser1"), User.superUser)
      val user2 =
        this.userService.create(this.getTestUser(13, "ListRetrievalTestUser2"), User.superUser)
      val userList = this.userService.retrieveListById(List(user1.id, user2.id))
      userList.size mustEqual 2
      List(user1, user2).contains(userList.head) mustEqual true
      List(user1, user2).contains(userList(1)) mustEqual true
    }

    "delete user" taggedAs UserTag in {
      val user =
        this.userService.create(this.getTestUser(14, "DeleteTest"), User.superUser)
      this.userService.delete(user.id, User.superUser)
      this.userService.retrieve(user.id).isEmpty mustEqual true
    }

    "delete user by OSM id" taggedAs UserTag in {
      val user =
        this.userService.create(this.getTestUser(15, "DeleteByOidTest"), User.superUser)
      this.userService.deleteByOsmID(user.osmProfile.id, User.superUser)
      this.userService.retrieve(user.id).isEmpty mustEqual true
    }

    "Search by OSM Username" taggedAs UserTag in {
      val user1 =
        this.userService.create(this.getTestUser(16, "OSMUsernameSearch1"), User.superUser)
      val user2 =
        this.userService.create(this.getTestUser(17, "OSMUsernameSearch2"), User.superUser)
      val user3 =
        this.userService.create(this.getTestUser(18, "OSMUsernameNotRelated"), User.superUser)

      val searchResultUser1 = UserSearchResult(
        user1.id,
        user1.osmProfile.id,
        user1.osmProfile.displayName,
        user1.osmProfile.avatarURL
      )
      val searchResultUser2 = UserSearchResult(
        user2.id,
        user2.osmProfile.id,
        user2.osmProfile.displayName,
        user2.osmProfile.avatarURL
      )
      val searchResultUser3 = UserSearchResult(
        user3.id,
        user3.osmProfile.id,
        user3.osmProfile.displayName,
        user3.osmProfile.avatarURL
      )

      val users1 = this.userService.searchByOSMUsername("Search")
      users1.size mustEqual 2
      List(searchResultUser1, searchResultUser2).contains(users1.head.toSearchResult) mustEqual true
      List(searchResultUser1, searchResultUser2).contains(users1(1).toSearchResult) mustEqual true

      val users2 = this.userService.searchByOSMUsername("NotRelated")
      users2.size mustEqual 1
      users2.head.toSearchResult mustEqual searchResultUser3

      val users3 = this.userService.searchByOSMUsername("NONE")
      users3.size mustEqual 0

      val searchResultsList = List(searchResultUser1, searchResultUser2, searchResultUser3)
      val users4            = this.userService.searchByOSMUsername("OSM", Paging(1))
      users4.size mustEqual 1
      val firstUser = users4.head.toSearchResult
      searchResultsList.contains(firstUser) mustEqual true

      val users5 = this.userService.searchByOSMUsername("OSM", Paging(1, 1))
      users5.size mustEqual 1
      val secondUser = users5.head.toSearchResult
      searchResultsList.contains(secondUser) mustEqual true
      secondUser should not be firstUser
      val users6 = this.userService.searchByOSMUsername("OSM", Paging(1, 2))
      users6.size mustEqual 1
      val thirdUser = users6.head.toSearchResult
      searchResultsList.contains(thirdUser) mustEqual true
      thirdUser should not be firstUser
      thirdUser should not be secondUser

      val comment =
        this.commentService.create(user1, this.defaultTask.id, "UserService Comment Add", None)
      val retrievedComment = this.commentService.retrieve(comment.id)
      val allComments = this.commentService.find(
        projectIdList = List(),
        challengeIdList = List(),
        taskIdList = List(this.defaultTask.id)
      )
      val users7 = this.userService.searchByOSMUsername(
        "",
        params =
          SearchParameters(taskParams = SearchTaskParameters(taskId = Some(this.defaultTask.id)))
      )
      users7.size mustEqual 1

      val users8 = this.userService.searchByOSMUsername(
        "",
        params = SearchParameters(taskParams = SearchTaskParameters(taskId = Some(123)))
      )
      users8.size mustEqual 0
    }

    "get users managing projects" taggedAs UserTag in {
      val managers =
        this.userService.getUsersManagingProject(this.defaultProject.id, user = User.superUser)
      managers.size mustEqual 1
      managers.head.osmId mustEqual this.defaultUser.osmProfile.id
      val managers2 =
        this.userService
          .getUsersManagingProject(this.defaultProject.id, Some(List.empty), User.superUser)
      managers2.size mustEqual 1
      managers2.head.osmId mustEqual this.defaultUser.osmProfile.id
      val managers3 = this.userService
        .getUsersManagingProject(
          this.defaultProject.id,
          Some(List(this.defaultUser.id)),
          User.superUser
        )
      managers3.size mustEqual 0
      val managers4 = this.userService
        .getUsersManagingProject(
          this.defaultProject.id,
          Some(List(this.defaultUser.osmProfile.id)),
          User.superUser
        )
      managers4.size mustEqual 1
      managers4.head.osmId mustEqual this.defaultUser.osmProfile.id
    }

    "get project managers indirectly through teams" taggedAs UserTag in {
      val freshUser = this.userService.create(
        this.getTestUser(44412345, "GetIndirectProjectManagersOUser"),
        User.superUser
      )

      val team = this.serviceManager.team
        .create(
          this.getTestTeam("UserService_getProjectManagersTest Team"),
          MemberObject.user(this.defaultUser.id),
          this.defaultUser
        )
        .get

      val project = this.serviceManager.project
        .create(
          Project(-1, this.defaultUser.osmProfile.id, "getAllProjectManagersProject"),
          this.defaultUser
        )

      this.serviceManager.team
        .addTeamMember(
          team,
          MemberObject.user(freshUser.id),
          Grant.ROLE_ADMIN,
          TeamMember.STATUS_MEMBER,
          User.superUser
        )

      this.serviceManager.team
        .addTeamToProject(
          team.id,
          project.id,
          Grant.ROLE_WRITE_ACCESS,
          User.superUser
        )

      this.permission.hasObjectWriteAccess(project, freshUser)
      val managers =
        this.userService.getUsersManagingProject(project.id, user = User.superUser)

      managers.map(_.userId).contains(this.defaultUser.id) mustEqual true
      managers.map(_.userId).contains(freshUser.id) mustEqual true
      managers.size mustEqual 2

      this.serviceManager.team
        .removeTeamFromProject(
          team.id,
          project.id,
          User.superUser
        )

      an[IllegalAccessException] should be thrownBy this.permission.hasObjectWriteAccess(
        project,
        freshUser
      )
      val managersAfterRemoval =
        this.userService.getUsersManagingProject(project.id, user = User.superUser)

      managersAfterRemoval.size mustEqual 1
      managersAfterRemoval.head.userId mustEqual this.defaultUser.id
    }

    "add a user to a project" taggedAs UserTag in {
      this.userService
        .getUsersManagingProject(
          this.defaultProject.id,
          None,
          User.superUser
        )
        .size mustEqual 1

      val user =
        this.userService.create(this.getTestUser(19, "AddUserToProjectTest"), User.superUser)

      this.userService.addUserToProject(
        user.osmProfile.id,
        this.defaultProject.id,
        Grant.ROLE_WRITE_ACCESS,
        User.superUser
      )

      this.userService
        .getUsersManagingProject(
          this.defaultProject.id,
          None,
          User.superUser
        )
        .size mustEqual 2
    }

    "remove a user from a project" taggedAs UserTag in {
      val user =
        this.userService.create(this.getTestUser(20, "RemoveUserFromProjectTest"), User.superUser)
      val project = this.serviceManager.project
        .create(
          Project(-1, user.osmProfile.id, "RemoveUserFromProjectTestProject"),
          user
        )

      this.userService.addUserToProject(
        this.defaultUser.osmProfile.id,
        project.id,
        Grant.ROLE_ADMIN,
        User.superUser
      )

      this.userService
        .getUsersManagingProject(
          project.id,
          None,
          User.superUser
        )
        .size mustEqual 2

      this.userService.removeUserFromProject(
        this.defaultUser.osmProfile.id,
        project.id,
        Some(Grant.ROLE_ADMIN),
        User.superUser
      )

      this.userService
        .getUsersManagingProject(
          project.id,
          None,
          User.superUser
        )
        .size mustEqual 1
    }

    "not remove last admin from project" taggedAs UserTag in {
      val user =
        this.userService
          .create(this.getTestUser(195838622, "RemoveLastAdminFromProjectTest"), User.superUser)
      val project = this.serviceManager.project
        .create(
          Project(-1, user.osmProfile.id, "RemoveLastAdminFromProjectTestProject"),
          user
        )

      an[InvalidException] should be thrownBy this.userService.removeUserFromProject(
        user.id,
        project.id,
        Some(Grant.ROLE_ADMIN),
        User.superUser
      )
    }

    "get user mappers for challenge" in {
      val result = this.userService.getChallengeMappers(randomChallenge.id)

      result.size mustEqual 2
      result.head.id mustEqual randomUser.id
    }

    "complex query" in {
      val user                          = User.superUser
      val excludeOtherReviewers         = true
      val taskStatus: Option[List[Int]] = None

      val reviewTasksType = 1
      val fetchBy =
        if (reviewTasksType == 2) "task_review.reviewed_by" else "task_review.review_requested_by"

      val filter1 = Filter(
        List(
          FilterGroup(
            List(
              BaseParameter(fetchBy, user.id),
              FilterParameter.conditional(
                "t.status",
                taskStatus.getOrElse(List.empty),
                Operator.IN,
                taskStatus.nonEmpty
              ),
              FilterParameter.conditional(
                "task_review.review_requested_by",
                user.id,
                negate = true,
                includeOnlyIfTrue = true
              ),
              FilterParameter.conditional(
                "task_review.review_status",
                List(Task.REVIEW_STATUS_REQUESTED, Task.REVIEW_STATUS_DISPUTED),
                Operator.IN,
                includeOnlyIfTrue = reviewTasksType == 1
              )
            )
          ),
          FilterGroup(
            List(
              SubQueryFilter(
                "",
                Query.simple(
                  List(
                    BaseParameter("p.enabled", "", Operator.BOOL),
                    BaseParameter("c.enabled", "", Operator.BOOL)
                  ),
                  includeWhere = false
                ),
                operator = Operator.CUSTOM
              ),
              BaseParameter("p.owner_id", user.osmProfile.id),
              SubQueryFilter(
                user.osmProfile.id.toString,
                Query.simple(
                  List(
                    BaseParameter("ug.group_id", "g.id", useValueDirectly = true),
                    BaseParameter("g.project_id", "p.id")
                  )
                ),
                table = Some("")
              )
            ),
            OR(),
            true
          ),
          FilterGroup(
            List(
              BaseParameter("task_review.reviewed_by", "", Operator.NULL),
              BaseParameter("task_review.reviewed_by", user.id)
            ),
            OR()
          )
        )
      )

      filter1.sql()
    }
  }

  override implicit val projectTestName: String = "UserServiceSpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val (c, u) =
      UserServiceSpec.setup(
        this.challengeDAL,
        this.taskDAL,
        this.serviceManager,
        this.defaultProject.id,
        this.getTestChallenge,
        this.getTestTask,
        this.getTestUser
      )
    randomChallenge = c
    randomUser = u
  }
}

object UserServiceSpec {
  def setup(
      challengeDAL: ChallengeDAL,
      taskDAL: TaskDAL,
      serviceManager: ServiceManager,
      projectId: Long,
      challengeFunc: (String, Long) => Challenge,
      taskFunc: (String, Long) => Task,
      userFunc: (Long, String) => User
  ): (Challenge, User) = {
    val createdChallenge =
      challengeDAL.insert(challengeFunc("mChallenge", projectId), User.superUser)

    val task = taskDAL
      .insert(taskFunc("iamtask1", createdChallenge.id), User.superUser)
    val task2 = taskDAL
      .insert(taskFunc("iamtask2", createdChallenge.id), User.superUser)
    val task3 = taskDAL
      .insert(taskFunc("iamtask3", createdChallenge.id), User.superUser)

    val randomUser1 = serviceManager.user.create(
      userFunc(12345, "RandomOUser"),
      User.superUser
    )

    val randomUser2 = serviceManager.user.create(
      userFunc(12346, "RandomOUser2"),
      User.superUser
    )

    val randomUser3 = serviceManager.user.create(
      userFunc(12346, "RandomOUser3"),
      User.superUser
    )

    taskDAL.setTaskStatus(List(task), Task.STATUS_FIXED, randomUser1, Some(true))
    taskDAL.setTaskStatus(List(task2), Task.STATUS_FIXED, randomUser2, Some(true))
    taskDAL.setTaskStatus(List(task3), Task.STATUS_SKIPPED, randomUser3, Some(true))

    (createdChallenge, randomUser1)
  }
}

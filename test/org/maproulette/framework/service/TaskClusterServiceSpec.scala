/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import java.util.UUID
import play.api.libs.json._

import org.maproulette.session.{
  SearchParameters,
  SearchChallengeParameters,
  SearchTaskParameters,
  SearchLocation
}
import org.maproulette.framework.model._
import org.maproulette.framework.psql.Paging
import org.maproulette.framework.util.{TaskTag, FrameworkHelper}
import play.api.Application

import org.maproulette.models.dal.{ChallengeDAL, TaskDAL}

/**
  * @author krotstan
  */
class TaskClusterServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val service: TaskClusterService = this.serviceManager.taskCluster
  var randomChallenge: Challenge  = null
  var randomTask: Task            = null

  "TaskClusterService" should {
    "get task clusters" taggedAs (TaskTag) in {
      val response = this.service.getTaskClusters(
        SearchParameters(
          challengeParams = SearchChallengeParameters(
            challengeIds = Some(List(randomChallenge.id))
          )
        )
      )
      response.length mustEqual 1
      response.head.numberOfPoints mustEqual 3
    }

    "get tasks in cluster" taggedAs (TaskTag) in {
      val response = this.service.getTasksInCluster(
        0,
        SearchParameters(
          challengeParams = SearchChallengeParameters(
            challengeIds = Some(List(randomChallenge.id))
          )
        )
      )
      response.length mustEqual 3
    }

    "get tasks in bounding box excluding locked" taggedAs (TaskTag) in {
      var randomUser = serviceManager.user.create(
        this.getTestUser(7123456, "RandomOUser"),
        User.superUser
      )
      this.taskDAL.lockItem(randomUser, this.randomTask)

      val (count, response) = this.service.getTasksInBoundingBox(
        User.superUser,
        SearchParameters(
          location = Some(SearchLocation(-180, -85, 180, 85)),
          challengeParams = SearchChallengeParameters(
            challengeIds = Some(List(randomChallenge.id))
          )
        )
      )
      count mustEqual 2
      response.length mustEqual 2
    }

    "get tasks in bounding box including locked" taggedAs (TaskTag) in {
      val (count, response) = this.service.getTasksInBoundingBox(
        User.superUser,
        SearchParameters(
          location = Some(SearchLocation(-180, -85, 180, 85)),
          challengeParams = SearchChallengeParameters(
            challengeIds = Some(List(randomChallenge.id))
          )
        ),
        ignoreLocked = true
      )
      count mustEqual 3
      response.length mustEqual 3
    }

    "get tasks in bounding box with paging" taggedAs (TaskTag) in {
      val (count, response) = this.service.getTasksInBoundingBox(
        User.superUser,
        SearchParameters(
          location = Some(SearchLocation(-180, -85, 180, 85)),
          challengeParams = SearchChallengeParameters(
            challengeIds = Some(List(randomChallenge.id))
          )
        ),
        Paging(1, 0),
        ignoreLocked = true
      )
      count mustEqual 3
      response.length mustEqual 1
    }

    "get tasks in bounding box with ordering" taggedAs (TaskTag) in {
      val (count, response) = this.service.getTasksInBoundingBox(
        User.superUser,
        SearchParameters(
          location = Some(SearchLocation(-180, -85, 180, 85)),
          challengeParams = SearchChallengeParameters(
            challengeIds = Some(List(randomChallenge.id))
          )
        ),
        ignoreLocked = true,
        sort = "id",
        orderDirection = "DESC"
      )
      count mustEqual 3
      response.last.id mustEqual randomTask.id
    }
  }

  "get tasks in bounding box excluding task ids" taggedAs (TaskTag) in {
    val (count, response) = this.service.getTasksInBoundingBox(
      User.superUser,
      SearchParameters(
        location = Some(SearchLocation(-180, -85, 180, 85)),
        challengeParams = SearchChallengeParameters(
          challengeIds = Some(List(randomChallenge.id))
        ),
        taskParams = SearchTaskParameters(
          excludeTaskIds = Some(List(randomTask.id))
        )
      ),
      ignoreLocked = true
    )
    count mustEqual 2
    response.length mustEqual 2
    (response.head.id != randomTask.id) mustEqual true
    (response.last.id != randomTask.id) mustEqual true
  }

  override implicit val projectTestName: String = "TaskSpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val (c, t) =
      TaskClusterServiceSpec.setup(
        this.challengeDAL,
        this.taskDAL,
        this.serviceManager,
        this.defaultProject.id,
        this.getTestTask,
        this.getTestUser
      )
    randomChallenge = c
    randomTask = t
  }
}

object TaskClusterServiceSpec {
  def setup(
      challengeDAL: ChallengeDAL,
      taskDAL: TaskDAL,
      serviceManager: ServiceManager,
      projectId: Long,
      taskFunc: (String, Long) => Task,
      userFunc: (Long, String) => User
  ): (Challenge, Task) = {
    // Project and Challenge must be enabled for a normal reviewer to find a review task
    serviceManager.project.update(projectId, Json.obj("enabled" -> true), User.superUser)
    val clusterChallenge = challengeDAL
      .insert(
        Challenge(
          -1,
          "clusterChallenge",
          null,
          null,
          general = ChallengeGeneral(
            User.superUser.osmProfile.id,
            projectId,
            "TestChallengeInstruction",
            enabled = true
          ),
          creation = ChallengeCreation(),
          priority = ChallengePriority(),
          extra = ChallengeExtra()
        ),
        User.superUser
      )

    val task = taskDAL
      .insert(
        taskFunc(UUID.randomUUID().toString, clusterChallenge.id),
        User.superUser
      )
    var task2 = taskDAL
      .insert(
        taskFunc(UUID.randomUUID().toString, clusterChallenge.id),
        User.superUser
      )
    var task3 = taskDAL
      .insert(
        taskFunc(UUID.randomUUID().toString, clusterChallenge.id),
        User.superUser
      )

    (clusterChallenge, task)
  }
}

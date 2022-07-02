/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import java.util.UUID

import org.maproulette.framework.model._
import org.maproulette.framework.util.{TaskTag, FrameworkHelper}
import org.maproulette.exception.{InvalidException, NotFoundException}
import play.api.Application

import org.maproulette.models.dal.{ChallengeDAL, TaskDAL}

/**
  * @author krotstan
  */
class TaskBundleServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val service: TaskBundleService = this.serviceManager.taskBundle
  var challenge: Challenge       = null

  "TaskBundleService" should {
    "create a task Bundle" taggedAs (TaskTag) in {
      val task1 = taskDAL
        .insert(
          getTestTask(UUID.randomUUID().toString, challenge.id),
          User.superUser
        )
      var task2 = taskDAL
        .insert(
          getTestTask(UUID.randomUUID().toString, challenge.id),
          User.superUser
        )

      val response =
        this.service.createTaskBundle(User.superUser, "my bundle", List(task1.id, task2.id))
      response.taskIds.length mustEqual 2

      // tasks.bundle_id is NOT set until setTaskStatus is called!!!
      // This means you can add tasks to as many bundles as you want until
      // work has been completed on it.
      taskDAL.setTaskStatus(
        List(task1, task2),
        Task.STATUS_FIXED,
        User.superUser,
        bundleId = Some(response.bundleId),
        primaryTaskId = Some(task1.id)
      )

      // Cannot create a bundle with tasks already assigned
      intercept[InvalidException] {
        this.service.createTaskBundle(User.superUser, "my bundle again", List(task1.id, task2.id))
      }
    }

    "not create a task Bundle with no tasks" taggedAs (TaskTag) in {
      // Cannot create a bundle with no tasks
      intercept[InvalidException] {
        this.service.createTaskBundle(User.superUser, "my bundle again", List())
      }
    }

    "not create a task Bundle with tasks from different challenges " taggedAs (TaskTag) in {
      val task1 = taskDAL
        .insert(
          getTestTask(UUID.randomUUID().toString, challenge.id),
          User.superUser
        )
      var challenge2 = challengeDAL.insert(
        Challenge(
          -1,
          "bundleChallenge2",
          null,
          null,
          general = ChallengeGeneral(
            User.superUser.osmProfile.id,
            this.defaultProject.id,
            "TestChallengeInstruction"
          ),
          creation = ChallengeCreation(),
          priority = ChallengePriority(),
          extra = ChallengeExtra()
        ),
        User.superUser
      )
      var task2 = taskDAL
        .insert(
          getTestTask(UUID.randomUUID().toString, challenge2.id),
          User.superUser
        )

      // Cannot create a bundle with tasks from different challenges
      intercept[InvalidException] {
        this.service.createTaskBundle(User.superUser, "bad bundle", List(task1.id, task2.id))
      }
    }

    "not create a task Bundle with cooperative tasks" taggedAs (TaskTag) in {
      val task1 = taskDAL
        .insert(
          getTestTask(UUID.randomUUID().toString, challenge.id).copy(
            cooperativeWork = Some("{\"meta\": {\"version\": 1} }")
          ),
          User.superUser
        )

      // Cannot create a bundle with cooperative tasks
      intercept[InvalidException] {
        this.service.createTaskBundle(User.superUser, "bad bundle again", List(task1.id))
      }
    }

    "get a task Bundle" taggedAs (TaskTag) in {
      val task1 = taskDAL
        .insert(
          getTestTask(UUID.randomUUID().toString, challenge.id),
          User.superUser
        )
      var task2 = taskDAL
        .insert(
          getTestTask(UUID.randomUUID().toString, challenge.id),
          User.superUser
        )

      val bundle =
        this.service.createTaskBundle(User.superUser, "my bundle for get", List(task1.id, task2.id))

      val response = this.service.getTaskBundle(User.superUser, bundle.bundleId)
      response.bundleId mustEqual bundle.bundleId
      response.taskIds.length mustEqual 2
    }

    "delete a task bundle" taggedAs (TaskTag) in {
      val task1 = taskDAL
        .insert(
          getTestTask(UUID.randomUUID().toString, challenge.id),
          User.superUser
        )
      var task2 = taskDAL
        .insert(
          getTestTask(UUID.randomUUID().toString, challenge.id),
          User.superUser
        )

      val bundle = this.service
        .createTaskBundle(User.superUser, "my bundle for delete", List(task1.id, task2.id))

      // tasks.bundle_id is NOT set until setTaskStatus is called
      taskDAL.setTaskStatus(
        List(task1, task2),
        Task.STATUS_FIXED,
        User.superUser,
        bundleId = Some(bundle.bundleId),
        primaryTaskId = Some(task1.id)
      )

      this.service.deleteTaskBundle(User.superUser, bundle.bundleId)
      an[NotFoundException] should be thrownBy
        this.service.getTaskBundle(User.superUser, bundle.bundleId)
    }

    "delete a task bundle with permission check" taggedAs (TaskTag) in {
      val task1 = taskDAL
        .insert(
          getTestTask(UUID.randomUUID().toString, challenge.id),
          User.superUser
        )
      var task2 = taskDAL
        .insert(
          getTestTask(UUID.randomUUID().toString, challenge.id),
          User.superUser
        )

      val bundle = this.service
        .createTaskBundle(User.superUser, "my bundle for delete", List(task1.id, task2.id))

      // tasks.bundle_id is NOT set until setTaskStatus is called
      taskDAL.setTaskStatus(
        List(task1, task2),
        Task.STATUS_FIXED,
        User.superUser,
        bundleId = Some(bundle.bundleId),
        primaryTaskId = Some(task1.id)
      )

      val randomUser = serviceManager.user.create(
        this.getTestUser(1012345, "RandomOUser"),
        User.superUser
      )

      // Random user is not allowed to delete this bundle
      an[IllegalAccessException] should be thrownBy
        this.service.deleteTaskBundle(randomUser, bundle.bundleId)
    }

    "unbundle a task" taggedAs (TaskTag) in {
      val task1 = taskDAL
        .insert(
          getTestTask(UUID.randomUUID().toString, challenge.id),
          User.superUser
        )
      var task2 = taskDAL
        .insert(
          getTestTask(UUID.randomUUID().toString, challenge.id),
          User.superUser
        )

      val bundle = this.service
        .createTaskBundle(User.superUser, "my bundle for unbundle", List(task1.id, task2.id))

      // tasks.bundle_id is NOT set until setTaskStatus is called
      taskDAL.setTaskStatus(
        List(task1, task2),
        Task.STATUS_FIXED,
        User.superUser,
        bundleId = Some(bundle.bundleId),
        primaryTaskId = Some(task1.id)
      )

      this.service.unbundleTasks(User.superUser, bundle.bundleId, List(task2.id))
      val response = this.service.getTaskBundle(User.superUser, bundle.bundleId)
      response.taskIds.length mustEqual 1
      response.taskIds.head mustEqual task1.id
    }

    "unbundle a task with permission check" taggedAs (TaskTag) in {
      val task1 = taskDAL
        .insert(
          getTestTask(UUID.randomUUID().toString, challenge.id),
          User.superUser
        )
      var task2 = taskDAL
        .insert(
          getTestTask(UUID.randomUUID().toString, challenge.id),
          User.superUser
        )

      val bundle = this.service
        .createTaskBundle(User.superUser, "my bundle for unbundle", List(task1.id, task2.id))

      // tasks.bundle_id is NOT set until setTaskStatus is called
      taskDAL.setTaskStatus(
        List(task1, task2),
        Task.STATUS_FIXED,
        User.superUser,
        bundleId = Some(bundle.bundleId),
        primaryTaskId = Some(task1.id)
      )

      val randomUser = serviceManager.user.create(
        this.getTestUser(1022345, "RandomOUser2"),
        User.superUser
      )

      // Random user is not allowed to delete this bundle
      an[IllegalAccessException] should be thrownBy
        this.service.unbundleTasks(randomUser, bundle.bundleId, List(task2.id))
    }

  }

  override implicit val projectTestName: String = "TaskBundleSpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    challenge = TaskBundleServiceSpec.setup(
      this.challengeDAL,
      this.taskDAL,
      this.serviceManager,
      this.defaultProject.id,
      this.getTestTask,
      this.getTestUser
    )
  }
}

object TaskBundleServiceSpec {
  def setup(
      challengeDAL: ChallengeDAL,
      taskDAL: TaskDAL,
      serviceManager: ServiceManager,
      projectId: Long,
      taskFunc: (String, Long) => Task,
      userFunc: (Long, String) => User
  ): Challenge = {
    challengeDAL.insert(
      Challenge(
        -1,
        "bundleChallenge",
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
  }
}

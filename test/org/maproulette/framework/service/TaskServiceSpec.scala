/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import java.util.UUID
import play.api.libs.json._

import org.maproulette.framework.model._
import org.maproulette.framework.psql.Paging
import org.maproulette.framework.util.{TaskTag, FrameworkHelper}
import org.maproulette.exception.NotFoundException
import play.api.Application

/**
  * @author krotstan
  */
class TaskServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val service: TaskService = this.serviceManager.task

  "TaskService" should {
    "update completion responses" taggedAs (TaskTag) in {
      val testResponse = "{\"a\":1}"
      this.service.updateCompletionResponses(defaultTask.id, defaultUser, Json.parse(testResponse))
      val task = this.service.retrieve(defaultTask.id)
      task.get.completionResponses.get.toString mustEqual testResponse
    }

    "update completion responses again ok" taggedAs (TaskTag) in {
      val testResponse = "{\"a\":2,\"b\":\"yes\"}"
      this.service.updateCompletionResponses(defaultTask.id, defaultUser, Json.parse(testResponse))
      val task = this.service.retrieve(defaultTask.id)
      task.get.completionResponses.get.toString mustEqual testResponse
    }

    "Fail if task not found when update completion responses" taggedAs (TaskTag) in {
      val testResponse = "{\"a\":1}"
      intercept[NotFoundException] {
        this.service.updateCompletionResponses(-1010, defaultUser, Json.parse(testResponse))
      }
    }

    "retrieve list of tasks by id" taggedAs (TaskTag) in {
      val firstTask = this.taskDAL
        .insert(
          this.getTestTask(UUID.randomUUID().toString, this.defaultChallenge.id),
          User.superUser
        )
      val secondTask = this.taskDAL
        .insert(
          this.getTestTask(UUID.randomUUID().toString, this.defaultChallenge.id),
          User.superUser
        )

      val tasks = this.service.retrieveListById(List(firstTask.id, secondTask.id))
      tasks.size mustEqual 2
      List(firstTask.id, secondTask.id).contains(tasks.head.id) mustEqual true
      List(firstTask.id, secondTask.id).contains(tasks(1).id) mustEqual true
    }

    "honors paging when retrieving tasks by id" taggedAs (TaskTag) in {
      val firstTask = this.taskDAL
        .insert(
          this.getTestTask(UUID.randomUUID().toString, this.defaultChallenge.id),
          User.superUser
        )
      val secondTask = this.taskDAL
        .insert(
          this.getTestTask(UUID.randomUUID().toString, this.defaultChallenge.id),
          User.superUser
        )

      val tasks = this.service.retrieveListById(List(firstTask.id, secondTask.id), Paging(1, 0))
      tasks.size mustEqual 1
      List(firstTask.id, secondTask.id).contains(tasks.head.id) mustEqual true
    }

    "returns an empty list if no task ids given when retrieving tasks by id" taggedAs (TaskTag) in {
      this.service.retrieveListById(List.empty).isEmpty mustEqual true
    }
  }

  override implicit val projectTestName: String = "TaskSpecProject"
}

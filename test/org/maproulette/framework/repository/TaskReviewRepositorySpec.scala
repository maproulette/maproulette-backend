/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import org.maproulette.framework.model.User
import org.maproulette.framework.util.{TaskReviewTag, FrameworkHelper}
import play.api.Application

/**
  * @author krotstan
  */
class TaskReviewRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  val repository: TaskReviewRepository =
    this.application.injector.instanceOf(classOf[TaskReviewRepository])

  "TaskReviewRepository" should {
    "retrieve task with review" taggedAs TaskReviewTag in {
      val user = this.getTestUser(246810, "TaskReviewUser")
      val task =
        this.taskDAL.insert(this.getTestTask("xyzReview", this.defaultChallenge.id), User.superUser)
      this.taskDAL.setTaskStatus(List(task), 2, user, Some(true))

      val retrievedTask =
        this.repository.getTaskWithReview(task.id)

      retrievedTask.task.id mustEqual task.id
      retrievedTask.review.reviewStatus mustEqual Some(0)
    }
  }

  override implicit val projectTestName: String = "TaskReviewRepositorySpecProject"
}

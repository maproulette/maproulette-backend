/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import org.maproulette.framework.model.{Achievement, User, Task}
import org.maproulette.framework.util.{FrameworkHelper, UserTag}
import play.api.Application
import org.scalatestplus.mockito.MockitoSugar

/**
  * @author nrotstan
  */
class AchievementServiceSpec(implicit val application: Application)
    extends FrameworkHelper
    with MockitoSugar {
  val service: AchievementService = this.serviceManager.achievement

  var randomUser: User  = null
  var anotherUser: User = null

  "AchievementService" should {
    "award an achievement if user fixes their first task" taggedAs UserTag in {
      val task = this.serviceManager.task.retrieve(defaultTask.id).get
      val updatedUser =
        this.service.awardTaskCompletionAchievements(this.defaultUser, task, Task.STATUS_FIXED)

      updatedUser.get.achievements
        .getOrElse(List.empty)
        .contains(Achievement.FIXED_TASK) mustEqual true
    }

    "does not award an achievement if the task isn't fixed" taggedAs UserTag in {
      val task = this.serviceManager.task.retrieve(defaultTask.id).get
      val updatedUser =
        this.service
          .awardTaskCompletionAchievements(this.anotherUser, task, Task.STATUS_FALSE_POSITIVE)

      updatedUser.get.achievements
        .getOrElse(List.empty)
        .contains(Achievement.FIXED_TASK) mustEqual false
    }

    "award an achievement if user crosses points threshold" taggedAs UserTag in {
      val task = this.serviceManager.task.retrieve(defaultTask.id).get
      val user = this.defaultUser.copy(score = Some(100))
      val updatedUser =
        this.service.awardTaskCompletionAchievements(user, task, Task.STATUS_FIXED)

      updatedUser.get.achievements
        .getOrElse(List.empty)
        .contains(Achievement.POINTS_100) mustEqual true
    }

    "award an achievement if user reviews their first task" taggedAs UserTag in {
      val task = this.serviceManager.task.retrieve(defaultTask.id).get
      val updatedUser =
        this.service
          .awardTaskReviewAchievements(this.defaultUser, task, Task.REVIEW_STATUS_APPROVED)

      updatedUser.get.achievements
        .getOrElse(List.empty)
        .contains(Achievement.REVIEWED_TASK) mustEqual true
    }

    "does not award an achievement if the task isn't completed" taggedAs UserTag in {
      val task = this.serviceManager.task.retrieve(defaultTask.id).get
      val updatedUser =
        this.service
          .awardTaskCompletionAchievements(this.anotherUser, task, Task.REVIEW_STATUS_DISPUTED)

      updatedUser.get.achievements
        .getOrElse(List.empty)
        .contains(Achievement.REVIEWED_TASK) mustEqual false
    }
  }

  override implicit val projectTestName: String = "AchievementServiceSpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    anotherUser = this.serviceManager.user.create(
      this.getTestUser(22398765, "AnotherUser"),
      User.superUser
    )
  }
}

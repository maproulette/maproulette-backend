/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository


import org.maproulette.framework.model.{User, Task}
import org.maproulette.framework.util.{TaskTag, FrameworkHelper}
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.BaseParameter
import org.maproulette.session.SearchParameters
import play.api.Application

/**
  * @author krotstan
  */
class TaskClusterRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  val repository: TaskClusterRepository =
    this.application.injector.instanceOf(classOf[TaskClusterRepository])

  "TaskClusterRepository" should {
    "retrieve task clusters" taggedAs TaskTag in {
      val user = this.getTestUser(446812, "TaskUser")
      val task =
        this.taskDAL.insert(this.getTestTask("xyzReview", this.defaultChallenge.id), User.superUser)
      this.taskDAL.setTaskStatus(List(task), 2, user, Some(true))

      val query = Query.simple(
        List(
          BaseParameter(
            Task.FIELD_STATUS,
            2,
            table = Some("tasks")
          )
        )
      )

      val result = this.repository.queryTaskClusters(query, 10, SearchParameters())
      result.length mustEqual 1
    }
  }

  override implicit val projectTestName: String = "TaskClusterRepositorySpecProject"
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import play.api.libs.json._

import org.maproulette.exception.NotFoundException
import org.maproulette.framework.model._
import org.maproulette.framework.psql._
import org.maproulette.framework.repository.TaskRepository
import org.maproulette.models.dal.TaskDAL

/**
  * Service layer for Task
  *
  * @author krotstan
  */
@Singleton
class TaskService @Inject() (repository: TaskRepository, taskDAL: TaskDAL) {

  /**
    * Retrieves an object of that type
    *
    * @param id The identifier for the object
    * @return An optional object, None if not found
    */
  def retrieve(id: Long): Option[Task] = this.taskDAL.retrieveById(id)

  /**
    * Retrieve tasks matching the given ids
    *
    * @param ids    The ids of the tasks to retrieve
    * @param paging The page of results to retrieve, defaults to all
    */
  def retrieveListById(ids: List[Long], paging: Paging = Paging()): List[Task] = {
    this.taskDAL.retrieveListById(
      if (paging.limit < 1) -1 else paging.limit,
      if (paging.limit < 1) 0 else paging.limit * paging.page
    )(ids)
  }

  /**
    * Updates task completiong responses
    *
    * @param duration - age of task reviews to treat as 'expired'
    * @return The number of taskReviews that were expired
    */
  def updateCompletionResponses(taskId: Long, user: User, completionResponses: JsValue): Unit = {
    val task: Task = this.retrieve(taskId) match {
      case Some(t) => t
      case None =>
        throw new NotFoundException(
          s"Task with $taskId not found, cannot update completion responses."
        )
    }

    this.repository.updateCompletionResponses(task, user, completionResponses)
    this.taskDAL.cacheManager.withOptionCaching { () =>
      Some(task.copy(completionResponses = Some(completionResponses.toString())))
    }
  }

  /**
    * Retrieve a task attachment identified by attachmentId
    *
    * @param taskId       The id of the task with the attachment
    * @param attachmentId The id of the attachment
    */
  def getTaskAttachment(taskId: Long, attachmentId: String): Option[JsObject] =
    this.repository.getTaskAttachment(taskId, attachmentId)
}

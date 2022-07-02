/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model.{ArchivableTask, TaskLogEntry, TaskReview}
import org.maproulette.framework.repository.TaskHistoryRepository
import org.maproulette.permissions.Permission
import org.maproulette.data.Actions
import org.maproulette.provider.websockets.WebSocketProvider

/**
  * Service layer for TaskHistory
  *
  * @author krotstan
  */
@Singleton
class TaskHistoryService @Inject() (
    repository: TaskHistoryRepository,
    serviceManager: ServiceManager,
    permission: Permission,
    webSocketProvider: WebSocketProvider
) {

  /**
    * Returns a history log for the task -- includes comments, status actions,
    * review actions
    * @param taskId
    * @return List of TaskLogEntry
    */
  def getTaskHistoryLog(taskId: Long): List[TaskLogEntry] = {
    val comments      = repository.getComments(taskId)
    val reviews       = repository.getReviews(taskId)
    val statusActions = repository.getStatusActions(taskId)
    val actions       = repository.getActions(taskId, Actions.ACTION_TYPE_UPDATED)

    ((comments ++ reviews ++ statusActions ++ actions).sortWith(sortByDate)).reverse
  }

  /**
    * Returns a review history log for a list of tasks
    * @param tasks
    * @return List of TaskLogEntry
    */
  def getTaskReviewHistory(tasks: List[ArchivableTask]): List[TaskReview] = {
    var reviews: List[TaskReview] = List();
    tasks.foreach(task => {
      val taskHistory = repository.getReviewLogs(task.id)
      reviews = reviews.concat(taskHistory)
    })

    reviews
  }

  private def sortByDate(entry1: TaskLogEntry, entry2: TaskLogEntry) = {
    entry1.timestamp.getMillis() < entry2.timestamp.getMillis()
  }
}

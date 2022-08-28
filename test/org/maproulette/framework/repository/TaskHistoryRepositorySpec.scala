/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import org.maproulette.data.{Actions, TaskItem, ActionManager, TaskStatusSet}
import org.maproulette.framework.model.{User, TaskLogEntry, Task}
import org.maproulette.framework.util.{TaskTag, FrameworkHelper}
import play.api.Application

/**
  * @author krotstan
  */
class TaskHistoryRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  val repository: TaskHistoryRepository =
    this.application.injector.instanceOf(classOf[TaskHistoryRepository])
  val commentRepository: CommentRepository =
    this.application.injector.instanceOf(classOf[CommentRepository])
  val actionManager: ActionManager =
    this.application.injector.instanceOf(classOf[ActionManager])

  "TaskHistoryRepository" should {
    "getComments" taggedAs TaskTag in {
      val task = this.taskDAL
        .insert(this.getTestTask("xyzTaskForComments", this.defaultChallenge.id), User.superUser)
      this.commentRepository.create(User.superUser, task.id, "my comment", None)

      val taskLogs = this.repository.getComments(task.id)
      taskLogs.head.taskId mustEqual task.id
      taskLogs.head.comment.get mustEqual "my comment"
      taskLogs.head.user.get mustEqual User.superUser.id
      taskLogs.head.actionType mustEqual TaskLogEntry.ACTION_COMMENT
    }

    "getReviews" taggedAs TaskTag in {
      var task =
        this.taskDAL
          .insert(this.getTestTask("xyzTaskForReviews", this.defaultChallenge.id), User.superUser)
      this.taskDAL.setTaskStatus(List(task), 2, User.superUser, Some(true))
      task = this.serviceManager.task.retrieve(task.id).get
      this.serviceManager.taskReview.setTaskReviewStatus(
        task,
        Task.REVIEW_STATUS_APPROVED,
        User.superUser,
        None
      )
      task = this.serviceManager.task.retrieve(task.id).get

      var taskLogs = this.repository.getReviews(task.id)

      // First review is review request
      taskLogs.head.taskId mustEqual task.id
      taskLogs.head.reviewRequestedBy.get mustEqual User.superUser.id
      taskLogs.head.reviewedBy mustEqual None
      taskLogs.head.reviewStatus.get mustEqual Task.REVIEW_STATUS_REQUESTED
      taskLogs.head.actionType mustEqual TaskLogEntry.ACTION_REVIEW

      // Second review is the actual review to approve
      taskLogs.last.taskId mustEqual task.id
      taskLogs.last.reviewRequestedBy.get mustEqual User.superUser.id
      taskLogs.last.reviewedBy.get mustEqual User.superUser.id
      taskLogs.last.reviewStatus.get mustEqual Task.REVIEW_STATUS_APPROVED
      taskLogs.last.actionType mustEqual TaskLogEntry.ACTION_REVIEW

      this.serviceManager.taskReview.setMetaReviewStatus(
        task,
        Task.REVIEW_STATUS_APPROVED,
        User.superUser,
        None
      )

      taskLogs = this.repository.getReviews(task.id)
      // Last is meta review
      taskLogs.last.taskId mustEqual task.id
      taskLogs.last.reviewRequestedBy.get mustEqual User.superUser.id
      taskLogs.last.reviewedBy.get mustEqual User.superUser.id
      taskLogs.last.reviewStatus.get mustEqual Task.REVIEW_STATUS_APPROVED
      taskLogs.last.actionType mustEqual TaskLogEntry.ACTION_META_REVIEW
    }
  }

  "getStatusActions" taggedAs TaskTag in {
    val task = this.taskDAL
      .insert(this.getTestTask("xyzTaskForStatusActions", this.defaultChallenge.id), User.superUser)
    this.taskDAL.setTaskStatus(List(task), 3, User.superUser, Some(true))

    val taskLogs = this.repository.getStatusActions(task.id)
    taskLogs.head.taskId mustEqual task.id
    taskLogs.head.user.get mustEqual User.superUser.id
    taskLogs.head.actionType mustEqual TaskLogEntry.ACTION_STATUS_CHANGE
    taskLogs.head.oldStatus.get mustEqual 0
    taskLogs.head.status.get mustEqual 3
  }

  "getActions" taggedAs TaskTag in {
    val task = this.taskDAL
      .insert(this.getTestTask("xyzTaskForStatusActions", this.defaultChallenge.id), User.superUser)
    this.actionManager.setAction(
      Some(User.superUser),
      new TaskItem(task.id),
      TaskStatusSet(1),
      task.name
    )

    val taskLogs = this.repository.getActions(task.id, Actions.ACTION_TYPE_TASK_STATUS_SET)
    taskLogs.head.taskId mustEqual task.id
    taskLogs.head.user.get mustEqual User.superUser.id
    taskLogs.head.actionType mustEqual TaskLogEntry.ACTION_UPDATE
  }

  override implicit val projectTestName: String = "TaskHistoryRepositorySpecProject"
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.FiniteDuration
import org.joda.time.DateTime

import org.maproulette.framework.model._
import org.maproulette.framework.psql.{Query, _}
import org.maproulette.framework.psql.filter.{BaseParameter, _}
import org.maproulette.framework.repository.{
  TaskReviewRepository,
  TaskRepository,
  TaskClusterRepository
}
import org.maproulette.framework.mixins.ReviewSearchMixin
import org.maproulette.exception.InvalidException
import org.maproulette.session.SearchParameters
import org.maproulette.permissions.Permission
import org.maproulette.provider.websockets.{WebSocketMessages, WebSocketProvider}
import org.maproulette.data.{ChallengeType, Actions}

/**
  * Service layer for TaskReview
  *
  * @author krotstan
  */
@Singleton
class TaskReviewService @Inject() (
    repository: TaskReviewRepository,
    serviceManager: ServiceManager,
    taskRepository: TaskRepository,
    taskClusterRepository: TaskClusterRepository,
    permission: Permission,
    webSocketProvider: WebSocketProvider
) extends ReviewSearchMixin {

  /**
    * Gets a Task object with review data
    *
    * @param taskId
    */
  def getTaskWithReview(taskId: Long): TaskWithReview = {
    this.repository.getTaskWithReview(taskId)
  }

  /**
    * Gets and claims a task for review.
    *
    * @param user        The user executing the request
    * @param primaryTask id of task that you wish to start/claim
    * @return task
    */
  def startTaskReview(user: User, primaryTask: Task): Option[Task] = {
    if (primaryTask.review.reviewClaimedBy.getOrElse(null) != null &&
        primaryTask.review.reviewClaimedBy.getOrElse(null) != user.id.toLong) {
      throw new InvalidException("This task is already being reviewed by someone else.")
    }

    // Each task in bundle must be claimed
    var taskList = List(primaryTask)
    if (primaryTask.isBundlePrimary.getOrElse(false)) {
      primaryTask.bundleId match {
        case Some(bId) =>
          this.serviceManager.taskBundle.getTaskBundle(user, bId).tasks match {
            case Some(tList) =>
              taskList = tList
            case None => // do nothing -- just use our current task
          }
        case None => // no bundle id, do nothing
      }
    }

    this.repository.claimTaskReview(taskList, user)
    val updatedTask = this.getTaskWithReview(primaryTask.id)

    webSocketProvider.sendMessage(
      WebSocketMessages.reviewClaimed(
        WebSocketMessages.ReviewData(updatedTask)
      )
    )

    Option(updatedTask.task)
  }

  /**
    * Releases a claim on a task for review.
    *
    * @param user The user executing the request
    * @param task id of task that you wish to release
    * @return task
    */
  def cancelTaskReview(user: User, task: Task): Option[Task] = {
    if (task.review.reviewClaimedBy.getOrElse(null) != user.id.toLong) {
      throw new InvalidException("This task is not currently being reviewed by you.")
    }
    // Unclaim bundle?????
    this.repository.unclaimTaskReview(task, user)

    val updatedTask = this.getTaskWithReview(task.id)

    webSocketProvider.sendMessage(
      WebSocketMessages.reviewUpdate(
        WebSocketMessages.ReviewData(updatedTask)
      )
    )

    Option(updatedTask.task)
  }

  /**
    * Marks expired taskReviews as unnecessary.
    *
    * @param duration - age of task reviews to treat as 'expired'
    * @return The number of taskReviews that were expired
    */
  def expireTaskReviews(duration: FiniteDuration): Int = {
    this.repository.expireTaskReviews(duration)
  }

  /**
    * Gets and claims the next task for review.
    *
    * @param user The user executing the request
    * @param searchParameters
    * @param onlySaved
    * @param sort
    * @param order
    * @param lastTaskId
    * @param excludeOtherReviewers
    * @return task
    */
  def nextTaskReview(
      user: User,
      searchParameters: SearchParameters,
      onlySaved: Boolean = false,
      sort: String,
      order: String,
      lastTaskId: Option[Long] = None,
      excludeOtherReviewers: Boolean = false,
      asMetaReview: Boolean = false
  ): Option[Task] = {
    // If User is not a reviewer, then there is no next task to review
    if (!user.settings.isReviewer.getOrElse(false) && !permission.isSuperUser(user)) {
      return None
    }

    val params = copyParamsForMetaReview(asMetaReview, searchParameters)

    val position = lastTaskId match {
      case Some(taskId) => {
        // Queries for a map of (taskId -> row position)
        val rowMap = this.repository.queryTasksWithRowNumber(
          getReviewRequestedQueries(
            user,
            params,
            onlySaved,
            Paging(-1, 0),
            sort,
            order,
            false,
            excludeOtherReviewers,
            asMetaReview
          ),
          taskId
        )
        rowMap.get(taskId)

        // If our task id is in the row map, we have a position for it
        rowMap.get(taskId) match {
          case Some(row) => row
          case _         => 0
        }
      }
      case _ => 0
    }

    this.repository
      .queryTasks(
        getReviewRequestedQueries(
          user,
          params,
          onlySaved,
          Paging(1, position),
          sort,
          order,
          false,
          excludeOtherReviewers,
          asMetaReview
        ),
        params
      )
      .headOption
  }

  /**
    * Gets a list of tasks that have requested review (and are in this user's project group)
    *
    * @param user            The user executing the request
    * @param limit           The number of tasks to return
    * @param offset          Offset to start paging
    * @param sort            Sort column
    * @param order           DESC or ASC
    * @param includeDisputed Whether disputed tasks whould be returned in results (default is true)
    * @return A list of tasks
    */
  def getReviewRequestedTasks(
      user: User,
      searchParameters: SearchParameters,
      onlySaved: Boolean = false,
      paging: Paging = Paging(-1, 0),
      sort: String,
      order: String,
      includeDisputed: Boolean = true,
      excludeOtherReviewers: Boolean = false
  ): (Int, List[Task]) = {
    // If User is not a reviewer, then there is no next task to review
    if (!user.settings.isReviewer.getOrElse(false) && !permission.isSuperUser(user)) {
      return (0, List[Task]())
    }

    val query =
      getReviewRequestedQueries(
        user,
        searchParameters,
        onlySaved,
        paging,
        sort,
        order,
        includeDisputed,
        excludeOtherReviewers,
        false
      )

    // Let's copy into our search params that we are searching only by
    // reviewRequested and Contested task reviews. The repository can
    // then use this information to help performance of the queries by
    // pre-filtering task_review table with a WITH () clause.
    val params = searchParameters.taskParams.taskReviewStatus match {
      case Some(rs) => searchParameters
      case None =>
        searchParameters.copy(
          taskParams = searchParameters.taskParams.copy(
            taskReviewStatus = Some(
              List(Task.REVIEW_STATUS_REQUESTED, Task.REVIEW_STATUS_DISPUTED)
            )
          )
        )
    }

    (this.repository.queryTaskCount(query, params), this.repository.queryTasks(query, params))
  }

  /**
    * Retrieve tasks geographically closest to the given task id wit the
    * given set of search parameters.
    */
  def getNearbyReviewTasks(
      user: User,
      searchParameters: SearchParameters,
      proximityId: Long,
      limit: Int = 5,
      excludeOtherReviewers: Boolean = false,
      onlySaved: Boolean = false
  ): List[Task] = {
    val order = Order(
      List(
        OrderField(
          s"""
            ST_Distance(tasks.location, (SELECT location FROM tasks WHERE id = $proximityId)),
            tasks.status, RANDOM()
          """,
          table = Some(""),
          isColumn = false
        )
      )
    )

    var query = this.setupReviewSearchClause(
      Query.simple(List(), order = order, paging = Paging(limit, 0)),
      user,
      permission,
      searchParameters,
      REVIEW_REQUESTED_TASKS,
      true,
      onlySaved,
      excludeOtherReviewers
    )

    query = addClaimedByFilter(query, user.id)
    query = addLockedFilter(query)
    query = query.addFilterGroup(
      FilterGroup(
        List(
          BaseParameter(
            Task.FIELD_ID,
            proximityId,
            Operator.NE,
            table = Some("tasks")
          )
        )
      )
    )

    this.repository.queryTasksWithLocked(query)
  }

  /**
    * Gets a list of tasks that have been reviewed (either by this user or requested by this user)
    *
    * @param user              The user executing the request
    * @param allowReviewNeeded Whether we should include review requested tasks as well
    * @param limit             The amount of tasks to be returned
    * @param offset            Offset to start paging
    * @param sort              Column to sort
    * @param order             DESC or ASC
    * @return A list of tasks
    */
  def getReviewedTasks(
      user: User,
      searchParameters: SearchParameters,
      allowReviewNeeded: Boolean = false,
      limit: Int = -1,
      offset: Int = 0,
      sort: String,
      order: String,
      asMetaReview: Boolean = false
  ): (Int, List[Task]) = {
    // If User is not a reviewer only allow if not searching for this user's
    // mapped tasks which have been reviewed.
    if (!user.settings.isReviewer.getOrElse(false) && !permission.isSuperUser(user)) {
      if (searchParameters.reviewParams.mappers != Some(List(user.id))) {
        return (0, List[Task]())
      }
    }

    // If this is as meta review than we need to limit by task review status to those
    // tasks that have all ready been review approved.
    val params = copyParamsForMetaReview(asMetaReview, searchParameters)

    var query = setupReviewSearchClause(
      Query.simple(List(), order = buildOrdering(sort, order), paging = Paging(limit, offset)),
      user,
      permission,
      params,
      if (allowReviewNeeded) ALL_REVIEWED_TASKS else MY_REVIEWED_TASKS
    )

    if (asMetaReview) {
      query = addClaimedByFilter(query, user.id)
    }

    (this.repository.queryTaskCount(query, params), this.repository.queryTasks(query, params))
  }

  /**
    * Retrieves task clusters for review criteria
    *
    * @param user
    * @param reviewTasksType
    * @param params         SearchParameters used to filter the tasks in the cluster
    * @param numberOfPoints Number of cluster points to group all the tasks by
    * @param c              an implicit connection
    * @return A list of task clusters
    */
  def getReviewTaskClusters(
      user: User,
      reviewTasksType: Int,
      searchParameters: SearchParameters,
      numberOfPoints: Int = this.taskClusterRepository.DEFAULT_NUMBER_OF_POINTS,
      onlySaved: Boolean = false,
      excludeOtherReviewers: Boolean = false
  ): List[TaskCluster] = {
    val params = copyParamsForMetaReview(reviewTasksType == META_REVIEW_TASKS, searchParameters)

    var query = setupReviewSearchClause(
      Query.simple(List()),
      user,
      permission,
      params,
      reviewTasksType,
      true,
      onlySaved,
      excludeOtherReviewers
    )

    if (reviewTasksType == META_REVIEW_TASKS) {
      query = addClaimedByFilter(query, user.id)
    }

    val reviewField = reviewTasksType match {
      case MY_REVIEWED_TASKS    => TaskReview.FIELD_REVIEW_REQUESTED_BY
      case REVIEWED_TASKS_BY_ME => TaskReview.FIELD_REVIEWED_BY
      case _                    => null
    }

    if (reviewField != null) {
      query = query.addFilterGroup(
        FilterGroup(
          List(
            BaseParameter(
              reviewField,
              user.id,
              Operator.EQ,
              table = Some(TaskReview.TABLE)
            )
          )
        )
      )
    }

    this.taskClusterRepository.queryTaskClusters(query, numberOfPoints, params)
  }

  /**
    * Sets the review status for a task. The user cannot set the status of a task unless the object has
    * been locked by the same user before hand.
    * Will throw an InvalidException if the task review status cannot be set due to the current review status
    * Will throw an IllegalAccessException if the user is a not a reviewer, or if the task is locked by
    * a different user.
    *
    * @param task         The task to set the status for
    * @param reviewStatus The review status to set
    * @param user         The user setting the status
    * @return The number of rows updated, should only ever be 1
    */
  def setTaskReviewStatus(
      task: Task,
      reviewStatus: Int,
      user: User,
      actionId: Option[Long],
      commentContent: String = "",
      errorTags: String = "",
      notify: Boolean = true
  ): Int = {
    if (!permission.isSuperUser(user) && !user.settings.isReviewer.get && reviewStatus != Task.REVIEW_STATUS_REQUESTED &&
        reviewStatus != Task.REVIEW_STATUS_DISPUTED && reviewStatus != Task.REVIEW_STATUS_UNNECESSARY) {
      throw new IllegalAccessException("User must be a reviewer to edit task review status.")
    } else if (reviewStatus == Task.REVIEW_STATUS_UNNECESSARY) {
      if (task.review.reviewStatus.getOrElse(0) != Task.REVIEW_STATUS_REQUESTED) {
        // We should not update review status to Unnecessary unless the review status is requested
        return 0
      }

      this.permission.hasWriteAccess(ChallengeType(), user)(task.parent)
    }

    var fetchBy = "reviewed_by"

    val isDisputed = task.review.reviewStatus.getOrElse(-1) != Task.REVIEW_STATUS_DISPUTED &&
      reviewStatus == Task.REVIEW_STATUS_DISPUTED
    val needsReReview = (task.review.reviewStatus.getOrElse(-1) != Task.REVIEW_STATUS_REQUESTED &&
      reviewStatus == Task.REVIEW_STATUS_REQUESTED) || isDisputed

    // We need to mark this task as needing to be meta reviewed again if
    // the initial meta review was rejected and this review doesn't need a re-review first
    val needsMetaReviewAgain = task.review.metaReviewStatus
      .getOrElse(-1) == Task.REVIEW_STATUS_REJECTED &&
      !needsReReview

    var reviewedBy          = task.review.reviewedBy
    var reviewRequestedBy   = task.review.reviewRequestedBy
    var additionalReviewers = task.review.additionalReviewers

    // Make sure we have an updated claimed at time.
    val reviewClaimedAt = this.getTaskWithReview(task.id).task.review.reviewClaimedAt

    // If the original reviewer is not the same as the user asking for this
    // review status change than we have a "meta-review" situation. Let's leave
    // the original reviewer as the reviewedBy on the task. The user will
    // still be noted as a reviewer in the task_review_history
    val originalReviewer =
      if (reviewedBy != None && reviewedBy.get != user.id) {
        if (!needsReReview) {
          // Add reviewer to the additionalReviewers if not the original
          if (additionalReviewers == None) {
            additionalReviewers = Some(List())
          }
          if (!additionalReviewers.contains(user.id) && !needsReReview) {
            additionalReviewers = Some(additionalReviewers.get :+ user.id)
          }
        }
        reviewedBy
      } else Some(user.id)

    // If we are changing the status back to "needsReview" then this task
    // has been fixed by the mapper and the mapper is requesting review again
    val fetchByUser =
      if (needsReReview) {
        fetchBy = "review_requested_by"
        reviewRequestedBy = Some(user.id)
        user.id
      } else {
        reviewedBy = Some(user.id)
        originalReviewer.get
      }

    val updatedRows = this.repository.updateTaskReview(
      user,
      task,
      reviewStatus,
      fetchBy,
      fetchByUser,
      additionalReviewers,
      if (needsMetaReviewAgain) Some(Task.REVIEW_STATUS_REQUESTED) else None,
      errorTags
    )

    webSocketProvider.sendMessage(
      WebSocketMessages.reviewUpdate(
        WebSocketMessages.ReviewData(this.getTaskWithReview(task.id))
      )
    )

    val comment = commentContent.nonEmpty match {
      case true =>
        Some(this.serviceManager.comment.create(user, task.id, commentContent, actionId, notify))
      case false => None
    }

    // Don't send a notification for every task in a task bundle, only the
    // primary task
    if (task.bundleId.isEmpty || task.isBundlePrimary.getOrElse(false)) {
      if (!isDisputed) {
        if (needsReReview) {
          // Let's note in the task_review_history table that this task needs review again
          this.repository.insertTaskReviewHistory(
            task,
            user.id,
            task.review.reviewedBy.get,
            None,
            reviewStatus,
            null
          )

          this.serviceManager.notification.createReviewNotification(
            user,
            task.review.reviewedBy.getOrElse(-1),
            reviewStatus,
            task,
            comment,
            errorTags = errorTags
          )
        } else {
          // Let's note in the task_review_history table that this task was reviewed
          // and also who the original reviewer was (assuming we have one)
          this.repository.insertTaskReviewHistory(
            task,
            task.review.reviewRequestedBy.get,
            user.id,
            if (originalReviewer.getOrElse(0) != user.id) Some(originalReviewer.get)
            else None,
            reviewStatus,
            reviewClaimedAt.getOrElse(null),
            errorTags
          )

          if (reviewStatus != Task.REVIEW_STATUS_UNNECESSARY) {
            // Only if the review status changes should we send a message
            if (task.review.reviewStatus.get != reviewStatus) {
              this.serviceManager.notification.createReviewNotification(
                user,
                task.review.reviewRequestedBy.getOrElse(-1),
                reviewStatus,
                task,
                comment,
                errorTags = errorTags
              )
            }

            if (needsMetaReviewAgain) {
              // Let the meta reviewer know that they need to meta review this task again
              this.serviceManager.notification.createReviewRevisedNotification(
                user,
                task.review.metaReviewedBy.get,
                Task.REVIEW_STATUS_REQUESTED,
                task,
                comment,
                true
              )
            }

            // Let's let the original reviewer know that the review status
            // has been changed.
            if (originalReviewer.get != reviewedBy.get) {
              this.serviceManager.notification.createReviewRevisedNotification(
                user,
                originalReviewer.get,
                reviewStatus,
                task,
                comment
              )
            }
          }
        }
      } else {
        // For disputed tasks.
        this.repository.insertTaskReviewHistory(
          task,
          user.id,
          task.review.reviewedBy.get,
          None,
          reviewStatus,
          null
        )
      }
    }

    this.taskRepository.cacheManager.withOptionCaching { () =>
      Some(
        task.copy(review = task.review.copy(
          reviewStatus = Some(reviewStatus),
          reviewRequestedBy = reviewRequestedBy,
          reviewedBy = reviewedBy,
          reviewedAt = Some(new DateTime()),
          reviewClaimedBy = None,
          reviewClaimedAt = None,
          metaReviewStatus =
            if (needsMetaReviewAgain) Some(Task.REVIEW_STATUS_REQUESTED)
            else task.review.metaReviewStatus
        )
        )
      )
    }

    if (reviewStatus != Task.REVIEW_STATUS_UNNECESSARY) {
      if (!needsReReview) {
        var reviewStartTime: Option[Long] = None
        task.review.reviewClaimedAt match {
          case Some(t) =>
            reviewStartTime = Some(t.getMillis())
          case None => // do nothing
        }

        this.serviceManager.userMetrics.updateUserScore(
          None,
          None,
          Option(reviewStatus),
          task.review.reviewedBy != None,
          false,
          None,
          task.review.reviewRequestedBy.get
        )
        this.serviceManager.userMetrics.updateUserScore(
          None,
          None,
          Option(reviewStatus),
          false,
          true,
          reviewStartTime,
          user.id
        )
      } else if (reviewStatus == Task.REVIEW_STATUS_DISPUTED) {
        this.serviceManager.userMetrics.updateUserScore(
          None,
          None,
          Option(reviewStatus),
          true,
          true,
          None,
          task.review.reviewedBy.get
        )
        this.serviceManager.userMetrics.updateUserScore(
          None,
          None,
          Option(reviewStatus),
          true,
          false,
          None,
          task.review.reviewRequestedBy.get
        )
      }
    }

    if (reviewStatus == Task.REVIEW_STATUS_APPROVED ||
        reviewStatus == Task.REVIEW_STATUS_APPROVED_WITH_REVISIONS ||
        reviewStatus == Task.REVIEW_STATUS_APPROVED_WITH_FIXES_AFTER_REVISIONS ||
        reviewStatus == Task.REVIEW_STATUS_REJECTED ||
        reviewStatus == Task.REVIEW_STATUS_ASSISTED) {
      this.serviceManager.achievement.awardTaskReviewAchievements(user, task, reviewStatus)
    }

    updatedRows
  }

  /**
    * Sets the Meta Review Status
    *
    * @param task         The task to set the status for
    * @param reviewStatus The review status to set
    * @param user         The user setting the meta review status
    * @return The number of rows updated, should only ever be 1
    */
  def setMetaReviewStatus(
      task: Task,
      reviewStatus: Int,
      user: User,
      actionId: Option[Long],
      commentContent: String = "",
      errorTags: String = ""
  ): Int = {
    if (task.review.reviewStatus == None) {
      // A meta reviewer cannot review a task that has not been reviewed yet.
      throw new InvalidException(
        "Unable to set meta review status on a task that has not been reviewed." +
          "meta reviewer has not initially reviewed this task yet."
      )
    }
    // 1. The meta reviewer must be a reviewer to set the meta review status.
    // 2. Reviewer cannot meta review their own reviews
    // 3. Reviewer may set meta review status back to 'requested'
    // 4. Super users can do anything
    // 5. Only challenge admins can mark meta review status as unnecessary
    if (!permission.isSuperUser(user)) {
      if (!user.settings.isReviewer.get) {
        throw new IllegalAccessException(
          "User must be a reviewer to change meta task review status."
        )
      }

      if (reviewStatus == Task.REVIEW_STATUS_DISPUTED) {
        // Disputed is not supported as a valid meta review status
        throw new InvalidException("Disputed is not supported as a valid meta review status.")
      }
      if (reviewStatus == Task.REVIEW_STATUS_UNNECESSARY) {
        // Only challenge admins can set status to unnecessary
        this.permission.hasWriteAccess(ChallengeType(), user)(task.parent)
      } else if (reviewStatus == Task.REVIEW_STATUS_REQUESTED) {
        if (task.review.reviewedBy.getOrElse(-1) != user.id) {
          throw new IllegalAccessException(
            "Only the original reviewer can request another meta-review on this task."
          )
        } else if (task.review.metaReviewedBy == None) {
          // A meta reviewer must have already completed a review to request a meta review again
          throw new InvalidException(
            "Unable to set meta review status to 'requested' as a " +
              "meta reviewer has not initially reviewed this task yet."
          )
        }
      } else {
        // cannot not meta review your own work
        if (task.review.reviewedBy.getOrElse(-1) == user.id) {
          throw new IllegalAccessException(
            "Reviewers cannot change the meta-review status on a task they reviewed."
          )
        }
      }
    }

    // Make sure we have an updated claimed at time.
    val reviewClaimedAt = this.getTaskWithReview(task.id).task.review.reviewClaimedAt

    val metaReviewer = task.review.metaReviewedBy match {
      case Some(m) => m
      case None    => user.id
    }

    // Update the meta_review_by and meta_review_status column on the task_review
    // if error tags are currently on the review, and if this meta review provides no error tags, retain the existing tags
    val updatedRows = this.repository.updateTaskReview(
      user,
      task,
      task.review.reviewStatus.get,
      "meta_reviewed_by",
      metaReviewer,
      task.review.additionalReviewers,
      Some(reviewStatus),
      errorTags = if (errorTags.isEmpty) task.errorTags else errorTags
    )

    // Notify the Task Review has been updated
    webSocketProvider.sendMessage(
      WebSocketMessages.reviewUpdate(
        WebSocketMessages.ReviewData(this.getTaskWithReview(task.id))
      )
    )

    val comment = commentContent.nonEmpty match {
      case true =>
        Some(this.serviceManager.comment.create(user, task.id, commentContent, actionId))
      case false => None
    }

    // Don't send a notification for every task in a task bundle, only the
    // primary task
    if (task.bundleId.isEmpty || task.isBundlePrimary.getOrElse(false)) {
      // Let's note in the task_review_history table that this task was meta reviewed (or requested)
      this.repository.insertMetaTaskReviewHistory(
        task,
        if (metaReviewer != user.id) Some(task.review.reviewedBy.get) else None,
        metaReviewer,
        reviewStatus,
        reviewClaimedAt.getOrElse(null),
        errorTags = errorTags
      )

      if (reviewStatus == Task.REVIEW_STATUS_REQUESTED) {
        // Let the meta reviewer know that they need to meta review this task again
        this.serviceManager.notification.createReviewRevisedNotification(
          user,
          metaReviewer,
          reviewStatus,
          task,
          comment,
          true
        )
      } else if (reviewStatus != Task.REVIEW_STATUS_UNNECESSARY) {
        // Let reviewer know their task has been meta reviewed
        this.serviceManager.notification.createReviewNotification(
          user,
          task.review.reviewedBy.getOrElse(-1),
          reviewStatus,
          task,
          comment,
          true,
          errorTags = errorTags
        )
      }
    }

    this.taskRepository.cacheManager.withOptionCaching { () =>
      Some(
        task.copy(review = task.review.copy(
          metaReviewStatus = Some(reviewStatus),
          metaReviewedBy = Some(metaReviewer),
          reviewedAt = Some(new DateTime()),
          reviewClaimedAt = None,
          reviewClaimedBy = None
        )
        )
      )
    }

    updatedRows
  }

  private def buildOrdering(sort: String, order: String): Order = {
    sort match {
      case sortColumn if sortColumn.nonEmpty =>
        // We have two "id" columns: one for Tasks and one for taskReview. So
        // we need to specify which one to sort by for the SQL query.
        val table =
          if (sortColumn == "id" || sortColumn == "status") Some("tasks")
          else if (sortColumn == "reviewed_at") Some("task_review")
          else Some("")
        val direction = if (order == "DESC") Order.DESC else Order.ASC

        Order(
          List(
            OrderField(
              name = sortColumn,
              direction = direction,
              table = table
            )
          )
        )
      case _ => Order()
    }
  }

  private def getReviewRequestedQueries(
      user: User,
      searchParameters: SearchParameters,
      onlySaved: Boolean = false,
      paging: Paging = Paging(-1, 0),
      sort: String,
      order: String,
      includeDisputed: Boolean = true,
      excludeOtherReviewers: Boolean = false,
      asMetaReview: Boolean = false
  ): Query = {

    val query = this.setupReviewSearchClause(
      Query.simple(
        List(),
        order = this.buildOrdering(if (sort == "name") "tasks.name" else sort, order),
        paging = paging
      ),
      user,
      permission,
      searchParameters,
      if (asMetaReview) META_REVIEW_TASKS else REVIEW_REQUESTED_TASKS,
      includeDisputed,
      onlySaved,
      excludeOtherReviewers
    )

    addClaimedByFilter(query, user.id)
  }

  private def addClaimedByFilter(query: Query, userId: Long): Query = {
    // Unclaimed or claimed by me for review
    // "(task_review.review_claimed_at IS NULL OR task_review.review_claimed_by = ${user.id})"
    query.addFilterGroup(
      FilterGroup(
        List(
          BaseParameter(
            TaskReview.FIELD_REVIEW_CLAIMED_BY,
            None,
            Operator.NULL,
            table = Some(TaskReview.TABLE)
          ),
          BaseParameter(
            TaskReview.FIELD_REVIEW_CLAIMED_BY,
            userId,
            Operator.EQ,
            table = Some(TaskReview.TABLE)
          )
        ),
        OR()
      )
    )
  }

  private def addLockedFilter(query: Query): Query = {
    query.addFilterGroup(
      FilterGroup(
        List(
          SubQueryFilter(
            Task.FIELD_ID,
            Query.simple(
              List(
                BaseParameter(
                  "item_type",
                  Actions.ITEM_TYPE_TASK,
                  Operator.EQ,
                  useValueDirectly = true,
                  table = None
                )
              ),
              s"SELECT item_id from locked"
            ),
            negate = true,
            Operator.IN,
            Some("tasks")
          )
        )
      )
    )
  }
}

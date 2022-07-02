/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.exception.{InvalidException, NotFoundException}
import org.maproulette.framework.model.{StatusActions, TaskReview, User, UserMetrics, Task}
import org.maproulette.framework.psql.filter._
import org.maproulette.framework.psql.{OR, Query}
import org.maproulette.framework.repository.{TaskReviewMetricsRepository, UserRepository}
import org.maproulette.permissions.Permission

import scala.collection.mutable

/**
  * @author mcuthbert
  */
@Singleton
class UserMetricService @Inject() (
    repository: UserRepository,
    taskReviewMetricsRepository: TaskReviewMetricsRepository,
    userService: UserService,
    permission: Permission,
    config: Config
) {

  /**
    * Gets metrics for a user
    *
    * @param userId       The id of the user you are requesting the saved challenges for
    * @param user         The user making the request
    * @param taskMonthDuration The task duration used for the counts for the standard metrics
    * @param reviewMonthDuration The review duration for the counts for the review metrics
    * @param reviewerMonthDuration The reviewer duration for the counts for the reviewer metrics
    * @param startDate The start date for the standard metrics
    * @param endDate The end date for the standard metrics
    * @param reviewStartDate The start date for the review metrics
    * @param reviewEndDate The end date for the review metrics
    * @param reviewerStartDate The start date for the reviewer metrics
    * @param reviewerEndDate The end date for the reviewer metrics
    * @return A map of maps where the key is
    */
  def getMetricsForUser(
      userId: Long,
      user: User,
      taskMonthDuration: Int,
      reviewMonthDuration: Int,
      reviewerMonthDuration: Int,
      startDate: String,
      endDate: String,
      reviewStartDate: String,
      reviewEndDate: String,
      reviewerStartDate: String,
      reviewerEndDate: String
  ): Map[String, Map[String, Int]] = {

    val targetUser = this.userService.retrieve(userId)
    var isReviewer = false
    targetUser match {
      case Some(u) =>
        if (u.settings.leaderboardOptOut.getOrElse(false) && !permission.isSuperUser(user) && userId != user.id) {
          throw new IllegalAccessException(s"User metrics are not public for this user.")
        }
        isReviewer = u.settings.isReviewer.getOrElse(false)
      case _ =>
        throw new NotFoundException(s"Could not find user with id: $userId")
    }

    // Fetch task metrics
    val timeClause = this.getMetricsTimeClause(
      taskMonthDuration,
      startDate,
      endDate,
      s"${StatusActions.FIELD_CREATED}",
      StatusActions.TABLE
    )
    val taskCounts = this.repository.getUserTaskCounts(userId, timeClause)

    // Now fetch Review Metrics
    val reviewTimeClause =
      this.getMetricsTimeClause(
        reviewMonthDuration,
        reviewStartDate,
        reviewEndDate,
        TaskReview.FIELD_REVIEWED_AT,
        TaskReview.TABLE
      )
    val reviewCounts = this.taskReviewMetricsRepository.getTaskReviewCounts(
      Query(
        Filter(
          List(
            FilterGroup(List(BaseParameter(TaskReview.FIELD_REVIEW_REQUESTED_BY, userId))),
            FilterGroup(
              List(
                BaseParameter(
                  TaskReview.FIELD_REVIEW_STATUS,
                  Task.REVIEW_STATUS_UNNECESSARY,
                  Operator.EQ,
                  true
                )
              )
            ),
            FilterGroup(
              List(
                reviewTimeClause,
                BaseParameter(TaskReview.FIELD_REVIEWED_AT, null, Operator.NULL)
              ),
              OR()
            )
          )
        )
      ),
      false
    )

    if (isReviewer) {
      val reviewerTimeClause =
        this.getMetricsTimeClause(
          reviewerMonthDuration,
          reviewerStartDate,
          reviewerEndDate,
          TaskReview.FIELD_REVIEWED_AT,
          TaskReview.TABLE
        )

      val asReviewerCounts = this.taskReviewMetricsRepository.getTaskReviewCounts(
        Query.simple(
          List(
            BaseParameter(TaskReview.FIELD_REVIEWED_BY, userId),
            reviewerTimeClause,
            BaseParameter(
              TaskReview.FIELD_REVIEW_STATUS,
              List(Task.REVIEW_STATUS_UNNECESSARY),
              Operator.IN,
              true
            )
          )
        ),
        true
      )
      Map(
        "tasks"           -> taskCounts,
        "reviewTasks"     -> reviewCounts,
        "asReviewerTasks" -> asReviewerCounts
      )
    } else {
      Map("tasks" -> taskCounts, "reviewTasks" -> reviewCounts)
    }
  }

  private def getMetricsTimeClause(
      duration: Int,
      startDate: String,
      endDate: String,
      field: String,
      table: String
  ): DateParameter = {
    val dates =
      try {
        (Some(DateTime.parse(startDate)), Some(DateTime.parse(endDate)))
      } catch {
        case _: IllegalArgumentException => (None, None)
        case e: Throwable                => throw new InvalidException(e.getMessage)
      }
    duration match {
      case _ if dates._1.isDefined =>
        DateParameter(
          field,
          dates._1.get,
          dates._2.get,
          Operator.BETWEEN,
          table = Some(table)
        )
      case 0 =>
        DateParameter(
          field,
          DateTime.now.withDayOfMonth(1),
          DateTime.now,
          Operator.BETWEEN,
          table = Some(table)
        )
      case -1 =>
        // All time.
        DateParameter(
          field,
          new DateTime(2000, 1, 1, 0, 0, 0, 0),
          DateTime.now,
          Operator.BETWEEN,
          table = Some(table)
        )
      case x =>
        DateParameter(
          field,
          DateTime.now.minusMonths(x),
          DateTime.now,
          Operator.BETWEEN,
          table = Some(table)
        )
    }
  }

  /**
    * Updates the user's score in the user_metrics table.
    *
    * @param taskStatus The new status of the task to credit the user for.
    * @param taskReviewStatus The review status of the task to credit the user for.
    * @param isReviewRevision Whether this is the first review or is occurring after
    *                         a revision (due to a rejected status)
    * @param asReviewer Whether the user is the reviewer (true) or the mapper (false)
    * @param userId       The user who should get the credit
    */
  def updateUserScore(
      taskStatus: Option[Int],
      taskTimeSpent: Option[Long] = None,
      taskReviewStatus: Option[Int],
      isReviewRevision: Boolean = false,
      asReviewer: Boolean = false,
      reviewStartTime: Option[Long] = None,
      userId: Long
  ): Option[User] = {
    // We need to invalidate the user in the cache.
    this.userService.cacheManager.withDeletingCache(id => userService.retrieve(id)) {
      implicit cachedItem =>
        val insertBuffer = mutable.ListBuffer[Parameter[_]]()
        val stateTuple   = setupStateTuple(taskStatus)

        if (stateTuple._1 != 0) {
          insertBuffer.addOne(this.customFilter(UserMetrics.FIELD_SCORE, value = stateTuple._1))
        }
        if (stateTuple._2.nonEmpty) {
          insertBuffer.addOne(this.customFilter(stateTuple._2))
        }

        taskReviewStatus match {
          case Some(Task.REVIEW_STATUS_REJECTED) =>
            if (!asReviewer) {
              insertBuffer.addOne(this.customFilter(UserMetrics.FIELD_TOTAL_REJECTED))
              if (!isReviewRevision) {
                insertBuffer.addOne(this.customFilter(UserMetrics.FIELD_INITIAL_REJECTED))
              }
            }
          case Some(Task.REVIEW_STATUS_APPROVED) =>
            if (!asReviewer) {
              insertBuffer.addOne(this.customFilter(UserMetrics.FIELD_TOTAL_APPROVED))
              if (!isReviewRevision) {
                insertBuffer.addOne(this.customFilter(UserMetrics.FIELD_INITIAL_APPROVED))
              }
            }
          case Some(Task.REVIEW_STATUS_APPROVED_WITH_REVISIONS) =>
            if (!asReviewer) {
              insertBuffer.addOne(this.customFilter(UserMetrics.FIELD_TOTAL_APPROVED))
              if (!isReviewRevision) {
                insertBuffer.addOne(this.customFilter(UserMetrics.FIELD_INITIAL_APPROVED))
              }
            }
          case Some(Task.REVIEW_STATUS_APPROVED_WITH_FIXES_AFTER_REVISIONS) =>
            if (!asReviewer) {
              insertBuffer.addOne(this.customFilter(UserMetrics.FIELD_TOTAL_APPROVED))
              if (!isReviewRevision) {
                insertBuffer.addOne(this.customFilter(UserMetrics.FIELD_INITIAL_APPROVED))
              }
            }
          case Some(Task.REVIEW_STATUS_ASSISTED) =>
            if (!asReviewer) {
              insertBuffer.addOne(this.customFilter(UserMetrics.FIELD_TOTAL_ASSISTED))
              if (!isReviewRevision) {
                insertBuffer.addOne(this.customFilter(UserMetrics.FIELD_INITIAL_ASSISTED))
              }
            }
          case Some(Task.REVIEW_STATUS_DISPUTED) =>
            if (asReviewer) {
              insertBuffer.addOne(this.customFilter(UserMetrics.FIELD_TOTAL_DISPUTED_AS_REVIEWER))
            } else {
              insertBuffer.addOne(this.customFilter(UserMetrics.FIELD_TOTAL_DISPUTED_AS_MAPPER))
              // Let's rollback mapper's rejected score
              insertBuffer.addOne(this.customFilter(UserMetrics.FIELD_TOTAL_REJECTED, "-"))
            }
          case _ =>
        }

        if (asReviewer) {
          reviewStartTime match {
            case Some(rTime) =>
              insertBuffer.addOne(this.customFilter(UserMetrics.FIELD_TASKS_WITH_REVIEW_TIME))
              insertBuffer.addOne(
                BaseParameter(
                  UserMetrics.FIELD_TOTAL_REVIEW_TIME,
                  s"=(${UserMetrics.FIELD_TOTAL_REVIEW_TIME} + (SELECT EXTRACT(EPOCH FROM NOW())) * 1000 - $rTime)",
                  Operator.CUSTOM
                )
              )
            case None => // not a review
          }
        } else {
          // Only update time if user isn't "skipping"
          if (taskStatus.getOrElse(0) != Task.STATUS_SKIPPED) {
            taskTimeSpent match {
              case Some(time) =>
                insertBuffer.addOne(this.customFilter(UserMetrics.FIELD_TASKS_WITH_TIME))
                insertBuffer.addOne(
                  this.customFilter(UserMetrics.FIELD_TOTAL_TIME_SPENT, value = time)
                )
              case _ => // not updating time
            }
          }
        }
        this.repository.updateUserScore(userId, insertBuffer.toList)
        Some(cachedItem)
    }(id = userId)
    this.userService.retrieve(userId)
  }

  /**
    * Rolls back a user's score by the number of status points. This is useful when
    * a completion status is being changed to another status.
    *
    * @param taskStatus
    * @param userId
    */
  def rollbackUserScore(
      taskStatus: Int,
      userId: Long
  ): Option[User] = {
    // We need to invalidate the user in the cache.
    this.userService.cacheManager.withDeletingCache(id => userService.retrieve(id)) {
      implicit cachedItem =>
        val insertBuffer = mutable.ListBuffer[Parameter[_]]()
        val stateTuple   = setupStateTuple(Some(taskStatus))

        if (stateTuple._1 != 0) {
          insertBuffer.addOne(
            this.customFilter(UserMetrics.FIELD_SCORE, "-", value = stateTuple._1)
          )
        }
        if (stateTuple._2.nonEmpty) {
          insertBuffer.addOne(this.customFilter(stateTuple._2, "-", 1))
        }
        this.repository.updateUserScore(userId, insertBuffer.toList)
        Some(cachedItem)
    }(id = userId)
    this.userService.retrieve(userId)
  }

  private def customFilter(
      key: String,
      sign: String = "+",
      value: Long = 1
  ): Parameter[String] =
    BaseParameter(key, s"=($key$sign$value)", Operator.CUSTOM)

  private def setupStateTuple(taskStatus: Option[Int]): (Int, String) = {
    taskStatus match {
      case Some(Task.STATUS_FIXED) => (config.taskScoreFixed, UserMetrics.FIELD_TOTAL_FIXED)
      case Some(Task.STATUS_FALSE_POSITIVE) =>
        (config.taskScoreFalsePositive, UserMetrics.FIELD_TOTAL_FALSE_POSITIVE)
      case Some(Task.STATUS_ALREADY_FIXED) =>
        (config.taskScoreAlreadyFixed, UserMetrics.FIELD_TOTAL_ALREADY_FIXED)
      case Some(Task.STATUS_TOO_HARD) =>
        (config.taskScoreTooHard, UserMetrics.FIELD_TOTAL_TOO_HARD)
      case Some(Task.STATUS_SKIPPED) =>
        (config.taskScoreSkipped, UserMetrics.FIELD_TOTAL_SKIPPED)
      case _ => (0, "")
    }
  }
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.mixins

import org.maproulette.framework.model.Task
import org.maproulette.Config

/**
  * LeaderboardMixin provides methods to setup query filters for
  * searching/building the leaderboard.
  */
trait LeaderboardMixin {

  /**
    * Returns the SQL to sum a user's status actions for ranking purposes
    **/
  def scoreSumSQL(config: Config): String = {
    s"""SUM(CASE sa.status
         WHEN ${Task.STATUS_FIXED} THEN ${config.taskScoreFixed}
         WHEN ${Task.STATUS_FALSE_POSITIVE} THEN ${config.taskScoreFalsePositive}
         WHEN ${Task.STATUS_ALREADY_FIXED} THEN ${config.taskScoreAlreadyFixed}
         WHEN ${Task.STATUS_TOO_HARD} THEN ${config.taskScoreTooHard}
         WHEN ${Task.STATUS_SKIPPED} THEN ${config.taskScoreSkipped}
         ELSE 0
       END)"""
  }

  /**
    * Returns the SQL to sum review status actions for ranking purposes
    **/
  def reviewScoreSumSQL(config: Config): String = {
    s"""SUM(CASE review_status
         WHEN ${Task.REVIEW_STATUS_APPROVED} THEN 1
         WHEN ${Task.REVIEW_STATUS_APPROVED_WITH_REVISIONS} THEN 1
         WHEN ${Task.REVIEW_STATUS_APPROVED_WITH_FIXES_AFTER_REVISIONS} THEN 1
         WHEN ${Task.REVIEW_STATUS_ASSISTED} THEN 1
         WHEN ${Task.REVIEW_STATUS_REJECTED} THEN 1
         WHEN ${Task.REVIEW_STATUS_DISPUTED} THEN 0
         ELSE 0
       END)"""
  }

  /**
    * Returns the SQL to sum a user's number of completed tasks
    **/
  def tasksSumSQL(): String = {
    s"""COALESCE(SUM(CASE sa.status
               WHEN ${Task.STATUS_FIXED} THEN 1
               WHEN ${Task.STATUS_FALSE_POSITIVE} THEN 1
               WHEN ${Task.STATUS_ALREADY_FIXED} THEN 1
               WHEN ${Task.STATUS_TOO_HARD} THEN 1
               WHEN ${Task.STATUS_SKIPPED} THEN 0
               ELSE 0
             END), 0)"""
  }

  /**
    * Returns the SQL to sum a user's number of tasks they reviewed
    * Note: Disputed tasks in the task_review_history do not count
    * since there will already be an entry for their original task review.
    **/
  def reviewSumSQL(): String = {
    s"""COALESCE(SUM(CASE review_status
               WHEN ${Task.REVIEW_STATUS_APPROVED} THEN 1
               WHEN ${Task.REVIEW_STATUS_APPROVED_WITH_REVISIONS} THEN 1
               WHEN ${Task.REVIEW_STATUS_APPROVED_WITH_FIXES_AFTER_REVISIONS} THEN 1
               WHEN ${Task.REVIEW_STATUS_ASSISTED} THEN 1
               WHEN ${Task.REVIEW_STATUS_REJECTED} THEN 1
               WHEN ${Task.REVIEW_STATUS_DISPUTED} THEN 0
               ELSE 0
             END), 0)"""
  }

  /**
    * Returns the SQL to sum a user's average time spent per task
    **/
  def timeSpentSQL(): String = {
    s"""COALESCE(SUM(tasks.completed_time_spent) /
        SUM(CASE
             WHEN tasks.completed_time_spent > 0 THEN 1
             ELSE 0
           END), 0)"""
  }

  /**
    * Returns the SQL to sum a user's average time spent per review
    **/
  def reviewTimeSpentSQL(): String = {
    val avgReviewTime =
      "CAST(EXTRACT(epoch FROM (task_review_history.reviewed_at - task_review_history.review_started_at)) * 1000 AS INT)"

    s"""COALESCE(SUM(${avgReviewTime}) /
        SUM(CASE
             WHEN (${avgReviewTime}) > 0 THEN 1
             ELSE 0
           END), 0)"""
  }

  /**
    * Returns the SQL to sum a user's number of tasks by review status
    **/
  def reviewStatusSumSQL(reviewStatus: Int): String = {
    s"""COALESCE(SUM(CASE review_status
               WHEN ${reviewStatus} THEN 1
               ELSE 0
             END), 0)"""
  }

  /**
    * Returns the SQL to sum how many reviews were additional reviews
    **/
  def additionalReviewsSumSQL(): String = {
    s"""COALESCE(SUM(CASE
               WHEN original_reviewer IS NOT NULL THEN 1
               ELSE 0
             END), 0)"""
  }
}

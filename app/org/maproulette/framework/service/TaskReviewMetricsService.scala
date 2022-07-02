/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}

import org.maproulette.framework.model._
import org.maproulette.framework.psql.Query
import org.maproulette.framework.repository.TaskReviewMetricsRepository
import org.maproulette.framework.mixins.ReviewSearchMixin
import org.maproulette.session.SearchParameters
import org.maproulette.permissions.Permission

/**
  * Service layer for TaskReviewMetrics
  *
  * @author krotstan
  */
@Singleton
class TaskReviewMetricsService @Inject() (
    repository: TaskReviewMetricsRepository,
    permission: Permission
) extends ReviewSearchMixin {

  /**
    * Gets a list of tasks that have been reviewed (either by this user or requested by this user)
    *
    * @param user      The user executing the request
    * @param reviewTasksType
    * @param searchParameters
    * @return A list of tasks
    */
  def getReviewMetrics(
      user: User,
      reviewTasksType: Int,
      searchParameters: SearchParameters,
      onlySaved: Boolean = false,
      excludeOtherReviewers: Boolean = false
  ): ReviewMetrics = {
    val params = copyParamsForMetaReview(reviewTasksType == META_REVIEW_TASKS, searchParameters)

    val query = this.setupReviewSearchClause(
      Query.empty,
      user,
      permission,
      params,
      reviewTasksType,
      true,
      onlySaved,
      excludeOtherReviewers
    )

    this.repository
      .executeReviewMetricsQuery(
        query,
        projectIds = params.projectIds,
        challengeIds = params.challengeParams.challengeIds
      )
      .head
  }

  /**
    * Gets a list of tasks that have been reviewed (either by this user or requested by this user)
    *
    * @param user      The user executing the request
    * @param searchParameters
    * @param onlySaved Only include saved challenges
    * @return A list of review metrics by mapper
    */
  def getMapperMetrics(
      user: User,
      params: SearchParameters,
      onlySaved: Boolean = false
  ): List[ReviewMetrics] = {
    val query = this.setupReviewSearchClause(
      Query.empty,
      user,
      permission,
      params,
      4,
      true,
      onlySaved,
      excludeOtherReviewers = true,
      includeVirtualProjects = true
    )

    this.repository.executeReviewMetricsQuery(
      query,
      groupByMappers = true
    )
  }

  /**
    * Gets a list of tasks that have been reviewed (either by this user or requested by this user)
    *
    * @param user      The user executing the request
    * @param searchParameters
    * @param onlySaved Only include saved challenges
    * @return A list of review metrics by mapper
    */
  def getMetaReviewMetrics(
      user: User,
      params: SearchParameters,
      onlySaved: Boolean = false
  ): List[ReviewMetrics] = {
    val query = this.setupReviewSearchClause(
      Query.empty,
      user,
      permission,
      params,
      4,
      true,
      onlySaved,
      excludeOtherReviewers = true,
      includeVirtualProjects = true
    )

    this.repository.executeReviewMetricsQuery(
      query,
      groupByReviewers = true
    )
  }

  /*
   * Gets a list of tag metrics for the review tasks that meet the given
   * criteria.
   *
   * @return A list of tasks
   */
  def getReviewTagMetrics(
      user: User,
      reviewTasksType: Int,
      params: SearchParameters,
      onlySaved: Boolean = false,
      excludeOtherReviewers: Boolean = false
  ): List[ReviewMetrics] = {
    val query = this.setupReviewSearchClause(
      Query.empty,
      user,
      permission,
      params,
      reviewTasksType,
      true,
      onlySaved,
      excludeOtherReviewers
    )

    this.repository.executeReviewMetricsQuery(
      query,
      groupByTags = true
    )
  }
}

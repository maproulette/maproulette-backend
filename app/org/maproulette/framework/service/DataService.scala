/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.maproulette.permissions.Permission
import org.maproulette.framework.model.ReviewMetrics
import org.maproulette.framework.mixins.SearchParametersMixin
import org.maproulette.framework.repository.TaskReviewMetricsRepository
import org.maproulette.session.SearchParameters

/**
  * Service layer for Data business logic
  *
  * @author krotstan
  */
@Singleton
class DataService @Inject() (
    taskReviewMetricsRepository: TaskReviewMetricsRepository,
    permission: Permission
) extends SearchParametersMixin {

  /**
    * Returns tag metrics given the search parameters.
    */
  def getTagMetrics(params: SearchParameters): List[ReviewMetrics] = {
    val searchParams = SearchParameters.withDefaultAllTaskStatuses(params)
    val query        = this.filterOnSearchParameters(searchParams)

    this.taskReviewMetricsRepository.executeReviewMetricsQuery(query, groupByTags = true)
  }
}

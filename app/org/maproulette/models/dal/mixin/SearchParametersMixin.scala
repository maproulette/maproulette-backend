/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.models.dal.mixin

import anorm.NamedParameter
import org.maproulette.models.utils.DALHelper
import org.maproulette.session.SearchParameters

import scala.collection.mutable.ListBuffer

/**
  * NOTE: This class has quite a few side effects that need to be taken into account. Specifically
  * that the "whereClause" and "joinClause" are updated through the string builder functions
  * and not returned. So basically functioning like InOut Parameters. Not the best approach
  *
  * @author mcuthbert
  */
@deprecated
trait SearchParametersMixin
    extends DALHelper
    with org.maproulette.framework.mixins.SearchParametersMixin {

  def updateWhereClause(
      params: SearchParameters,
      whereClause: StringBuilder,
      joinClause: StringBuilder
  )(implicit projectSearch: Boolean = true): ListBuffer[NamedParameter] = {
    val parameters = new ListBuffer[NamedParameter]()

    this.paramsLocation(params, whereClause)
    this.paramsBounding(params, whereClause)
    this.paramsTaskStatus(params, whereClause)
    this.paramsTaskId(params, whereClause)
    this.paramsProjectSearch(params, whereClause)
    this.paramsTaskReviewStatus(params, whereClause)
    this.paramsMetaReviewStatus(params, whereClause)
    this.paramsOwner(params, whereClause)
    this.paramsReviewer(params, whereClause)
    this.paramsMetaReviewer(params, whereClause)
    this.paramsMapper(params, whereClause)
    this.paramsTaskPriorities(params, whereClause)
    this.paramsTaskTags(params, whereClause)
    this.paramsPriority(params, whereClause)
    this.paramsChallengeDifficulty(params, whereClause)
    this.paramsChallengeStatus(params, whereClause)
    this.paramsChallengeRequiresLocal(params, whereClause)
    this.paramsBoundingGeometries(params, whereClause)

    // For efficiency can only query on task properties with a parent challenge id
    this.paramsTaskProps(params, whereClause)

    parameters ++= this.addSearchToQuery(params, whereClause)(projectSearch)
    parameters ++= this.addChallengeTagMatchingToQuery(params, whereClause, joinClause)
    parameters
  }

  def addSearchToQuery(
      params: SearchParameters,
      whereClause: StringBuilder
  )(implicit projectSearch: Boolean = true): ListBuffer[NamedParameter] = {
    val parameters = new ListBuffer[NamedParameter]()
    if (projectSearch) {
      parameters ++= this.paramsProjects(params, whereClause)
      this.paramsProjectEnabled(params, whereClause)
    }
    parameters ++= this.paramsChallenges(params, whereClause)
    this.paramsChallengeEnabled(params, whereClause)

    parameters
  }

  def paramsProjectSearch(params: SearchParameters, whereClause: StringBuilder): Unit = {
    this.appendInWhereClause(whereClause, this.filterProjectSearch(params).sql())
  }

  def paramsProjects(params: SearchParameters, whereClause: StringBuilder): List[NamedParameter] = {
    val filter = this.filterProjects(params)
    this.appendInWhereClause(whereClause, filter.sql())
    filter.parameters()
  }

  def paramsProjectEnabled(params: SearchParameters, whereClause: StringBuilder): Unit = {
    this.appendInWhereClause(whereClause, this.filterProjectEnabled(params).sql())
  }

  def paramsLocation(params: SearchParameters, whereClause: StringBuilder): Unit = {
    this.appendInWhereClause(whereClause, this.filterLocation(params).sql())
  }

  def paramsBounding(params: SearchParameters, whereClause: StringBuilder): Unit = {
    this.appendInWhereClause(whereClause, this.filterBounding(params).sql())
  }

  def paramsTaskStatus(
      params: SearchParameters,
      whereClause: StringBuilder,
      defaultStatuses: List[Int] = List(0, 3, 6)
  ): Unit = {
    this.appendInWhereClause(whereClause, this.filterTaskStatus(params, defaultStatuses).sql())
  }

  def paramsTaskId(params: SearchParameters, whereClause: StringBuilder): Unit = {
    this.appendInWhereClause(whereClause, this.filterTaskId(params).sql())
  }

  def paramsTaskPriorities(params: SearchParameters, whereClause: StringBuilder): Unit = {
    this.appendInWhereClause(whereClause, this.filterTaskPriorities(params).sql())
  }

  def paramsPriority(params: SearchParameters, whereClause: StringBuilder): Unit = {
    this.appendInWhereClause(whereClause, this.filterPriority(params).sql())
  }

  def paramsTaskTags(params: SearchParameters, whereClause: StringBuilder): Unit = {
    this.appendInWhereClause(whereClause, this.filterTaskTags(params).sql())
  }

  def paramsTaskReviewStatus(
      params: SearchParameters,
      whereClause: StringBuilder
  ): Unit = {
    this.appendInWhereClause(whereClause, this.filterTaskReviewStatus(params).sql())
  }

  def paramsMetaReviewStatus(
      params: SearchParameters,
      whereClause: StringBuilder
  ): Unit = {
    this.appendInWhereClause(whereClause, this.filterMetaReviewStatus(params).sql())
  }

  def paramsChallengeEnabled(params: SearchParameters, whereClause: StringBuilder): Unit = {
    this.appendInWhereClause(whereClause, this.filterChallengeEnabled(params).sql())
  }

  def paramsChallengeDifficulty(params: SearchParameters, whereClause: StringBuilder): Unit = {
    this.appendInWhereClause(whereClause, this.filterChallengeDifficulty(params).sql())
  }

  def paramsChallengeStatus(params: SearchParameters, whereClause: StringBuilder): Unit = {
    this.appendInWhereClause(whereClause, this.filterChallengeStatus(params).sql())
  }

  def paramsChallenges(
      params: SearchParameters,
      whereClause: StringBuilder
  ): List[NamedParameter] = {
    val filter = this.filterChallenges(params)
    this.appendInWhereClause(whereClause, filter.sql())
    filter.parameters()
  }

  def paramsChallengeRequiresLocal(
      params: SearchParameters,
      whereClause: StringBuilder
  ): Unit = {
    this.appendInWhereClause(whereClause, this.filterChallengeRequiresLocal(params).sql())
  }

  def paramsBoundingGeometries(params: SearchParameters, whereClause: StringBuilder): Unit = {
    this.appendInWhereClause(whereClause, this.filterBoundingGeometries(params).sql())
  }

  def paramsTaskProps(params: SearchParameters, whereClause: StringBuilder): Unit = {
    this.appendInWhereClause(whereClause, this.filterTaskProps(params).sql())
  }

  def paramsOwner(
      params: SearchParameters,
      whereClause: StringBuilder
  ): Unit = {
    this.appendInWhereClause(whereClause, this.filterOwner(params).sql())
  }

  def paramsReviewer(
      params: SearchParameters,
      whereClause: StringBuilder
  ): Unit = {
    this.appendInWhereClause(whereClause, this.filterReviewer(params).sql())
  }

  def paramsMetaReviewer(
      params: SearchParameters,
      whereClause: StringBuilder
  ): Unit = {
    this.appendInWhereClause(whereClause, this.filterMetaReviewer(params).sql())
  }

  def paramsMapper(
      params: SearchParameters,
      whereClause: StringBuilder
  ): Unit = {
    this.appendInWhereClause(whereClause, this.filterMapper(params).sql())
  }

  def paramsMappers(
      params: SearchParameters,
      whereClause: StringBuilder
  ): Unit = {
    this.appendInWhereClause(whereClause, this.filterReviewMappers(params).sql())
  }

  def paramsReviewers(
      params: SearchParameters,
      whereClause: StringBuilder
  ): Unit = {
    this.appendInWhereClause(whereClause, this.filterReviewers(params).sql())
  }

  def paramsReviewDate(
      params: SearchParameters,
      whereClause: StringBuilder
  ): Unit = {
    this.appendInWhereClause(whereClause, this.filterReviewDate(params).sql())
  }

  def paramsMappedOnDate(
      params: SearchParameters,
      whereClause: StringBuilder
  ): Unit = {
    this.appendInWhereClause(whereClause, this.filterMappedOnDate(params).sql())
  }
}

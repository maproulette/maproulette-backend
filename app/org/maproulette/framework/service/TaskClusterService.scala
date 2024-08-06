/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}

import org.maproulette.Config
import org.maproulette.exception.InvalidException
import org.maproulette.framework.model._
import org.maproulette.framework.psql._
import org.maproulette.framework.psql.filter._
import org.maproulette.framework.repository.TaskClusterRepository
import org.maproulette.framework.mixins.{SearchParametersMixin, TaskFilterMixin}
import org.maproulette.session.SearchParameters

/**
  * Service layer for TaskCluster
  *
  * @author krotstan
  */
@Singleton
class TaskClusterService @Inject() (repository: TaskClusterRepository)
    extends SearchParametersMixin
    with TaskFilterMixin {

  /**
    * Retrieves task clusters
    *
    * @param params         SearchParameters used to filter the tasks in the cluster
    * @param numberOfPoints Number of cluster points to group all the tasks by
    * @return A list of task clusters
    */
  def getTaskClusters(
      params: SearchParameters,
      numberOfPoints: Int = this.repository.DEFAULT_NUMBER_OF_POINTS
  ): List[TaskCluster] = {
    val filtered = this.filterOnSearchParameters(params)(false)
    val query    = this.filterOutDeletedParents(filtered)

    this.repository.queryTaskClusters(query, numberOfPoints, params)
  }

  /**
    * Gets the specific tasks within a cluster
    *
    * @param clusterId      The id of the cluster
    * @param params         SearchParameters used to filter the tasks in the cluster
    * @param numberOfPoints Number of cluster points to group all the tasks by
    * @return A list of clustered task points
    */
  def getTasksInCluster(
      clusterId: Int,
      params: SearchParameters,
      numberOfPoints: Int = this.repository.DEFAULT_NUMBER_OF_POINTS
  ): List[ClusteredPoint] = {
    val query = this.filterOutDeletedParents(this.filterOnSearchParameters(params)(false))
    this.repository.queryTasksInCluster(query, clusterId, numberOfPoints)
  }

  /**
    * This function will retrieve all the tasks in a given bounded area. You can use various search
    * parameters to limit the tasks retrieved in the bounding box area.
    *
    * @param params        The search parameters from the cookie or the query string parameters.
    * @param paging        This allows paging for the tasks within in the bounding box
    * @param ignoreLocked  Whether to include locked tasks (by other users) or not
    * @return The list of Tasks found within the bounding box and the total count of tasks if not bounding
    */
  def getTasksInBoundingBox(
      user: User,
      params: SearchParameters,
      paging: Paging = Paging(Config.DEFAULT_LIST_SIZE, 0),
      ignoreLocked: Boolean = false,
      sort: String = "",
      orderDirection: String = "ASC"
  ): (Int, List[ClusteredPoint]) = {
    params.location match {
      case Some(sl) => // params has location
      case None =>
        params.boundingGeometries match {
          case Some(bp) => // params has bounding polygons
          case None =>
            throw new InvalidException(
              "Bounding Box (or Bounding Polygons) required to retrieve tasks within a bounding box"
            )
        }
    }

    var query =
      this.filterOutLocked(
        user,
        this.filterOutDeletedParents(this.filterOnSearchParameters(params)(false)),
        ignoreLocked
      )

    query = params.taskParams.excludeTaskIds match {
      case Some(excludedIds) if !excludedIds.isEmpty =>
        //this.appendInWhereClause(whereClause, s"(tasks.id NOT IN (${excludedIds.mkString(",")}))")
        query.addFilterGroup(
          FilterGroup(
            List(
              BaseParameter(
                Task.FIELD_ID,
                excludedIds.mkString(","),
                Operator.IN,
                negate = true,
                useValueDirectly = true,
                table = Some("tasks")
              )
            )
          )
        )
      case _ => query
    }

    this.repository.queryTasksInBoundingBox(
      query,
      this.getOrder(sort, orderDirection),
      paging
    )
  }

  /**
    * This function will retrieve all the task marker data in a given bounded area. You can use various search
    * parameters to limit the tasks retrieved in the bounding box area.
    *
    * @param params        The search parameters from the cookie or the query string parameters.
    * @param ignoreLocked  Whether to include locked tasks (by other users) or not
    * @return The list of Tasks found within the bounding box
    */
  def getTaskMarkerDataInBoundingBox(
      user: User,
      params: SearchParameters,
      limit: Int,
      ignoreLocked: Boolean = false
  ): (List[ClusteredPoint]) = {
    params.location match {
      case Some(sl) => // params has location
      case None =>
        params.boundingGeometries match {
          case Some(bp) => // params has bounding polygons
          case None =>
            throw new InvalidException(
              "Bounding Box (or Bounding Polygons) required to retrieve tasks within a bounding box"
            )
        }
    }

    var query =
      this.filterOutLocked(
        user,
        this.filterOutDeletedParents(this.filterOnSearchParameters(params)(false)),
        ignoreLocked
      )

    query = params.taskParams.excludeTaskIds match {
      case Some(excludedIds) if !excludedIds.isEmpty =>
        //this.appendInWhereClause(whereClause, s"(tasks.id NOT IN (${excludedIds.mkString(",")}))")
        query.addFilterGroup(
          FilterGroup(
            List(
              BaseParameter(
                Task.FIELD_ID,
                excludedIds.mkString(","),
                Operator.IN,
                negate = true,
                useValueDirectly = true,
                table = Some("tasks")
              )
            )
          )
        )
      case _ => query
    }

    this.repository.queryTaskMarkerDataInBoundingBox(
      query,
      limit
    )
  }
}

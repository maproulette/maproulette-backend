/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection

import anorm.~
import anorm._
import anorm.SqlParser.{get, int, str}
import javax.inject.{Inject, Singleton}
import org.maproulette.session.SearchParameters
import org.maproulette.framework.psql.{Query, Order, Paging}
import org.maproulette.framework.model.{ClusteredPoint, Point, TaskCluster}
import play.api.db.Database
import play.api.libs.json._

import org.maproulette.models.dal.ChallengeDAL

@Singleton
class TaskClusterRepository @Inject() (override val db: Database, challengeDAL: ChallengeDAL)
    extends RepositoryMixin {
  implicit val baseTable: String = "tasks"

  val DEFAULT_NUMBER_OF_POINTS = 100

  val pointParser = this.challengeDAL.pointParser

  private val joinClause = """
    INNER JOIN challenges c ON c.id = tasks.parent_id
    INNER JOIN projects p ON p.id = c.parent_id
    LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id
  """

  // SQL query used to select list of ClusteredPoint data
  private val selectTaskMarkersSQL = s"""
    SELECT tasks.id, tasks.name, tasks.parent_id, c.name, tasks.instruction, tasks.status, tasks.mapped_on,
          tasks.completed_time_spent, tasks.completed_by,
          tasks.bundle_id, tasks.is_bundle_primary, tasks.cooperative_work_json::TEXT as cooperative_work,
          task_review.review_status, task_review.review_requested_by, task_review.reviewed_by, task_review.reviewed_at,
          task_review.review_started_at, task_review.meta_review_status, task_review.meta_reviewed_by,
          task_review.meta_reviewed_at, task_review.additional_reviewers,
          ST_AsGeoJSON(tasks.location) AS location, priority,
          CASE WHEN task_review.review_started_at IS NULL
                THEN 0
                ELSE EXTRACT(epoch FROM (task_review.reviewed_at - task_review.review_started_at)) END
          AS reviewDuration FROM tasks
          $joinClause
  """

  /**
    * Queries task clusters with the given query filters and number of points
    *
    * @param query - Query with the built-in filters
    * @param numberOfPoints - Number of points to return
    * @param params - SearchParameters to save with the search
    */
  def queryTaskClusters(
      query: Query,
      numberOfPoints: Int,
      params: SearchParameters
  ): List[TaskCluster] = {
    this.withMRTransaction { implicit c =>
      val result = SQL(
        s"""
          WITH filtered_tasks AS (
            SELECT tasks.*, 
                   tasks.id as taskId, 
                   tasks.status as taskStatus,
                   tasks.priority as taskPriority, 
                   tasks.geojson::TEXT as taskGeojson,
                   task_review.*, 
                   c.name as challengeName,
                   tasks.location AS taskLocation,
                   c.id AS challengeId,
                   c.status AS challengeStatus
            FROM tasks
            $joinClause
            WHERE ${query.filter.sql()}
            AND tasks.location IS NOT NULL
          ),
          task_clusters AS (
            SELECT *, 
                   ST_ClusterKMeans(filtered_tasks.taskLocation, 
                     (SELECT LEAST(COUNT(*), $numberOfPoints) FROM filtered_tasks)::Integer) OVER () AS kmeans
            FROM filtered_tasks
          )
        
          SELECT kmeans, 
                 count(*) as numberOfPoints,
                 CASE WHEN count(*)=1 THEN (array_agg(taskId))[1] END as taskId,
                 CASE WHEN count(*)=1 THEN (array_agg(taskGeojson))[1] END as geojson,
                 CASE WHEN count(*)=1 THEN (array_agg(taskStatus))[1] END as taskStatus,
                 CASE WHEN count(*)=1 THEN (array_agg(taskPriority))[1] END as taskPriority,
                 ST_AsGeoJSON(ST_Centroid(ST_Collect(taskLocation))) AS geom,
                 ST_AsGeoJSON(ST_ConvexHull(ST_Collect(taskLocation))) AS bounding,
                 array_agg(distinct challengeId) as challengeIds
          FROM task_clusters
          GROUP BY kmeans 
          ORDER BY kmeans
        """
      ).on(query.parameters(): _*).as(this.getTaskClusterParser(params).*)

      // Filter out invalid clusters.
      result.filter(_ != None).asInstanceOf[List[TaskCluster]]
    }
  }

  /**
    * Queries tasks in a cluster given a clusterId and same query
    * @param query
    * @param clusterId
    * @param numberOfPoints
    * @param c              an implicit connection
    * @return A list of clustered task points
    */
  def queryTasksInCluster(
      query: Query,
      clusterId: Int,
      numberOfPoints: Int
  )(implicit c: Option[Connection] = None): List[ClusteredPoint] = {
    this.withMRConnection { implicit c =>
      val result = SQL(s"""
          WITH filtered_tasks AS (
            SELECT tasks.*, 
                   tasks.id as taskId, 
                   tasks.status as taskStatus,
                   tasks.priority as taskPriority, 
                   tasks.geojson::TEXT as taskGeojson,
                   task_review.*, 
                   c.name as challengeName,
                   tasks.location AS taskLocation,
                   c.id AS challengeId,
                   c.status AS challengeStatus
            FROM tasks
            $joinClause
            WHERE ${query.filter.sql()}
            AND tasks.location IS NOT NULL
          ),
          task_clusters AS (
            SELECT *, 
                   ST_ClusterKMeans(filtered_tasks.taskLocation, 
                     (SELECT LEAST(COUNT(*), $numberOfPoints) FROM filtered_tasks)::Integer) OVER () AS kmeans
            FROM filtered_tasks
          )
          SELECT *, cooperative_work_json::TEXT as cooperative_work,
                 ST_AsGeoJSON(taskLocation) AS location
          FROM task_clusters
          WHERE kmeans = $clusterId
      """).as(this.pointParser.*)

      result
    }
  }

  /**
    * Queries tasks in a bounding box
    *
    * @param query         Query to execute
    * @param order         Order to apply
    * @param paging        Paging to apply
    * @return The list of Tasks found within the bounding box and the total count of tasks if not bounding
    */
  def queryTasksInBoundingBox(
      query: Query,
      order: Order,
      paging: Paging
  ): (Int, List[ClusteredPoint]) = {
    this.withMRTransaction { implicit c =>
      val count =
        query.build(s"""
            SELECT count(*) FROM tasks
            $joinClause
          """).as(SqlParser.int("count").single)

      val resultsQuery = query.copy(order = order, paging = paging).build(selectTaskMarkersSQL)
      val results      = resultsQuery.as(this.pointParser.*)

      (count, results)
    }
  }

  /**
    * Queries task markers in a bounding box
    *
    * @param query         Query to execute
    * @param limit         Maximum number of results to return
    * @param c             An available connection
    * @return The list of Tasks found within the bounding box
    */
  def queryTaskMarkerDataInBoundingBox(
      query: Query,
      limit: Int
  ): List[ClusteredPoint] = {
    this.withMRTransaction { implicit c =>
      val finalQuery = query.copy(finalClause = s"LIMIT $limit").build(selectTaskMarkersSQL)
      finalQuery.as(this.pointParser.*)
    }
  }

  private def getTaskClusterParser(params: SearchParameters): anorm.RowParser[Serializable] = {
    int("kmeans") ~ int("numberOfPoints") ~ get[Option[Long]]("taskId") ~
      get[Option[Int]]("taskStatus") ~ get[Option[Int]]("taskPriority") ~ get[Option[String]](
      "geojson"
    ) ~
      str("geom") ~ str("bounding") ~ get[List[Long]]("challengeIds") map {
      case kmeans ~ totalPoints ~ taskId ~ taskStatus ~ taskPriority ~ geojson ~ geom ~ bounding ~ challengeIds =>
        val locationJSON = Json.parse(geom)
        val coordinates  = (locationJSON \ "coordinates").as[List[Double]]
        // Let's check to make sure we received valid number of coordinates.
        if (coordinates.length > 1) {
          val point = Point(coordinates(1), coordinates.head)
          TaskCluster(
            kmeans,
            totalPoints,
            taskId,
            taskStatus,
            taskPriority,
            params,
            point,
            Json.parse(bounding),
            challengeIds,
            geojson.map(Json.parse(_))
          )
        } else {
          None
        }
    }
  }
}

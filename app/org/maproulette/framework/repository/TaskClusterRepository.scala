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
import org.maproulette.session.{SearchParameters, SearchLocation}
import org.maproulette.framework.psql.{Query, Order, Paging}
import org.maproulette.framework.model.{
  ClusteredPoint,
  Point,
  TaskCluster,
  TaskClusterSummary,
  TaskMarker,
  TaskMarkerLocation
}
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
    LEFT OUTER JOIN locked l ON l.item_id = tasks.id
  """

  // SQL query used to select list of ClusteredPoint data
  private val selectTaskMarkersSQL = s"""
    SELECT tasks.id, tasks.name, tasks.parent_id, c.name, tasks.instruction, tasks.status, tasks.mapped_on,
          tasks.completed_time_spent, tasks.completed_by,
          tasks.bundle_id, tasks.is_bundle_primary, tasks.cooperative_work_json::TEXT as cooperative_work,
          task_review.review_status, task_review.review_requested_by, task_review.reviewed_by, task_review.reviewed_at,
          task_review.review_started_at, task_review.meta_review_status, task_review.meta_reviewed_by,
          task_review.meta_reviewed_at, task_review.additional_reviewers,
          ST_AsGeoJSON(tasks.location) AS location, priority, l.user_id as locked_by,
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
                   c.status AS challengeStatus,
                   l.user_id as locked_by
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

  private def getTaskClusterSummaryParser(): anorm.RowParser[Serializable] = {
    int("kmeans") ~ int("numberOfPoints") ~ get[Option[Long]]("taskId") ~
      get[Option[Int]]("taskStatus") ~
      str("geom") ~ str("bounding") map {
      case kmeans ~ totalPoints ~ taskId ~ taskStatus ~ geom ~ bounding =>
        val locationJSON = Json.parse(geom)
        val coordinates  = (locationJSON \ "coordinates").as[List[Double]]
        // Let's check to make sure we received valid number of coordinates.
        if (coordinates.length > 1) {
          val point = Point(coordinates(1), coordinates.head)
          TaskClusterSummary(
            kmeans,
            totalPoints,
            taskId,
            taskStatus,
            point,
            Json.parse(bounding)
          )
        } else {
          None
        }
    }
  }

  /**
    * Queries task markers
    *
    * @param statuses List of task status filters
    * @param global   Whether to include global challenges
    * @return List of task markers
    */
  def queryTaskMarkers(
      statuses: List[Int],
      global: Boolean
  ): List[TaskMarker] = {
    // Use a global bounding box covering the entire world
    val worldBoundingBox = SearchLocation(-180.0, -90.0, 180.0, 90.0)
    this.queryTaskMarkersWithBoundingBox(statuses, global, worldBoundingBox)
  }

  def queryTaskMarkersClustered(
      statuses: List[Int],
      global: Boolean,
      boundingBox: SearchLocation
  ): List[TaskClusterSummary] = {
    this.withMRTransaction { implicit c =>
      var left       = boundingBox.left
      var bottom     = boundingBox.bottom
      var right      = boundingBox.right
      var top        = boundingBox.top
      var statusList = statuses.mkString(",")
      var filters    = s"""
  WHERE t.location && ST_MakeEnvelope($left, $bottom, $right, $top, 4326)
    """
      filters += s" AND c.deleted = false"
      filters += s" AND c.enabled = true"
      filters += s" AND c.is_archived = false"
      if (!global) {
        filters += s" AND c.is_global = false"
      }
      if (statuses.nonEmpty) {
        filters += s" AND t.status IN ($statusList)"
      }
      filters += s" AND t.location IS NOT NULL"

      var query = s"""
WITH filtered_tasks AS (
  SELECT 
    t.id AS taskId,
    t.status AS taskStatus,
    t.location AS taskLocation
  FROM tasks t
  INNER JOIN challenges c ON c.id = t.parent_id
  ${filters}
),
cluster_input AS (
  SELECT 
    *,
    LEAST(COUNT(*) OVER (), 50) AS cluster_count
  FROM filtered_tasks
),
task_clusters AS (
  SELECT 
    *,
    ST_ClusterKMeans(taskLocation, cluster_count::int) OVER () AS kmeans
  FROM cluster_input
)
SELECT 
  kmeans,
  COUNT(*) AS numberOfPoints,
  CASE WHEN COUNT(*) = 1 THEN (ARRAY_AGG(taskId))[1] END AS taskId,
  CASE WHEN COUNT(*) = 1 THEN (ARRAY_AGG(taskStatus))[1] END AS taskStatus,
  ST_AsGeoJSON(ST_Centroid(ST_Collect(taskLocation))) AS geom,
  ST_AsGeoJSON(ST_ConvexHull(ST_Collect(taskLocation))) AS bounding
FROM task_clusters
GROUP BY kmeans
ORDER BY kmeans;
"""
      SQL(query)
        .as(this.getTaskClusterSummaryParser().*)
        .filter(_ != None)
        .asInstanceOf[List[TaskClusterSummary]]
    }
  }

  /**
    * Queries task markers with bounding box filtering
    *
    * @param statuses List of task status filters
    * @param global   Whether to include global challenges
    * @param boundingBox   Search parameters including bounding box
    * @return List of task markers within the bounding box
    */
  def queryTaskMarkersWithBoundingBox(
      statuses: List[Int],
      global: Boolean,
      boundingBox: SearchLocation
  ): List[TaskMarker] = {
    this.withMRTransaction { implicit c =>
      var query =
        """
    SELECT tasks.id, ST_AsGeoJSON(tasks.location) AS location, tasks.status
        FROM tasks
        INNER JOIN challenges c ON c.id = tasks.parent_id
        WHERE c.deleted = false
        AND c.enabled = true
        AND c.is_archived = false
        AND tasks.location IS NOT NULL
    """

      if (!global) {
        query += " AND c.is_global = false"
      }

      if (statuses.nonEmpty) {
        query += s" AND tasks.status IN (${statuses.mkString(",")})"
      }

      var left   = boundingBox.left
      var bottom = boundingBox.bottom
      var right  = boundingBox.right
      var top    = boundingBox.top
      query += s" AND ST_Intersects(tasks.location, ST_MakeEnvelope($left, $bottom, $right, $top, 4326))"

      SQL(query)
        .as((int("id") ~ str("location") ~ int("status")).map {
          case id ~ location ~ status =>
            val locationJSON = Json.parse(location)
            val coordinates  = (locationJSON \ "coordinates").as[List[Double]]
            TaskMarker(
              id,
              TaskMarkerLocation(coordinates(1), coordinates.head),
              status
            )
        }.*)
    }
  }

  /**
    * Counts task markers in the given bounding box
    *
    * @param statuses List of task status filters
    * @param global   Whether to include global challenges
    * @param boundingBox   Search parameters including bounding box
    * @return Count of task markers
    */
  def queryCountTaskMarkers(
      statuses: List[Int],
      global: Boolean,
      boundingBox: SearchLocation
  ): Int = {
    this.withMRTransaction { implicit c =>
      var query =
        """
    SELECT COUNT(*) as count
        FROM tasks
        INNER JOIN challenges c ON c.id = tasks.parent_id
        WHERE c.deleted = false
        AND c.enabled = true
        AND c.is_archived = false
        AND tasks.location IS NOT NULL
    """

      if (!global) {
        query += " AND c.is_global = false"
      }

      if (statuses.nonEmpty) {
        query += s" AND tasks.status IN (${statuses.mkString(",")})"
      }

      var left   = boundingBox.left
      var bottom = boundingBox.bottom
      var right  = boundingBox.right
      var top    = boundingBox.top
      query += s" AND ST_Intersects(tasks.location, ST_MakeEnvelope($left, $bottom, $right, $top, 4326))"

      SQL(query).as(int("count").single)
    }
  }
}

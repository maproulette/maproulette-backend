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
import org.maproulette.session.{SearchLocation, SearchParameters}
import org.maproulette.framework.psql.{Order, Paging, Query}
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

  // Constants for SQL queries
  private val TASK_COLUMNS =
    """
    tasks.id,
    tasks.name,
    tasks.parent_id,
    c.name,
    tasks.instruction,
    tasks.status,
    tasks.mapped_on,
    tasks.completed_time_spent,
    tasks.completed_by,
    tasks.bundle_id,
    tasks.is_bundle_primary,
    tasks.cooperative_work_json::TEXT as cooperative_work,
    task_review.review_status,
    task_review.review_requested_by,
    task_review.reviewed_by,
    task_review.reviewed_at,
    task_review.review_started_at,
    task_review.meta_review_status,
    task_review.meta_reviewed_by,
    task_review.meta_reviewed_at,
    task_review.additional_reviewers,
    ST_AsGeoJSON(tasks.location) AS location,
    priority,
    CASE 
        WHEN task_review.review_started_at IS NULL THEN 0
        ELSE EXTRACT(epoch FROM (task_review.reviewed_at - task_review.review_started_at))
    END AS reviewDuration
  """

  private val JOIN_CLAUSE = """
    INNER JOIN challenges c ON c.id = tasks.parent_id
    INNER JOIN projects p ON p.id = c.parent_id
    LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id
  """

  private val filteredTasksCTE = """
    WITH filtered_tasks AS (
      SELECT tasks.id
      FROM tasks
      INNER JOIN challenges c ON c.id = tasks.parent_id
      INNER JOIN projects p ON p.id = c.parent_id
      LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id
      WHERE %s
    )
  """

  /**
    * Queries task clusters within the given parameters
    *
    * @param query - Query with the built in filters
    * @param numberOfPoints - Number of points to return (capped by actual result count)
    * @param params - SearchParameters to save with the search
    * @return List of TaskCluster objects representing grouped task points
    * @note The clustering uses K-means algorithm to group nearby tasks.
    *       When count(*) = 1, additional task details are included in the result.
    *       The convex hull (bounding) is calculated for each cluster group.
    */
  def queryTaskClusters(
      query: Query,
      numberOfPoints: Int,
      params: SearchParameters
  ): List[TaskCluster] = {
    // Validate input parameters
    require(numberOfPoints > 0, "Number of points must be positive")
    require(query != null, "Query cannot be null")
    require(params != null, "Search parameters cannot be null")

    this.withMRTransaction { implicit c =>
      val (sql, parameters) = this.getTaskClusterQuery(query, numberOfPoints)
      sql.insert(
        0,
        """
        SELECT kmeans, count(*) as numberOfPoints,
          CASE WHEN count(*)=1 THEN (array_agg(taskId))[1] END as taskId,
          CASE WHEN count(*)=1 THEN (array_agg(taskGeojson))[1] END as geojson,
          CASE WHEN count(*)=1 THEN (array_agg(taskStatus))[1] END as taskStatus,
          CASE WHEN count(*)=1 THEN (array_agg(taskPriority))[1] END as taskPriority,
          ST_AsGeoJSON(ST_Centroid(ST_Collect(taskLocation))) AS geom,
          ST_AsGeoJSON(ST_ConvexHull(ST_Collect(taskLocation))) AS bounding,
          array_agg(distinct challengeIds) as challengeIds
      """
      )
      sql.append("GROUP BY kmeans ORDER BY kmeans")

      val result = SQL(sql.toString).on(parameters: _*).as(this.getTaskClusterParser(params).*)

      // Filter out invalid clusters.
      result.filter(_ != None).asInstanceOf[List[TaskCluster]]
    }
  }

  private def getTaskClusterQuery(
      query: Query,
      numberOfPoints: Int
  ): (StringBuilder, List[NamedParameter]) = {
    val queryString = s"""FROM (
            SELECT tasks.*, tasks.id as taskId, tasks.status as taskStatus,
                   tasks.priority as taskPriority, tasks.geojson::TEXT as taskGeojson,
                   task_review.*, c.name as challengeName,
                   ST_ClusterKMeans(tasks.location,
                      (SELECT
                          CASE WHEN COUNT(*) < $numberOfPoints THEN COUNT(*) ELSE $numberOfPoints END
                        FROM tasks
                        $JOIN_CLAUSE
                        WHERE ${query.filter.sql()}
                      )::Integer
                    ) OVER () AS kmeans, tasks.location as taskLocation, tasks.parent_id as challengeIds
            FROM tasks
            $JOIN_CLAUSE
            WHERE ${query.filter.sql()}
          ) AS ksub
          WHERE location IS NOT NULL
      """

    return (new StringBuilder(queryString), query.parameters())
  }

  /**
    * Queries tasks in a cluster given a clusterId and same query
    * @param query
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
      val whereClause       = new StringBuilder
      val (sql, parameters) = this.getTaskClusterQuery(query, numberOfPoints)
      sql.insert(0, """SELECT *, cooperative_work_json::TEXT as cooperative_work,
            ST_AsGeoJSON(taskLocation) AS location
        """)
      sql.append(s" AND kmeans = $clusterId")
      SQL(sql.toString).as(this.pointParser.*)
    }
  }

  /**
    * Queries tasks within a specific bounding box with pagination support
    *
    * @param query The base query containing filters
    * @param order Sorting criteria for the results
    * @param paging Pagination parameters
    * @param location Optional bounding box coordinates
    * @return Tuple of (total count, list of points)
    * @note Uses a CTE (Common Table Expression) for better performance by filtering
    *       tasks first before joining with other tables. The spatial index is used
    *       when location filter is provided.
    */
  def queryTasksInBoundingBox(
      query: Query,
      order: Order,
      paging: Paging,
      location: Option[SearchLocation]
  ): (Int, List[ClusteredPoint]) = {
    // Validate paging parameters
    require(paging.limit > 0, "Page limit must be positive")
    require(paging.page >= 0, "Page number must be non-negative")

    // Validate location coordinates if provided
    location.foreach { loc =>
      require(loc.left < loc.right, "Invalid longitude range")
      require(loc.bottom < loc.top, "Invalid latitude range")
    }

    this.withMRTransaction { implicit c =>
      val locationClause = location
        .map(loc =>
          s"tasks.location && ST_MakeEnvelope(${loc.left}, ${loc.bottom}, ${loc.right}, ${loc.top}, 4326)"
        )
        .getOrElse("TRUE")

      val cte = filteredTasksCTE.format(query.filter.sql())

      val count = SQL(s"""
        ${cte}
        SELECT count(*) FROM filtered_tasks
      """).as(SqlParser.int("count").single)

      val results = SQL(s"""
        ${cte}
        SELECT ${TASK_COLUMNS}
        FROM filtered_tasks
        INNER JOIN tasks ON tasks.id = filtered_tasks.id
        INNER JOIN challenges c ON c.id = tasks.parent_id
        INNER JOIN projects p ON p.id = c.parent_id
        LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id
        WHERE ${locationClause}
        ${order.sql()} LIMIT ${paging.limit} OFFSET ${paging.page}
      """).as(pointParser.*)

      (count, results)
    }
  }

  /**
    * Queries for task marker data using a CTE-based approach for better performance.
    * First filters tasks to a smaller set before joining with other tables.
    * Applies spatial filtering in the main query for optimal index usage.
    *
    * @param query The query containing all filter parameters
    * @param limit Maximum number of results to return
    * @return List of ClusteredPoint objects
    */
  def queryTaskMarkerDataInBoundingBox(
      query: Query,
      location: Option[SearchLocation],
      limit: Int
  ): List[ClusteredPoint] = {
    this.withMRTransaction { implicit c =>
      val locationClause = location
        .map(loc =>
          s"tasks.location && ST_MakeEnvelope(${loc.left}, ${loc.bottom}, ${loc.right}, ${loc.top}, 4326)"
        )
        .getOrElse("TRUE")

      SQL(s"""
        ${filteredTasksCTE.format(query.filter.sql())}
        SELECT ${TASK_COLUMNS}
        FROM filtered_tasks
        INNER JOIN tasks ON tasks.id = filtered_tasks.id
        INNER JOIN challenges c ON c.id = tasks.parent_id
        INNER JOIN projects p ON p.id = c.parent_id
        LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id
        WHERE ${locationClause}
        LIMIT ${limit}
      """).on(query.parameters(): _*).as(pointParser.*)
    }
  }

  private def getTaskClusterParser(params: SearchParameters): anorm.RowParser[Serializable] = {
    int("kmeans") ~ int("numberOfPoints") ~ get[Option[Long]]("taskId") ~
      get[Option[Int]]("taskStatus") ~ get[Option[Int]]("taskPriority") ~ get[Option[String]](
      "geojson"
    ) ~ str("geom") ~
      str("bounding") ~ get[List[Long]]("challengeIds") map {
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

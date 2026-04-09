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
  OverlappingTaskMarker,
  Point,
  TaskCluster,
  TaskClusterSummary,
  TaskMarker,
  TaskMarkerLocation
}
import play.api.db.Database
import play.api.libs.json._

import org.maproulette.models.dal.ChallengeDAL
import org.maproulette.framework.service.ServiceManager

@Singleton
class TaskClusterRepository @Inject() (
    override val db: Database,
    challengeDAL: ChallengeDAL,
    serviceManager: ServiceManager
) extends RepositoryMixin {
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

  /**
    * Simple query for challenge tasks in a bounding box with pagination
    *
    * @param bounds       The bounding box to search within
    * @param challengeIds List of challenge IDs to filter by (optional)
    * @param limit        Maximum number of results per page
    * @param offset       Offset for pagination
    * @return Tuple of (total count, list of tasks)
    */
  def queryChallengeTasksInBounds(
      bounds: Option[SearchLocation],
      challengeIds: Option[List[Long]],
      limit: Int,
      offset: Int
  ): (Int, List[ClusteredPoint]) = {
    this.withMRTransaction { implicit c =>
      var whereConditions = List(
        "c.deleted = false",
        "c.enabled = true",
        "c.is_archived = false",
        "tasks.location IS NOT NULL"
      )

      // Add bounding box filter
      bounds.foreach { b =>
        whereConditions = whereConditions :+
          s"ST_Intersects(tasks.location, ST_MakeEnvelope(${b.left}, ${b.bottom}, ${b.right}, ${b.top}, 4326))"
      }

      // Add challenge ID filter
      challengeIds.foreach { ids =>
        if (ids.nonEmpty) {
          whereConditions = whereConditions :+ s"c.id IN (${ids.mkString(",")})"
        }
      }

      val whereClause = whereConditions.mkString(" AND ")

      // Count query
      val countQuery = s"""
        SELECT COUNT(DISTINCT tasks.id) as count
        FROM tasks
        INNER JOIN challenges c ON c.id = tasks.parent_id
        WHERE $whereClause
      """
      val count      = SQL(countQuery).as(int("count").single)

      // Data query with pagination
      val dataQuery = s"""
        SELECT tasks.id, tasks.name, tasks.parent_id, c.name, tasks.instruction, tasks.status, 
               tasks.mapped_on, tasks.completed_time_spent, tasks.completed_by,
               tasks.bundle_id, tasks.is_bundle_primary, tasks.cooperative_work_json::TEXT as cooperative_work,
               NULL::INTEGER as "task_review.review_status",
               NULL::BIGINT as "task_review.review_requested_by",
               NULL::BIGINT as "task_review.reviewed_by",
               NULL::TIMESTAMP as "task_review.reviewed_at",
               NULL::TIMESTAMP as "task_review.review_started_at",
               NULL::BIGINT[] as "task_review.additional_reviewers",
               NULL::INTEGER as "task_review.meta_review_status",
               NULL::BIGINT as "task_review.meta_reviewed_by",
               NULL::TIMESTAMP as "task_review.meta_reviewed_at",
               ST_AsGeoJSON(tasks.location) AS location, 
               tasks.priority, 
               l.user_id as locked_by
        FROM tasks
        INNER JOIN challenges c ON c.id = tasks.parent_id
        LEFT JOIN locked l ON l.item_id = tasks.id AND l.item_type = 2
        WHERE $whereClause
        ORDER BY tasks.id
        LIMIT $limit OFFSET $offset
      """
      val results   = SQL(dataQuery).as(this.pointParser.*)

      (count, results)
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
      boundingBox: SearchLocation,
      locationId: Option[Long] = None,
      keywords: Option[String] = None,
      difficulty: Option[Int] = None
  ): List[TaskClusterSummary] = {
    this.withMRTransaction { implicit c =>
      val left       = boundingBox.left
      val bottom     = boundingBox.bottom
      val right      = boundingBox.right
      val top        = boundingBox.top
      val statusList = statuses.mkString(",")

      // Build joins for keywords filtering if keywords are provided
      val joins = if (keywords.isDefined && keywords.get.trim.nonEmpty) {
        " INNER JOIN tags_on_challenges toc ON c.id = toc.challenge_id" +
          " INNER JOIN tags tags_table ON toc.tag_id = tags_table.id"
      } else ""

      val query = s"""
WITH eligible_challenges AS MATERIALIZED (
  SELECT c.id
  FROM challenges c
  INNER JOIN projects p ON p.id = c.parent_id
  ${joins}
  WHERE c.deleted = false
    AND c.enabled = true
    AND c.is_archived = false
    AND p.deleted = false
    AND p.enabled = true
    ${if (!global) "AND c.is_global = false" else ""}
    ${difficulty.map(d => s"AND c.difficulty = $d").getOrElse("")}
    ${keywords
        .filter(_.trim.nonEmpty)
        .map { kws =>
          val keywordList = kws.split(",").map(_.trim.toLowerCase).filter(_.nonEmpty)
          if (keywordList.nonEmpty) {
            val keywordConditions =
              keywordList
                .map(kw => s"LOWER(tags_table.name) = '${kw.replace("'", "''")}'")
                .mkString(" OR ")
            s"AND ($keywordConditions)"
          } else ""
        }
        .getOrElse("")}
),
filtered_tasks AS MATERIALIZED (
  SELECT DISTINCT
    t.id AS taskId,
    t.status AS taskStatus,
    t.location AS taskLocation
  FROM tasks t
  WHERE t.parent_id IN (SELECT id FROM eligible_challenges)
    AND t.location && ST_MakeEnvelope($left, $bottom, $right, $top, 4326)
    ${if (statuses.nonEmpty) s"AND t.status IN ($statusList)" else ""}
    AND t.location IS NOT NULL
    ${locationId
        .flatMap(placeId =>
          serviceManager.nominatim
            .getLocationPolygon(placeId)
            .map(wkt => s"AND ST_Intersects(t.location, ST_GeomFromText('$wkt', 4326))")
        )
        .getOrElse("")}
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
    * @param locationId Optional Nominatim place_id for polygon filtering
    * @return List of task markers within the bounding box
    */
  def queryTaskMarkersWithBoundingBox(
      statuses: List[Int],
      global: Boolean,
      boundingBox: SearchLocation,
      locationId: Option[Long] = None,
      keywords: Option[String] = None,
      difficulty: Option[Int] = None
  ): List[TaskMarker] = {
    this.withMRTransaction { implicit c =>
      var query =
        """
    SELECT DISTINCT tasks.id, ST_AsGeoJSON(tasks.location) AS location, tasks.status, tasks.priority,
           tasks.bundle_id, l.user_id as locked_by
        FROM tasks
        INNER JOIN challenges c ON c.id = tasks.parent_id
        INNER JOIN projects p ON p.id = c.parent_id
        LEFT JOIN locked l ON l.item_id = tasks.id AND l.item_type = 2
    """

      // Add joins for keywords filtering if keywords are provided
      if (keywords.isDefined && keywords.get.trim.nonEmpty) {
        query += " INNER JOIN tags_on_challenges toc ON c.id = toc.challenge_id"
        query += " INNER JOIN tags t ON toc.tag_id = t.id"
      }

      query += """
        WHERE c.deleted = false
        AND c.enabled = true
        AND c.is_archived = false
        AND p.deleted = false
        AND p.enabled = true
        AND tasks.location IS NOT NULL
    """

      if (!global) {
        query += " AND c.is_global = false"
      }

      if (statuses.nonEmpty) {
        query += s" AND tasks.status IN (${statuses.mkString(",")})"
      }

      // Filter by keywords if provided
      keywords.foreach { kws =>
        if (kws.trim.nonEmpty) {
          val keywordList = kws.split(",").map(_.trim.toLowerCase).filter(_.nonEmpty)
          if (keywordList.nonEmpty) {
            val keywordConditions =
              keywordList.map(kw => s"LOWER(t.name) = '${kw.replace("'", "''")}'").mkString(" OR ")
            query += s" AND ($keywordConditions)"
          }
        }
      }

      // Filter by difficulty if provided
      difficulty.foreach { diff =>
        query += s" AND c.difficulty = $diff"
      }

      var left   = boundingBox.left
      var bottom = boundingBox.bottom
      var right  = boundingBox.right
      var top    = boundingBox.top
      query += s" AND ST_Intersects(tasks.location, ST_MakeEnvelope($left, $bottom, $right, $top, 4326))"

      // Add location polygon filter if location_id is provided
      locationId.foreach { placeId =>
        serviceManager.nominatim.getLocationPolygon(placeId).foreach { wkt =>
          query += s" AND ST_Intersects(tasks.location, ST_GeomFromText('$wkt', 4326))"
        }
      }

      SQL(query)
        .as(
          (int("id") ~ str("location") ~ int("status") ~ int("priority") ~ get[Option[Long]](
            "bundle_id"
          ) ~ get[Option[Long]]("locked_by")).map {
            case id ~ location ~ status ~ priority ~ bundleId ~ lockedBy =>
              val locationJSON = Json.parse(location)
              val coordinates  = (locationJSON \ "coordinates").as[List[Double]]
              TaskMarker(
                id,
                TaskMarkerLocation(coordinates(1), coordinates.head),
                status,
                priority,
                bundleId,
                lockedBy
              )
          }.*
        )
    }
  }

  /**
    * Queries task markers with bounding box filtering and overlap detection.
    * Uses PostGIS ST_ClusterDBSCAN to detect tasks at the same location.
    *
    * @param statuses List of task status filters
    * @param global   Whether to include global challenges
    * @param boundingBox   Search parameters including bounding box
    * @param locationId Optional Nominatim place_id for polygon filtering
    * @param keywords Optional comma-separated list of keywords to filter by
    * @param difficulty Optional difficulty level to filter by
    * @return Tuple of (single task markers, overlapping task markers)
    */
  def queryTaskMarkersWithOverlaps(
      statuses: List[Int],
      global: Boolean,
      boundingBox: SearchLocation,
      locationId: Option[Long] = None,
      keywords: Option[String] = None,
      difficulty: Option[Int] = None
  ): (List[TaskMarker], List[OverlappingTaskMarker]) = {
    this.withMRTransaction { implicit c =>
      val left       = boundingBox.left
      val bottom     = boundingBox.bottom
      val right      = boundingBox.right
      val top        = boundingBox.top
      val statusList = statuses.mkString(",")

      // Build joins for keywords filtering if keywords are provided
      val keywordJoins = if (keywords.isDefined && keywords.get.trim.nonEmpty) {
        " INNER JOIN tags_on_challenges toc ON c.id = toc.challenge_id" +
          " INNER JOIN tags tags_table ON toc.tag_id = tags_table.id"
      } else ""

      val keywordFilter = keywords
        .filter(_.trim.nonEmpty)
        .map { kws =>
          val keywordList = kws.split(",").map(_.trim.toLowerCase).filter(_.nonEmpty)
          if (keywordList.nonEmpty) {
            val keywordConditions =
              keywordList
                .map(kw => s"LOWER(tags_table.name) = '${kw.replace("'", "''")}'")
                .mkString(" OR ")
            s"AND ($keywordConditions)"
          } else ""
        }
        .getOrElse("")

      val difficultyFilter = difficulty.map(d => s"AND c.difficulty = $d").getOrElse("")

      val globalFilter = if (!global) "AND c.is_global = false" else ""

      val statusFilter = if (statuses.nonEmpty) s"AND tasks.status IN ($statusList)" else ""

      val locationFilter = locationId
        .flatMap(placeId =>
          serviceManager.nominatim
            .getLocationPolygon(placeId)
            .map(wkt => s"AND ST_Intersects(tasks.location, ST_GeomFromText('$wkt', 4326))")
        )
        .getOrElse("")

      // Use PostGIS ST_ClusterDBSCAN for efficient overlap detection
      // eps = 0.000001 degrees (~0.1 meters), minpoints = 1 to include all points
      val query = s"""
        SELECT
          tasks.id,
          ST_Y(tasks.location) as lat,
          ST_X(tasks.location) as lng,
          tasks.status,
          tasks.priority,
          tasks.bundle_id,
          l.user_id as locked_by,
          ST_ClusterDBSCAN(tasks.location, eps := 0.000001, minpoints := 1) OVER () as cluster_id
        FROM tasks
        INNER JOIN challenges c ON c.id = tasks.parent_id
        INNER JOIN projects p ON p.id = c.parent_id
        LEFT JOIN locked l ON l.item_id = tasks.id AND l.item_type = 2
        $keywordJoins
        WHERE c.deleted = false
          AND c.enabled = true
          AND c.is_archived = false
          AND p.deleted = false
          AND p.enabled = true
          AND tasks.location IS NOT NULL
          AND ST_Intersects(tasks.location, ST_MakeEnvelope($left, $bottom, $right, $top, 4326))
          $globalFilter
          $statusFilter
          $keywordFilter
          $difficultyFilter
          $locationFilter
      """

      val allTasks = SQL(query)
        .as(
          (get[Long]("id") ~ get[Double]("lat") ~ get[Double]("lng") ~ get[Int]("status") ~ get[
            Int
          ](
            "priority"
          ) ~ get[Option[Long]]("bundle_id") ~ get[Option[Long]]("locked_by") ~ get[Int](
            "cluster_id"
          )).map {
            case taskId ~ lat ~ lng ~ status ~ priority ~ bundleId ~ lockedBy ~ clusterId =>
              (
                taskId,
                TaskMarkerLocation(lat, lng),
                status,
                priority,
                bundleId,
                lockedBy,
                clusterId
              )
          }.*
        )

      // Group by cluster_id
      val clusters = allTasks.groupBy(_._7)

      val singleMarkers     = scala.collection.mutable.ListBuffer[TaskMarker]()
      val overlappingGroups = scala.collection.mutable.ListBuffer[OverlappingTaskMarker]()

      clusters.values.foreach { clusterTasks =>
        if (clusterTasks.length == 1) {
          val (taskId, location, status, priority, bundleId, lockedBy, _) = clusterTasks.head
          singleMarkers += TaskMarker(taskId, location, status, priority, bundleId, lockedBy)
        } else {
          val location = clusterTasks.head._2
          val tasksInCluster = clusterTasks.map {
            case (tId, tLoc, tStatus, tPriority, tBundleId, tLockedBy, _) =>
              TaskMarker(tId, tLoc, tStatus, tPriority, tBundleId, tLockedBy)
          }.toList
          overlappingGroups += OverlappingTaskMarker(location, tasksInCluster)
        }
      }

      (singleMarkers.toList, overlappingGroups.toList)
    }
  }

  /**
    * Counts task markers in the given bounding box
    *
    * @param statuses List of task status filters
    * @param global   Whether to include global challenges
    * @param boundingBox   Search parameters including bounding box
    * @param locationId Optional Nominatim place_id for polygon filtering
    * @return Count of task markers
    */
  def queryCountTaskMarkers(
      statuses: List[Int],
      global: Boolean,
      boundingBox: SearchLocation,
      locationId: Option[Long] = None,
      keywords: Option[String] = None,
      difficulty: Option[Int] = None
  ): Int = {
    this.withMRTransaction { implicit c =>
      var query =
        """
    SELECT COUNT(DISTINCT tasks.id) as count
        FROM tasks
        INNER JOIN challenges c ON c.id = tasks.parent_id
        INNER JOIN projects p ON p.id = c.parent_id
    """

      // Add joins for keywords filtering if keywords are provided
      if (keywords.isDefined && keywords.get.trim.nonEmpty) {
        query += " INNER JOIN tags_on_challenges toc ON c.id = toc.challenge_id"
        query += " INNER JOIN tags t ON toc.tag_id = t.id"
      }

      query += """
        WHERE c.deleted = false
        AND c.enabled = true
        AND c.is_archived = false
        AND p.deleted = false
        AND p.enabled = true
        AND tasks.location IS NOT NULL
    """

      if (!global) {
        query += " AND c.is_global = false"
      }

      if (statuses.nonEmpty) {
        query += s" AND tasks.status IN (${statuses.mkString(",")})"
      }

      // Filter by keywords if provided
      keywords.foreach { kws =>
        if (kws.trim.nonEmpty) {
          val keywordList = kws.split(",").map(_.trim.toLowerCase).filter(_.nonEmpty)
          if (keywordList.nonEmpty) {
            val keywordConditions =
              keywordList.map(kw => s"LOWER(t.name) = '${kw.replace("'", "''")}'").mkString(" OR ")
            query += s" AND ($keywordConditions)"
          }
        }
      }

      // Filter by difficulty if provided
      difficulty.foreach { diff =>
        query += s" AND c.difficulty = $diff"
      }

      var left   = boundingBox.left
      var bottom = boundingBox.bottom
      var right  = boundingBox.right
      var top    = boundingBox.top
      query += s" AND ST_Intersects(tasks.location, ST_MakeEnvelope($left, $bottom, $right, $top, 4326))"

      // Add location polygon filter if location_id is provided
      locationId.foreach { placeId =>
        serviceManager.nominatim.getLocationPolygon(placeId).foreach { wkt =>
          query += s" AND ST_Intersects(tasks.location, ST_GeomFromText('$wkt', 4326))"
        }
      }

      SQL(query).as(int("count").single)
    }
  }
}

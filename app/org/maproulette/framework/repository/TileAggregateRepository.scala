/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection

import anorm._
import anorm.SqlParser.{get, int, double}
import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model.{TileAggregate, FilterCounts, TaskMarker, TaskMarkerLocation, ClusterPoint}
import org.maproulette.session.SearchLocation
import play.api.db.Database
import play.api.libs.json.Json

/**
  * Repository for accessing pre-computed tile aggregates.
  * Tiles track tasks with status 0, 3, or 6, with breakdowns by difficulty and global.
  */
@Singleton
class TileAggregateRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = "tile_aggregates"

  /**
    * Parser for converting database rows to TileAggregate objects
    */
  private val tileAggregateParser: RowParser[TileAggregate] = {
    get[Int]("z") ~
      get[Int]("x") ~
      get[Int]("y") ~
      get[Int]("task_count") ~
      get[Option[String]]("counts_by_filter") ~
      get[Option[Double]]("centroid_lat") ~
      get[Option[Double]]("centroid_lng") map {
      case z ~ x ~ y ~ taskCount ~ countsJson ~ centroidLat ~ centroidLng =>
        val filterCounts = countsJson
          .map { json =>
            try {
              FilterCounts.fromJson(Json.parse(json))
            } catch {
              case _: Exception => FilterCounts()
            }
          }
          .getOrElse(FilterCounts())

        TileAggregate(
          z,
          x,
          y,
          taskCount,
          filterCounts,
          centroidLat.getOrElse(0.0),
          centroidLng.getOrElse(0.0)
        )
    }
  }

  /**
    * Get tiles for a bounding box at a specific zoom level.
    * Handles anti-meridian crossing (when bounds.left > bounds.right).
    */
  def getTilesInBounds(
      zoom: Int,
      bounds: SearchLocation
  )(implicit c: Option[Connection] = None): List[TileAggregate] = {
    this.withMRConnection { implicit c =>
      val minY = latToTileY(bounds.top, zoom)
      val maxY = latToTileY(bounds.bottom, zoom)

     
      if (bounds.left > bounds.right) {
       
        val leftMinX  = lngToTileX(bounds.left, zoom)
        val leftMaxX  = (1 << zoom) - 1
        val rightMinX = 0
        val rightMaxX = lngToTileX(bounds.right, zoom)

        val leftTiles = SQL"""
          SELECT z, x, y, task_count, counts_by_filter::text as counts_by_filter,
                 centroid_lat, centroid_lng
          FROM tile_aggregates
          WHERE z = $zoom
            AND x >= $leftMinX AND x <= $leftMaxX
            AND y >= $minY AND y <= $maxY
            AND task_count > 0
        """.as(tileAggregateParser.*)

        val rightTiles = SQL"""
          SELECT z, x, y, task_count, counts_by_filter::text as counts_by_filter,
                 centroid_lat, centroid_lng
          FROM tile_aggregates
          WHERE z = $zoom
            AND x >= $rightMinX AND x <= $rightMaxX
            AND y >= $minY AND y <= $maxY
            AND task_count > 0
        """.as(tileAggregateParser.*)

        leftTiles ++ rightTiles
      } else {
       
        val minX = lngToTileX(bounds.left, zoom)
        val maxX = lngToTileX(bounds.right, zoom)

        SQL"""
          SELECT z, x, y, task_count, counts_by_filter::text as counts_by_filter,
                 centroid_lat, centroid_lng
          FROM tile_aggregates
          WHERE z = $zoom
            AND x >= $minX AND x <= $maxX
            AND y >= $minY AND y <= $maxY
            AND task_count > 0
        """.as(tileAggregateParser.*)
      }
    }
  }

  /**
    * Get a single tile by coordinates
    */
  def getTile(z: Int, x: Int, y: Int)(
      implicit c: Option[Connection] = None
  ): Option[TileAggregate] = {
    this.withMRConnection { implicit c =>
      SQL"""
        SELECT z, x, y, task_count, counts_by_filter::text as counts_by_filter,
               centroid_lat, centroid_lng
        FROM tile_aggregates
        WHERE z = $z AND x = $x AND y = $y
      """.as(tileAggregateParser.singleOpt)
    }
  }

  /**
    * Get child tiles for recursive drilling (zoom level z+1)
    */
  def getChildTiles(z: Int, x: Int, y: Int)(
      implicit c: Option[Connection] = None
  ): List[TileAggregate] = {
    this.withMRConnection { implicit c =>
      
      val childZ    = z + 1
      val childXMin = x * 2
      val childXMax = x * 2 + 1
      val childYMin = y * 2
      val childYMax = y * 2 + 1

      SQL"""
        SELECT z, x, y, task_count, counts_by_filter::text as counts_by_filter,
               centroid_lat, centroid_lng
        FROM tile_aggregates
        WHERE z = $childZ
          AND x >= $childXMin AND x <= $childXMax
          AND y >= $childYMin AND y <= $childYMax
          AND task_count > 0
      """.as(tileAggregateParser.*)
    }
  }

  /**
    * Get tiles at a specific zoom level whose centroids fall within a polygon.
    * First filters by bounding box (fast indexed lookup), then by polygon containment.
    * The polygon is simplified to reduce complexity for faster spatial queries.
    */
  def getTilesInPolygon(
      zoom: Int,
      polygonWkt: String,
      bounds: SearchLocation
  )(implicit c: Option[Connection] = None): List[TileAggregate] = {
    this.withMRConnection { implicit c =>
      val minX = lngToTileX(bounds.left, zoom)
      val maxX = lngToTileX(bounds.right, zoom)
      val minY = latToTileY(bounds.top, zoom)
      val maxY = latToTileY(bounds.bottom, zoom)

      SQL"""
        WITH simplified AS (
          SELECT ST_Simplify(ST_GeomFromText($polygonWkt, 4326), $SIMPLIFY_TOLERANCE) as geom
        )
        SELECT z, x, y, task_count, counts_by_filter::text as counts_by_filter,
               centroid_lat, centroid_lng
        FROM tile_aggregates
        CROSS JOIN simplified
        WHERE z = $zoom
          AND x >= $minX AND x <= $maxX
          AND y >= $minY AND y <= $maxY
          AND task_count > 0
          AND ST_Contains(simplified.geom, ST_SetSRID(ST_MakePoint(centroid_lng, centroid_lat), 4326))
      """.as(tileAggregateParser.*)
    }
  }

  /**
    * Get tile bounds as (west, south, east, north)
    */
  def getTileBounds(z: Int, x: Int, y: Int): (Double, Double, Double, Double) = {
    val west  = tileToLng(x, z)
    val east  = tileToLng(x + 1, z)
    val north = tileToLat(y, z)
    val south = tileToLat(y + 1, z)
    (west, south, east, north)
  }

  /**
    * Get tile bounds as SearchLocation
    */
  def getTileBoundsAsSearchLocation(z: Int, x: Int, y: Int): SearchLocation = {
    val (west, south, east, north) = getTileBounds(z, x, y)
    SearchLocation(west, south, east, north)
  }

  /**
    * Fetch all task markers in a bounding box with a single query.
    * Much more efficient than querying per-tile when total count is low.
    */
  def getTaskMarkersInBounds(
      bounds: SearchLocation,
      difficulty: Option[Int] = None,
      global: Boolean = false,
      limit: Int = 2000
  )(implicit c: Option[Connection] = None): List[TaskMarker] = {
    this.withMRConnection { implicit c =>
      val left   = bounds.left
      val bottom = bounds.bottom
      val right  = bounds.right
      val top    = bounds.top

      // SQL SAFETY: These use #$ string interpolation which is safe here because:
      // - globalFilter is a hardcoded string literal (no user input)
      // - difficultyFilter uses a validated Int from difficulty.map, not user-provided strings
      val globalFilter     = if (!global) "AND c.is_global = false" else ""
      val difficultyFilter = difficulty.map(d => s"AND c.difficulty = $d").getOrElse("")

      SQL"""
        SELECT DISTINCT tasks.id, ST_Y(tasks.location) as lat, ST_X(tasks.location) as lng,
               tasks.status, tasks.priority, tasks.bundle_id, l.user_id as locked_by
        FROM tasks
        INNER JOIN challenges c ON c.id = tasks.parent_id
        INNER JOIN projects p ON p.id = c.parent_id
        LEFT JOIN locked l ON l.item_id = tasks.id AND l.item_type = 2
        WHERE tasks.location && ST_MakeEnvelope($left, $bottom, $right, $top, 4326)
          AND ST_Intersects(tasks.location, ST_MakeEnvelope($left, $bottom, $right, $top, 4326))
          AND tasks.status IN (0, 3, 6)
          AND c.deleted = false AND c.enabled = true AND c.is_archived = false
          AND p.deleted = false AND p.enabled = true
          #$globalFilter
          #$difficultyFilter
        LIMIT $limit
      """.as(taskMarkerParser.*)
    }
  }

  /**
    * Count tasks within a polygon (for deciding fetch vs cluster strategy)
    */
  def countTasksInPolygon(
      polygonWkt: String,
      difficulty: Option[Int] = None,
      global: Boolean = false
  )(implicit c: Option[Connection] = None): Int = {
    this.withMRConnection { implicit c =>
      val globalFilter     = if (!global) "AND c.is_global = false" else ""
      val difficultyFilter = difficulty.map(d => s"AND c.difficulty = $d").getOrElse("")

      SQL"""
        SELECT COUNT(DISTINCT tasks.id)::int as count
        FROM tasks
        INNER JOIN challenges c ON c.id = tasks.parent_id
        INNER JOIN projects p ON p.id = c.parent_id
        WHERE tasks.location && ST_GeomFromText($polygonWkt, 4326)
          AND ST_Intersects(tasks.location, ST_GeomFromText($polygonWkt, 4326))
          AND tasks.status IN (0, 3, 6)
          AND c.deleted = false AND c.enabled = true AND c.is_archived = false
          AND p.deleted = false AND p.enabled = true
          #$globalFilter
          #$difficultyFilter
      """.as(SqlParser.int("count").single)
    }
  }

  /**
    * Fetch task markers within a polygon (for location_id filtering)
    * Uses bounding box operator for index acceleration.
    */
  def getTaskMarkersInPolygon(
      polygonWkt: String,
      difficulty: Option[Int] = None,
      global: Boolean = false,
      limit: Option[Int] = None
  )(implicit c: Option[Connection] = None): List[TaskMarker] = {
    this.withMRConnection { implicit c =>
      val globalFilter     = if (!global) "AND c.is_global = false" else ""
      val difficultyFilter = difficulty.map(d => s"AND c.difficulty = $d").getOrElse("")
      val limitClause      = limit.map(l => s"LIMIT $l").getOrElse("")

      SQL"""
        SELECT DISTINCT tasks.id, ST_Y(tasks.location) as lat, ST_X(tasks.location) as lng,
               tasks.status, tasks.priority, tasks.bundle_id, l.user_id as locked_by
        FROM tasks
        INNER JOIN challenges c ON c.id = tasks.parent_id
        INNER JOIN projects p ON p.id = c.parent_id
        LEFT JOIN locked l ON l.item_id = tasks.id AND l.item_type = 2
        WHERE tasks.location && ST_GeomFromText($polygonWkt, 4326)
          AND ST_Intersects(tasks.location, ST_GeomFromText($polygonWkt, 4326))
          AND tasks.status IN (0, 3, 6)
          AND c.deleted = false AND c.enabled = true AND c.is_archived = false
          AND p.deleted = false AND p.enabled = true
          #$globalFilter
          #$difficultyFilter
        #$limitClause
      """.as(taskMarkerParser.*)
    }
  }

  
  private val MAX_CLUSTER_POINTS = 50000

  /**
    * Get clustered task markers within a polygon using PostGIS kmeans.
    * Returns cluster centroids with counts.
    * For very large datasets (>50K points), uses statistical sampling to maintain performance.
    */
  def getClusteredTasksInPolygon(
      polygonWkt: String,
      difficulty: Option[Int] = None,
      global: Boolean = false,
      numClusters: Int = 80
  )(implicit c: Option[Connection] = None): List[ClusterPoint] = {
    this.withMRConnection { implicit c =>
      val globalFilter     = if (!global) "AND c.is_global = false" else ""
      val difficultyFilter = difficulty.map(d => s"AND c.difficulty = $d").getOrElse("")

      // Use sampling for very large datasets to prevent slow K-means clustering
      // The sample maintains spatial distribution while limiting compute time
      SQL"""
        WITH task_points AS (
          SELECT tasks.location
          FROM tasks
          INNER JOIN challenges c ON c.id = tasks.parent_id
          INNER JOIN projects p ON p.id = c.parent_id
          WHERE tasks.location && ST_GeomFromText($polygonWkt, 4326)
            AND ST_Intersects(tasks.location, ST_GeomFromText($polygonWkt, 4326))
            AND tasks.status IN (0, 3, 6)
            AND c.deleted = false AND c.enabled = true AND c.is_archived = false
            AND p.deleted = false AND p.enabled = true
            #$globalFilter
            #$difficultyFilter
        ),
        point_count AS (
          SELECT COUNT(*) as total FROM task_points
        ),
        sampled_points AS (
          SELECT location
          FROM task_points
          WHERE (SELECT total FROM point_count) <= $MAX_CLUSTER_POINTS
             OR random() < ($MAX_CLUSTER_POINTS::float / GREATEST((SELECT total FROM point_count), 1))
        ),
        clustered AS (
          SELECT ST_ClusterKMeans(location, $numClusters) OVER() as cluster_id, location
          FROM sampled_points
        )
        SELECT
          AVG(ST_Y(location)) as lat,
          AVG(ST_X(location)) as lng,
          COUNT(*)::int as count
        FROM clustered
        GROUP BY cluster_id
        HAVING COUNT(*) > 0
      """.as(clusterPointParser.*)
    }
  }

  private val clusterPointParser: RowParser[ClusterPoint] = {
    get[Double]("lat") ~
      get[Double]("lng") ~
      get[Int]("count") map {
      case lat ~ lng ~ count =>
        ClusterPoint(lat, lng, count)
    }
  }

  // Simplification tolerance in degrees (~1km at equator) - reduces polygon complexity significantly
  private val SIMPLIFY_TOLERANCE = 0.01

  /**
    * Count tasks within a simplified polygon.
    * Uses ST_Simplify to reduce polygon complexity for faster queries.
    */
  def countTasksInPolygonSimplified(
      polygonWkt: String,
      difficulty: Option[Int] = None,
      global: Boolean = false
  )(implicit c: Option[Connection] = None): Int = {
    this.withMRConnection { implicit c =>
      val globalFilter     = if (!global) "AND c.is_global = false" else ""
      val difficultyFilter = difficulty.map(d => s"AND c.difficulty = $d").getOrElse("")

      SQL"""
        WITH simplified AS (
          SELECT ST_Simplify(ST_GeomFromText($polygonWkt, 4326), $SIMPLIFY_TOLERANCE) as geom
        )
        SELECT COUNT(DISTINCT tasks.id)::int as count
        FROM tasks
        CROSS JOIN simplified
        INNER JOIN challenges c ON c.id = tasks.parent_id
        INNER JOIN projects p ON p.id = c.parent_id
        WHERE tasks.location && simplified.geom
          AND ST_Intersects(tasks.location, simplified.geom)
          AND tasks.status IN (0, 3, 6)
          AND c.deleted = false AND c.enabled = true AND c.is_archived = false
          AND p.deleted = false AND p.enabled = true
          #$globalFilter
          #$difficultyFilter
      """.as(SqlParser.int("count").single)
    }
  }

  /**
    * Fetch task markers within a simplified polygon.
    */
  def getTaskMarkersInPolygonSimplified(
      polygonWkt: String,
      difficulty: Option[Int] = None,
      global: Boolean = false,
      limit: Option[Int] = None
  )(implicit c: Option[Connection] = None): List[TaskMarker] = {
    this.withMRConnection { implicit c =>
      val globalFilter     = if (!global) "AND c.is_global = false" else ""
      val difficultyFilter = difficulty.map(d => s"AND c.difficulty = $d").getOrElse("")
      val limitClause      = limit.map(l => s"LIMIT $l").getOrElse("")

      SQL"""
        WITH simplified AS (
          SELECT ST_Simplify(ST_GeomFromText($polygonWkt, 4326), $SIMPLIFY_TOLERANCE) as geom
        )
        SELECT DISTINCT tasks.id, ST_Y(tasks.location) as lat, ST_X(tasks.location) as lng,
               tasks.status, tasks.priority, tasks.bundle_id, l.user_id as locked_by
        FROM tasks
        CROSS JOIN simplified
        INNER JOIN challenges c ON c.id = tasks.parent_id
        INNER JOIN projects p ON p.id = c.parent_id
        LEFT JOIN locked l ON l.item_id = tasks.id AND l.item_type = 2
        WHERE tasks.location && simplified.geom
          AND ST_Intersects(tasks.location, simplified.geom)
          AND tasks.status IN (0, 3, 6)
          AND c.deleted = false AND c.enabled = true AND c.is_archived = false
          AND p.deleted = false AND p.enabled = true
          #$globalFilter
          #$difficultyFilter
        #$limitClause
      """.as(taskMarkerParser.*)
    }
  }

  /**
    * Get clustered task markers within a simplified polygon.
    */
  def getClusteredTasksInPolygonSimplified(
      polygonWkt: String,
      difficulty: Option[Int] = None,
      global: Boolean = false,
      numClusters: Int = 80
  )(implicit c: Option[Connection] = None): List[ClusterPoint] = {
    this.withMRConnection { implicit c =>
      val globalFilter     = if (!global) "AND c.is_global = false" else ""
      val difficultyFilter = difficulty.map(d => s"AND c.difficulty = $d").getOrElse("")

      SQL"""
        WITH simplified AS (
          SELECT ST_Simplify(ST_GeomFromText($polygonWkt, 4326), $SIMPLIFY_TOLERANCE) as geom
        ),
        task_points AS (
          SELECT tasks.location
          FROM tasks
          CROSS JOIN simplified
          INNER JOIN challenges c ON c.id = tasks.parent_id
          INNER JOIN projects p ON p.id = c.parent_id
          WHERE tasks.location && simplified.geom
            AND ST_Intersects(tasks.location, simplified.geom)
            AND tasks.status IN (0, 3, 6)
            AND c.deleted = false AND c.enabled = true AND c.is_archived = false
            AND p.deleted = false AND p.enabled = true
            #$globalFilter
            #$difficultyFilter
        ),
        point_count AS (
          SELECT COUNT(*) as total FROM task_points
        ),
        sampled_points AS (
          SELECT location
          FROM task_points
          WHERE (SELECT total FROM point_count) <= $MAX_CLUSTER_POINTS
             OR random() < ($MAX_CLUSTER_POINTS::float / GREATEST((SELECT total FROM point_count), 1))
        ),
        clustered AS (
          SELECT ST_ClusterKMeans(location, $numClusters) OVER() as cluster_id, location
          FROM sampled_points
        )
        SELECT
          AVG(ST_Y(location)) as lat,
          AVG(ST_X(location)) as lng,
          COUNT(*)::int as count
        FROM clustered
        GROUP BY cluster_id
        HAVING COUNT(*) > 0
      """.as(clusterPointParser.*)
    }
  }

  private val taskMarkerParser: RowParser[TaskMarker] = {
    get[Long]("id") ~
      get[Double]("lat") ~
      get[Double]("lng") ~
      get[Int]("status") ~
      get[Int]("priority") ~
      get[Option[Long]]("bundle_id") ~
      get[Option[Long]]("locked_by") map {
      case id ~ lat ~ lng ~ status ~ priority ~ bundleId ~ lockedBy =>
        TaskMarker(id, TaskMarkerLocation(lat, lng), status, priority, bundleId, lockedBy)
    }
  }

  /**
    * Rebuild all tiles for a specific zoom level
    */
  def rebuildZoomLevel(zoom: Int)(implicit c: Option[Connection] = None): Int = {
    this.withMRTransaction { implicit c =>
      SQL"SELECT rebuild_zoom_level($zoom)"
        .as(SqlParser.int("rebuild_zoom_level").single)
    }
  }

  /**
    * Get total count of pre-computed tiles
    */
  def getTotalTileCount()(implicit c: Option[Connection] = None): Int = {
    this.withMRConnection { implicit c =>
      SQL"SELECT COUNT(*)::int as count FROM tile_aggregates"
        .as(SqlParser.int("count").single)
    }
  }

  // Web Mercator coordinate conversion functions
  def lngToTileX(lng: Double, zoom: Int): Int = {
    math.floor((lng + 180.0) / 360.0 * (1 << zoom)).toInt
  }

  def latToTileY(lat: Double, zoom: Int): Int = {
    val latClamped = math.max(-85.0511, math.min(85.0511, lat))
    val latRad     = math.toRadians(latClamped)
    math
      .floor(
        (1.0 - math.log(math.tan(latRad) + 1.0 / math.cos(latRad)) / math.Pi) / 2.0 * (1 << zoom)
      )
      .toInt
  }

  def tileToLng(x: Int, zoom: Int): Double = {
    x.toDouble / (1 << zoom) * 360.0 - 180.0
  }

  def tileToLat(y: Int, zoom: Int): Double = {
    val n = math.Pi - 2.0 * math.Pi * y.toDouble / (1 << zoom)
    math.toDegrees(math.atan(math.sinh(n)))
  }
}

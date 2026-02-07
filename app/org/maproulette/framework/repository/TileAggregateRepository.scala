/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection

import anorm._
import anorm.SqlParser.{get, int, double}
import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model.{TileAggregate, FilterCounts, TaskMarker, TaskMarkerLocation}
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
    * Get tiles for a bounding box at a specific zoom level
    */
  def getTilesInBounds(
      zoom: Int,
      bounds: SearchLocation
  )(implicit c: Option[Connection] = None): List[TileAggregate] = {
    this.withMRConnection { implicit c =>
      val minX = lngToTileX(bounds.left, zoom)
      val maxX = lngToTileX(bounds.right, zoom)
      val minY = latToTileY(bounds.top, zoom)
      val maxY = latToTileY(bounds.bottom, zoom)

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
      // Child tiles at z+1: (x*2, y*2), (x*2+1, y*2), (x*2, y*2+1), (x*2+1, y*2+1)
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
      global: Boolean = false
  )(implicit c: Option[Connection] = None): List[TaskMarker] = {
    this.withMRConnection { implicit c =>
      val boundsGeom =
        s"ST_MakeEnvelope(${bounds.left}, ${bounds.bottom}, ${bounds.right}, ${bounds.top}, 4326)"

      var query = s"""
        SELECT DISTINCT tasks.id, ST_Y(tasks.location) as lat, ST_X(tasks.location) as lng,
               tasks.status, tasks.priority, tasks.bundle_id, l.user_id as locked_by
        FROM tasks
        INNER JOIN challenges c ON c.id = tasks.parent_id
        INNER JOIN projects p ON p.id = c.parent_id
        LEFT JOIN locked l ON l.item_id = tasks.id AND l.item_type = 2
        WHERE tasks.location && $boundsGeom
          AND ST_Intersects(tasks.location, $boundsGeom)
          AND tasks.status IN (0, 3, 6)
          AND c.deleted = false AND c.enabled = true AND c.is_archived = false
          AND p.deleted = false AND p.enabled = true
      """

      if (!global) {
        query += " AND c.is_global = false"
      }

      difficulty.foreach { d =>
        query += s" AND c.difficulty = $d"
      }

      SQL(query).as(taskMarkerParser.*)
    }
  }

  /**
    * Fetch actual task markers for a tile (for low-count tiles)
    * Filters by status 0, 3, 6 and optionally by difficulty and global
    */
  def getTaskMarkersForTile(
      z: Int,
      x: Int,
      y: Int,
      difficulty: Option[Int] = None,
      global: Boolean = false
  )(implicit c: Option[Connection] = None): List[TaskMarker] = {
    this.withMRConnection { implicit c =>
      val (west, south, east, north) = getTileBounds(z, x, y)
      val tileGeom                   = s"ST_MakeEnvelope($west, $south, $east, $north, 4326)"

      var query = s"""
        SELECT DISTINCT tasks.id, ST_Y(tasks.location) as lat, ST_X(tasks.location) as lng,
               tasks.status, tasks.priority, tasks.bundle_id, l.user_id as locked_by
        FROM tasks
        INNER JOIN challenges c ON c.id = tasks.parent_id
        INNER JOIN projects p ON p.id = c.parent_id
        LEFT JOIN locked l ON l.item_id = tasks.id AND l.item_type = 2
        WHERE tasks.location && $tileGeom
          AND ST_Intersects(tasks.location, $tileGeom)
          AND tasks.status IN (0, 3, 6)
          AND c.deleted = false AND c.enabled = true AND c.is_archived = false
          AND p.deleted = false AND p.enabled = true
      """

      if (!global) {
        query += " AND c.is_global = false"
      }

      difficulty.foreach { d =>
        query += s" AND c.difficulty = $d"
      }

      SQL(query).as(taskMarkerParser.*)
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
      // Use bounding box of polygon for index acceleration
      var query = s"""
        SELECT COUNT(DISTINCT tasks.id)::int as count
        FROM tasks
        INNER JOIN challenges c ON c.id = tasks.parent_id
        INNER JOIN projects p ON p.id = c.parent_id
        WHERE tasks.location && ST_GeomFromText('$polygonWkt', 4326)
          AND ST_Intersects(tasks.location, ST_GeomFromText('$polygonWkt', 4326))
          AND tasks.status IN (0, 3, 6)
          AND c.deleted = false AND c.enabled = true AND c.is_archived = false
          AND p.deleted = false AND p.enabled = true
      """

      if (!global) {
        query += " AND c.is_global = false"
      }

      difficulty.foreach { d =>
        query += s" AND c.difficulty = $d"
      }

      SQL(query).as(SqlParser.int("count").single)
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
      // Use && operator with polygon for index acceleration
      var query = s"""
        SELECT DISTINCT tasks.id, ST_Y(tasks.location) as lat, ST_X(tasks.location) as lng,
               tasks.status, tasks.priority, tasks.bundle_id, l.user_id as locked_by
        FROM tasks
        INNER JOIN challenges c ON c.id = tasks.parent_id
        INNER JOIN projects p ON p.id = c.parent_id
        LEFT JOIN locked l ON l.item_id = tasks.id AND l.item_type = 2
        WHERE tasks.location && ST_GeomFromText('$polygonWkt', 4326)
          AND ST_Intersects(tasks.location, ST_GeomFromText('$polygonWkt', 4326))
          AND tasks.status IN (0, 3, 6)
          AND c.deleted = false AND c.enabled = true AND c.is_archived = false
          AND p.deleted = false AND p.enabled = true
      """

      if (!global) {
        query += " AND c.is_global = false"
      }

      difficulty.foreach { d =>
        query += s" AND c.difficulty = $d"
      }

      limit.foreach { l =>
        query += s" LIMIT $l"
      }

      SQL(query).as(taskMarkerParser.*)
    }
  }

  /**
    * Get clustered task markers within a polygon using PostGIS kmeans.
    * Returns cluster centroids with counts.
    */
  def getClusteredTasksInPolygon(
      polygonWkt: String,
      difficulty: Option[Int] = None,
      global: Boolean = false,
      numClusters: Int = 80
  )(implicit c: Option[Connection] = None): List[ClusterPoint] = {
    this.withMRConnection { implicit c =>
      var filterClause = s"""
        tasks.location && ST_GeomFromText('$polygonWkt', 4326)
        AND ST_Intersects(tasks.location, ST_GeomFromText('$polygonWkt', 4326))
        AND tasks.status IN (0, 3, 6)
        AND c.deleted = false AND c.enabled = true AND c.is_archived = false
        AND p.deleted = false AND p.enabled = true
      """

      if (!global) {
        filterClause += " AND c.is_global = false"
      }

      difficulty.foreach { d =>
        filterClause += s" AND c.difficulty = $d"
      }

      val query = s"""
        WITH task_points AS (
          SELECT tasks.location
          FROM tasks
          INNER JOIN challenges c ON c.id = tasks.parent_id
          INNER JOIN projects p ON p.id = c.parent_id
          WHERE $filterClause
        ),
        clustered AS (
          SELECT ST_ClusterKMeans(location, $numClusters) OVER() as cluster_id, location
          FROM task_points
        )
        SELECT
          AVG(ST_Y(location)) as lat,
          AVG(ST_X(location)) as lng,
          COUNT(*)::int as count
        FROM clustered
        GROUP BY cluster_id
        HAVING COUNT(*) > 0
      """

      SQL(query).as(clusterPointParser.*)
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

  /**
    * Fetch task markers for a tile that are also within a polygon.
    * Uses both tile bounds and polygon intersection for filtering.
    */
  def getTaskMarkersForTileInPolygon(
      z: Int,
      x: Int,
      y: Int,
      polygonWkt: String,
      difficulty: Option[Int] = None,
      global: Boolean = false
  )(implicit c: Option[Connection] = None): List[TaskMarker] = {
    this.withMRConnection { implicit c =>
      val (west, south, east, north) = getTileBounds(z, x, y)
      val tileGeom                   = s"ST_MakeEnvelope($west, $south, $east, $north, 4326)"

      var query = s"""
        SELECT DISTINCT tasks.id, ST_Y(tasks.location) as lat, ST_X(tasks.location) as lng,
               tasks.status, tasks.priority, tasks.bundle_id, l.user_id as locked_by
        FROM tasks
        INNER JOIN challenges c ON c.id = tasks.parent_id
        INNER JOIN projects p ON p.id = c.parent_id
        LEFT JOIN locked l ON l.item_id = tasks.id AND l.item_type = 2
        WHERE tasks.location && $tileGeom
          AND ST_Intersects(tasks.location, $tileGeom)
          AND ST_Intersects(tasks.location, ST_GeomFromText('$polygonWkt', 4326))
          AND tasks.status IN (0, 3, 6)
          AND c.deleted = false AND c.enabled = true AND c.is_archived = false
          AND p.deleted = false AND p.enabled = true
      """

      if (!global) {
        query += " AND c.is_global = false"
      }

      difficulty.foreach { d =>
        query += s" AND c.difficulty = $d"
      }

      SQL(query).as(taskMarkerParser.*)
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
    * Process the tile refresh queue
    */
  def processRefreshQueue(batchSize: Int = 1000)(implicit c: Option[Connection] = None): Int = {
    this.withMRTransaction { implicit c =>
      SQL"SELECT process_tile_refresh_queue($batchSize)"
        .as(SqlParser.int("process_tile_refresh_queue").single)
    }
  }

  /**
    * Get count of queued tiles awaiting refresh
    */
  def getQueueSize()(implicit c: Option[Connection] = None): Int = {
    this.withMRConnection { implicit c =>
      SQL"SELECT COUNT(*)::int as count FROM tile_refresh_queue"
        .as(SqlParser.int("count").single)
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
    * Clear the refresh queue
    */
  def clearRefreshQueue()(implicit c: Option[Connection] = None): Int = {
    this.withMRTransaction { implicit c =>
      SQL"DELETE FROM tile_refresh_queue".executeUpdate()
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

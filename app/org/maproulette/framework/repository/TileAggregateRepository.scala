/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection

import anorm._
import anorm.SqlParser.{get, int, double}
import anorm.postgresql._
import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model.{TileTaskGroup, FilterCounts, TaskMarker, TaskMarkerLocation, ClusterPoint}
import org.maproulette.session.SearchLocation
import play.api.db.Database
import play.api.libs.json.Json

/**
  * Repository for accessing pre-computed tile task groups.
  * All zoom levels (0-14) use overlap detection via ST_ClusterDBSCAN.
  * Groups are categorized as single (group_type=0) or overlapping (group_type=1).
  */
@Singleton
class TileAggregateRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = "tile_task_groups"

  // Simplification tolerance in degrees (~1km at equator)
  private val SIMPLIFY_TOLERANCE = 0.01

  private val tileTaskGroupParser: RowParser[TileTaskGroup] = {
    get[Long]("id") ~
      get[Int]("z") ~
      get[Int]("x") ~
      get[Int]("y") ~
      get[Int]("group_type") ~
      get[Double]("centroid_lat") ~
      get[Double]("centroid_lng") ~
      get[List[Long]]("task_ids") ~
      get[Int]("task_count") ~
      get[Option[String]]("counts_by_filter") map {
      case id ~ z ~ x ~ y ~ groupType ~ centroidLat ~ centroidLng ~ taskIds ~ taskCount ~ countsJson =>
        val filterCounts = countsJson
          .map { json =>
            try {
              FilterCounts.fromJson(Json.parse(json))
            } catch {
              case _: Exception => FilterCounts()
            }
          }
          .getOrElse(FilterCounts())

        TileTaskGroup(id, z, x, y, groupType, centroidLat, centroidLng, taskIds, taskCount, filterCounts)
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
    * Get pre-computed task groups in a bounding box at a specific zoom level.
    * Handles anti-meridian crossing (when bounds.left > bounds.right).
    */
  def getTaskGroupsInBounds(
      zoom: Int,
      bounds: SearchLocation
  )(implicit c: Option[Connection] = None): List[TileTaskGroup] = {
    this.withMRConnection { implicit c =>
      val minY = latToTileY(bounds.top, zoom)
      val maxY = latToTileY(bounds.bottom, zoom)

      if (bounds.left > bounds.right) {
        // Anti-meridian crossing
        val effectiveZoom = if (zoom < 14) zoom + ZOOM_OFFSET else zoom
        val leftMinX  = lngToTileX(bounds.left, zoom)
        val leftMaxX  = (1 << effectiveZoom) - 1
        val rightMinX = 0
        val rightMaxX = lngToTileX(bounds.right, zoom)

        val leftGroups = SQL"""
          SELECT id, z, x, y, group_type, centroid_lat, centroid_lng,
                 task_ids, task_count, counts_by_filter::text as counts_by_filter
          FROM tile_task_groups
          WHERE z = $zoom
            AND x >= $leftMinX AND x <= $leftMaxX
            AND y >= $minY AND y <= $maxY
            AND task_count > 0
        """.as(tileTaskGroupParser.*)

        val rightGroups = SQL"""
          SELECT id, z, x, y, group_type, centroid_lat, centroid_lng,
                 task_ids, task_count, counts_by_filter::text as counts_by_filter
          FROM tile_task_groups
          WHERE z = $zoom
            AND x >= $rightMinX AND x <= $rightMaxX
            AND y >= $minY AND y <= $maxY
            AND task_count > 0
        """.as(tileTaskGroupParser.*)

        leftGroups ++ rightGroups
      } else {
        val minX = lngToTileX(bounds.left, zoom)
        val maxX = lngToTileX(bounds.right, zoom)

        SQL"""
          SELECT id, z, x, y, group_type, centroid_lat, centroid_lng,
                 task_ids, task_count, counts_by_filter::text as counts_by_filter
          FROM tile_task_groups
          WHERE z = $zoom
            AND x >= $minX AND x <= $maxX
            AND y >= $minY AND y <= $maxY
            AND task_count > 0
        """.as(tileTaskGroupParser.*)
      }
    }
  }

  /**
    * Get pre-computed task groups within a polygon at a specific zoom level.
    * Filters groups whose centroids fall within the polygon.
    */
  def getTaskGroupsInPolygon(
      zoom: Int,
      polygonWkt: String,
      bounds: SearchLocation
  )(implicit c: Option[Connection] = None): List[TileTaskGroup] = {
    this.withMRConnection { implicit c =>
      val minX = lngToTileX(bounds.left, zoom)
      val maxX = lngToTileX(bounds.right, zoom)
      val minY = latToTileY(bounds.top, zoom)
      val maxY = latToTileY(bounds.bottom, zoom)

      SQL"""
        WITH simplified AS (
          SELECT ST_Simplify(ST_GeomFromText($polygonWkt, 4326), $SIMPLIFY_TOLERANCE) as geom
        )
        SELECT id, z, x, y, group_type, centroid_lat, centroid_lng,
               task_ids, task_count, counts_by_filter::text as counts_by_filter
        FROM tile_task_groups
        CROSS JOIN simplified
        WHERE z = $zoom
          AND x >= $minX AND x <= $maxX
          AND y >= $minY AND y <= $maxY
          AND task_count > 0
          AND ST_Contains(simplified.geom, ST_SetSRID(ST_MakePoint(centroid_lng, centroid_lat), 4326))
      """.as(tileTaskGroupParser.*)
    }
  }

  /**
    * Fetch all task markers in a bounding box with a single query.
    * Used when total count is low enough to return individual tasks.
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
    * Rebuild a specific zoom level with overlap detection
    */
  def rebuildZoomLevel(zoom: Int)(implicit c: Option[Connection] = None): Int = {
    this.withMRTransaction { implicit c =>
      SQL"SELECT rebuild_zoom_level($zoom)"
        .as(SqlParser.int("rebuild_zoom_level").single)
    }
  }

  /**
    * Get total count of pre-computed task groups
    */
  def getTotalTaskGroupCount()(implicit c: Option[Connection] = None): Int = {
    this.withMRConnection { implicit c =>
      SQL"SELECT COUNT(*)::int as count FROM tile_task_groups"
        .as(SqlParser.int("count").single)
    }
  }

  // Offset for tile coordinate calculations (zoom 0 uses zoom 2 grid, etc.)
  // Must match the offset in rebuild_zoom_level() SQL function
  private val ZOOM_OFFSET = 2

  // Web Mercator coordinate conversion functions
  // For zoom 0-13, we use effectiveZoom = zoom + ZOOM_OFFSET for more granular tiles
  def lngToTileX(lng: Double, zoom: Int): Int = {
    val effectiveZoom = if (zoom < 14) zoom + ZOOM_OFFSET else zoom
    math.floor((lng + 180.0) / 360.0 * (1 << effectiveZoom)).toInt
  }

  def latToTileY(lat: Double, zoom: Int): Int = {
    val effectiveZoom = if (zoom < 14) zoom + ZOOM_OFFSET else zoom
    val latClamped = math.max(-85.0511, math.min(85.0511, lat))
    val latRad     = math.toRadians(latClamped)
    math
      .floor(
        (1.0 - math.log(math.tan(latRad) + 1.0 / math.cos(latRad)) / math.Pi) / 2.0 * (1 << effectiveZoom)
      )
      .toInt
  }

  def tileToLng(x: Int, zoom: Int): Double = {
    val effectiveZoom = if (zoom < 14) zoom + ZOOM_OFFSET else zoom
    x.toDouble / (1 << effectiveZoom) * 360.0 - 180.0
  }

  def tileToLat(y: Int, zoom: Int): Double = {
    val effectiveZoom = if (zoom < 14) zoom + ZOOM_OFFSET else zoom
    val n = math.Pi - 2.0 * math.Pi * y.toDouble / (1 << effectiveZoom)
    math.toDegrees(math.atan(math.sinh(n)))
  }
}

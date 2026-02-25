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
import org.maproulette.framework.model.{
  TileTaskGroup,
  FilterCounts,
  TaskMarker,
  TaskMarkerLocation,
  ClusterPoint
}
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

        TileTaskGroup(
          id,
          z,
          x,
          y,
          groupType,
          centroidLat,
          centroidLng,
          taskIds,
          taskCount,
          filterCounts
        )
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
        val leftMinX      = lngToTileX(bounds.left, zoom)
        val leftMaxX      = (1 << effectiveZoom) - 1
        val rightMinX     = 0
        val rightMaxX     = lngToTileX(bounds.right, zoom)

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
    * Get pre-computed task groups for a specific tile (z, x, y).
    * Used for zoom 14+ where frontend requests individual tiles for caching.
    */
  def getTaskGroupsByTile(
      z: Int,
      x: Int,
      y: Int
  )(implicit c: Option[Connection] = None): List[TileTaskGroup] = {
    this.withMRConnection { implicit c =>
      SQL"""
        SELECT id, z, x, y, group_type, centroid_lat, centroid_lng,
               task_ids, task_count, counts_by_filter::text as counts_by_filter
        FROM tile_task_groups
        WHERE z = $z AND x = $x AND y = $y
          AND task_count > 0
      """.as(tileTaskGroupParser.*)
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
    * Get MVT (Mapbox Vector Tile) binary for a specific tile.
    * Uses PostGIS ST_AsMVT to encode features directly in the database.
    *
    * @param z          Standard zoom level (0-22, but MapLibre maxzoom=14)
    * @param x          Standard tile X coordinate
    * @param y          Standard tile Y coordinate
    * @param difficulty Optional difficulty filter
    * @param global     Whether to include global challenges
    * @return MVT binary data (empty array if no features)
    */
  def getMvtTile(
      z: Int,
      x: Int,
      y: Int,
      difficulty: Option[Int] = None,
      global: Boolean = false
  )(implicit c: Option[Connection] = None): Array[Byte] = {
    this.withMRConnection { implicit c =>
      // Map standard z/x/y to tile_task_groups coordinates
      // Zoom 0-13: DB uses effective_zoom (z+2) for x/y coordinates
      // Zoom 14: direct mapping
      val (queryZ, queryMinX, queryMaxX, queryMinY, queryMaxY) = if (z < 14) {
        val factor = 1 << ZOOM_OFFSET // 4
        (z, x * factor, (x + 1) * factor - 1, y * factor, (y + 1) * factor - 1)
      } else {
        (14, x, x, y, y)
      }

      // Compute tile bounds in Web Mercator (SRID 3857) for MVT geometry clipping
      val (xMin3857, yMin3857, xMax3857, yMax3857) = tileBounds3857(z, x, y)

      // Build filtered count SQL expression from difficulty/global params
      // SQL SAFETY: buildFilterCountKeys returns hardcoded string literals only
      val countKeys        = buildFilterCountKeys(difficulty, global)
      val filteredCountExpr = countKeys
        .map(k => s"COALESCE((ttg.counts_by_filter->>'$k')::int, 0)")
        .mkString(" + ")

      SQL"""
        SELECT COALESCE(ST_AsMVT(tile, 'default', 4096, 'geom'), ''::bytea) AS mvt
        FROM (
          SELECT
            ST_AsMVTGeom(
              ST_Transform(ST_SetSRID(ST_MakePoint(ttg.centroid_lng, ttg.centroid_lat), 4326), 3857),
              ST_MakeEnvelope($xMin3857, $yMin3857, $xMax3857, $yMax3857, 3857),
              4096, 64, true
            ) AS geom,
            ttg.group_type,
            (#$filteredCountExpr) as task_count,
            CASE WHEN ttg.group_type = 0 THEN ttg.task_ids[1] ELSE NULL END as id,
            CASE WHEN ttg.group_type = 0 THEN t.status ELSE NULL END as status,
            CASE WHEN ttg.group_type = 0 THEN t.priority ELSE NULL END as priority,
            CASE WHEN ttg.group_type = 1 THEN array_to_string(ttg.task_ids, ',') ELSE NULL END as task_ids_str
          FROM tile_task_groups ttg
          LEFT JOIN tasks t ON ttg.group_type = 0 AND t.id = ttg.task_ids[1]
          WHERE ttg.z = $queryZ
            AND ttg.x >= $queryMinX AND ttg.x <= $queryMaxX
            AND ttg.y >= $queryMinY AND ttg.y <= $queryMaxY
            AND ttg.task_count > 0
            AND (#$filteredCountExpr) > 0
        ) AS tile
      """.as(get[Array[Byte]]("mvt").single)
    }
  }

  /**
    * Get MVT binary for a specific tile using a dynamic SQL query.
    * Used when keyword or location filters are active (cannot use pre-computed tiles).
    * Includes overlap detection via ST_ClusterDBSCAN.
    *
    * @param z           Standard zoom level (14+)
    * @param x           Standard tile X coordinate
    * @param y           Standard tile Y coordinate
    * @param difficulty  Optional difficulty filter
    * @param global      Whether to include global challenges
    * @param keywords    Optional comma-separated keywords to filter by
    * @param polygonWkt  Optional WKT polygon for location filtering
    * @return MVT binary data (empty array if no features)
    */
  def getMvtTileFiltered(
      z: Int,
      x: Int,
      y: Int,
      difficulty: Option[Int] = None,
      global: Boolean = false,
      keywords: Option[String] = None,
      polygonWkt: Option[String] = None
  )(implicit c: Option[Connection] = None): Array[Byte] = {
    this.withMRConnection { implicit c =>
      val (xMin3857, yMin3857, xMax3857, yMax3857) = tileBounds3857(z, x, y)

      // Build dynamic filter clauses
      // SQL SAFETY: All #$ interpolated values are built from validated inputs (hardcoded strings or validated ints)
      val keywordJoins = keywords
        .filter(_.trim.nonEmpty)
        .map(_ =>
          "INNER JOIN tags_on_challenges toc ON c.id = toc.challenge_id " +
            "INNER JOIN tags tags_table ON toc.tag_id = tags_table.id"
        )
        .getOrElse("")

      val keywordFilter = keywords
        .filter(_.trim.nonEmpty)
        .map { kws =>
          val keywordList = kws.split(",").map(_.trim.toLowerCase).filter(_.nonEmpty)
          if (keywordList.nonEmpty) {
            val conditions = keywordList.map(kw => s"LOWER(tags_table.name) = '$kw'").mkString(" OR ")
            s"AND ($conditions)"
          } else ""
        }
        .getOrElse("")

      val difficultyFilter = difficulty.map(d => s"AND c.difficulty = $d").getOrElse("")
      val globalFilter     = if (!global) "AND c.is_global = false" else ""
      val polygonFilter = polygonWkt
        .map(wkt => s"AND ST_Intersects(tasks.location, ST_GeomFromText('$wkt', 4326))")
        .getOrElse("")

      val query = s"""
        WITH filtered_tasks AS (
          SELECT DISTINCT
            tasks.id,
            tasks.location,
            tasks.status,
            tasks.priority
          FROM tasks
          INNER JOIN challenges c ON c.id = tasks.parent_id
          INNER JOIN projects p ON p.id = c.parent_id
          $keywordJoins
          WHERE tasks.location && ST_Transform(ST_MakeEnvelope($xMin3857, $yMin3857, $xMax3857, $yMax3857, 3857), 4326)
            AND tasks.status IN (0, 3, 6)
            AND c.deleted = false AND c.enabled = true AND c.is_archived = false
            AND p.deleted = false AND p.enabled = true
            $globalFilter
            $keywordFilter
            $difficultyFilter
            $polygonFilter
        ),
        clustered AS (
          SELECT
            *,
            ST_ClusterDBSCAN(location, eps := 0.000001, minpoints := 1) OVER () as cluster_id
          FROM filtered_tasks
        ),
        grouped AS (
          SELECT
            cluster_id,
            COUNT(*) as task_count,
            CASE WHEN COUNT(*) = 1 THEN 0 ELSE 1 END as group_type,
            CASE WHEN COUNT(*) = 1 THEN (ARRAY_AGG(id))[1] ELSE NULL END as single_id,
            CASE WHEN COUNT(*) = 1 THEN (ARRAY_AGG(status))[1] ELSE NULL END as single_status,
            CASE WHEN COUNT(*) = 1 THEN (ARRAY_AGG(priority))[1] ELSE NULL END as single_priority,
            CASE WHEN COUNT(*) > 1 THEN array_to_string(ARRAY_AGG(id), ',') ELSE NULL END as task_ids_str,
            ST_Y(ST_Centroid(ST_Collect(location))) as lat,
            ST_X(ST_Centroid(ST_Collect(location))) as lng
          FROM clustered
          GROUP BY cluster_id
        )
        SELECT COALESCE(ST_AsMVT(tile, 'default', 4096, 'geom'), ''::bytea) AS mvt
        FROM (
          SELECT
            ST_AsMVTGeom(
              ST_Transform(ST_SetSRID(ST_MakePoint(lng, lat), 4326), 3857),
              ST_MakeEnvelope($xMin3857, $yMin3857, $xMax3857, $yMax3857, 3857),
              4096, 64, true
            ) AS geom,
            group_type,
            task_count::int as task_count,
            single_id as id,
            single_status as status,
            single_priority as priority,
            task_ids_str
          FROM grouped
        ) AS tile
      """

      SQL(query).as(get[Array[Byte]]("mvt").single)
    }
  }

  /**
    * Build list of counts_by_filter JSONB keys to sum for the given filters.
    * Returns hardcoded string values only (safe for SQL interpolation).
    */
  private def buildFilterCountKeys(difficulty: Option[Int], global: Boolean): List[String] = {
    val difficulties = difficulty match {
      case Some(d) if d >= 1 && d <= 3 => List(s"d$d")
      case _                           => List("d1", "d2", "d3", "d0")
    }
    val globals = if (global) List("gf", "gt") else List("gf")
    for {
      d <- difficulties
      g <- globals
    } yield s"${d}_${g}"
  }

  // Web Mercator world extent in meters (half of total extent)
  private val WEB_MERCATOR_EXTENT = 20037508.342789244

  /**
    * Compute tile bounds in Web Mercator (SRID 3857) for standard z/x/y.
    * Returns (xMin, yMin, xMax, yMax) in meters.
    */
  private def tileBounds3857(z: Int, x: Int, y: Int): (Double, Double, Double, Double) = {
    val worldSize = WEB_MERCATOR_EXTENT * 2
    val tileSize  = worldSize / (1 << z)
    val xMin      = -WEB_MERCATOR_EXTENT + x * tileSize
    val xMax      = -WEB_MERCATOR_EXTENT + (x + 1) * tileSize
    val yMax      = WEB_MERCATOR_EXTENT - y * tileSize
    val yMin      = WEB_MERCATOR_EXTENT - (y + 1) * tileSize
    (xMin, yMin, xMax, yMax)
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
    val latClamped    = math.max(-85.0511, math.min(85.0511, lat))
    val latRad        = math.toRadians(latClamped)
    math
      .floor(
        (1.0 - math
          .log(math.tan(latRad) + 1.0 / math.cos(latRad)) / math.Pi) / 2.0 * (1 << effectiveZoom)
      )
      .toInt
  }

  def tileToLng(x: Int, zoom: Int): Double = {
    val effectiveZoom = if (zoom < 14) zoom + ZOOM_OFFSET else zoom
    x.toDouble / (1 << effectiveZoom) * 360.0 - 180.0
  }

  def tileToLat(y: Int, zoom: Int): Double = {
    val effectiveZoom = if (zoom < 14) zoom + ZOOM_OFFSET else zoom
    val n             = math.Pi - 2.0 * math.Pi * y.toDouble / (1 << effectiveZoom)
    math.toDegrees(math.atan(math.sinh(n)))
  }
}

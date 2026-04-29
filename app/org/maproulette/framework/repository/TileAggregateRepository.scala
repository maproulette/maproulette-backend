/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection

import anorm._
import anorm.SqlParser.get
import javax.inject.{Inject, Singleton}
import play.api.db.Database

/**
  * Repository backing the pre-computed tile task groups and on-demand MVT
  * generation.
  *
  * Tile building standard:
  *   - Zoom 0..10: per-tile ST_ClusterDBSCAN on Web Mercator coordinates with
  *     an epsilon proportional to tile pixel size. Multiple cluster rows per
  *     (z, x, y).
  *   - Zoom 11: one row per distinct ground location (overlap-aware). The
  *     frontend overzooms this for z = 12..22, so individual task markers are
  *     visible starting at zoom 11.
  *
  * Each zoom level stores rows on its own native x/y grid — there is no
  * ZOOM_OFFSET indirection anymore.
  */
@Singleton
class TileAggregateRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = "tile_task_groups"

  // Web Mercator world extent in meters (half of total extent).
  private val WEB_MERCATOR_EXTENT = 20037508.342789244

  /**
    * MVT tile for the unfiltered path. Served from the pre-computed
    * `tile_task_groups` table.
    */
  def getMvtTilePrecomputed(
      z: Int,
      x: Int,
      y: Int,
      difficulty: Option[Int],
      global: Boolean
  )(implicit c: Option[Connection] = None): Array[Byte] = {
    this.withMRConnection { implicit c =>
      val (xMin, yMin, xMax, yMax) = tileBounds3857(z, x, y)

      // counts_by_filter has a fixed key set (see evolution 107/112). The
      // keys themselves are never user input, so composing them as a SQL
      // expression is safe.
      val filteredCountExpr = buildFilterCountKeys(difficulty, global)
        .map(k => s"COALESCE((ttg.counts_by_filter->>'$k')::int, 0)")
        .mkString(" + ")

      SQL"""
        SELECT COALESCE(ST_AsMVT(tile, 'default', 4096, 'geom'), ''::bytea) AS mvt
        FROM (
          SELECT
            ST_AsMVTGeom(
              ST_Transform(ST_SetSRID(ST_MakePoint(ttg.centroid_lng, ttg.centroid_lat), 4326), 3857),
              ST_MakeEnvelope($xMin, $yMin, $xMax, $yMax, 3857),
              4096, 64, true
            ) AS geom,
            ttg.group_type,
            (#$filteredCountExpr) AS task_count,
            CASE WHEN ttg.group_type IN (0, 1) THEN ttg.task_ids[1] ELSE NULL END AS id,
            CASE WHEN ttg.group_type = 0 THEN t.status   ELSE NULL END AS status,
            CASE WHEN ttg.group_type = 0 THEN t.priority ELSE NULL END AS priority,
            CASE WHEN ttg.group_type = 0 THEN t.parent_id ELSE NULL END AS challenge_id,
            CASE WHEN ttg.group_type = 1 THEN array_to_string(ttg.task_ids, ',') ELSE NULL END AS task_ids_str
          FROM tile_task_groups ttg
          LEFT JOIN tasks t ON ttg.group_type = 0 AND t.id = ttg.task_ids[1]
          WHERE ttg.z = $z AND ttg.x = $x AND ttg.y = $y
            AND ttg.task_count > 0
            AND (#$filteredCountExpr) > 0
        ) AS tile
      """.as(get[Array[Byte]]("mvt").single)
    }
  }

  /**
    * MVT tile for the filtered path. Tasks are clustered on the fly within
    * the requested tile bounds; used when keyword/location filters preclude
    * serving pre-computed rows.
    *
    * All user-provided values (keywords, polygon WKT, difficulty) are passed
    * as bound parameters — nothing is string-concatenated into the SQL.
    */
  def getMvtTileFiltered(
      z: Int,
      x: Int,
      y: Int,
      difficulty: Option[Int],
      global: Boolean,
      keywords: Option[String],
      polygonWkt: Option[String]
  )(implicit c: Option[Connection] = None): Array[Byte] = {
    this.withMRConnection { implicit c =>
      val (xMin, yMin, xMax, yMax) = tileBounds3857(z, x, y)

      val keywordList = keywords
        .map(_.split(",").map(_.trim.toLowerCase).filter(_.nonEmpty).toList)
        .getOrElse(Nil)

      val hasKeywords = keywordList.nonEmpty
      val hasPolygon  = polygonWkt.exists(_.trim.nonEmpty)

      // Only join the tag tables when a keyword filter is present.
      val keywordJoins =
        if (hasKeywords)
          "INNER JOIN tags_on_challenges toc ON c.id = toc.challenge_id " +
            "INNER JOIN tags tg ON toc.tag_id = tg.id"
        else ""

      // Bind each keyword under a predictable name (kw0, kw1, …). Parameter
      // names are code-controlled; values come through Anorm's binding.
      val keywordParamNames = keywordList.indices.map(i => s"kw$i").toList
      val keywordClause =
        if (hasKeywords)
          "AND LOWER(tg.name) IN (" +
            keywordParamNames.map(n => s"{$n}").mkString(", ") + ")"
        else ""
      val polygonClause =
        if (hasPolygon)
          "AND ST_Intersects(t.location, ST_GeomFromText({polygon}, 4326))"
        else ""
      val difficultyClause =
        if (difficulty.isDefined) "AND c.difficulty = {difficulty}" else ""
      val globalClause = if (!global) "AND c.is_global = false" else ""

      // In-tile clustering epsilon matches the precomputed path and the
      // frontend Supercluster radius of 25 pixels. Supercluster normalizes
      // radius with an extent of 512, so 25 Supercluster pixels equals
      // 200 MVT pixels (MVT extent = 4096, and 25/512 == 200/4096). At
      // z ≥ 12 we keep the small overlap-only epsilon so single-location
      // overlaps still collapse without merging neighboring distinct tasks.
      val epsMeters =
        if (z >= 12) 0.05 // ~5 cm — overlap-only
        else 200.0 * (WEB_MERCATOR_EXTENT * 2) / ((1L << z) * 4096.0)

      val query = s"""
        WITH filtered_tasks AS (
          SELECT
            t.id,
            t.parent_id AS challenge_id,
            ST_Transform(t.location, 3857) AS loc3857,
            t.location AS loc4326,
            t.status,
            t.priority
          FROM tasks t
          INNER JOIN challenges c ON c.id = t.parent_id
          INNER JOIN projects   p ON p.id = c.parent_id
          $keywordJoins
          WHERE t.location && ST_Transform(
                  ST_MakeEnvelope({xMin}, {yMin}, {xMax}, {yMax}, 3857), 4326)
            AND t.status IN (0, 3, 6)
            AND c.deleted = false AND c.enabled = true AND c.is_archived = false
            AND p.deleted = false AND p.enabled = true
            $globalClause
            $difficultyClause
            $keywordClause
            $polygonClause
        ),
        clustered AS (
          SELECT
            ST_ClusterDBSCAN(loc3857, eps := {eps}, minpoints := 1) OVER () AS cluster_id,
            id, challenge_id, loc3857, loc4326, status, priority
          FROM filtered_tasks
        ),
        grouped AS (
          SELECT
            cluster_id,
            COUNT(*)::int AS task_count,
            CASE WHEN COUNT(*) = 1 THEN 0 ELSE 1 END AS group_type,
            (ARRAY_AGG(id           ORDER BY id))[1] AS single_id,
            (ARRAY_AGG(challenge_id ORDER BY id))[1] AS single_challenge_id,
            (ARRAY_AGG(status       ORDER BY id))[1] AS single_status,
            (ARRAY_AGG(priority     ORDER BY id))[1] AS single_priority,
            CASE WHEN COUNT(*) > 1
                 THEN array_to_string(ARRAY_AGG(id ORDER BY id), ',')
                 ELSE NULL END AS task_ids_str,
            ST_Centroid(ST_Collect(loc3857)) AS centroid3857
          FROM clustered
          GROUP BY cluster_id
        )
        SELECT COALESCE(ST_AsMVT(tile, 'default', 4096, 'geom'), ''::bytea) AS mvt
        FROM (
          SELECT
            ST_AsMVTGeom(
              centroid3857,
              ST_MakeEnvelope({xMin}, {yMin}, {xMax}, {yMax}, 3857),
              4096, 64, true
            ) AS geom,
            group_type,
            task_count,
            CASE WHEN group_type = 0 THEN single_id           ELSE NULL END AS id,
            CASE WHEN group_type = 0 THEN single_status       ELSE NULL END AS status,
            CASE WHEN group_type = 0 THEN single_priority     ELSE NULL END AS priority,
            CASE WHEN group_type = 0 THEN single_challenge_id ELSE NULL END AS challenge_id,
            task_ids_str
          FROM grouped
        ) AS tile
      """

      val params = scala.collection.mutable.ListBuffer[NamedParameter](
        NamedParameter("xMin", xMin),
        NamedParameter("yMin", yMin),
        NamedParameter("xMax", xMax),
        NamedParameter("yMax", yMax),
        NamedParameter("eps", epsMeters)
      )
      keywordParamNames.zip(keywordList).foreach {
        case (name, value) => params += NamedParameter(name, value)
      }
      if (hasPolygon) {
        params += NamedParameter("polygon", polygonWkt.get)
      }
      if (difficulty.isDefined) {
        params += NamedParameter("difficulty", difficulty.get)
      }

      SQL(query).on(params.toSeq: _*).as(get[Array[Byte]]("mvt").single)
    }
  }

  /**
    * Build the list of counts_by_filter JSON keys to sum for the given
    * filters. Returns a hardcoded key set — safe to interpolate into SQL.
    */
  private def buildFilterCountKeys(
      difficulty: Option[Int],
      global: Boolean
  ): List[String] = {
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

  /**
    * Compute tile bounds in Web Mercator (SRID 3857) for standard z/x/y.
    * Returns (xMin, yMin, xMax, yMax) in meters.
    */
  private def tileBounds3857(z: Int, x: Int, y: Int): (Double, Double, Double, Double) = {
    val worldSize = WEB_MERCATOR_EXTENT * 2
    val tileSize  = worldSize / (1L << z)
    val xMin      = -WEB_MERCATOR_EXTENT + x * tileSize
    val xMax      = -WEB_MERCATOR_EXTENT + (x + 1) * tileSize
    val yMax      = WEB_MERCATOR_EXTENT - y * tileSize
    val yMin      = WEB_MERCATOR_EXTENT - (y + 1) * tileSize
    (xMin, yMin, xMax, yMax)
  }

  /** Rebuild a single zoom level (full rebuild). */
  def rebuildZoomLevel(zoom: Int)(implicit c: Option[Connection] = None): Int = {
    this.withMRTransaction { implicit c =>
      SQL"SELECT rebuild_zoom_level($zoom)"
        .as(SqlParser.int("rebuild_zoom_level").single)
    }
  }

  /** Incremental rebuild that processes the dirty-tile queue. */
  def rebuildDirtyTiles(
      limit: Int = 500
  )(implicit c: Option[Connection] = None): Int = {
    this.withMRTransaction { implicit c =>
      SQL"SELECT rebuild_dirty_tiles($limit)"
        .as(SqlParser.int("rebuild_dirty_tiles").single)
    }
  }

  /** Total number of precomputed task groups across all zoom levels. */
  def getTotalTaskGroupCount()(implicit c: Option[Connection] = None): Int = {
    this.withMRConnection { implicit c =>
      SQL"SELECT COUNT(*)::int AS count FROM tile_task_groups"
        .as(SqlParser.int("count").single)
    }
  }

  /** Number of tiles currently waiting for a rebuild. */
  def getDirtyTileCount()(implicit c: Option[Connection] = None): Int = {
    this.withMRConnection { implicit c =>
      SQL"SELECT COUNT(*)::int AS count FROM tile_dirty_marks"
        .as(SqlParser.int("count").single)
    }
  }
}

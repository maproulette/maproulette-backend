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
  * Repository backing the pre-computed tile cells and on-demand MVT generation.
  *
  * Tile building standard:
  *   - Zoom 0..11: pre-computed grid cells in `tile_cells`. A cell at display
  *     zoom z is a slippy tile at zoom z + CELL_BITS; clustering is just grid
  *     binning, so it is exact, deterministic and identical for filtered and
  *     unfiltered requests.
  *   - Zoom 12: served live from `tasks` as individual / overlap-deduped
  *     markers (one feature per distinct ground location). The frontend
  *     overzooms this for z = 13+.
  *
  * All four code paths (this repository's live queries, plus `rebuild_leaf_cell`
  * and `rebuild_all_tile_cells` in evolution 107) share one eligibility filter:
  * a task is available work when it has a valid location, `status IN (0,3,6)`,
  * is not archived, and its challenge/project are enabled and not deleted or
  * archived. `enabled` is MapRoulette's "discoverable" flag, so requiring it on
  * both challenge and project keeps hidden work off the explore map. Keep all
  * four paths in sync.
  */
@Singleton
class TileAggregateRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = "tile_cells"

  // Web Mercator world extent in meters (half of total extent).
  private val WEB_MERCATOR_EXTENT = 20037508.342789244

  // A cell at display zoom z is a slippy tile at zoom z + CELL_BITS, so each
  // display tile is a 2^CELL_BITS square of cells. Must match evolution 107.
  private val CELL_BITS = 4

  /** Highest display zoom served as pre-computed grid cells. */
  val MAX_CELL_ZOOM = 11

  /** Display zoom served live as individual task markers. */
  val TASK_ZOOM = 12

  // ---------------------------------------------------------------------------
  // MVT generation
  // ---------------------------------------------------------------------------

  /**
    * MVT for display zoom 0..11 with no keyword/location filter. Served from the
    * pre-computed `tile_cells` table; difficulty/global are applied by summing
    * `counts_by_filter` buckets.
    */
  def getMvtCellsPrecomputed(
      z: Int,
      x: Int,
      y: Int,
      difficulty: Option[Int],
      global: Boolean
  )(implicit c: Option[Connection] = None): Array[Byte] = {
    this.withMRConnection { implicit c =>
      val (xMin, yMin, xMax, yMax)     = tileBounds3857(z, x, y)
      val (cxMin, cyMin, cxMax, cyMax) = cellRange(x, y)

      // counts_by_filter has a fixed, code-controlled key set, so composing the
      // keys into a SQL expression is safe (no user input is interpolated).
      val countExpr = buildFilterCountKeys(difficulty, global)
        .map(k => s"COALESCE((tc.counts_by_filter->>'$k')::int, 0)")
        .mkString(" + ")

      SQL"""
        SELECT COALESCE(ST_AsMVT(tile, 'default', 4096, 'geom'), ''::bytea) AS mvt
        FROM (
          SELECT
            ST_AsMVTGeom(
              ST_Transform(
                ST_SetSRID(ST_MakePoint(tc.sum_lng / tc.task_count, tc.sum_lat / tc.task_count), 4326),
                3857),
              ST_MakeEnvelope($xMin, $yMin, $xMax, $yMax, 3857),
              4096, 64, true
            ) AS geom,
            2 AS group_type,
            (#$countExpr) AS task_count
          FROM tile_cells tc
          WHERE tc.z = $z
            AND tc.cx BETWEEN $cxMin AND $cxMax
            AND tc.cy BETWEEN $cyMin AND $cyMax
            AND tc.task_count > 0
            AND (#$countExpr) > 0
        ) AS tile
      """.as(get[Array[Byte]]("mvt").single)
    }
  }

  /**
    * MVT for display zoom 0..11 with keyword filters. Tasks are grid-binned on
    * the fly using the *same* cell grid as the pre-computed path, so a filtered
    * map clusters identically to an unfiltered one.
    */
  def getMvtCellsLive(
      z: Int,
      x: Int,
      y: Int,
      difficulty: Option[Int],
      global: Boolean,
      keywords: Option[String]
  )(implicit c: Option[Connection] = None): Array[Byte] = {
    this.withMRConnection { implicit c =>
      val (xMin, yMin, xMax, yMax) = tileBounds3857(z, x, y)
      val cellZoom                 = z + CELL_BITS
      val filter                   = liveFilter(difficulty, global, keywords)

      val query = s"""
        WITH binned AS (
          SELECT
            lng_to_tile_x(ST_X(t.location), $cellZoom) AS cx,
            lat_to_tile_y(ST_Y(t.location), $cellZoom) AS cy,
            COUNT(*)::int            AS task_count,
            SUM(ST_Y(t.location))    AS sum_lat,
            SUM(ST_X(t.location))    AS sum_lng
          FROM tasks t
          INNER JOIN challenges c ON c.id = t.parent_id
          INNER JOIN projects   p ON p.id = c.parent_id
          ${filter.joins}
          WHERE t.location && ST_Transform(
                  ST_MakeEnvelope({xMin}, {yMin}, {xMax}, {yMax}, 3857), 4326)
            AND NOT ST_IsEmpty(t.location)
            ${filter.where}
          GROUP BY 1, 2
        )
        SELECT COALESCE(ST_AsMVT(tile, 'default', 4096, 'geom'), ''::bytea) AS mvt
        FROM (
          SELECT
            ST_AsMVTGeom(
              ST_Transform(
                ST_SetSRID(ST_MakePoint(sum_lng / task_count, sum_lat / task_count), 4326),
                3857),
              ST_MakeEnvelope({xMin}, {yMin}, {xMax}, {yMax}, 3857),
              4096, 64, true
            ) AS geom,
            2 AS group_type,
            task_count
          FROM binned
        ) AS tile
      """

      val params = boundsParams(xMin, yMin, xMax, yMax) ++ filter.params
      SQL(query).on(params: _*).as(get[Array[Byte]]("mvt").single)
    }
  }

  /**
    * MVT for display zoom 12, served live from `tasks`. Emits one feature per
    * distinct ground location: `group_type=0` for a lone task (with id/status/
    * priority), `group_type=1` for an overlap stack (with `task_ids_str`).
    * Used for every z=12 request, filtered or not.
    */
  def getMvtTasksLive(
      z: Int,
      x: Int,
      y: Int,
      difficulty: Option[Int],
      global: Boolean,
      keywords: Option[String]
  )(implicit c: Option[Connection] = None): Array[Byte] = {
    this.withMRConnection { implicit c =>
      val (xMin, yMin, xMax, yMax) = tileBounds3857(z, x, y)
      val filter                   = liveFilter(difficulty, global, keywords)

      val query = s"""
        WITH eligible AS (
          SELECT t.id, t.status, t.priority, t.parent_id AS challenge_id, t.location
          FROM tasks t
          INNER JOIN challenges c ON c.id = t.parent_id
          INNER JOIN projects   p ON p.id = c.parent_id
          ${filter.joins}
          WHERE t.location && ST_Transform(
                  ST_MakeEnvelope({xMin}, {yMin}, {xMax}, {yMax}, 3857), 4326)
            AND NOT ST_IsEmpty(t.location)
            ${filter.where}
        ),
        grouped AS (
          SELECT
            ST_SnapToGrid(location, 0.0000001) AS snap,
            COUNT(*)::int AS task_count,
            (ARRAY_AGG(id           ORDER BY id))[1] AS single_id,
            (ARRAY_AGG(status       ORDER BY id))[1] AS single_status,
            (ARRAY_AGG(priority     ORDER BY id))[1] AS single_priority,
            (ARRAY_AGG(challenge_id ORDER BY id))[1] AS single_challenge_id,
            array_to_string(ARRAY_AGG(id ORDER BY id), ',') AS task_ids_str,
            ST_Centroid(ST_Collect(location)) AS centroid
          FROM eligible
          GROUP BY 1
        )
        SELECT COALESCE(ST_AsMVT(tile, 'default', 4096, 'geom'), ''::bytea) AS mvt
        FROM (
          SELECT
            ST_AsMVTGeom(
              ST_Transform(centroid, 3857),
              ST_MakeEnvelope({xMin}, {yMin}, {xMax}, {yMax}, 3857),
              4096, 64, true
            ) AS geom,
            CASE WHEN task_count = 1 THEN 0 ELSE 1 END AS group_type,
            task_count,
            CASE WHEN task_count = 1 THEN single_id           ELSE NULL END AS id,
            CASE WHEN task_count = 1 THEN single_status       ELSE NULL END AS status,
            CASE WHEN task_count = 1 THEN single_priority     ELSE NULL END AS priority,
            CASE WHEN task_count = 1 THEN single_challenge_id ELSE NULL END AS challenge_id,
            CASE WHEN task_count > 1 THEN task_ids_str        ELSE NULL END AS task_ids_str
          FROM grouped
        ) AS tile
      """

      val params = boundsParams(xMin, yMin, xMax, yMax) ++ filter.params
      SQL(query).on(params: _*).as(get[Array[Byte]]("mvt").single)
    }
  }

  // ---------------------------------------------------------------------------
  // Dirty-cell queue
  // ---------------------------------------------------------------------------

  /**
    * Drain the dirty-cell queue: recompute up to `limit` leaf cells from the
    * base tables and roll the changes up to z=0. `newestFirst` drains the most
    * recently marked cells first (used by the synchronous post-commit drain).
    * Returns the number of leaf cells processed.
    */
  def rebuildDirtyCells(
      limit: Int = 512,
      newestFirst: Boolean = false
  )(implicit c: Option[Connection] = None): Int = {
    this.withMRTransaction { implicit c =>
      SQL"SELECT rebuild_dirty_cells($limit, $newestFirst) AS n"
        .as(SqlParser.int("n").single)
    }
  }

  /** Full rebuild of the whole pyramid. Returns the number of cells created. */
  def rebuildAll()(implicit c: Option[Connection] = None): Int = {
    this.withMRTransaction { implicit c =>
      SQL"SELECT rebuild_all_tile_cells() AS n".as(SqlParser.int("n").single)
    }
  }

  /** Total number of pre-computed grid cells across all zoom levels. */
  def getCellCount()(implicit c: Option[Connection] = None): Int = {
    this.withMRConnection { implicit c =>
      SQL"SELECT COUNT(*)::int AS count FROM tile_cells"
        .as(SqlParser.int("count").single)
    }
  }

  /** Number of leaf cells currently waiting for a recompute. */
  def getDirtyCellCount()(implicit c: Option[Connection] = None): Int = {
    this.withMRConnection { implicit c =>
      SQL"SELECT COUNT(*)::int AS count FROM tile_dirty_cells"
        .as(SqlParser.int("count").single)
    }
  }

  /**
    * Age in seconds of the oldest entry in the dirty-cell queue, or 0 when the
    * queue is empty. A climbing value means the drain is falling behind.
    */
  def getDirtyQueueLagSeconds()(implicit c: Option[Connection] = None): Int = {
    this.withMRConnection { implicit c =>
      SQL"""SELECT COALESCE(
              EXTRACT(EPOCH FROM (NOW() - MIN(marked_at))), 0)::int AS lag
            FROM tile_dirty_cells"""
        .as(SqlParser.int("lag").single)
    }
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /** Shared FROM-join / WHERE fragment + bound parameters for the live paths. */
  private case class LiveFilter(joins: String, where: String, params: Seq[NamedParameter])

  /**
    * Build the eligibility + difficulty/global/keyword filter shared by the live
    * MVT queries. All user-provided values are bound parameters; only
    * code-controlled identifiers are interpolated.
    */
  private def liveFilter(
      difficulty: Option[Int],
      global: Boolean,
      keywords: Option[String]
  ): LiveFilter = {
    val keywordList = keywords
      .map(_.split(",").map(_.trim.toLowerCase).filter(_.nonEmpty).toList)
      .getOrElse(Nil)
    val hasKeywords = keywordList.nonEmpty

    val joins =
      if (hasKeywords)
        "INNER JOIN tags_on_challenges toc ON c.id = toc.challenge_id " +
          "INNER JOIN tags tg ON toc.tag_id = tg.id"
      else ""

    val keywordParamNames = keywordList.indices.map(i => s"kw$i").toList
    val keywordClause =
      if (hasKeywords)
        "AND LOWER(tg.name) IN (" + keywordParamNames.map(n => s"{$n}").mkString(", ") + ")"
      else ""
    val difficultyClause = if (difficulty.isDefined) "AND c.difficulty = {difficulty}" else ""
    val globalClause     = if (!global) "AND c.is_global = false" else ""

    val where =
      s"""AND t.status IN (0, 3, 6)
          AND t.archived = false
          AND c.deleted = false AND c.enabled = true AND c.is_archived = false
          AND p.deleted = false AND p.enabled = true
          $globalClause
          $difficultyClause
          $keywordClause"""

    val params = scala.collection.mutable.ListBuffer[NamedParameter]()
    keywordParamNames.zip(keywordList).foreach {
      case (name, value) => params += NamedParameter(name, value)
    }
    if (difficulty.isDefined) params += NamedParameter("difficulty", difficulty.get)

    LiveFilter(joins, where, params.toSeq)
  }

  private def boundsParams(
      xMin: Double,
      yMin: Double,
      xMax: Double,
      yMax: Double
  ): Seq[NamedParameter] =
    Seq(
      NamedParameter("xMin", xMin),
      NamedParameter("yMin", yMin),
      NamedParameter("xMax", xMax),
      NamedParameter("yMax", yMax)
    )

  /**
    * Build the list of `counts_by_filter` JSON keys to sum for the given
    * filters. Returns a hardcoded key set — safe to interpolate into SQL.
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

  /** Inclusive cell-coordinate range covered by display tile (x, y). */
  private def cellRange(x: Int, y: Int): (Int, Int, Int, Int) = {
    val span = 1 << CELL_BITS
    (x << CELL_BITS, y << CELL_BITS, (x << CELL_BITS) + span - 1, (y << CELL_BITS) + span - 1)
  }

  /**
    * Tile bounds in Web Mercator (SRID 3857) for standard z/x/y.
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
}

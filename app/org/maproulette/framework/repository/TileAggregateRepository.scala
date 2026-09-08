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
  *   - Zoom 0..11: k-means clusters computed per request. The `tile_cells`
  *     pyramid is no longer emitted directly -- it is the *input* to k-means,
  *     a set of fine-grained micro-aggregates that makes the clustering cheap
  *     without dictating where the markers land. See "Clustering" below.
  *   - Zoom 12: served live from `tasks` as individual / overlap-deduped
  *     markers (one feature per distinct ground location). The frontend
  *     overzooms this for z = 13+.
  *
  * Clustering
  * ----------
  * Emitting one marker per grid cell made dense regions look like graph paper:
  * cells are evenly spaced and axis-aligned, so the markers were too. Instead a
  * tile now reads micro-aggregates DETAIL_BITS levels *deeper* than the display
  * zoom (a 64x64 lattice of them per tile, ~4 MVT pixels apart), runs
  * `ST_ClusterKMeans` over them in Web Mercator, then merges any centroids that
  * would land within MIN_SEPARATION_PX of each other. Marker positions follow
  * the data instead of the grid, and cluster extent adapts to local density.
  *
  * The merge is what makes zooming out behave. k-means returns exactly k
  * clusters however tightly packed its input is, so a dense region that shrinks
  * to a handful of pixels at low zoom would otherwise get every one of its k
  * centroids stacked on the same spot. Merging by on-screen distance means the
  * marker count falls out of how much room the data actually occupies: k is
  * only a ceiling, and zooming out consolidates clusters for real.
  *
  * Reading a deeper pyramid level costs no extra storage -- level z+DETAIL_BITS
  * already exists -- and the row count per tile is bounded by construction, so
  * every tile feeds k-means the same small, fixed-size input regardless of how
  * many tasks it covers.
  *
  * Two consequences worth knowing:
  *   - Clusters are computed per tile, so a cluster that straddles a tile
  *     boundary is split by it. Counts stay exact (every task belongs to
  *     exactly one micro-aggregate, which belongs to exactly one tile), but the
  *     seam can be visible as slightly denser markers along tile edges.
  *   - Marker placement is no longer a pure function of position alone; it
  *     depends on the whole tile's contents. It is still a pure function of
  *     (z, x, y, filters), which is what tile caching requires.
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
  // pyramid level holds a 2^CELL_BITS square of cells per display tile.
  // Must match the tile evolutions (107, lowered to 3 by 121).
  private val CELL_BITS = 3

  // How many pyramid levels below the display zoom to read micro-aggregates
  // from. A display tile at zoom z is fed level min(z + DETAIL_BITS,
  // MAX_CELL_ZOOM), giving up to 2^(CELL_BITS + DETAIL_BITS) = 64 cells per
  // axis -- a 4096-point, ~4-pixel lattice. Fine enough that k-means centroids
  // track the real task distribution rather than the grid, small enough that
  // clustering stays a sub-millisecond operation on a fixed-size input.
  private val DETAIL_BITS = 3

  // Upper bound on markers emitted per tile at zoom 0..11. This is a ceiling,
  // not a target: the separation pass below merges whatever would overlap, so
  // the actual count adapts to how much room the data occupies on screen. A
  // 256px tile fits about (256 / 25)^2 markers at the minimum separation, so
  // asking for more than 100 could never survive the merge.
  private val MAX_CLUSTERS = 100

  // Minimum on-screen distance between two emitted markers, in CSS pixels.
  // k-means always returns exactly k clusters no matter how tightly packed the
  // input is, so at low zoom a dense region collapses to a few pixels and every
  // centroid lands on top of its neighbours. Merging centroids closer than this
  // is what stops markers stacking; it also means zooming out genuinely
  // consolidates clusters instead of just redrawing the same pile.
  private val MIN_SEPARATION_PX = 25.0

  /** CSS pixels per tile edge -- the unit MIN_SEPARATION_PX is measured in. */
  private val TILE_SIZE_PX = 256.0

  // One merge pass separates centroids that were within MIN_SEPARATION_PX, but
  // the merged centroid can land back within range of a third one, so a single
  // pass is not a fixpoint. Measured over every populated tile in a full
  // pyramid, two passes converged (a third and fourth changed nothing); three
  // is that plus margin. Each pass runs on at most MAX_CLUSTERS rows, so the
  // extra pass is free.
  private val SEPARATION_PASSES = 3

  /** Highest display zoom backed by the pre-computed pyramid. */
  val MAX_CELL_ZOOM = 11

  /** Display zoom served live as individual task markers. */
  val TASK_ZOOM = 12

  // ---------------------------------------------------------------------------
  // MVT generation
  // ---------------------------------------------------------------------------

  /**
    * MVT for display zoom 0..11 with no keyword/location filter. Micro-aggregates
    * come from the pre-computed `tile_cells` pyramid (difficulty/global applied by
    * summing `counts_by_filter` buckets) and are clustered with k-means.
    */
  def getMvtCellsPrecomputed(
      z: Int,
      x: Int,
      y: Int,
      difficulty: Option[Int],
      global: Boolean
  )(implicit c: Option[Connection] = None): Array[Byte] = {
    val (xMin, yMin, xMax, yMax)     = tileBounds3857(z, x, y)
    val (cxMin, cyMin, cxMax, cyMax) = cellRange(z, x, y)

    // counts_by_filter has a fixed, code-controlled key set, so composing the
    // keys into a SQL expression is safe (no user input is interpolated).
    val countExpr = buildFilterCountKeys(difficulty, global)
      .map(k => s"COALESCE((tc.counts_by_filter->>'$k')::int, 0)")
      .mkString(" + ")

    // `weight` is the cell's *total* task count, which is what sum_lat/sum_lng
    // sum over -- so the emitted centroid stays the all-tasks centroid even
    // when a difficulty/global filter narrows the reported count. Cells with
    // nothing left after filtering drop out entirely.
    val src = s"""
      SELECT
        tc.cx,
        tc.cy,
        tc.sum_lat,
        tc.sum_lng,
        tc.task_count::double precision AS weight,
        ($countExpr)                    AS task_count
      FROM tile_cells tc
      WHERE tc.z = {levelZ}
        AND tc.cx BETWEEN {cxMin} AND {cxMax}
        AND tc.cy BETWEEN {cyMin} AND {cyMax}
        AND tc.task_count > 0
        AND ($countExpr) > 0
      ORDER BY tc.cx, tc.cy
    """

    val params = boundsParams(xMin, yMin, xMax, yMax) ++ Seq(
      NamedParameter("epsMeters", separationMeters(z)),
      NamedParameter("levelZ", detailLevel(z)),
      NamedParameter("cxMin", cxMin),
      NamedParameter("cxMax", cxMax),
      NamedParameter("cyMin", cyMin),
      NamedParameter("cyMax", cyMax)
    )
    mvtQuery(kmeansMvtQuery(src), params)
  }

  /**
    * MVT for display zoom 0..11 with keyword filters. Keyword membership cannot be
    * pre-computed, so the micro-aggregates are binned from `tasks` on the fly --
    * on the *same* grid the pre-computed path reads, then clustered by the same
    * k-means. A filtered map therefore clusters like an unfiltered one.
    */
  def getMvtCellsLive(
      z: Int,
      x: Int,
      y: Int,
      difficulty: Option[Int],
      global: Boolean,
      keywords: Option[String]
  )(implicit c: Option[Connection] = None): Array[Byte] = {
    val (xMin, yMin, xMax, yMax) = tileBounds3857(z, x, y)
    val binZoom                  = detailLevel(z) + CELL_BITS
    val filter                   = liveFilter(difficulty, global, keywords)

    // Every task here already passes the filter, so the centroid weight and
    // the reported count are the same number.
    val src = s"""
      SELECT
        lng_to_tile_x(ST_X(t.location), $binZoom) AS cx,
        lat_to_tile_y(ST_Y(t.location), $binZoom) AS cy,
        SUM(ST_Y(t.location))                     AS sum_lat,
        SUM(ST_X(t.location))                     AS sum_lng,
        COUNT(*)::double precision                AS weight,
        COUNT(*)::int                             AS task_count
      FROM tasks t
      INNER JOIN challenges c ON c.id = t.parent_id
      INNER JOIN projects   p ON p.id = c.parent_id
      ${filter.joins}
      WHERE t.location && ST_Transform(
              ST_MakeEnvelope({xMin}, {yMin}, {xMax}, {yMax}, 3857), 4326)
        AND NOT ST_IsEmpty(t.location)
        ${filter.where}
      GROUP BY 1, 2
      ORDER BY 1, 2
    """

    val params = boundsParams(xMin, yMin, xMax, yMax) ++
      Seq(NamedParameter("epsMeters", separationMeters(z))) ++ filter.params
    mvtQuery(kmeansMvtQuery(src), params)
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

    mvtQuery(query, boundsParams(xMin, yMin, xMax, yMax) ++ filter.params)
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

  /**
    * Run an MVT query and return its bytes, with JIT disabled for the duration.
    *
    * These queries carry a large *estimated* cost -- a spatial scan crossed with
    * a pile of geometry expressions -- while doing very little actual work, so
    * LLVM compilation never pays for itself. Worse, k-means pushes the estimate
    * past `jit_optimize_above_cost` / `jit_inline_above_cost` (500k by default),
    * which switches on LLVM optimization and inlining: measured on the keyword
    * path, ~400ms of compilation on top of ~44ms of query. `SET LOCAL` scopes
    * the setting to this transaction, so pooled connections are unaffected.
    */
  private def mvtQuery(query: String, params: Seq[NamedParameter])(
      implicit c: Option[Connection] = None
  ): Array[Byte] =
    this.withMRTransaction { implicit c =>
      SQL("SET LOCAL jit = off").execute()
      SQL(query).on(params: _*).as(get[Array[Byte]]("mvt").single)
    }

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

  /**
    * Pyramid level the micro-aggregates for a display tile are read from:
    * DETAIL_BITS below the display zoom, clamped to the leaf. Reading deeper
    * than the display zoom is what gives k-means a fine enough input to place
    * markers off the grid.
    */
  private def detailLevel(z: Int): Int = math.min(z + DETAIL_BITS, MAX_CELL_ZOOM)

  /**
    * MIN_SEPARATION_PX expressed in Web Mercator meters at display zoom `z`.
    * Mercator maps pixels to 3857 units linearly at every latitude, so this is
    * an exact pixel distance rather than an approximation that drifts near the
    * poles.
    */
  private def separationMeters(z: Int): Double =
    MIN_SEPARATION_PX * (WEB_MERCATOR_EXTENT * 2) / ((1L << z) * TILE_SIZE_PX)

  /**
    * Inclusive cell-coordinate range, at `detailLevel(z)`'s cell grid, covered by
    * display tile (z, x, y). Cell coordinates at level `l` are slippy-tile
    * coordinates at zoom `l + CELL_BITS`, so the display tile covers
    * `2^(CELL_BITS + detailLevel(z) - z)` of them per axis.
    */
  private def cellRange(z: Int, x: Int, y: Int): (Int, Int, Int, Int) = {
    val bits = CELL_BITS + (detailLevel(z) - z)
    val span = 1 << bits
    (x << bits, y << bits, (x << bits) + span - 1, (y << bits) + span - 1)
  }

  /**
    * Wrap a micro-aggregate source query in the k-means clustering + MVT
    * encoding shared by both zoom 0..11 paths.
    *
    * `src` must yield `sum_lat`, `sum_lng`, `weight` (the denominator those sums
    * are over) and `task_count` (the number to report), in a stable order so
    * repeated requests for the same tile cluster identically -- tiles are HTTP
    * cached, and a marker that drifts between two cache entries is worse than
    * one in a slightly suboptimal spot.
    *
    * Clustering happens on Web Mercator coordinates so distance means the same
    * thing in every direction on screen; clustering raw lon/lat would distort
    * cluster shape more and more with latitude. `src` is MATERIALIZED because it
    * is read twice -- once to size `k`, once to cluster -- and on the keyword
    * path recomputing it would mean a second scan of `tasks`. `k` is capped at
    * the input size because ST_ClusterKMeans rejects asking for more clusters
    * than it has points, and floored at 1 so the argument is always valid. An
    * empty `src` yields no rows at all, so the window function never runs and
    * ST_AsMVT's NULL becomes an empty tile.
    *
    * `m0` is the raw k-means result; `m1..mN` are the separation passes that
    * enforce MIN_SEPARATION_PX (see `separationPass`). Every stage carries the
    * same four additive columns, which is why the passes can chain: merging
    * markers is just summing them, so counts survive any number of passes.
    */
  private def kmeansMvtQuery(src: String): String = {
    val passes = 1.to(SEPARATION_PASSES).map(i => separationPass(s"m${i - 1}", s"m$i"))
    s"""
    WITH src AS MATERIALIZED (
      $src
    ),
    k AS (
      SELECT GREATEST(1, LEAST($MAX_CLUSTERS, COUNT(*)))::int AS n FROM src
    ),
    m0 AS (
      SELECT
        SUM(sum_lat)    AS sum_lat,
        SUM(sum_lng)    AS sum_lng,
        SUM(weight)     AS weight,
        SUM(task_count) AS task_count
      FROM (
        SELECT
          ST_ClusterKMeans(
            ST_Transform(
              ST_SetSRID(ST_MakePoint(src.sum_lng / src.weight, src.sum_lat / src.weight), 4326),
              3857),
            k.n) OVER () AS cluster_id,
          src.sum_lat,
          src.sum_lng,
          src.weight,
          src.task_count
        FROM src CROSS JOIN k
      ) clustered
      GROUP BY cluster_id
    ),
    ${passes.mkString(",\n    ")}
    SELECT COALESCE(ST_AsMVT(tile, 'default', 4096, 'geom'), ''::bytea) AS mvt
    FROM (
      SELECT
        ST_AsMVTGeom(
          ST_Transform(
            ST_SetSRID(ST_MakePoint(sum_lng / weight, sum_lat / weight), 4326),
            3857),
          ST_MakeEnvelope({xMin}, {yMin}, {xMax}, {yMax}, 3857),
          4096, 64, true
        ) AS geom,
        2 AS group_type,
        task_count::int AS task_count
      FROM m$SEPARATION_PASSES
    ) AS tile
  """
  }

  /**
    * One separation pass: collapse every marker in `from` that sits within
    * MIN_SEPARATION_PX of another into a single weighted marker in `to`.
    *
    * DBSCAN with `minpoints = 1` is single-linkage clustering at `eps` -- every
    * point is a core point, so nothing is reported as noise. That matters:
    * DBSCAN marks noise with a NULL cluster id, and a GROUP BY would fold all of
    * it into one bogus marker sitting at the average of unrelated places.
    */
  private def separationPass(from: String, to: String): String = s"""$to AS (
      SELECT
        SUM(sum_lat)    AS sum_lat,
        SUM(sum_lng)    AS sum_lng,
        SUM(weight)     AS weight,
        SUM(task_count) AS task_count
      FROM (
        SELECT
          ST_ClusterDBSCAN(
            ST_Transform(
              ST_SetSRID(ST_MakePoint(c.sum_lng / c.weight, c.sum_lat / c.weight), 4326),
              3857),
            {epsMeters}::float8, 1) OVER () AS merge_id,
          c.sum_lat,
          c.sum_lng,
          c.weight,
          c.task_count
        FROM $from c
      ) q
      GROUP BY merge_id
    )"""

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

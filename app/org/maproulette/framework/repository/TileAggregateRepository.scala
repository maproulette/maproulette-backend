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
  * tile reads micro-aggregates DETAIL_BITS levels *deeper* than the display
  * zoom (a 64x64 lattice of them per tile, ~8 CSS pixels apart) and runs
  * `ST_ClusterKMeans` over them in Web Mercator. Marker positions follow the
  * data instead of the grid, and cluster extent adapts to local density.
  *
  * Two screen-pixel measures then decide how many of those clusters are drawn
  * and how far apart they sit. k-means returns exactly k clusters however
  * tightly packed its input is, so a dense region that shrinks to a few pixels
  * at low zoom would otherwise get every one of its k centroids stacked on the
  * same spot.
  *
  *   - `k` is the number of CANDIDATE_PITCH_PX grid squares the tile's
  *     micro-aggregates actually occupy, so cluster count tracks how much room
  *     the data takes up on screen and zooming out consolidates markers for
  *     real, because the data covers fewer squares.
  *   - The candidates are then thinned to a hard minimum on-screen distance of
  *     MIN_SEPARATION_PX (see `separationWalk`): biggest cluster first, and any
  *     candidate closer than that to one already kept is absorbed into the
  *     nearest kept marker rather than drawn. Frontend cluster bubbles are up
  *     to 54 CSS px across, so this is what stops them overlapping.
  *
  * The grid sizes `k` at half the separation distance on purpose: offering
  * k-means more candidate positions than can survive the thinning lets it place
  * them on the data, and the walk keeps whichever ones fit. Measured on a
  * 166k-task database, sizing candidates on the separation grid instead cost
  * about a quarter of the markers for no change in spacing.
  *
  * This replaced a chain of `ST_ClusterDBSCAN(eps, minpoints = 1)` merge passes,
  * which is single-linkage clustering: A merges with B, B with C, and the
  * cluster walks across the tile a marker at a time. Dense regions are exactly
  * where every centroid has a neighbour within eps, so the walk never stopped --
  * on that same database the tile covering South Africa emitted *one* marker at
  * every display zoom from 2 to 6, and at z=5 its member cells spanned
  * 1005 x 1202 km (205 x 246 CSS px). The thinning walk cannot chain: it
  * compares each candidate against the markers already kept, never against the
  * ones those markers displaced, so `MIN_SEPARATION_PX` is a floor on the
  * distance between drawn markers and a ceiling on how far an absorbed count
  * sits from the marker reporting it. The same tile now emits 4 markers at z=3,
  * 29 at z=5 and 37 at z=11, no two of them closer than 64 CSS px.
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
  * A tile is a pure function of (z, x, y). The map applies no filters at all:
  * challenge-level filters (difficulty, global, keywords) belong to the grid and
  * list views, so the map shows every task that is available work and the
  * pyramid stores a single count per cell (evolution 123). Nothing here reads a
  * user's identity or a filter parameter, which is what keeps tiles HTTP
  * cacheable and lets zoom 0..11 come entirely from pre-computed cells.
  *
  * All three code paths (this repository's z=12 query, plus `rebuild_leaf_cell`
  * and `rebuild_all_tile_cells`, last redefined in evolution 123) share one
  * eligibility filter: a task is available work when it has a valid location,
  * `status IN (0,3,6)`, is not archived, and its challenge/project are enabled
  * and not deleted or archived, with the challenge not paused. `enabled` is
  * MapRoulette's "discoverable" flag, so requiring it on both challenge and
  * project keeps hidden work off the explore map; a paused challenge has work
  * that cannot be locked or completed, so it is off the map too. Keep all three
  * paths in sync.
  */
@Singleton
class TileAggregateRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = "tile_cells"

  // Web Mercator world extent in meters (half of total extent).
  private val WEB_MERCATOR_EXTENT = 20037508.342789244

  // A cell at display zoom z is a slippy tile at zoom z + CELL_BITS, so each
  // pyramid level holds a 2^CELL_BITS square of cells per display tile.
  // Must match the tile evolutions (107).
  private val CELL_BITS = 4

  // How many pyramid levels below the display zoom to read micro-aggregates
  // from. A display tile at zoom z is fed level min(z + DETAIL_BITS,
  // MAX_CELL_ZOOM), giving up to 2^(CELL_BITS + DETAIL_BITS) = 64 cells per
  // axis -- a 4096-point, ~4-pixel lattice. Fine enough that k-means centroids
  // track the real task distribution rather than the grid, small enough that
  // clustering stays a sub-millisecond operation on a fixed-size input.
  //
  // This is the knob to turn for lattice resolution, not CELL_BITS. The level
  // is clamped at MAX_CELL_ZOOM, so near the top of the pyramid the display
  // zoom eats into the depth available and the tile falls back on CELL_BITS
  // alone: at z = MAX_CELL_ZOOM a tile reads 2^CELL_BITS per axis whatever
  // DETAIL_BITS says. With CELL_BITS = 4 that is 16 per axis, 256 cells of
  // 32 CSS px -- finer than MIN_SEPARATION_PX, so several of them still fall in
  // one grid square and the clustering has something to do. Coarsening the grid
  // to CELL_BITS = 3 instead would leave 64 cells of 64px there, each wider than
  // a grid square: one occupied square per cell, k equal to the input size,
  // k-means an identity, and the top cluster zoom back to one marker per cell.
  private val DETAIL_BITS = 2

  // Hard minimum on-screen distance between two emitted markers, in CSS pixels.
  // Frontend cluster bubbles run 30 to 54 CSS px across (radius 15-27 by count),
  // so 64 leaves the largest pair a visible gap and every smaller pair more.
  // `separationWalk` enforces this exactly rather than on average: raise it for
  // an emptier map, lower it for a denser one, but not below 54 or bubbles
  // start to touch.
  private val MIN_SEPARATION_PX = 64.0

  // Pitch of the grid whose occupied squares set `k`, the number of candidate
  // clusters k-means places. Half the separation distance: the thinning walk can
  // only keep candidates that are far enough apart, so handing it a finer choice
  // of positions leaves more of them standing (measured: about a quarter more
  // markers than sizing `k` on the separation grid itself, at identical
  // spacing). Finer still mostly buys k-means work that the walk throws away.
  private val CANDIDATE_PITCH_PX = MIN_SEPARATION_PX / 2

  /**
    * CSS pixels per tile edge -- the unit MIN_SEPARATION_PX is measured in.
    *
    * 512, not 256: MapLibre vector sources default to `tileSize: 512`, so the
    * frontend requests zoom `floor(map zoom)` and draws one of these tiles
    * across 512 CSS pixels. Measuring against 256 made every pixel figure here
    * mean twice as much ground as it claimed.
    */
  private val TILE_SIZE_PX = 512.0

  // Upper bound on the candidate clusters k-means is asked for. A cost backstop,
  // not a shaping knob: `k` is the number of occupied candidate squares and a
  // tile holds only (TILE_SIZE_PX / CANDIDATE_PITCH_PX)^2 of them, so the
  // ceiling is set to exactly that and never binds. Lowering it would stop the
  // grid, rather than this number, from deciding the marker count. Markers
  // actually emitted are far fewer -- the thinning walk keeps at most
  // (TILE_SIZE_PX / MIN_SEPARATION_PX)^2 of them.
  private val MAX_CLUSTERS =
    math.ceil(math.pow(TILE_SIZE_PX / CANDIDATE_PITCH_PX, 2)).toInt

  /** Highest display zoom backed by the pre-computed pyramid. */
  val MAX_CELL_ZOOM = 11

  /** Display zoom served live as individual task markers. */
  val TASK_ZOOM = 12

  // ---------------------------------------------------------------------------
  // MVT generation
  // ---------------------------------------------------------------------------

  /**
    * MVT for display zoom 0..11. Micro-aggregates come from the pre-computed
    * `tile_cells` pyramid and are clustered with k-means. This is every request
    * below z=12: with no filters to apply, there is nothing a tile needs from
    * `tasks`, so the cost is bounded by the cell count (a few thousand rows read
    * through the primary key) rather than by how many tasks the database holds.
    */
  def getMvtCellsPrecomputed(
      z: Int,
      x: Int,
      y: Int
  )(implicit c: Option[Connection] = None): Array[Byte] =
    mvtQuery(kmeansMvtQuery(precomputedSource), precomputedParams(z, x, y))

  /**
    * Micro-aggregate source for the pre-computed path: the cells of one display
    * tile. A cell's `sum_lat`/`sum_lng` are sums over its tasks, so dividing by
    * `task_count` gives their centroid, and the count is also the weight the
    * clustering carries -- the number the marker will report.
    */
  private val precomputedSource: String = """
      SELECT
        tc.cx,
        tc.cy,
        tc.sum_lat,
        tc.sum_lng,
        tc.task_count::double precision AS weight,
        tc.task_count                   AS task_count
      FROM tile_cells tc
      WHERE tc.z = {levelZ}
        AND tc.cx BETWEEN {cxMin} AND {cxMax}
        AND tc.cy BETWEEN {cyMin} AND {cyMax}
        AND tc.task_count > 0
      ORDER BY tc.cx, tc.cy
    """

  /** Bound parameters shared by every pre-computed-path query for a tile. */
  private def precomputedParams(z: Int, x: Int, y: Int): Seq[NamedParameter] = {
    val (xMin, yMin, xMax, yMax)     = tileBounds3857(z, x, y)
    val (cxMin, cyMin, cxMax, cyMax) = cellRange(z, x, y)
    boundsParams(xMin, yMin, xMax, yMax) ++ clusteringParams(z) ++ Seq(
      NamedParameter("levelZ", detailLevel(z)),
      NamedParameter("cxMin", cxMin),
      NamedParameter("cxMax", cxMax),
      NamedParameter("cyMin", cyMin),
      NamedParameter("cyMax", cyMax)
    )
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
      y: Int
  )(implicit c: Option[Connection] = None): Array[Byte] = {
    val (xMin, yMin, xMax, yMax) = tileBounds3857(z, x, y)

    val query = s"""
      WITH eligible AS (
        SELECT t.id, t.status, t.priority, t.parent_id AS challenge_id, t.location
        FROM tasks t
        INNER JOIN challenges c ON c.id = t.parent_id
        INNER JOIN projects   p ON p.id = c.parent_id
        WHERE t.location && ST_Transform(
                ST_MakeEnvelope({xMin}, {yMin}, {xMax}, {yMax}, 3857), 4326)
          AND NOT ST_IsEmpty(t.location)
          $AVAILABLE_WORK
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

    mvtQuery(query, boundsParams(xMin, yMin, xMax, yMax))
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
    * LLVM compilation never pays for itself. Worse, the estimate can pass
    * `jit_optimize_above_cost` / `jit_inline_above_cost` (500k by default),
    * which switches on LLVM optimization and inlining. Measured on the z=12
    * query over a whole-world envelope: 903ms with JIT on against 136ms with it
    * off, nearly all of it compilation. `SET LOCAL` scopes the setting to this
    * transaction, so pooled connections are unaffected.
    */
  private def mvtQuery(query: String, params: Seq[NamedParameter])(
      implicit c: Option[Connection] = None
  ): Array[Byte] =
    this.withMRTransaction { implicit c =>
      SQL("SET LOCAL jit = off").execute()
      SQL(query).on(params: _*).as(get[Array[Byte]]("mvt").single)
    }

  /**
    * The eligibility filter: what makes a task available work. Mirrored
    * verbatim by `rebuild_leaf_cell` and `rebuild_all_tile_cells` (evolution
    * 123), which is how the pre-computed pyramid and this live z=12 query agree
    * on what the map shows. Keep them in sync.
    *
    * Assumes `tasks t`, `challenges c` and `projects p` are in scope. No user
    * input reaches it, and it takes no parameters -- there is nothing to filter
    * by beyond availability itself.
    */
  private val AVAILABLE_WORK: String =
    """AND t.status IN (0, 3, 6)
          AND t.archived = false
          AND c.deleted = false AND c.enabled = true AND c.is_archived = false
          AND c.paused = false
          AND p.deleted = false AND p.enabled = true"""

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
    * Pyramid level the micro-aggregates for a display tile are read from:
    * DETAIL_BITS below the display zoom, clamped to the leaf. Reading deeper
    * than the display zoom is what gives k-means a fine enough input to place
    * markers off the grid.
    */
  private def detailLevel(z: Int): Int = math.min(z + DETAIL_BITS, MAX_CELL_ZOOM)

  /**
    * A CSS-pixel distance expressed in Web Mercator meters at display zoom `z`.
    * Mercator maps pixels to 3857 units linearly at every latitude, so this is
    * an exact pixel distance rather than an approximation that drifts near the
    * poles.
    */
  private def pixelsToMeters(px: Double, z: Int): Double =
    px * (WEB_MERCATOR_EXTENT * 2) / ((1L << z) * TILE_SIZE_PX)

  /** Bound parameters for the two pixel distances the clustering is sized by. */
  private def clusteringParams(z: Int): Seq[NamedParameter] =
    Seq(
      NamedParameter("epsMeters", pixelsToMeters(MIN_SEPARATION_PX, z)),
      NamedParameter("candidateMeters", pixelsToMeters(CANDIDATE_PITCH_PX, z))
    )

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
    * The k-means clustering chain shared by both zoom 0..11 paths, wrapped
    * around a micro-aggregate source query (see `clusteredQuery`).
    *
    * `src` must yield `sum_lat`, `sum_lng`, `weight` (the denominator those sums
    * are over) and `task_count` (the number to report), in a stable order.
    * ST_ClusterKMeans seeds from the order its input arrives in: the same points
    * in a different order give a genuinely different partition, not just
    * relabelled clusters, and the sort does reach it -- `k` is a single row, so
    * the join over `src` is driven by the ordered CTE scan. Tiles are HTTP
    * cached, so a marker that moves between two requests for the same tile is a
    * correctness problem; the grid merge only pins down markers that shared a
    * square, so the ordering is what pins down the rest.
    *
    * Clustering happens on Web Mercator coordinates so distance means the same
    * thing in every direction on screen; clustering raw lon/lat would distort
    * cluster shape more and more with latitude. `src` is MATERIALIZED because it
    * is read twice -- once to size `k`, once to cluster. `k` is capped at
    * the input size because ST_ClusterKMeans rejects asking for more clusters
    * than it has points, and floored at 1 so the argument is always valid. An
    * empty `src` yields no rows at all, so the window function never runs and
    * ST_AsMVT's NULL becomes an empty tile.
    *
    * `candidates` is the raw k-means result and `markers` is what survives the
    * thinning walk (see `separationWalk`), with the counts of the candidates it
    * displaced folded in. `markers` still carries the same four columns, but
    * `weight` is no longer the denominator of `task_count`: it stays the weight
    * of the surviving candidate alone, so `sum_lat / weight` is that candidate's
    * own centroid and absorbing a neighbour raises a marker's count without
    * moving it. Nothing downstream needs the two to agree -- the position comes
    * from the sums and the label from `task_count`.
    */
  private def kmeansMvtQuery(src: String): String =
    clusteredQuery(
      src,
      s"""
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
      FROM markers
    ) AS tile"""
    )

  /**
    * The clustered markers for a tile as plain numbers, before MVT encoding.
    * Shares the whole `src` -> k-means -> grid-merge chain with the MVT paths,
    * so a test can assert where a marker landed and what it reports. Markers
    * outside the tile are dropped, matching ST_AsMVTGeom's clipping.
    */
  private[repository] def clusterMarkers(
      z: Int,
      x: Int,
      y: Int
  )(implicit c: Option[Connection] = None): List[(Double, Double, Int)] = {
    val query = clusteredQuery(
      precomputedSource,
      s"""
    SELECT lat, lng, task_count
    FROM (
      SELECT
        sum_lat / weight AS lat,
        sum_lng / weight AS lng,
        task_count::int  AS task_count
      FROM markers
    ) positioned
    WHERE ST_Intersects(
            ST_Transform(ST_SetSRID(ST_MakePoint(lng, lat), 4326), 3857),
            ST_MakeEnvelope({xMin}, {yMin}, {xMax}, {yMax}, 3857))"""
    )
    this.withMRTransaction { implicit c =>
      SQL(query)
        .on(precomputedParams(z, x, y): _*)
        .as((get[Double]("lat") ~ get[Double]("lng") ~ get[Int]("task_count")).*)
        .map { case lat ~ lng ~ count => (lat, lng, count) }
    }
  }

  /**
    * Wrap a micro-aggregate source query in the k-means clustering shared by
    * both zoom 0..11 paths, then apply `projection` to the clustered result.
    */
  private def clusteredQuery(src: String, projection: String): String =
    s"""
    WITH RECURSIVE src AS MATERIALIZED (
      $src
    ),
    pts AS (
      ${mercatorPoints("src")}
    ),
    k AS (
      SELECT GREATEST(1, LEAST($MAX_CLUSTERS, COUNT(*)))::int AS n
      FROM (
        SELECT DISTINCT
          FLOOR(ST_X(pts.geom) / {candidateMeters}::float8),
          FLOOR(ST_Y(pts.geom) / {candidateMeters}::float8)
        FROM pts
      ) occupied
    ),
    candidates AS (
      SELECT
        SUM(sum_lat)    AS sum_lat,
        SUM(sum_lng)    AS sum_lng,
        SUM(weight)     AS weight,
        SUM(task_count) AS task_count
      FROM (
        SELECT
          ST_ClusterKMeans(pts.geom, k.n) OVER () AS cluster_id,
          pts.sum_lat,
          pts.sum_lng,
          pts.weight,
          pts.task_count
        FROM pts CROSS JOIN k
      ) clustered
      GROUP BY cluster_id
    ),
    $separationWalk
    $projection
  """

  /**
    * Project a relation of weighted sums to its Web Mercator marker positions,
    * keeping the four additive columns alongside. `sum_lat / weight` is the
    * weighted centroid of whatever the row aggregates.
    */
  private def mercatorPoints(from: String): String = s"""SELECT
        $from.sum_lat,
        $from.sum_lng,
        $from.weight,
        $from.task_count,
        ST_Transform(
          ST_SetSRID(
            ST_MakePoint($from.sum_lng / $from.weight, $from.sum_lat / $from.weight), 4326),
          3857) AS geom
      FROM $from"""

  /**
    * The candidate ranking, the thinning walk over it, and the `markers` it
    * leaves: every candidate cluster in rank order, keeping one only when no
    * already-kept marker sits within MIN_SEPARATION_PX of it.
    *
    * Rank is by descending task count, so the markers a reader would notice
    * missing are the ones kept, and a displaced candidate is always smaller than
    * whatever displaced it. Position breaks ties, which makes the emitted
    * markers independent of how k-means happened to label its clusters.
    *
    * Comparing against the kept set -- rather than against every earlier
    * candidate -- is what bounds the damage. Each displaced candidate is within
    * MIN_SEPARATION_PX of the marker that displaced it, so folding its count
    * into the nearest kept marker moves that count by less than one separation
    * distance. Comparing against all earlier candidates instead would also give
    * a valid separation, but a candidate could then be displaced by one that was
    * itself displaced, leaving its tasks reported arbitrarily far away.
    *
    * The walk is inherently sequential -- whether a candidate is kept depends on
    * the whole prefix before it -- hence the recursive CTE carrying the kept
    * ranks in an array. It runs on at most MAX_CLUSTERS rows.
    */
  private val separationWalk: String = s"""ranked AS (
      SELECT
        ROW_NUMBER() OVER (
          ORDER BY placed.task_count DESC, placed.sum_lat, placed.sum_lng
        ) AS rank,
        placed.sum_lat,
        placed.sum_lng,
        placed.weight,
        placed.task_count,
        placed.geom
      FROM (
        ${mercatorPoints("candidates")}
      ) placed
    ),
    walk AS (
      SELECT ranked.rank, TRUE AS kept, ARRAY[ranked.rank] AS kept_ranks
      FROM ranked
      WHERE ranked.rank = 1
      UNION ALL
      SELECT
        next.rank,
        NOT EXISTS (
          SELECT 1
          FROM ranked held
          WHERE held.rank = ANY(walk.kept_ranks)
            AND ST_DWithin(held.geom, next.geom, {epsMeters}::float8)
        ) AS kept,
        CASE
          WHEN EXISTS (
            SELECT 1
            FROM ranked held
            WHERE held.rank = ANY(walk.kept_ranks)
              AND ST_DWithin(held.geom, next.geom, {epsMeters}::float8)
          ) THEN walk.kept_ranks
          ELSE walk.kept_ranks || next.rank
        END AS kept_ranks
      FROM walk
      INNER JOIN ranked next ON next.rank = walk.rank + 1
    ),
    kept AS (
      SELECT ranked.*
      FROM ranked
      INNER JOIN walk ON walk.rank = ranked.rank AND walk.kept
    ),
    markers AS (
      SELECT
        kept.sum_lat,
        kept.sum_lng,
        kept.weight,
        kept.task_count + COALESCE(absorbed.task_count, 0) AS task_count
      FROM kept
      LEFT JOIN (
        SELECT host.rank AS host_rank, SUM(displaced.task_count) AS task_count
        FROM (
          SELECT ranked.*
          FROM ranked
          INNER JOIN walk ON walk.rank = ranked.rank AND NOT walk.kept
        ) displaced
        CROSS JOIN LATERAL (
          SELECT kept.rank
          FROM kept
          ORDER BY kept.geom <-> displaced.geom, kept.rank
          LIMIT 1
        ) host
        GROUP BY host.rank
      ) absorbed ON absorbed.host_rank = kept.rank
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

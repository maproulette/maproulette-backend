/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import anorm._
import org.maproulette.framework.util.{FrameworkHelper, TileAggregateRepoTag}
import play.api.Application
import play.api.db.Database

/**
  * Integration tests for the tile pipeline:
  *   1. A task mutation fires `mark_dirty_on_task_change_trigger`, enqueueing
  *      the affected leaf cell in `tile_dirty_cells`.
  *   2. `rebuildDirtyCells` drains the queue, recomputing each leaf cell from
  *      the base tables and rolling the change up to z=0.
  *   3. MVT generation clusters those cells with k-means and encodes the result.
  *
  * A tile takes no filters -- the map shows all available work -- so these
  * assert on cell contents and cluster geometry only.
  *
  * The background `TileDirtyListener` is disabled under the test configuration
  * so queue state is observable deterministically here.
  */
class TileAggregateRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  val repository: TileAggregateRepository =
    this.application.injector.instanceOf(classOf[TileAggregateRepository])

  val db: Database = this.application.injector.instanceOf(classOf[Database])

  override implicit val projectTestName: String = "TileAggregateRepositorySpecProject"

  "TileAggregateRepository" should {
    "drain a queued dirty cell via rebuildDirtyCells" taggedAs TileAggregateRepoTag in {
      // Seed a dirty leaf cell at coordinates with no tasks; the drain should
      // pop it and correctly leave no tile_cells row behind.
      db.withConnection { implicit c =>
        SQL"DELETE FROM tile_dirty_cells".executeUpdate()
        SQL"INSERT INTO tile_dirty_cells (cx, cy) VALUES (1, 1)".executeUpdate()
      }

      val processed = repository.rebuildDirtyCells(limit = 1000)
      processed must be >= 1
      repository.getDirtyCellCount() mustEqual 0
    }

    "fire the task-change trigger and queue a dirty cell on status update" taggedAs
      TileAggregateRepoTag in {
      db.withConnection { implicit c =>
        SQL"DELETE FROM tile_dirty_cells".executeUpdate()
      }

      // A raw UPDATE exercises the trigger directly, without setTaskStatus's
      // synchronous post-commit drain emptying the queue again.
      db.withConnection { implicit c =>
        SQL"UPDATE tasks SET status = 3 WHERE id = ${defaultTask.id}".executeUpdate()
      }

      // The trigger marks the leaf cell covering the task's location.
      repository.getDirtyCellCount() must be >= 1

      val processed = repository.rebuildDirtyCells(limit = 1000)
      processed must be >= 1
      repository.getDirtyCellCount() mustEqual 0
    }

    // -----------------------------------------------------------------------
    // MVT generation
    // -----------------------------------------------------------------------

    "read micro-aggregates from the detail level, not the display zoom" taggedAs
      TileAggregateRepoTag in {
      // Only a level-2 row exists -- the detail level for display zoom 0. If the
      // clustering ever went back to reading `z = <display zoom>`, this tile
      // would come back empty.
      db.withConnection { implicit c =>
        SQL"DELETE FROM tile_cells".executeUpdate()
        SQL"""INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng)
              VALUES (2, 10, 20, 5, -164.6, -304.06)""".executeUpdate()
      }

      repository.getMvtCellsPrecomputed(0, 0, 0).length must be > 0
    }

    "return an empty tile where there are no cells" taggedAs TileAggregateRepoTag in {
      db.withConnection { implicit c =>
        SQL"DELETE FROM tile_cells".executeUpdate()
      }

      // Empty input must not reach ST_ClusterKMeans with k = 0.
      repository.getMvtCellsPrecomputed(0, 0, 0) mustEqual Array.empty[Byte]
    }

    "cluster a populated tile identically on repeated requests" taggedAs
      TileAggregateRepoTag in {
      // More cells than the tile has candidate-grid squares, so k-means has to
      // actually partition them. With one cell per square it returns one cluster
      // per input and the whole clustering step is an identity, which would
      // prove nothing here.
      seedDetailCells(256)

      val first  = repository.getMvtCellsPrecomputed(0, 0, 0)
      val second = repository.getMvtCellsPrecomputed(0, 0, 0)

      first.length must be > 0
      // Tiles are HTTP cached, so a marker that moves between two requests for
      // the same tile is a correctness problem, not a cosmetic one.
      second mustEqual first
    }

    "weight a merged cluster's position by the tasks each cell holds" taggedAs
      TileAggregateRepoTag in {
      // Two cells close enough to merge into one marker at display zoom 0, one
      // holding 100 tasks and the other 1. The marker must land next to the 100
      // and report all 101: `sum_lat`/`sum_lng` are sums over tasks, so dividing
      // the merged sums by the merged count is a count-weighted mean, and an
      // unweighted midpoint would sit at lng 5 instead.
      db.withConnection { implicit c =>
        SQL"DELETE FROM tile_cells".executeUpdate()
        SQL"""INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng)
              VALUES (2, 30, 30, 1, 0.0, 0.0)""".executeUpdate()
        SQL"""INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng)
              VALUES (2, 31, 30, 100, 0.0, 1000.0)""".executeUpdate()
      }

      val markers = repository.clusterMarkers(0, 0, 0)

      markers must have size 1
      val (_, lng, count) = markers.head
      count mustEqual 101
      // Cell centroids are lng 0 and lng 10; weighting by 1 vs 100 lands at ~9.9.
      lng must be > 9.0
    }

    "report every task in the cells it read" taggedAs TileAggregateRepoTag in {
      // 64 cells of 10 tasks each: whatever the clustering does with them, the
      // markers it emits must account for all 640. Nothing is filtered out on
      // the way through.
      seedDetailCells(64)

      val markers = repository.clusterMarkers(0, 0, 0)

      markers.map(_._3).sum mustEqual 640
    }

    "merge clusters that would overlap on screen" taggedAs TileAggregateRepoTag in {
      // 64 micro-aggregates packed into a single leaf cell's worth of ground: at
      // display zoom 0 the whole group sits inside one candidate-grid square, so
      // k-means is asked for a single cluster and one marker comes back.
      db.withConnection { implicit c =>
        SQL"DELETE FROM tile_cells".executeUpdate()
        0.until(64).foreach { i =>
          val lat = -32.90 + (i / 8) * 0.001
          val lng = -60.80 + (i % 8) * 0.001
          SQL"""INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng)
                VALUES (2, ${20 + i % 8}, ${34 + i / 8}, 10, ${lat * 10}, ${lng * 10})"""
            .executeUpdate()
        }
      }

      val world = repository.getMvtCellsPrecomputed(0, 0, 0)
      world.length must be > 0
      // One feature: an MVT layer with a single point stays small. 64 separate
      // stacked markers -- the pre-merge behaviour -- would be several times this.
      world.length must be < 120
    }

    "keep a chain of nearby clusters apart instead of collapsing the tile" taggedAs
      TileAggregateRepoTag in {
      // 16 micro-aggregates strung along the equator 20 degrees apart. At display
      // zoom 0 MIN_SEPARATION_PX is 45 degrees of longitude, so every cell is
      // inside the separation distance of its neighbour while the chain as a
      // whole spans most of the tile.
      //
      // That is what the old transitive `ST_ClusterDBSCAN(eps, minpoints = 1)`
      // merge could not survive: A merges with B, B with C, and the whole line
      // walks into a single marker sitting in the middle of it. On production
      // data that collapsed entire dense tiles to one marker. The thinning walk
      // compares each candidate only against the markers already kept, so the
      // line has to come back resolved into a marker every 45 degrees.
      db.withConnection { implicit c =>
        SQL"DELETE FROM tile_cells".executeUpdate()
        0.until(16).foreach { i =>
          val lng = -150.0 + i * 20.0
          SQL"""INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng)
                VALUES (2, $i, 32, 10, 0.0, ${lng * 10})"""
            .executeUpdate()
        }
      }

      val markers = repository.clusterMarkers(0, 0, 0)

      // A 300 degree span at one marker per 45 degrees is about 6 markers, where
      // chaining gave exactly one. The bound stays under that so it does not
      // pin down the exact k-means partition.
      markers.size must be >= 5
      markers.map(_._3).sum mustEqual 160
      // No marker may have swallowed the whole line.
      markers.map(_._3).max must be < 160

      // The separation floor, checked directly: every cell sits on the equator,
      // so the on-screen distance between two markers is their longitude gap,
      // and MIN_SEPARATION_PX is 45 degrees of it at display zoom 0.
      val lngs = markers.map(_._2).sorted
      lngs.zip(lngs.tail).foreach {
        case (west, east) => (east - west) must be >= 44.0
      }
    }

    "serve the task-level path" taggedAs TileAggregateRepoTag in {

      // The one path that still reads `tasks`. It runs through
      // `SET LOCAL jit = off`, so this also proves that statement is accepted
      // on a pooled connection.
      noException must be thrownBy repository.getMvtTasksLive(12, 0, 0)
    }
  }

  /**
    * Seed `count` micro-aggregates spread across the detail level for display
    * tile 0/0/0 (level 2, a 16-wide block of the 64x64 cell range), on a lattice
    * that stays inside valid lat/lng for counts up to 256.
    */
  private def seedDetailCells(count: Int): Unit = {
    db.withConnection { implicit c =>
      SQL"DELETE FROM tile_cells".executeUpdate()
      0.until(count).foreach { i =>
        val cx  = i % 16
        val cy  = i / 16
        val lat = -60.0 + cy * 7.0
        val lng = -170.0 + cx * 21.0
        SQL"""INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng)
              VALUES (2, $cx, $cy, 10, ${lat * 10}, ${lng * 10})"""
          .executeUpdate()
      }
    }
  }

}

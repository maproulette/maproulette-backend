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
      // Only a level-3 row exists -- the detail level for display zoom 0. If the
      // clustering ever went back to reading `z = <display zoom>`, this tile
      // would come back empty.
      db.withConnection { implicit c =>
        SQL"DELETE FROM tile_cells".executeUpdate()
        SQL"""INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng, counts_by_filter)
              VALUES (3, 10, 20, 5, -164.6, -304.06, '{"d0_gf": 5}'::jsonb)""".executeUpdate()
      }

      repository.getMvtCellsPrecomputed(0, 0, 0, None, global = true).length must be > 0
    }

    "return an empty tile where there are no cells" taggedAs TileAggregateRepoTag in {
      db.withConnection { implicit c =>
        SQL"DELETE FROM tile_cells".executeUpdate()
      }

      // Empty input must not reach ST_ClusterKMeans with k = 0.
      repository.getMvtCellsPrecomputed(0, 0, 0, None, global = true) mustEqual Array.empty[Byte]
    }

    "cluster a populated tile identically on repeated requests" taggedAs
      TileAggregateRepoTag in {
      seedDetailCells(64)

      val first  = repository.getMvtCellsPrecomputed(0, 0, 0, None, global = true)
      val second = repository.getMvtCellsPrecomputed(0, 0, 0, None, global = true)

      first.length must be > 0
      // Tiles are HTTP cached, so a marker that moves between two requests for
      // the same tile is a correctness problem, not a cosmetic one.
      second mustEqual first
    }

    "apply the difficulty filter to clustered counts" taggedAs TileAggregateRepoTag in {
      seedDetailCells(64)

      // Every seeded cell is difficulty 1, so asking for difficulty 2 must clear
      // the tile: clustering happens after the counts_by_filter buckets are
      // summed, not before.
      repository.getMvtCellsPrecomputed(0, 0, 0, Some(1), global = true).length must be > 0
      repository.getMvtCellsPrecomputed(0, 0, 0, Some(2), global = true) mustEqual
        Array.empty[Byte]
    }

    "merge clusters that would overlap on screen" taggedAs TileAggregateRepoTag in {
      // 64 micro-aggregates packed into a single leaf cell's worth of ground:
      // at display zoom 0 the whole group spans well under the 25px minimum, so
      // k-means' 64 candidate centroids must collapse to exactly one marker.
      db.withConnection { implicit c =>
        SQL"DELETE FROM tile_cells".executeUpdate()
        0.until(64).foreach { i =>
          val lat = -32.90 + (i / 8) * 0.001
          val lng = -60.80 + (i % 8) * 0.001
          SQL"""INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng, counts_by_filter)
                VALUES (3, ${20 + i % 8}, ${34 + i / 8}, 10, ${lat * 10}, ${lng * 10},
                        '{"d1_gf": 10}'::jsonb)""".executeUpdate()
        }
      }

      val world = repository.getMvtCellsPrecomputed(0, 0, 0, None, global = true)
      world.length must be > 0
      // One feature: an MVT layer with a single point stays small. 64 separate
      // stacked markers -- the pre-merge behaviour -- would be several times this.
      world.length must be < 120
    }

    "serve the keyword-filtered and task-level paths" taggedAs TileAggregateRepoTag in {

      // Exercises the on-the-fly binning + k-means path and the z=12 task path.
      // Both run through `SET LOCAL jit = off`, so this also proves that
      // statement is accepted on a pooled connection.
      noException must be thrownBy
        repository.getMvtCellsLive(0, 0, 0, None, global = true, Some("nonexistent-keyword"))
      noException must be thrownBy
        repository.getMvtTasksLive(12, 0, 0, None, global = true, None)
    }
  }

  /**
    * Seed `count` micro-aggregates spread across the detail level for display
    * tile 0/0/0 (level 3, an 8x8 block of the 64x64 cell range). Enough distinct
    * points that k-means has to actually partition them rather than hand back
    * one cluster per cell.
    */
  private def seedDetailCells(count: Int): Unit = {
    db.withConnection { implicit c =>
      SQL"DELETE FROM tile_cells".executeUpdate()
      0.until(count).foreach { i =>
        val cx  = i % 8
        val cy  = i / 8
        val lat = -60.0 + cy * 5.0
        val lng = -170.0 + cx * 40.0
        SQL"""INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng, counts_by_filter)
              VALUES (3, $cx, $cy, 10, ${lat * 10}, ${lng * 10}, '{"d1_gf": 10}'::jsonb)"""
          .executeUpdate()
      }
    }
  }

}

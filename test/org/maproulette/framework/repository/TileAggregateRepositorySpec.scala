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
  * Integration tests for the grid-binned tile pipeline:
  *   1. A task mutation fires `mark_dirty_on_task_change_trigger`, enqueueing
  *      the affected leaf cell in `tile_dirty_cells`.
  *   2. `rebuildDirtyCells` drains the queue, recomputing each leaf cell from
  *      the base tables and rolling the change up to z=0.
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
  }
}

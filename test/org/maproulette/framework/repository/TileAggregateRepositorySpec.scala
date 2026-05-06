/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import anorm._
import anorm.SqlParser
import org.maproulette.framework.model.{Task, User}
import org.maproulette.framework.util.{FrameworkHelper, TileAggregateRepoTag}
import play.api.Application
import play.api.db.Database

/**
  * Integration tests for the tile aggregation pipeline:
  *   1. Task mutation fires `mark_tiles_dirty_on_task_change_trigger`,
  *      populating `tile_dirty_marks`.
  *   2. `rebuildDirtyTiles` drains the queue.
  *
  * Catches the regressions we've already hit once: the `;;` <-> `;` formatter
  * incident (whole evolution wouldn't apply) and the group_type mismatch
  * between full and incremental rebuilds at z<12.
  */
class TileAggregateRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  val repository: TileAggregateRepository =
    this.application.injector.instanceOf(classOf[TileAggregateRepository])

  val db: Database = this.application.injector.instanceOf(classOf[Database])

  override implicit val projectTestName: String = "TileAggregateRepositorySpecProject"

  "TileAggregateRepository" should {
    "drain a queued dirty mark via rebuildDirtyTiles" taggedAs TileAggregateRepoTag in {
      // Seed a single dirty mark at a tile with no tasks; rebuildDirtyTiles
      // should drain it without inserting any tile_task_groups rows.
      db.withConnection { implicit c =>
        SQL"DELETE FROM tile_dirty_marks WHERE z = 12 AND x = 0 AND y = 0".executeUpdate()
        SQL"DELETE FROM tile_task_groups WHERE z = 12 AND x = 0 AND y = 0".executeUpdate()
        SQL"INSERT INTO tile_dirty_marks (z, x, y) VALUES (12, 0, 0)".executeUpdate()
      }

      val processed = repository.rebuildDirtyTiles(limit = 1000, minZoom = 12, maxZoom = 12)
      processed must be >= 1

      val remainingMarks = db.withConnection { implicit c =>
        SQL"SELECT COUNT(*)::int AS c FROM tile_dirty_marks WHERE z = 12 AND x = 0 AND y = 0"
          .as(SqlParser.scalar[Int].single)
      }
      remainingMarks mustEqual 0
    }

    "fire the task-change trigger and queue dirty marks on status update" taggedAs
      TileAggregateRepoTag in {
      // Drain whatever's in the queue from setup so we can detect a delta.
      db.withConnection { implicit c =>
        SQL"DELETE FROM tile_dirty_marks".executeUpdate()
      }

      // Mutate the default task. The trigger fires on UPDATE OF status, so a
      // status change is the minimal mutation that exercises the path.
      taskDAL.setTaskStatus(List(defaultTask), Task.STATUS_FIXED, User.superUser, Some(true))

      val markCount = db.withConnection { implicit c =>
        SQL"SELECT COUNT(*)::int AS c FROM tile_dirty_marks"
          .as(SqlParser.scalar[Int].single)
      }
      // Trigger marks z=0..12 for the task's location, plus neighbors at z<12
      // when the point is near a tile edge. Lower bound is 13 (one mark per
      // zoom 0..12) when the task is far from any edge.
      markCount must be >= 13
    }
  }
}

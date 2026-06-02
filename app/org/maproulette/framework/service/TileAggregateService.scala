/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.maproulette.framework.repository.TileAggregateRepository
import org.slf4j.LoggerFactory

/**
  * Service layer for tile-based task aggregation and MVT generation.
  *
  * Tile building standard:
  *   - Zoom 0..11: pre-computed grid cells (`tile_cells`). Each display tile is
  *     a fixed grid of cells; clustering is grid binning, so it is exact and
  *     identical whether or not filters are applied.
  *   - Zoom 12: served live from `tasks` as overlap-aware unclustered markers.
  *     MapLibre overzooms this through z=18+.
  *
  * Difficulty/global filters at z<12 are answered from the pre-computed
  * `counts_by_filter` buckets. Keyword filters cannot be pre-computed, so those
  * requests go through an on-the-fly grid-binning query that uses the same cell
  * grid — a filtered map therefore clusters identically to an unfiltered one.
  *
  * Tiles are not spatially filtered server-side: a tile is a pure function of
  * (z, x, y) and the difficulty/global/keyword filters, so it stays HTTP
  * cacheable. Location filtering (e.g. "only France") is applied client-side by
  * highlighting the area, not by mutating tile contents.
  */
@Singleton
class TileAggregateService @Inject() (
    repository: TileAggregateRepository
) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  /** Inclusive ceiling of zoom levels the server emits MVT for. */
  val MAX_ZOOM = repository.TASK_ZOOM

  /**
    * Get MVT bytes for the given tile. Returns an empty `Array[Byte]` when no
    * features match or z is outside the served range, letting the controller
    * serve an empty 200 response that MapLibre treats as "no data here".
    *
    * Routing:
    *   - z > MAX_ZOOM: empty; MapLibre overzooms the last native tile.
    *   - z == 12: live `tasks` query (individual / overlap markers).
    *   - z in 0..11 without keyword filters: pre-computed `tile_cells`.
    *   - z in 0..11 with keyword filters: on-the-fly grid-binning query.
    */
  def getMvtTile(
      z: Int,
      x: Int,
      y: Int,
      difficulty: Option[Int] = None,
      global: Boolean = false,
      keywords: Option[String] = None
  ): Array[Byte] = {
    if (z < 0 || z > MAX_ZOOM) return Array.empty[Byte]

    val hasKeywords = keywords.exists(_.trim.nonEmpty)

    if (z == repository.TASK_ZOOM) {
      repository.getMvtTasksLive(z, x, y, difficulty, global, keywords)
    } else if (!hasKeywords) {
      repository.getMvtCellsPrecomputed(z, x, y, difficulty, global)
    } else {
      repository.getMvtCellsLive(z, x, y, difficulty, global, keywords)
    }
  }

  /**
    * Drain the dirty-cell queue. Recomputes affected leaf cells from the base
    * tables and rolls the changes up to z=0. Returns the number of leaf cells
    * processed.
    */
  def rebuildDirtyCells(limit: Int = 512): Int =
    repository.rebuildDirtyCells(limit, newestFirst = false)

  /** Full rebuild of the pyramid (initial population / crash recovery). */
  def rebuildAll(): Int = repository.rebuildAll()

  /** Stats for ops / debugging. */
  def getStats(): Map[String, Int] = {
    Map(
      "totalCells"     -> repository.getCellCount(),
      "dirtyCells"     -> repository.getDirtyCellCount(),
      "dirtyQueueLagS" -> repository.getDirtyQueueLagSeconds()
    )
  }
}

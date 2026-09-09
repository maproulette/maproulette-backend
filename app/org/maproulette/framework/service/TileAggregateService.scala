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
  *   - Zoom 0..11: k-means clusters over fine-grained micro-aggregates. The
  *     `tile_cells` pyramid supplies those micro-aggregates cheaply; k-means
  *     decides where the markers actually land, so dense areas no longer render
  *     as an axis-aligned grid of bubbles. See TileAggregateRepository.
  *   - Zoom 12: served live from `tasks` as overlap-aware unclustered markers.
  *     MapLibre overzooms this through z=18+.
  *
  * A tile is a pure function of (z, x, y) -- nothing else. The map shows all
  * available work and applies no filters: challenge-level filters (difficulty,
  * global, keywords) belong to the grid and list views, so no request needs
  * per-task filtering and every tile below z=12 is answered from pre-computed
  * cells. Location filtering (e.g. "only France") is applied client-side by
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
    *   - z in 0..11: k-means over the pre-computed `tile_cells` pyramid.
    */
  def getMvtTile(z: Int, x: Int, y: Int): Array[Byte] = {
    if (z < 0 || z > MAX_ZOOM) return Array.empty[Byte]

    if (z == repository.TASK_ZOOM) repository.getMvtTasksLive(z, x, y)
    else repository.getMvtCellsPrecomputed(z, x, y)
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

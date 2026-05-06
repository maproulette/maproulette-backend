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
  *   - Zoom 0..11: pre-computed, clustered. Each zoom lives on its own
  *     native grid and a single tile can contain many cluster features.
  *   - Zoom 12: pre-computed, unclustered. One feature per distinct ground
  *     location (overlap-aware). MapLibre overzooms this through z=18.
  *
  * Filtered requests (keyword / location filters) skip the pre-computed
  * table entirely and go through an on-the-fly ST_AsMVT query with bound
  * parameters.
  */
@Singleton
class TileAggregateService @Inject() (
    repository: TileAggregateRepository,
    nominatimService: NominatimService
) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  /** Inclusive ceiling of zoom levels the server emits MVT for. At z=0..11
    * tiles are clustered; at z=12 they are overlap-aware unclustered. Above
    * this ceiling the server returns empty bytes and MapLibre overzooms the
    * last native tile client-side — z=12 already has each task as its own
    * feature, so overzooming is pixel-accurate. */
  val MAX_PRECOMPUTED_ZOOM = 12

  /**
    * Get MVT bytes for the given tile. Returns an empty `Array[Byte]` if no
    * features match or if z is outside the served range, letting the controller
    * serve an empty 200 response that MapLibre treats as "no data here".
    *
    * Routing:
    *   - z > MAX_PRECOMPUTED_ZOOM: empty; MapLibre overzooms the last native tile.
    *   - z ≤ MAX_PRECOMPUTED_ZOOM without filters: precomputed `tile_task_groups`.
    *     Fast constant-time lookups, clustering is already materialized.
    *   - z ≤ MAX_PRECOMPUTED_ZOOM with keyword/location filters: on-the-fly
    *     query against the `tasks` table. Difficulty/global don't trigger the
    *     filtered path because their counts already live in `counts_by_filter`.
    */
  def getMvtTile(
      z: Int,
      x: Int,
      y: Int,
      difficulty: Option[Int] = None,
      global: Boolean = false,
      keywords: Option[String] = None,
      locationId: Option[Long] = None
  ): Array[Byte] = {
    if (z < 0 || z > MAX_PRECOMPUTED_ZOOM) return Array.empty[Byte]

    val hasKeywords = keywords.exists(_.trim.nonEmpty)
    val hasLocation = locationId.isDefined
    val hasFilters  = hasKeywords || hasLocation

    if (!hasFilters) {
      repository.getMvtTilePrecomputed(z, x, y, difficulty, global)
    } else {
      val polygonWkt =
        locationId.flatMap(id => nominatimService.getPolygonByPlaceId(id))
      repository.getMvtTileFiltered(z, x, y, difficulty, global, keywords, polygonWkt)
    }
  }

  /** True when the MVT returned for (z, …) can be cached publicly. */
  def isCacheable(
      difficulty: Option[Int],
      global: Boolean,
      keywords: Option[String],
      locationId: Option[Long]
  ): Boolean = {
    val hasKeywords = keywords.exists(_.trim.nonEmpty)
    val hasLocation = locationId.isDefined
    !hasKeywords && !hasLocation
  }

  /**
    * Drain the dirty-tile queue in batches. Intended to run on a short
    * schedule so task mutations become visible quickly without requiring a
    * full zoom-level rebuild. The zoom range lets the scheduler split the
    * queue into a fast high-zoom loop and a slower low-zoom loop so bulk
    * imports don't starve the user-visible high-zoom tiles.
    */
  def rebuildDirtyTiles(
      limit: Int = 500,
      minZoom: Int = 0,
      maxZoom: Int = 22
  ): Int = {
    val processed = repository.rebuildDirtyTiles(limit, minZoom, maxZoom)
    if (processed > 0) {
      logger.info(s"Rebuilt $processed dirty tiles (z=[$minZoom..$maxZoom])")
    }
    processed
  }

  /** Drain the most-recently-marked dirty tiles. Called synchronously from
    * TaskDAL after a single mutation commits so the originating user sees
    * their own change immediately, without waiting for the scheduler loop. */
  def rebuildRecentDirtyTiles(
      limit: Int = 20,
      minZoom: Int = 13,
      maxZoom: Int = 22
  ): Int = repository.rebuildRecentDirtyTiles(limit, minZoom, maxZoom)

  /** Stats for ops / debugging. */
  def getStats(): Map[String, Int] = {
    Map(
      "totalTaskGroups" -> repository.getTotalTaskGroupCount(),
      "dirtyTiles"      -> repository.getDirtyTileCount()
    )
  }
}

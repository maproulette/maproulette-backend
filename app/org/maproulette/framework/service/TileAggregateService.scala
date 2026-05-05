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
  *   - Zoom 0..10: pre-computed, clustered. Each zoom lives on its own native
  *     grid (no ZOOM_OFFSET) and a single tile can contain many cluster
  *     features.
  *   - Zoom 11: pre-computed, unclustered. One feature per distinct ground
  *     location (overlap-aware). The frontend overzooms this for zoom levels
  *     12..22, so individual task markers become visible at zoom 11 instead
  *     of zoom 14.
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

  /** Inclusive ceiling of zoom levels we precompute. */
  val MAX_PRECOMPUTED_ZOOM = 12

  /** Zoom level at which precomputed rows become individual task markers. */
  val UNCLUSTERED_ZOOM = 12

  /**
    * Upper zoom cap on the tile endpoint. z > 11 is served on-the-fly from
    * the `tasks` table so individual markers are pixel-accurate at any zoom;
    * z > MAX_SERVED_ZOOM returns empty and MapLibre overzooms the last tile.
    */
  val MAX_SERVED_ZOOM = 18

  /**
    * Get MVT bytes for the given tile. Returns an empty `Array[Byte]` if no
    * features match, letting the controller serve an empty 200 response that
    * MapLibre treats as "no data here".
    *
    * Routing:
    *   - z ≤ MAX_PRECOMPUTED_ZOOM without filters: precomputed `tile_task_groups`.
    *     Fast constant-time lookups, clustering is already materialized.
    *   - z > MAX_PRECOMPUTED_ZOOM (up to MAX_SERVED_ZOOM): on-the-fly query
    *     against the `tasks` table. Pixel-accurate positions at any zoom —
    *     overzooming the 4096-pixel z=11 MVT grid past z=18 leaves visible
    *     offsets, so we serve native tiles instead.
    *   - z > MAX_SERVED_ZOOM: empty; MapLibre overzooms the last native tile.
    *   - Any zoom with keyword/location filters: on-the-fly query.
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
    if (z < 0 || z > MAX_SERVED_ZOOM) return Array.empty[Byte]

    val hasKeywords = keywords.exists(_.trim.nonEmpty)
    val hasLocation = locationId.isDefined
    val hasFilters  = hasKeywords || hasLocation

    val usePrecomputed = !hasFilters && z <= MAX_PRECOMPUTED_ZOOM

    if (usePrecomputed) {
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

  /** Full rebuild of a specific zoom level. */
  def rebuildZoomLevel(zoom: Int): Int = {
    logger.info(s"Starting rebuild of zoom level $zoom")
    val groupsCreated = repository.rebuildZoomLevel(zoom)
    logger.info(s"Completed rebuild of zoom level $zoom: $groupsCreated groups created")
    groupsCreated
  }

  /** Full rebuild of every supported zoom level. */
  def rebuildAllTiles(): Int = {
    logger.info("Starting full rebuild of all tile task groups")
    var totalGroups = 0
    for (zoom <- 0 to MAX_PRECOMPUTED_ZOOM) {
      totalGroups += rebuildZoomLevel(zoom)
    }
    // The SQL side clamps to 0..MAX_PRECOMPUTED_ZOOM; iterating beyond is a no-op.
    logger.info(s"Completed full rebuild: $totalGroups total groups created")
    totalGroups
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

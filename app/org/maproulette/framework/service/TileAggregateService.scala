/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.maproulette.framework.repository.{TileAggregateRepository, TaskClusterRepository}
import org.slf4j.LoggerFactory

/**
  * Service layer for tile-based task aggregation.
  *
  * Zoom 0-13: Returns mix of singles, overlapping markers, and clusters
  *   - Tiles with 1 task at 1 location → single marker
  *   - Tiles with N tasks at 1 location → overlapping marker
  *   - Tiles with tasks at multiple locations → cluster
  * Zoom 14+: Returns individual tasks + overlapping markers (frontend handles clustering)
  */
@Singleton
class TileAggregateService @Inject() (
    repository: TileAggregateRepository,
    taskClusterRepository: TaskClusterRepository,
    nominatimService: NominatimService
) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  // Maximum pre-computed zoom level (zoom 14+ all use zoom 14 data)
  val MAX_PRECOMPUTED_ZOOM = 14

  /**
    * Get MVT (Mapbox Vector Tile) binary for a specific tile.
    * Handles all filtering logic in one place:
    *   - No keywords/location: use pre-computed tiles (fast)
    *   - Keywords/location at zoom < 14: return empty MVT (frontend shows zoom notice)
    *   - Keywords/location at zoom 14+: dynamic SQL query with ST_AsMVT
    *
    * @param z          Standard zoom level (0-22)
    * @param x          Standard tile X coordinate
    * @param y          Standard tile Y coordinate
    * @param difficulty Optional difficulty filter
    * @param global     Whether to include global challenges
    * @param keywords   Optional comma-separated keywords (triggers dynamic query)
    * @param locationId Optional Nominatim place_id for polygon filtering
    * @return MVT binary data
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
    val hasKeywords        = keywords.exists(_.trim.nonEmpty)
    val hasLocation        = locationId.isDefined
    val hasAdvancedFilters = hasKeywords || hasLocation

    // Advanced filters require zoom 14+
    if (hasAdvancedFilters && z < MAX_PRECOMPUTED_ZOOM) {
      return Array.empty[Byte]
    }

    if (hasAdvancedFilters) {
      // Dynamic query path: filter tasks live and encode as MVT
      val polygonWkt = locationId.flatMap(id => nominatimService.getPolygonByPlaceId(id))
      repository.getMvtTileFiltered(z, x, y, difficulty, global, keywords, polygonWkt)
    } else {
      // Pre-computed tile path (fast)
      val clampedZoom = math.max(0, math.min(z, MAX_PRECOMPUTED_ZOOM))
      repository.getMvtTile(clampedZoom, x, y, difficulty, global)
    }
  }

  /**
    * Full rebuild of a specific zoom level
    */
  def rebuildZoomLevel(zoom: Int): Int = {
    logger.info(s"Starting rebuild of zoom level $zoom")
    val groupsCreated = repository.rebuildZoomLevel(zoom)
    logger.info(s"Completed rebuild of zoom level $zoom: $groupsCreated groups created")
    groupsCreated
  }

  /**
    * Full rebuild of all zoom levels (0-14)
    */
  def rebuildAllTiles(): Int = {
    logger.info("Starting full rebuild of all tile task groups")
    var totalGroups = 0
    for (zoom <- 0 to MAX_PRECOMPUTED_ZOOM) {
      totalGroups += rebuildZoomLevel(zoom)
    }
    logger.info(s"Completed full rebuild: $totalGroups total groups created")
    totalGroups
  }

  /**
    * Get statistics about the tile system
    */
  def getStats(): Map[String, Int] = {
    Map("totalTaskGroups" -> repository.getTotalTaskGroupCount())
  }
}

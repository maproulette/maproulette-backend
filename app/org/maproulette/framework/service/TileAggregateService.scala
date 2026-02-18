/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model.{
  TaskMarker,
  TaskMarkerLocation,
  TileTaskGroup,
  ClusterPoint,
  TaskMarkerResponse,
  TaskClusterSummary,
  OverlappingTaskMarker,
  Point
}
import org.maproulette.framework.repository.{TileAggregateRepository, TaskClusterRepository}
import org.maproulette.session.SearchLocation
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

import scala.collection.mutable.ListBuffer

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
    * Get tile data for a bounding box with filtering.
    *
    * @param zoom        Map zoom level (0-22)
    * @param bounds      Bounding box
    * @param difficulty  Optional difficulty filter
    * @param global      Include global challenges
    * @param locationId  Optional Nominatim place_id for polygon filtering
    * @param keywords    Optional keywords (triggers fallback to dynamic query)
    * @return TaskMarkerResponse with clusters or tasks (including overlaps)
    */
  def getTileData(
      zoom: Int,
      bounds: SearchLocation,
      difficulty: Option[Int] = None,
      global: Boolean = false,
      locationId: Option[Long] = None,
      keywords: Option[String] = None
  ): TaskMarkerResponse = {

    // Keywords filter requires fallback (challenge-level filter, not pre-computed)
    if (keywords.exists(_.trim.nonEmpty)) {
      return getFallbackData(bounds, difficulty, global, locationId, keywords)
    }

    // Determine which pre-computed zoom level to query
    // Zoom 14-22 all use zoom 14 data (frontend clusters)
    // Zoom 0-13 use their respective pre-computed clusters
    val queryZoom = math.min(zoom, MAX_PRECOMPUTED_ZOOM)

    // Get polygon for location filtering if needed
    val polygonWkt = locationId.flatMap(id => nominatimService.getPolygonByPlaceId(id))

    // Fetch pre-computed groups
    val taskGroups = polygonWkt match {
      case Some(wkt) => repository.getTaskGroupsInPolygon(queryZoom, wkt, bounds)
      case None      => repository.getTaskGroupsInBounds(queryZoom, bounds)
    }

    // Apply difficulty/global filters
    val filteredGroups = taskGroups.flatMap { group =>
      val filteredCount = group.getFilteredCount(difficulty, global)
      if (filteredCount > 0) Some((group, filteredCount)) else None
    }

    val totalCount = filteredGroups.map(_._2).sum

    if (totalCount == 0) {
      return TaskMarkerResponse(totalCount = 0)
    }

    // Process all groups - separate by type
    returnMixedResponse(filteredGroups, totalCount)
  }

  /**
    * Return mixed response with clusters, singles, and overlapping markers.
    * Works for all zoom levels.
    *
    * Zoom 0-13: Mostly clusters, but isolated singles/overlaps are included
    * Zoom 14+: Only singles and overlapping markers (no clusters)
    */
  private def returnMixedResponse(
      groups: List[(TileTaskGroup, Int)],
      totalCount: Int
  ): TaskMarkerResponse = {
    val singleMarkers      = ListBuffer[TaskMarker]()
    val overlappingMarkers = ListBuffer[OverlappingTaskMarker]()
    val clusterPoints      = ListBuffer[(Point, Int)]()

    groups.foreach {
      case (group, filteredCount) =>
        if (group.isSingle) {
          // Single task - add as individual marker
          group.taskIds.headOption.foreach { taskId =>
            singleMarkers += TaskMarker(
              id = taskId,
              location = TaskMarkerLocation(group.centroidLat, group.centroidLng),
              status = 0,
              priority = 0,
              bundleId = None,
              lockedBy = None
            )
          }
        } else if (group.isOverlapping) {
          // Overlapping tasks - create overlapping marker with all tasks
          val tasks = group.taskIds.map { taskId =>
            TaskMarker(
              id = taskId,
              location = TaskMarkerLocation(group.centroidLat, group.centroidLng),
              status = 0,
              priority = 0,
              bundleId = None,
              lockedBy = None
            )
          }
          overlappingMarkers += OverlappingTaskMarker(
            TaskMarkerLocation(group.centroidLat, group.centroidLng),
            tasks
          )
        } else if (group.isCluster) {
          // Cluster - add as cluster point
          clusterPoints += ((Point(group.centroidLat, group.centroidLng), filteredCount))
        }
    }

    // Build cluster summaries
    val clusterSummaries = if (clusterPoints.nonEmpty) {
      Some(clusterPoints.toList.zipWithIndex.map {
        case ((point, count), idx) =>
          TaskClusterSummary(
            clusterId = idx,
            numberOfPoints = count,
            taskId = None,
            taskStatus = None,
            point = point,
            bounding = Json.obj()
          )
      })
    } else {
      None
    }

    TaskMarkerResponse(
      totalCount = totalCount,
      tasks = if (singleMarkers.nonEmpty) Some(singleMarkers.toList) else None,
      overlappingTasks = if (overlappingMarkers.nonEmpty) Some(overlappingMarkers.toList) else None,
      clusters = clusterSummaries
    )
  }

  /**
    * Fallback to dynamic query for keywords filtering.
    */
  private def getFallbackData(
      bounds: SearchLocation,
      difficulty: Option[Int],
      global: Boolean,
      locationId: Option[Long],
      keywords: Option[String]
  ): TaskMarkerResponse = {
    val statusList = List(0, 3, 6)

    val taskCount = taskClusterRepository.queryCountTaskMarkers(
      statusList,
      global,
      bounds,
      locationId,
      keywords,
      difficulty
    )

    if (taskCount >= 2000) {
      val clusters = taskClusterRepository.queryTaskMarkersClustered(
        statusList,
        global,
        bounds,
        locationId,
        keywords,
        difficulty
      )
      TaskMarkerResponse(totalCount = taskCount, clusters = Some(clusters))
    } else {
      val (singleMarkers, overlappingMarkers) = taskClusterRepository.queryTaskMarkersWithOverlaps(
        statusList,
        global,
        bounds,
        locationId,
        keywords,
        difficulty
      )
      TaskMarkerResponse(
        totalCount = taskCount,
        tasks = Some(singleMarkers),
        overlappingTasks = if (overlappingMarkers.nonEmpty) Some(overlappingMarkers) else None
      )
    }
  }

  /**
    * Get tile data for a specific tile (z, x, y).
    * Used for zoom 14+ where frontend requests individual tiles for caching.
    */
  def getTileDataByCoords(
      z: Int,
      x: Int,
      y: Int,
      difficulty: Option[Int] = None,
      global: Boolean = false
  ): TaskMarkerResponse = {
    // For zoom > 14, convert tile coords to zoom 14 and query that tile
    // Zoom 16 has 4x the tiles per dimension as zoom 14, so divide by 2^(z-14)
    val (queryZoom, queryX, queryY) = if (z > MAX_PRECOMPUTED_ZOOM) {
      val zoomDiff = z - MAX_PRECOMPUTED_ZOOM
      val scale    = 1 << zoomDiff // 2^zoomDiff
      (MAX_PRECOMPUTED_ZOOM, x / scale, y / scale)
    } else {
      (z, x, y)
    }

    val taskGroups = repository.getTaskGroupsByTile(queryZoom, queryX, queryY)

    // Apply difficulty/global filters
    val filteredGroups = taskGroups.flatMap { group =>
      val filteredCount = group.getFilteredCount(difficulty, global)
      if (filteredCount > 0) Some((group, filteredCount)) else None
    }

    val totalCount = filteredGroups.map(_._2).sum

    if (totalCount == 0) {
      return TaskMarkerResponse(totalCount = 0)
    }

    returnMixedResponse(filteredGroups, totalCount)
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

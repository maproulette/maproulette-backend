/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model.{
  TaskMarker,
  TileAggregate,
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
  * Provides efficient map display for large datasets by using pre-computed tiles
  * with filtering by difficulty and global, recursive drilling for location_id,
  * and re-clustering into ~80 clusters.
  */
@Singleton
class TileAggregateService @Inject() (
    repository: TileAggregateRepository,
    taskClusterRepository: TaskClusterRepository,
    nominatimService: NominatimService
) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  // Threshold for switching from clusters to individual tasks
  val CLUSTER_THRESHOLD = 2000

  // Maximum pre-computed zoom level
  val MAX_PRECOMPUTED_ZOOM = 14

  // Target tile range per axis - aim for 8-16 tiles across the viewport
  // This gives good granularity for clustering without overwhelming the query
  val TARGET_TILES_PER_AXIS = 12

  // Target number of clusters for final output
  val TARGET_CLUSTERS = 80

  /**
    * Get tile data for a bounding box with filtering.
    * Supports difficulty, global, location_id, and keywords filters.
    * Returns TaskMarkerResponse with clusters, tasks, and overlapping tasks.
    *
    * @param zoom        Zoom level
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

    // Calculate optimal query zoom based on viewport size
    // We want roughly TARGET_TILES_PER_AXIS tiles across the viewport for good clustering
    val effectiveZoom = calculateOptimalZoom(bounds)

    // Collect all data points (either tile centroids or actual tasks)
    val collectedPoints = ListBuffer[ClusterPoint]()
    val collectedTasks  = ListBuffer[TaskMarker]()

    if (locationId.isDefined) {
      // Location ID filtering with recursive drilling
      processLocationFiltering(
        effectiveZoom,
        bounds,
        difficulty,
        global,
        locationId.get,
        collectedPoints,
        collectedTasks
      )
    } else {
      // Standard tile-based processing
      processTiles(effectiveZoom, bounds, difficulty, global, collectedPoints, collectedTasks)
    }

    // Calculate total count
    val totalCount = collectedPoints.map(_.count).sum + collectedTasks.size

    // If we have few enough tasks, return them with overlap detection
    if (totalCount < CLUSTER_THRESHOLD && collectedTasks.nonEmpty) {
      val (singleMarkers, overlappingMarkers) = detectOverlaps(collectedTasks.toList)
      return TaskMarkerResponse(
        totalCount = totalCount,
        tasks = Some(singleMarkers),
        overlappingTasks = if (overlappingMarkers.nonEmpty) Some(overlappingMarkers) else None,
        clusters = None
      )
    }

    // Combine tile centroids with task locations for clustering
    val allPoints = collectedPoints.toList ++ collectedTasks.map { task =>
      ClusterPoint(task.location.lat, task.location.lng, 1)
    }

    if (allPoints.isEmpty) {
      return TaskMarkerResponse(totalCount = 0)
    }

    // Re-cluster into ~80 clusters, then merge nearby clusters to prevent visual overlap
    val initialClusters = kMeansClustering(allPoints, TARGET_CLUSTERS)
    val clusters        = mergeNearbyClusters(initialClusters, zoom)

    // Convert ClusterPoints to TaskClusterSummary
    val clusterSummaries = clusters.zipWithIndex.map {
      case (cp, idx) =>
        TaskClusterSummary(
          clusterId = idx,
          numberOfPoints = cp.count,
          taskId = None,
          taskStatus = None,
          point = Point(cp.lat, cp.lng),
          bounding = Json.obj()
        )
    }

    TaskMarkerResponse(
      totalCount = totalCount,
      tasks = None,
      overlappingTasks = None,
      clusters = Some(clusterSummaries)
    )
  }

  /**
    * Merge clusters that are too close together to prevent visual overlap.
    * Minimum distance is calculated based on viewport zoom level.
    * At lower zoom levels, clusters need to be farther apart in degrees.
    * Iterates until no more merges are possible.
    */
  private def mergeNearbyClusters(
      clusters: List[ClusterPoint],
      viewportZoom: Int
  ): List[ClusterPoint] = {
    if (clusters.size <= 1) return clusters

    // Calculate minimum distance in degrees based on zoom level
    // At zoom 0, world is ~360 degrees wide displayed in ~256 pixels
    // We want clusters to be at least ~25 pixels apart visually
    // degrees_per_pixel = 360 / (256 * 2^zoom)
    // min_distance = pixels * degrees_per_pixel
    val pixelBuffer    = 25.0
    val minDistanceDeg = pixelBuffer * 360.0 / (256.0 * math.pow(2, viewportZoom))

    var current  = clusters
    var changed  = true
    var maxIters = 50 // Prevent infinite loops

    while (changed && maxIters > 0) {
      changed = false
      maxIters -= 1

      val result = ListBuffer[ClusterPoint]()
      val used   = Array.fill(current.size)(false)

      for (i <- current.indices if !used(i)) {
        var merged = current(i)
        used(i) = true

        // Find all unused clusters within minimum distance and merge them
        for (j <- (i + 1) until current.size if !used(j)) {
          val other = current(j)
          val dist  = distance(merged.lat, merged.lng, other.lat, other.lng)

          if (dist < minDistanceDeg) {
            // Merge: weighted average of positions, sum of counts
            val totalCount = merged.count + other.count
            val newLat = (merged.lat * merged.count + other.lat * other.count) / totalCount
            val newLng = (merged.lng * merged.count + other.lng * other.count) / totalCount
            merged = ClusterPoint(newLat, newLng, totalCount)
            used(j) = true
            changed = true
          }
        }

        result += merged
      }

      current = result.toList
    }

    current
  }

  /**
    * Detect overlapping tasks (tasks at the same location within ~0.1 meters)
    * Groups tasks by location and separates single vs overlapping markers
    */
  private def detectOverlaps(
      tasks: List[TaskMarker]
  ): (List[TaskMarker], List[OverlappingTaskMarker]) = {
    // Group tasks by rounded location (precision of ~0.1 meters = 0.000001 degrees)
    val precision = 1000000.0
    val grouped = tasks.groupBy { task =>
      (
        math.round(task.location.lat * precision),
        math.round(task.location.lng * precision)
      )
    }

    val singleMarkers      = ListBuffer[TaskMarker]()
    val overlappingMarkers = ListBuffer[OverlappingTaskMarker]()

    grouped.values.foreach { groupTasks =>
      if (groupTasks.size == 1) {
        singleMarkers += groupTasks.head
      } else {
        // Use the first task's location as the representative location
        val location = groupTasks.head.location
        overlappingMarkers += OverlappingTaskMarker(location, groupTasks)
      }
    }

    (singleMarkers.toList, overlappingMarkers.toList)
  }

  /**
    * Process tiles in the bounding box using tile centroids.
    * Uses a two-phase approach:
    * 1. First pass: collect all tile centroids (single query, fast)
    * 2. If total count is low enough, fetch actual tasks in a single batched query
    */
  private def processTiles(
      zoom: Int,
      bounds: SearchLocation,
      difficulty: Option[Int],
      global: Boolean,
      collectedPoints: ListBuffer[ClusterPoint],
      collectedTasks: ListBuffer[TaskMarker]
  ): Unit = {
    val tiles = repository.getTilesInBounds(zoom, bounds)

    // First pass: calculate total count and collect tile info
    var totalCount = 0
    val tilesWithCounts = tiles.flatMap { tile =>
      val filteredCount = tile.getFilteredCount(difficulty, global)
      if (filteredCount > 0) {
        totalCount += filteredCount
        Some((tile, filteredCount))
      } else {
        None
      }
    }

    // If total count is low enough, fetch actual tasks in a single batched query
    if (totalCount < CLUSTER_THRESHOLD && tilesWithCounts.nonEmpty) {
      // Fetch all tasks in the bounding box with a single query (much faster than per-tile)
      val tasks = repository.getTaskMarkersInBounds(bounds, difficulty, global)
      collectedTasks ++= tasks
    } else {
      // Use tile centroids for clustering (no additional queries needed)
      tilesWithCounts.foreach {
        case (tile, filteredCount) =>
          collectedPoints += ClusterPoint(tile.centroidLat, tile.centroidLng, filteredCount)
      }
    }
  }

  /**
    * Process with location_id filtering using tile-based approach.
    * Filters pre-computed tiles whose centroids are within the polygon - much faster
    * than querying millions of tasks directly, even for complex multipolygons like France.
    */
  private def processLocationFiltering(
      startZoom: Int,
      bounds: SearchLocation,
      difficulty: Option[Int],
      global: Boolean,
      locationId: Long,
      collectedPoints: ListBuffer[ClusterPoint],
      collectedTasks: ListBuffer[TaskMarker]
  ): Unit = {
    // Get the location polygon from Nominatim
    val locationPolygon = nominatimService.getPolygonByPlaceId(locationId)

    locationPolygon match {
      case Some(polygonWkt) =>
        // Use tile-based filtering: first filter by viewport bounds (fast indexed lookup),
        // then check which tile centroids are within the polygon
        val tiles = repository.getTilesInPolygon(MAX_PRECOMPUTED_ZOOM, polygonWkt, bounds)

        // Calculate total count from tiles (with difficulty/global filtering)
        var totalCount = 0
        val tilesWithCounts = tiles.flatMap { tile =>
          val filteredCount = tile.getFilteredCount(difficulty, global)
          if (filteredCount > 0) {
            totalCount += filteredCount
            Some((tile, filteredCount))
          } else {
            None
          }
        }

        if (totalCount < CLUSTER_THRESHOLD && totalCount > 0) {
          // Low count - fetch actual tasks within the polygon
          val tasks = repository.getTaskMarkersInPolygonSimplified(
            polygonWkt, difficulty, global, Some(CLUSTER_THRESHOLD)
          )
          collectedTasks ++= tasks
        } else if (tilesWithCounts.nonEmpty) {
          // High count - use tile centroids for clustering
          tilesWithCounts.foreach {
            case (tile, filteredCount) =>
              collectedPoints += ClusterPoint(tile.centroidLat, tile.centroidLng, filteredCount)
          }
        }

      case None =>
        // Location not found, fall back to standard processing
        logger.warn(s"Location polygon not found for place_id: $locationId")
        processTiles(startZoom, bounds, difficulty, global, collectedPoints, collectedTasks)
    }
  }

  /**
    * Fallback to dynamic query for keywords filtering.
    * Uses same thresholds as tile-based path for consistency.
    */
  private def getFallbackData(
      bounds: SearchLocation,
      difficulty: Option[Int],
      global: Boolean,
      locationId: Option[Long],
      keywords: Option[String]
  ): TaskMarkerResponse = {
    val boundingBox = bounds
    val statusList  = List(0, 3, 6)

    val taskCount = taskClusterRepository.queryCountTaskMarkers(
      statusList,
      global,
      boundingBox,
      locationId,
      keywords,
      difficulty
    )

    // Use same threshold as tile-based path for consistency
    if (taskCount >= CLUSTER_THRESHOLD) {
      // Too many tasks - return clusters
      val clusters = taskClusterRepository.queryTaskMarkersClustered(
        statusList,
        global,
        boundingBox,
        locationId,
        keywords,
        difficulty
      )
      TaskMarkerResponse(totalCount = taskCount, clusters = Some(clusters))
    } else {
      // Few enough tasks - return individual markers with overlap detection
      val (singleMarkers, overlappingMarkers) = taskClusterRepository.queryTaskMarkersWithOverlaps(
        statusList,
        global,
        boundingBox,
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
    * Simple k-means clustering implementation.
    * Groups points into k clusters and returns cluster centroids with counts.
    */
  private def kMeansClustering(points: List[ClusterPoint], k: Int): List[ClusterPoint] = {
    if (points.isEmpty) return List.empty
    if (points.size <= k) return points

    val numClusters = math.min(k, points.size)

    // Initialize centroids using k-means++ style selection
    var centroids = initializeCentroids(points, numClusters)

    // Run k-means iterations
    val maxIterations = 20
    var iteration     = 0
    var changed       = true

    while (iteration < maxIterations && changed) {
      // Assign points to nearest centroid
      val assignments = points.map { point =>
        val nearest = centroids.zipWithIndex.minBy {
          case (centroid, _) =>
            distance(point.lat, point.lng, centroid._1, centroid._2)
        }._2
        (point, nearest)
      }

      // Recalculate centroids
      val newCentroids = (0 until numClusters).map { i =>
        val clusterPoints = assignments.filter(_._2 == i).map(_._1)
        if (clusterPoints.isEmpty) {
          centroids(i)
        } else {
          val totalWeight = clusterPoints.map(_.count).sum.toDouble
          val avgLat      = clusterPoints.map(p => p.lat * p.count).sum / totalWeight
          val avgLng      = clusterPoints.map(p => p.lng * p.count).sum / totalWeight
          (avgLat, avgLng)
        }
      }.toList

      changed = !newCentroids.equals(centroids)
      centroids = newCentroids
      iteration += 1
    }

    // Calculate final clusters with counts
    val assignments = points.map { point =>
      val nearest = centroids.zipWithIndex.minBy {
        case (centroid, _) =>
          distance(point.lat, point.lng, centroid._1, centroid._2)
      }._2
      (point, nearest)
    }

    centroids.zipWithIndex
      .map {
        case ((lat, lng), i) =>
          val count = assignments.filter(_._2 == i).map(_._1.count).sum
          ClusterPoint(lat, lng, count)
      }
      .filter(_.count > 0)
  }

  /**
    * Initialize centroids using k-means++ algorithm for better cluster quality.
    * K-means++ selects initial centroids that are well-spread across the data,
    * leading to faster convergence and better final clusters.
    */
  private def initializeCentroids(points: List[ClusterPoint], k: Int): List[(Double, Double)] = {
    if (points.isEmpty || k <= 0) return List.empty

    val random    = new scala.util.Random(42) // Fixed seed for reproducibility
    val centroids = ListBuffer[(Double, Double)]()

    // Step 1: Choose first centroid uniformly at random (weighted by count)
    val totalWeight   = points.map(_.count).sum.toDouble
    var cumWeight     = 0.0
    val targetWeight1 = random.nextDouble() * totalWeight
    val firstPoint = points.find { p =>
      cumWeight += p.count
      cumWeight >= targetWeight1
    }.getOrElse(points.head)
    centroids += ((firstPoint.lat, firstPoint.lng))

    // Step 2: Choose remaining centroids with probability proportional to D(x)²
    while (centroids.size < k) {
      // Calculate squared distance from each point to nearest centroid
      val distances = points.map { p =>
        val minDist = centroids.map { c =>
          distance(p.lat, p.lng, c._1, c._2)
        }.min
        // Weight by point count for proper distribution
        (p, minDist * minDist * p.count)
      }

      // Calculate cumulative distribution
      val totalDist = distances.map(_._2).sum
      if (totalDist <= 0) {
        // All points are at existing centroids, pick remaining randomly
        val remaining = points.filterNot { p =>
          centroids.exists(c => c._1 == p.lat && c._2 == p.lng)
        }
        if (remaining.nonEmpty) {
          val next = remaining(random.nextInt(remaining.size))
          centroids += ((next.lat, next.lng))
        } else {
          // Fallback: duplicate a centroid (shouldn't happen in practice)
          centroids += centroids.last
        }
      } else {
        // Select next centroid with probability proportional to D(x)²
        val target     = random.nextDouble() * totalDist
        var cumDist    = 0.0
        val nextPoint = distances.find { case (_, d) =>
          cumDist += d
          cumDist >= target
        }.map(_._1).getOrElse(points.head)
        centroids += ((nextPoint.lat, nextPoint.lng))
      }
    }

    centroids.toList
  }

  private def distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double = {
    // Simple Euclidean distance (sufficient for clustering purposes)
    val dLat = lat2 - lat1
    val dLng = lng2 - lng1
    math.sqrt(dLat * dLat + dLng * dLng)
  }

  /**
    * Calculate optimal zoom level for querying tiles based on viewport size.
    * Aims for TARGET_TILES_PER_AXIS tiles across the viewport for good clustering
    * without overwhelming the query with too many tiles.
    */
  private def calculateOptimalZoom(bounds: SearchLocation): Int = {
    // Calculate viewport width in degrees (handle anti-meridian crossing)
    val lngSpan = if (bounds.left > bounds.right) {
      (180.0 - bounds.left) + (bounds.right + 180.0)
    } else {
      bounds.right - bounds.left
    }
    val latSpan = bounds.top - bounds.bottom

    // Use the larger span to determine zoom level
    val maxSpan = math.max(lngSpan, latSpan)

    // At zoom z, each tile covers 360/2^z degrees of longitude
    // We want: maxSpan / (360/2^z) ≈ TARGET_TILES_PER_AXIS
    // Solving: 2^z ≈ TARGET_TILES_PER_AXIS * 360 / maxSpan
    // z ≈ log2(TARGET_TILES_PER_AXIS * 360 / maxSpan)
    val idealZoom = if (maxSpan > 0) {
      math.log(TARGET_TILES_PER_AXIS * 360.0 / maxSpan) / math.log(2)
    } else {
      MAX_PRECOMPUTED_ZOOM.toDouble
    }

    // Clamp to valid pre-computed zoom range (0-14)
    math.max(0, math.min(MAX_PRECOMPUTED_ZOOM, math.round(idealZoom).toInt))
  }

  /**
    * Full rebuild of a specific zoom level
    */
  def rebuildZoomLevel(zoom: Int): Int = {
    logger.info(s"Starting full rebuild of zoom level $zoom")
    val tilesCreated = repository.rebuildZoomLevel(zoom)
    logger.info(s"Completed rebuild of zoom level $zoom: $tilesCreated tiles created")
    tilesCreated
  }

  /**
    * Full rebuild of all zoom levels (0-14)
    */
  def rebuildAllTiles(): Int = {
    logger.info("Starting full rebuild of all tile aggregates")
    var totalTiles = 0
    for (zoom <- 0 to 14) {
      totalTiles += rebuildZoomLevel(zoom)
    }
    logger.info(s"Completed full rebuild: $totalTiles total tiles created")
    totalTiles
  }

  /**
    * Get statistics about the tile system
    */
  def getStats(): Map[String, Int] = {
    Map("totalTiles" -> repository.getTotalTileCount())
  }
}

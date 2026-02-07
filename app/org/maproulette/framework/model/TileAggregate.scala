/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import play.api.libs.json._

/**
  * Represents a cluster point (centroid with count) for map display
  *
  * @param lat   Cluster centroid latitude
  * @param lng   Cluster centroid longitude
  * @param count Number of tasks in this cluster
  */
case class ClusterPoint(
    lat: Double,
    lng: Double,
    count: Int
)

object ClusterPoint {
  implicit val clusterPointFormat: Format[ClusterPoint] = Json.format[ClusterPoint]
}

/**
  * Counts broken down by difficulty × global filter combinations.
  * Keys are like "d1_gf" = difficulty 1, global false
  *
  * @param d1_gf Difficulty 1 (Easy), global false
  * @param d1_gt Difficulty 1 (Easy), global true
  * @param d2_gf Difficulty 2 (Normal), global false
  * @param d2_gt Difficulty 2 (Normal), global true
  * @param d3_gf Difficulty 3 (Expert), global false
  * @param d3_gt Difficulty 3 (Expert), global true
  * @param d0_gf Difficulty not set, global false
  * @param d0_gt Difficulty not set, global true
  */
case class FilterCounts(
    d1_gf: Int = 0,
    d1_gt: Int = 0,
    d2_gf: Int = 0,
    d2_gt: Int = 0,
    d3_gf: Int = 0,
    d3_gt: Int = 0,
    d0_gf: Int = 0,
    d0_gt: Int = 0
) {

  /**
    * Get count for specific difficulty and global filters
    *
    * @param difficulty Optional difficulty filter (1, 2, 3)
    * @param global     Whether to include global challenges (true = all, false = non-global only)
    * @return Filtered count
    */
  def getFilteredCount(difficulty: Option[Int], global: Boolean): Int = {
    // global=true means "include global challenges" → return ALL tasks
    // global=false means "exclude global challenges" → return only non-global (*_gf)
    difficulty match {
      case Some(1) => if (global) d1_gf + d1_gt else d1_gf
      case Some(2) => if (global) d2_gf + d2_gt else d2_gf
      case Some(3) => if (global) d3_gf + d3_gt else d3_gf
      case None    =>
        // No difficulty filter - sum all difficulties
        if (global) d1_gf + d1_gt + d2_gf + d2_gt + d3_gf + d3_gt + d0_gf + d0_gt
        else d1_gf + d2_gf + d3_gf + d0_gf
      case _ =>
        // Unknown difficulty - treat as "other"
        if (global) d0_gf + d0_gt else d0_gf
    }
  }

  /**
    * Get total count (all combinations)
    */
  def total: Int = d1_gf + d1_gt + d2_gf + d2_gt + d3_gf + d3_gt + d0_gf + d0_gt
}

object FilterCounts {
  implicit val filterCountsFormat: Format[FilterCounts] = Json.format[FilterCounts]

  def fromJson(json: JsValue): FilterCounts = {
    FilterCounts(
      d1_gf = (json \ "d1_gf").asOpt[Int].getOrElse(0),
      d1_gt = (json \ "d1_gt").asOpt[Int].getOrElse(0),
      d2_gf = (json \ "d2_gf").asOpt[Int].getOrElse(0),
      d2_gt = (json \ "d2_gt").asOpt[Int].getOrElse(0),
      d3_gf = (json \ "d3_gf").asOpt[Int].getOrElse(0),
      d3_gt = (json \ "d3_gt").asOpt[Int].getOrElse(0),
      d0_gf = (json \ "d0_gf").asOpt[Int].getOrElse(0),
      d0_gt = (json \ "d0_gt").asOpt[Int].getOrElse(0)
    )
  }
}

/**
  * Represents a pre-computed tile aggregate for efficient map display at scale.
  * Uses Web Mercator tile coordinates (z/x/y).
  * Only tracks tasks with status 0, 3, or 6.
  *
  * @param z              Zoom level (0-14 for pre-computed)
  * @param x              Tile X coordinate
  * @param y              Tile Y coordinate
  * @param taskCount      Total tasks in this tile (all filters)
  * @param countsByFilter Counts broken down by difficulty × global
  * @param centroidLat    Centroid latitude of all tasks in tile
  * @param centroidLng    Centroid longitude of all tasks in tile
  */
case class TileAggregate(
    z: Int,
    x: Int,
    y: Int,
    taskCount: Int,
    countsByFilter: FilterCounts,
    centroidLat: Double,
    centroidLng: Double
) {

  /**
    * Get the filtered count for this tile based on difficulty and global filters
    */
  def getFilteredCount(difficulty: Option[Int], global: Boolean): Int = {
    countsByFilter.getFilteredCount(difficulty, global)
  }
}

object TileAggregate {
  implicit val tileAggregateWrites: Writes[TileAggregate] = Json.writes[TileAggregate]
  implicit val tileAggregateReads: Reads[TileAggregate]   = Json.reads[TileAggregate]
}

/**
  * Response for tile-based queries with combined clusters.
  * All data from tiles is combined and re-clustered into ~80 clusters.
  *
  * @param totalCount Total tasks matching the filter
  * @param clusters   Combined clusters (target ~80)
  * @param tasks      Individual task markers (when total < threshold)
  */
case class TileDataResponse(
    totalCount: Int,
    clusters: Option[List[ClusterPoint]] = None,
    tasks: Option[List[TaskMarker]] = None
)

object TileDataResponse {
  implicit val tileDataResponseWrites: Writes[TileDataResponse] = Json.writes[TileDataResponse]
  implicit val tileDataResponseReads: Reads[TileDataResponse]   = Json.reads[TileDataResponse]
}

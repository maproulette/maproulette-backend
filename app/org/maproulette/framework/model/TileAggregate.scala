/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model
import play.api.libs.json._

case class ClusterPoint(
    lat: Double,
    lng: Double,
    count: Int
)

object ClusterPoint {
  implicit val clusterPointFormat: Format[ClusterPoint] = Json.format[ClusterPoint]
}

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
    difficulty match {
      case Some(1) => if (global) d1_gf + d1_gt else d1_gf
      case Some(2) => if (global) d2_gf + d2_gt else d2_gf
      case Some(3) => if (global) d3_gf + d3_gt else d3_gf
      case Some(_) =>
        // Unknown difficulty values use d0 (unset/other) bucket
        if (global) d0_gf + d0_gt else d0_gf
      case None =>
        // No filter: sum all difficulty levels
        if (global) d1_gf + d1_gt + d2_gf + d2_gt + d3_gf + d3_gt + d0_gf + d0_gt
        else d1_gf + d2_gf + d3_gf + d0_gf
    }
  }

  /**
    * Get total count (all combinations)
    */
  def total: Int = d1_gf + d1_gt + d2_gf + d2_gt + d3_gf + d3_gt + d0_gf + d0_gt
}

object FilterCounts {
  implicit val filterCountsFormat: Format[FilterCounts] = Json.format[FilterCounts]
}

/**
  * Represents a pre-computed task group at any zoom level (0-14).
  *
  * Zoom 0-13: One cluster per tile (group_type=2, no task_ids)
  *   - As zoom increases, tiles get smaller, clusters naturally split
  *   - Frontend displays these as cluster markers
  *
  * Zoom 14: One entry per overlap group (group_type=0 or 1, with task_ids)
  *   - Frontend handles clustering for zoom levels 14-22
  *   - Returns individual task markers and overlapping task markers
  *
  * @param id             Database ID
  * @param z              Zoom level (0-14)
  * @param x              Tile X coordinate
  * @param y              Tile Y coordinate
  * @param groupType      0=single task, 1=overlapping tasks, 2=cluster
  * @param centroidLat    Centroid latitude of the group
  * @param centroidLng    Centroid longitude of the group
  * @param taskIds        List of task IDs (empty for clusters at zoom 0-13)
  * @param taskCount      Number of tasks in this group
  * @param countsByFilter Counts broken down by difficulty × global for filtering
  */
case class TileTaskGroup(
    id: Long,
    z: Int,
    x: Int,
    y: Int,
    groupType: Int,
    centroidLat: Double,
    centroidLng: Double,
    taskIds: List[Long],
    taskCount: Int,
    countsByFilter: FilterCounts
) {

  /**
    * Get the filtered count for this group based on difficulty and global filters
    */
  def getFilteredCount(difficulty: Option[Int], global: Boolean): Int = {
    countsByFilter.getFilteredCount(difficulty, global)
  }

  /**
    * Check if this is a single task (zoom 14 only)
    */
  def isSingle: Boolean = groupType == 0

  /**
    * Check if this is an overlapping group (zoom 14 only)
    */
  def isOverlapping: Boolean = groupType == 1

  /**
    * Check if this is a cluster (zoom 0-13)
    */
  def isCluster: Boolean = groupType == 2
}

object TileTaskGroup {
  implicit val tileTaskGroupWrites: Writes[TileTaskGroup] = Json.writes[TileTaskGroup]
  implicit val tileTaskGroupReads: Reads[TileTaskGroup]   = Json.reads[TileTaskGroup]
}

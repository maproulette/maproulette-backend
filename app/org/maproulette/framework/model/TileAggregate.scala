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

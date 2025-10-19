/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import play.api.libs.json._

/**
  * Simplified task cluster object for lightweight cluster queries
  *
  * @param clusterId The cluster identifier
  * @param numberOfPoints The number of tasks in this cluster
  * @param taskId Optional task ID (only present when cluster has exactly 1 task)
  * @param taskStatus Optional task status (only present when cluster has exactly 1 task)
  * @param taskPriority Optional task priority (only present when cluster has exactly 1 task)
  * @param point The centroid point of the cluster
  * @param bounding The bounding geometry of the cluster
  */
case class TaskClusterSummary(
    clusterId: Int,
    numberOfPoints: Int,
    taskId: Option[Long],
    taskStatus: Option[Int],
    point: Point,
    bounding: JsValue = Json.toJson("{}")
) extends DefaultWrites

object TaskClusterSummary {
  implicit val pointWrites: Writes[Point] = Json.writes[Point]
  implicit val pointReads: Reads[Point]   = Json.reads[Point]
  implicit val taskClusterSummaryWrites: Writes[TaskClusterSummary] =
    Json.writes[TaskClusterSummary]
  implicit val taskClusterSummaryReads: Reads[TaskClusterSummary] = Json.reads[TaskClusterSummary]
}

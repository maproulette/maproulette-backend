/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import play.api.libs.json._

/**
  * Response wrapper for task marker queries that includes total count
  * and either individual markers, clusters, or nothing if there are too many tasks
  *
  * @param totalCount   The total number of tasks matching the query
  * @param tasks        Optional list of individual task markers (non-overlapping)
  * @param overlappingTasks Optional list of overlapping task markers
  * @param clusters     Optional list of task cluster summaries
  */
case class TaskMarkerResponse(
    totalCount: Int,
    tasks: Option[List[TaskMarker]] = None,
    overlappingTasks: Option[List[OverlappingTaskMarker]] = None,
    clusters: Option[List[TaskClusterSummary]] = None
)

/**
  * Represents a group of tasks that share the same location
  *
  * @param location The shared location of all tasks in this overlap
  * @param tasks    List of task markers at this location
  */
case class OverlappingTaskMarker(
    location: TaskMarkerLocation,
    tasks: List[TaskMarker]
)

object OverlappingTaskMarker {
  implicit val overlappingTaskMarkerWrites: Writes[OverlappingTaskMarker] =
    Json.writes[OverlappingTaskMarker]
  implicit val overlappingTaskMarkerReads: Reads[OverlappingTaskMarker] =
    Json.reads[OverlappingTaskMarker]
}

object TaskMarkerResponse {
  implicit val taskMarkerResponseWrites: Writes[TaskMarkerResponse] =
    Json.writes[TaskMarkerResponse]
  implicit val taskMarkerResponseReads: Reads[TaskMarkerResponse] = Json.reads[TaskMarkerResponse]
}

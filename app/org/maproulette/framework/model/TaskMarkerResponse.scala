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
  * @param tasks        Optional list of individual task markers
  * @param clusters     Optional list of task cluster summaries
  */
case class TaskMarkerResponse(
    totalCount: Int,
    tasks: Option[List[TaskMarker]] = None,
    clusters: Option[List[TaskClusterSummary]] = None
)

object TaskMarkerResponse {
  implicit val taskMarkerResponseWrites: Writes[TaskMarkerResponse] =
    Json.writes[TaskMarkerResponse]
  implicit val taskMarkerResponseReads: Reads[TaskMarkerResponse] = Json.reads[TaskMarkerResponse]
}

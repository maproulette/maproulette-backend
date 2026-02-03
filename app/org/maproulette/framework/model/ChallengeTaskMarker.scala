/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.joda.time.DateTime
import play.api.libs.json.{JsValue, Json, Reads, Writes}
import play.api.libs.json.JodaWrites._
import play.api.libs.json.JodaReads._
import org.maproulette.framework.model.TaskMarkerLocation

case class SingleTaskMarker(
    id: Long,
    location: TaskMarkerLocation,
    status: Int,
    priority: Int,
    bundleId: Option[Long] = None,
    lockedBy: Option[Long] = None
)

case class OverlapTaskMarker(
    location: TaskMarkerLocation,
    tasks: List[SingleTaskMarker]
)

case class ChallengeTaskMarkersResponse(
    markers: List[SingleTaskMarker],
    overlaps: List[OverlapTaskMarker]
)

object OverlapTaskMarker {
  implicit val overlapTaskMarkerWrites: Writes[OverlapTaskMarker] =
    Json.writes[OverlapTaskMarker]
  implicit val overlapTaskMarkerReads: Reads[OverlapTaskMarker] =
    Json.reads[OverlapTaskMarker]
}

object SingleTaskMarker {
  implicit val singleTaskMarkerWrites: Writes[SingleTaskMarker] =
    Json.writes[SingleTaskMarker]
  implicit val singleTaskMarkerReads: Reads[SingleTaskMarker] =
    Json.reads[SingleTaskMarker]
}

object ChallengeTaskMarkersResponse {
  implicit val challengeTaskMarkersResponseWrites: Writes[ChallengeTaskMarkersResponse] =
    Json.writes[ChallengeTaskMarkersResponse]
  implicit val challengeTaskMarkersResponseReads: Reads[ChallengeTaskMarkersResponse] =
    Json.reads[ChallengeTaskMarkersResponse]
}

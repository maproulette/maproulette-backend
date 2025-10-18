/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.joda.time.DateTime
import play.api.libs.json.{JsValue, Json, Reads, Writes}
import play.api.libs.json.JodaWrites._
import play.api.libs.json.JodaReads._

/**
  * @author cuthbertm
  */
case class TaskMarkerLocation(lat: Double, lng: Double)

object TaskMarkerLocation {
  implicit val taskMarkerLocationWrites: Writes[TaskMarkerLocation] =
    Json.writes[TaskMarkerLocation]
  implicit val taskMarkerLocationReads: Reads[TaskMarkerLocation] = Json.reads[TaskMarkerLocation]
}

/**
  * A lightweight task marker model containing only essential data for map display
  *
  * @param id       The id of the task
  * @param location The latitude and longitude of the task
  * @param status   The status of the task
  */
case class TaskMarker(
    id: Long,
    location: TaskMarkerLocation,
    status: Int,
    challengeName: String
)

object TaskMarker {
  implicit val taskMarkerWrites: Writes[TaskMarker] = Json.writes[TaskMarker]
  implicit val taskMarkerReads: Reads[TaskMarker]   = Json.reads[TaskMarker]
}

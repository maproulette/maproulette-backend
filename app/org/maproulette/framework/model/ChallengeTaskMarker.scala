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

case class ChallengeTaskMarker(
    id: Long,
    location: TaskMarkerLocation,
    status: Int
)

object ChallengeTaskMarker {
  implicit val challengeTaskMarkerWrites: Writes[ChallengeTaskMarker] =
    Json.writes[ChallengeTaskMarker]
  implicit val challengeTaskMarkerReads: Reads[ChallengeTaskMarker] =
    Json.reads[ChallengeTaskMarker]
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.joda.time.DateTime
import play.api.libs.json.{Json, Format}
import play.api.libs.json.JodaWrites._
import play.api.libs.json.JodaReads._

/**
  * Mapping of object structure for fetching task lock data
  */
case class LockedTaskData(
    id: Long,
    challengeName: Option[String],
    startedAt: DateTime
)

// Define implicit Formats for LockedTaskData
object LockedTaskData {
  implicit val lockedTaskDataFormat: Format[LockedTaskData] = Json.format[LockedTaskData]
}

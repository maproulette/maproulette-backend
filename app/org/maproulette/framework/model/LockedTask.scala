/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.joda.time.DateTime
import org.maproulette.cache.CacheObject
import org.maproulette.framework.psql.CommonField
import play.api.libs.json.{Json, Format}
import play.api.libs.json.JodaWrites._
import play.api.libs.json.JodaReads._

// Define the LockedTask case class
case class LockedTask(
    override val id: Long,
    challengeName: Option[String],
    startedAt: DateTime
) extends CacheObject[Long] {
  // Implement the abstract member 'name'
  override def name: String = "LockedTask"
}

/**
  * Mapping between Task and Challenge and Lock
  */
case class LockedTaskData(
    id: Long,
    challengeName: Option[String],
    startedAt: DateTime
)

object LockedTask extends CommonField {
  // Use Json.format to automatically derive both Reads and Writes
  implicit val lockedTaskFormat: Format[LockedTask] = Json.format[LockedTask]
}

// Define implicit Formats for LockedTaskData
object LockedTaskData {
  implicit val lockedTaskDataFormat: Format[LockedTaskData] = Json.format[LockedTaskData]
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.joda.time.DateTime
import play.api.libs.json.{Json, Reads, Writes}
import play.api.libs.json.JodaWrites._
import play.api.libs.json.JodaReads._

/**
  * TaskLogEntry allows for showing task history actions. This includes
  * comment actions, status actions and review actions.
  * @author krotstan
  */
case class TaskLogEntry(
    taskId: Long,
    timestamp: DateTime,
    actionType: Int,
    user: Option[Int],
    oldStatus: Option[Int],
    status: Option[Int],
    startedAt: Option[DateTime],
    reviewStatus: Option[Int],
    reviewRequestedBy: Option[Int],
    reviewedBy: Option[Int],
    comment: Option[String],
    errorTags: Option[String]
) {}

object TaskLogEntry {
  implicit val taskLogEntryWrites: Writes[TaskLogEntry] = Json.writes[TaskLogEntry]
  implicit val taskLogEntryReads: Reads[TaskLogEntry]   = Json.reads[TaskLogEntry]

  val ACTION_COMMENT       = 0
  val ACTION_STATUS_CHANGE = 1
  val ACTION_REVIEW        = 2
  val ACTION_UPDATE        = 3
  val ACTION_META_REVIEW   = 4
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.joda.time.DateTime
import org.maproulette.cache.CacheObject
import org.maproulette.framework.psql.CommonField
import play.api.libs.json.{Json, Reads, Writes}
import play.api.libs.json.JodaWrites._
import play.api.libs.json.JodaReads._

/**
  * Tags sit outside of the object hierarchy and have no parent or children objects associated it.
  * It simply has a many to one mapping between tags and tasks. This allows tasks to be easily
  * searched for and organized. Helping people find tasks related to what interests them.
  *
  * @author cuthbertm
  */
case class Tag(
    override val id: Long,
    override val name: String,
    description: Option[String] = None,
    created: DateTime = DateTime.now(),
    modified: DateTime = DateTime.now(),
    tagType: String = "challenges",
    active: Boolean = true
) extends CacheObject[Long]

/**
  * Mapping between Task and Tag
  */
case class TaskTag(
    taskId: Long,
    tag: Tag
)

object Tag extends CommonField {
  implicit val tagWrites: Writes[Tag] = Json.writes[Tag]
  implicit val tagReads: Reads[Tag]   = Json.reads[Tag]

  val TABLE                    = "tags"
  val TABLE_TAGS_ON_TASKS      = "tags_on_tasks"
  val TABLE_TAGS_ON_CHALLENGES = "tags_on_challenges"
  val FIELD_TAG_TYPE           = "tag_type"
  val FIELD_ACTIVE             = "active"
  val FIELD_PARENT_ID          = "parent_id"
  val FIELD_ENABLED            = "enabled"
  val FIELD_TASK_ID            = "task_id"
  val FIELD_CHALLENGE_ID       = "challenge_id"
}

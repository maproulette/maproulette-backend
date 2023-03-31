/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.joda.time.DateTime
import org.maproulette.framework.psql.CommonField
import play.api.libs.json._
import play.api.libs.json.JodaWrites._
import play.api.libs.json.JodaReads._

/**
  * A comment can be associated to a Task, a comment contains the osm user that made the comment,
  * when it was created, the Task it is associated with, the actual comment and potentially the
  * action that was associated with the comment.
  *
  * @author cuthbertm
  */
case class Comment(
    id: Long,
    osm_id: Long,
    osm_username: String,
    avatarUrl: String,
    taskId: Long,
    challengeId: Long,
    projectId: Long,
    created: DateTime,
    comment: String,
    actionId: Option[Long] = None,
    fullCount: Int = 0
)

object Comment extends CommonField {
  implicit val writes: Writes[Comment] = Json.writes[Comment]
  implicit val reads: Reads[Comment]   = Json.reads[Comment]

  val TABLE              = "task_comments"
  val FIELD_OSM_ID       = "osm_id"
  val FIELD_OSM_USERNAME = "name"
  val FIELD_AVATAR_URL   = "avatar_url"
  val FIELD_TASK_ID      = "task_id"
  val FIELD_CHALLENGE_ID = "challenge_id"
  val FIELD_PROJECT_ID   = "project_id"
  val FIELD_COMMENT      = "comment"
  val FIELD_ACTION_ID    = "action_id"
}

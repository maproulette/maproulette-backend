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
  * A challenge comment is only associated to the challenge, a comment contains the osm user that made the comment,
  * when it was created, and the actual comment
  *
  * @author jschwarzenberger
  */
case class ChallengeComment(
    id: Long,
    osm_id: Long,
    osm_username: String,
    avatarUrl: String,
    challengeId: Long,
    projectId: Long,
    created: DateTime,
    comment: String,
    challengeName: Option[String] = None,
    fullCount: Int = 0
)

object ChallengeComment extends CommonField {
  implicit val writes: Writes[ChallengeComment] = Json.writes[ChallengeComment]
  implicit val reads: Reads[ChallengeComment]   = Json.reads[ChallengeComment]

  val TABLE              = "challenge_comments"
  val FIELD_OSM_ID       = "osm_id"
  val FIELD_OSM_USERNAME = "name"
  val FIELD_AVATAR_URL   = "avatar_url"
  val FIELD_CHALLENGE_ID = "challenge_id"
  val FIELD_PROJECT_ID   = "project_id"
  val FIELD_COMMENT      = "comment"
}

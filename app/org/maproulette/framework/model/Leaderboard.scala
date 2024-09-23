/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.joda.time.DateTime
import org.maproulette.framework.psql.CommonField
import play.api.libs.json.{Json, Writes}
import play.api.libs.json.JodaWrites._

case class LeaderboardChallenge(id: Long, name: String, activity: Int)
object LeaderboardChallenge {
  implicit val writes: Writes[LeaderboardChallenge] = Json.writes[LeaderboardChallenge]
}

case class LeaderboardUser(
    userId: Long,
    name: String,
    avatarURL: String,
    score: Int,
    rank: Int,
    completedTasks: Option[Int],
    avgTimeSpent: Option[Long],
    created: DateTime = new DateTime(),
    topChallenges: List[LeaderboardChallenge],
    reviewsApproved: Option[Int],
    reviewsAssisted: Option[Int],
    reviewsRejected: Option[Int],
    reviewsDisputed: Option[Int],
    additionalReviews: Option[Int]
)
object LeaderboardUser extends CommonField {
  implicit val writes: Writes[LeaderboardUser] = Json.writes[LeaderboardUser]

  val TABLE         = "user_leaderboard"
  val FIELD_USER_ID = "user_id"
}

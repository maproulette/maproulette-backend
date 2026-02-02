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
import play.api.libs.functional.syntax._

case class ChallengeReview(
    id: Long = -1,
    userId: Long = -1,
    challengeId: Long = -1,
    rating: Int,
    instructionsClear: Option[Int] = None,
    challengeInteresting: Option[Int] = None,
    imagerySuitable: Option[Int] = None,
    estimatedTime: Option[String] = None,
    difficulty: Option[String] = None,
    comment: Option[String] = None,
    created: DateTime = DateTime.now(),
    modified: DateTime = DateTime.now()
)

case class ChallengeReviewSummary(
    challengeId: Long,
    totalReviews: Int,
    avgRating: Double,
    avgInstructionsClear: Option[Double],
    avgChallengeInteresting: Option[Double],
    avgImagerySuitable: Option[Double],
    topEstimatedTime: Option[String],
    topDifficulty: Option[String]
)

object ChallengeReview extends CommonField {
  implicit val challengeReviewWrites: Writes[ChallengeReview] = Json.writes[ChallengeReview]
  implicit val challengeReviewReads: Reads[ChallengeReview] = (
    (__ \ "id").readWithDefault[Long](-1) and
      (__ \ "userId").readWithDefault[Long](-1) and
      (__ \ "challengeId").readWithDefault[Long](-1) and
      (__ \ "rating").read[Int] and
      (__ \ "instructionsClear").readNullable[Int] and
      (__ \ "challengeInteresting").readNullable[Int] and
      (__ \ "imagerySuitable").readNullable[Int] and
      (__ \ "estimatedTime").readNullable[String] and
      (__ \ "difficulty").readNullable[String] and
      (__ \ "comment").readNullable[String] and
      (__ \ "created").readWithDefault[DateTime](DateTime.now()) and
      (__ \ "modified").readWithDefault[DateTime](DateTime.now())
  )(ChallengeReview.apply _)
  implicit val summaryWrites: Writes[ChallengeReviewSummary]  = Json.writes[ChallengeReviewSummary]

  val TABLE = "challenge_reviews"

  val FIELD_USER_ID               = "user_id"
  val FIELD_CHALLENGE_ID          = "challenge_id"
  val FIELD_RATING                = "rating"
  val FIELD_INSTRUCTIONS_CLEAR    = "instructions_clear"
  val FIELD_CHALLENGE_INTERESTING = "challenge_interesting"
  val FIELD_IMAGERY_SUITABLE      = "imagery_suitable"
  val FIELD_ESTIMATED_TIME        = "estimated_time"
  val FIELD_DIFFICULTY            = "difficulty"
  val FIELD_COMMENT               = "comment"
}

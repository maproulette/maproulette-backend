/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.repository

import java.sql.Connection
import anorm._
import anorm.SqlParser._
import org.joda.time.DateTime

import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model.{ChallengeReview, ChallengeReviewSummary}
import play.api.db.Database

@Singleton
class ChallengeReviewRepository @Inject() (
    override val db: Database
) extends RepositoryMixin {
  implicit val baseTable: String = ChallengeReview.TABLE

  private val parser: RowParser[ChallengeReview] = {
    get[Long]("id") ~
      get[Long]("user_id") ~
      get[Long]("challenge_id") ~
      get[Int]("rating") ~
      get[Option[Int]]("instructions_clear") ~
      get[Option[Int]]("challenge_interesting") ~
      get[Option[Int]]("imagery_suitable") ~
      get[Option[String]]("estimated_time") ~
      get[Option[String]]("difficulty") ~
      get[Option[String]]("comment") ~
      get[DateTime]("created") ~
      get[DateTime]("modified") map {
      case id ~ userId ~ challengeId ~ rating ~ ic ~ ci ~ is ~ et ~ d ~ comment ~ created ~ modified =>
        ChallengeReview(
          id,
          userId,
          challengeId,
          rating,
          ic,
          ci,
          is,
          et,
          d,
          comment,
          created,
          modified
        )
    }
  }

  def upsert(
      review: ChallengeReview
  )(implicit c: Option[Connection] = None): Option[ChallengeReview] = {
    this.withMRTransaction { implicit c =>
      SQL(
        """INSERT INTO challenge_reviews
          |  (user_id, challenge_id, rating, instructions_clear,
          |   challenge_interesting, imagery_suitable, estimated_time,
          |   difficulty, comment, modified)
          |VALUES ({userId}, {challengeId}, {rating}, {instructionsClear},
          |        {challengeInteresting}, {imagerySuitable},
          |        {estimatedTime}, {difficulty}, {comment}, NOW())
          |ON CONFLICT (user_id, challenge_id) DO UPDATE SET
          |  rating = EXCLUDED.rating,
          |  instructions_clear = EXCLUDED.instructions_clear,
          |  challenge_interesting = EXCLUDED.challenge_interesting,
          |  imagery_suitable = EXCLUDED.imagery_suitable,
          |  estimated_time = EXCLUDED.estimated_time,
          |  difficulty = EXCLUDED.difficulty,
          |  comment = EXCLUDED.comment,
          |  modified = NOW()
          |RETURNING *""".stripMargin
      ).on(
          Symbol("userId")               -> review.userId,
          Symbol("challengeId")          -> review.challengeId,
          Symbol("rating")               -> review.rating,
          Symbol("instructionsClear")    -> review.instructionsClear,
          Symbol("challengeInteresting") -> review.challengeInteresting,
          Symbol("imagerySuitable")      -> review.imagerySuitable,
          Symbol("estimatedTime")        -> review.estimatedTime,
          Symbol("difficulty")           -> review.difficulty,
          Symbol("comment")              -> review.comment
        )
        .as(parser.singleOpt)
    }
  }

  def delete(userId: Long, challengeId: Long)(
      implicit c: Option[Connection] = None
  ): Boolean = {
    this.withMRTransaction { implicit c =>
      SQL("DELETE FROM challenge_reviews WHERE user_id = {uid} AND challenge_id = {cid}")
        .on(Symbol("uid") -> userId, Symbol("cid") -> challengeId)
        .execute()
    }
  }

  def getUserReview(userId: Long, challengeId: Long)(
      implicit c: Option[Connection] = None
  ): Option[ChallengeReview] = {
    this.withMRTransaction { implicit c =>
      SQL("SELECT * FROM challenge_reviews WHERE user_id = {uid} AND challenge_id = {cid}")
        .on(Symbol("uid") -> userId, Symbol("cid") -> challengeId)
        .as(parser.singleOpt)
    }
  }

  def getReviewsForChallenge(challengeId: Long, limit: Int = 10, offset: Int = 0)(
      implicit c: Option[Connection] = None
  ): List[ChallengeReview] = {
    this.withMRTransaction { implicit c =>
      SQL(
        """SELECT * FROM challenge_reviews
          |WHERE challenge_id = {cid}
          |ORDER BY modified DESC
          |LIMIT {limit} OFFSET {offset}""".stripMargin
      ).on(
          Symbol("cid")    -> challengeId,
          Symbol("limit")  -> limit,
          Symbol("offset") -> offset
        )
        .as(parser.*)
    }
  }

  def getSummary(challengeId: Long)(
      implicit c: Option[Connection] = None
  ): ChallengeReviewSummary = {
    this.withMRTransaction { implicit c =>
      SQL(
        """SELECT
          |  {cid} as challenge_id,
          |  COUNT(*)::int as total_reviews,
          |  COALESCE(AVG(rating), 0) as avg_rating,
          |  AVG(instructions_clear)::double precision as avg_instructions_clear,
          |  AVG(challenge_interesting)::double precision as avg_challenge_interesting,
          |  AVG(imagery_suitable)::double precision as avg_imagery_suitable,
          |  MODE() WITHIN GROUP (ORDER BY estimated_time) as top_estimated_time,
          |  MODE() WITHIN GROUP (ORDER BY difficulty) as top_difficulty
          |FROM challenge_reviews WHERE challenge_id = {cid}""".stripMargin
      ).on(Symbol("cid") -> challengeId)
        .as(
          (get[Long]("challenge_id") ~
            get[Int]("total_reviews") ~
            get[Double]("avg_rating") ~
            get[Option[Double]]("avg_instructions_clear") ~
            get[Option[Double]]("avg_challenge_interesting") ~
            get[Option[Double]]("avg_imagery_suitable") ~
            get[Option[String]]("top_estimated_time") ~
            get[Option[String]]("top_difficulty") map {
            case cid ~ tr ~ ar ~ aic ~ aci ~ ais ~ tet ~ td =>
              ChallengeReviewSummary(cid, tr, ar, aic, aci, ais, tet, td)
          }).single
        )
    }
  }

  def getRecommended(
      userId: Long,
      userLon: Option[Double],
      userLat: Option[Double],
      userDifficulty: Option[String],
      limit: Int = 20,
      offset: Int = 0
  )(implicit c: Option[Connection] = None): List[(Long, Double)] = {
    this.withMRTransaction { implicit c =>
      SQL(
        """WITH review_scores AS (
          |  SELECT challenge_id,
          |    COALESCE(AVG(rating), 0) as avg_rating,
          |    COUNT(*) as review_count,
          |    COALESCE(
          |      SUM(rating * EXP(-0.01 * EXTRACT(EPOCH FROM (NOW() - modified)) / 86400.0))
          |      / NULLIF(SUM(EXP(-0.01 * EXTRACT(EPOCH FROM (NOW() - modified)) / 86400.0)), 0),
          |      0
          |    ) as weighted_rating,
          |    AVG(instructions_clear) as avg_ic,
          |    AVG(challenge_interesting) as avg_ci,
          |    AVG(imagery_suitable) as avg_is,
          |    MODE() WITHIN GROUP (ORDER BY difficulty) as crowd_difficulty
          |  FROM challenge_reviews
          |  GROUP BY challenge_id
          |),
          |activity AS (
          |  SELECT parent_id as challenge_id,
          |    COUNT(*) FILTER (WHERE modified > NOW() - INTERVAL '7 days') as recent_completions
          |  FROM tasks
          |  WHERE status IN (2, 5, 6)
          |  GROUP BY parent_id
          |)
          |SELECT c.id,
          |  COALESCE(rs.weighted_rating / 5.0, 0) * 0.30 +
          |  COALESCE((COALESCE(rs.avg_ic, 0) + COALESCE(rs.avg_ci, 0) + COALESCE(rs.avg_is, 0)) / 15.0, 0) * 0.20 +
          |  CASE WHEN {userLon} IS NOT NULL AND c.location IS NOT NULL
          |    THEN (1.0 / (1.0 + ST_Distance(c.location::geography,
          |          ST_SetSRID(ST_MakePoint({userLon}, {userLat}), 4326)::geography) / 100000.0)) * 0.20
          |    ELSE 0 END +
          |  CASE WHEN {userDifficulty} IS NOT NULL AND rs.crowd_difficulty = {userDifficulty}
          |    THEN 0.15 ELSE 0 END +
          |  COALESCE(a.recent_completions::float / GREATEST(
          |    (SELECT MAX(x.recent_completions) FROM activity x), 1), 0) * 0.15
          |  AS relevance_score
          |FROM challenges c
          |INNER JOIN projects p ON p.id = c.parent_id
          |LEFT JOIN review_scores rs ON rs.challenge_id = c.id
          |LEFT JOIN activity a ON a.challenge_id = c.id
          |WHERE c.enabled = true AND c.deleted = false
          |  AND p.enabled = true AND p.deleted = false
          |  AND c.status = 3
          |  AND c.id NOT IN (SELECT challenge_id FROM challenge_reviews WHERE user_id = {userId})
          |ORDER BY relevance_score DESC
          |LIMIT {limit} OFFSET {offset}""".stripMargin
      ).on(
          Symbol("userId")         -> userId,
          Symbol("userLon")        -> userLon,
          Symbol("userLat")        -> userLat,
          Symbol("userDifficulty") -> userDifficulty,
          Symbol("limit")          -> limit,
          Symbol("offset")         -> offset
        )
        .as(
          (get[Long]("id") ~ get[Double]("relevance_score") map {
            case id ~ score => (id, score)
          }).*
        )
    }
  }
}

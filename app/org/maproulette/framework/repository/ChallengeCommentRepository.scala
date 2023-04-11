/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import anorm.SqlParser.{get, long}
import anorm._
import org.joda.time.DateTime
import org.maproulette.framework.model.{ChallengeComment, User}
import play.api.db.Database

import java.sql.Connection
import javax.inject.Inject

/**
  * Repository to handle all the database queries for the ChallengeComment object
  *
  * @author jschwarzenberger
  */
class ChallengeCommentRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = ChallengeComment.TABLE

  /**
    *
    * @param challengeId
    * @return A list of challenge comments
    */
  def queryByChallengeId(
      challengeId: Long
  )(implicit c: Option[Connection] = None): List[ChallengeComment] = {
    withMRConnection { implicit c =>
      val query =
        s"""
           |SELECT c.id, c.project_id, c.challenge_id, c.created, c.comment, c.osm_id, u.name, u.avatar_url FROM CHALLENGE_COMMENTS c
           |inner join users as u on c.osm_id = u.osm_id
           |WHERE challenge_id = ${challengeId}
         """.stripMargin
      SQL(query)
        .as(ChallengeCommentRepository.parser.*)
    }
  }

  /**
    *
    * @param userId id of user
    * @return A list of challenge comments
    */
  def queryByUserId(
      userId: Long,
      sort: String = "created",
      order: String = "DESC",
      limit: Int = 25,
      page: Int = 0
  )(implicit c: Option[Connection] = None): List[ChallengeComment] = {
    withMRConnection { implicit c =>
      var internalSort = s"""c.${sort}""";
      if (sort == "challenge_name") {
        internalSort = s"""ch.${sort}""";
      }

      val query =
        SQL"""
           SELECT count(*) OVER() AS full_count, c.id, c.project_id, c.challenge_id, c.created, c.comment, c.osm_id, u.name, u.avatar_url, ch.name as challenge_name FROM CHALLENGE_COMMENTS c
           inner join users as u on c.osm_id = u.osm_id
           inner join challenges as ch on c.challenge_id = ch.id
           WHERE u.id = $userId
           ORDER BY #$sort #$order LIMIT #$limit OFFSET #${(limit * page).toLong}
         """
      query.as(ChallengeCommentRepository.expandedParser.*)
    }
  }

  /**
    * Add comment to a challenge
    *
    * @param user     The user adding the comment
    * @param challengeId     Id of the challenge that is having the comment added to
    * @param comment  The actual comment
    * @param c        Implicit provided optional connection
    */
  def create(user: User, challengeId: Long, comment: String, projectId: Long)(
      implicit c: Option[Connection] = None
  ): ChallengeComment = {
    this.withMRTransaction { implicit c =>
      val query =
        s"""
           |INSERT INTO challenge_comments (osm_id, challenge_id, comment, project_id)
           |VALUES ({osm_id}, {challenge_id}, {comment}, {project_id})
           |RETURNING id, project_id, challenge_id, created
         """.stripMargin
      SQL(query)
        .on(
          Symbol("osm_id")       -> user.osmProfile.id,
          Symbol("challenge_id") -> challengeId,
          Symbol("comment")      -> comment,
          Symbol("project_id")   -> projectId
        )
        .as((long("id") ~ long("project_id") ~ long("challenge_id") ~ get[DateTime]("created") map {
          case id ~ projectId ~ challengeId ~ created =>
            ChallengeComment(
              id,
              user.osmProfile.id,
              user.osmProfile.displayName,
              user.osmProfile.avatarURL,
              challengeId: Long,
              projectId: Long,
              created: DateTime,
              comment: String
            )
        }).single)
    }
  }
}

object ChallengeCommentRepository {
  val parser: RowParser[ChallengeComment] = {
    long("id") ~ long("project_id") ~ long("challenge_id") ~ get[DateTime]("created") ~
      get[String]("comment") ~ long("osm_id") ~ get[String]("name") ~ get[String]("avatar_url") map {
      case id ~ projectId ~ challengeId ~ created ~ comment ~ osmId ~ name ~ avatarUrl =>
        ChallengeComment(
          id,
          osmId,
          name,
          avatarUrl,
          challengeId,
          projectId,
          created,
          comment
        )
    }
  }

  val expandedParser: RowParser[ChallengeComment] = {
    long("id") ~ long("project_id") ~ long("challenge_id") ~ get[DateTime]("created") ~
      get[String]("comment") ~ long("osm_id") ~ get[String]("name") ~ get[String]("avatar_url") ~ get[
      Option[String]
    ]("challenge_name") ~ get[Option[Int]]("full_count") map {
      case id ~ projectId ~ challengeId ~ created ~ comment ~ osmId ~ name ~ avatarUrl ~ challengeName ~ fullCount =>
        ChallengeComment(
          id,
          osmId,
          name,
          avatarUrl,
          challengeId,
          projectId,
          created,
          comment,
          challengeName = challengeName,
          fullCount = fullCount.getOrElse(0)
        )
    }
  }
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection

import anorm.SqlParser.get
import anorm.{RowParser, ~}
import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import org.maproulette.framework.psql.Query
import org.maproulette.framework.model.{LeaderboardUser, LeaderboardChallenge}
import play.api.db.Database

/**
  * @author krotstan
  */
@Singleton
class LeaderboardRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = LeaderboardUser.TABLE

  /**
    * Parser for LeaderboardChallenges
    */
  val leaderboardChallengeParser: RowParser[LeaderboardChallenge] = {
    get[Long]("challenge_id") ~
      get[String]("challenge_name") ~
      get[Int]("activity") ~
      get[Int]("challenges.status") map {
      case id ~ name ~ activity ~ status => {
        new LeaderboardChallenge(id, name, activity, status)
      }
    }
  }

  /**
    * Returns a parser for LeaderboardUsers. A block of code to fetch
    * the top challenges for each user must be included.
   **/
  def userLeaderboardParser(
      getTopChallengesBlock: Long => List[LeaderboardChallenge]
  ): RowParser[LeaderboardUser] = {
    get[Long]("user_id") ~
      get[String]("user_name") ~
      get[String]("user_avatar_url") ~
      get[Int]("user_score") ~
      get[Int]("user_ranking") ~
      get[DateTime]("created").? ~
      get[Int]("completed_tasks").? ~
      get[Long]("avg_time_spent").? ~
      get[Int]("reviews_approved").? ~
      get[Int]("reviews_assisted").? ~
      get[Int]("reviews_rejected").? ~
      get[Int]("reviews_disputed").? ~
      get[Int]("additional_reviews").? map {
      case userId ~ name ~ avatarURL ~ score ~ rank ~ created ~
            completedTasks ~ avgTimeSpent ~ reviewsApproved ~ reviewsAssisted ~
            reviewsRejected ~ reviewsDisputed ~ additional_reviews => {
        new LeaderboardUser(
          userId,
          name,
          avatarURL,
          score,
          rank,
          completedTasks,
          avgTimeSpent,
          created match {
            case Some(c) => c
            case _       => new DateTime()
          },
          getTopChallengesBlock(userId),
          reviewsApproved,
          reviewsAssisted,
          reviewsRejected,
          reviewsDisputed,
          additional_reviews
        )
      }
    }
  }

  /**
    * Query function that allows a user to build their own LeaderboardUsers
    * instead of using results stored in the user_leaderboard table.
    *
    * @param query The query to execute
    * @param c An implicit connection
    * @return A list of returned LeaderboardUsers
    */
  def query(query: Query, getTopChallengesBlock: Long => List[LeaderboardChallenge])(
      implicit c: Option[Connection] = None
  ): List[LeaderboardUser] = {
    withMRConnection { implicit c =>
      query.build().as(this.userLeaderboardParser(getTopChallengesBlock).*)
    }
  }

  /**
    * Query function that allows a user to build their own LeaderboardUsers
    * while ranking them instead of using results stored in the user_leaderboard
    * table.
    *
    * @param query The query to execute
    * @param rankQuery The query conditions to used to determine rank
    * @param c An implicit connection
    * @return A list of returned LeaderboardUsers
    */
  def queryWithRank(
      userId: Long,
      query: Query,
      rankQuery: Query,
      getTopChallengesBlock: Long => List[LeaderboardChallenge]
  )(implicit c: Option[Connection] = None): List[LeaderboardUser] = {
    withMRConnection { implicit c =>
      query.build(s"""WITH rankVariable (rankNum) as (
          SELECT user_ranking FROM (${rankQuery.sql()}) user_rank
          WHERE user_id = ${userId}
        )

        SELECT * FROM (${rankQuery.sql()}) ranks, rankVariable
      """).as(this.userLeaderboardParser(getTopChallengesBlock).*)
    }
  }

  /**
    * Queries the user_leaderboard table
    *
    * @param query
    * @param getTopChallengesBlock - function to return the top challenges for a user id
    * @return List of LeaderboardUsers
   **/
  def queryUserLeaderboard(
      query: Query,
      getTopChallengesBlock: Long => List[LeaderboardChallenge]
  ): List[LeaderboardUser] = {
    withMRConnection { implicit c =>
      query
        .build(
          """
        SELECT *,
              COALESCE(user_leaderboard.completed_tasks, 0) as completed_tasks,
              COALESCE(user_leaderboard.avg_time_spent, 0) as avg_time_spent
        FROM user_leaderboard
        """
        )
        .as(this.userLeaderboardParser(getTopChallengesBlock).*)
    }
  }

  /**
    * Queries the user_top_challenges table to retrieve leaderboard data for a specific challenge
    *
    * @param query The query object containing parameters for filtering and sorting
    * @return List of LeaderboardUsers representing the challenge leaderboard
    */
  def queryChallengeLeaderboard(
      query: Query
  ): List[LeaderboardUser] = {
    withMRConnection { implicit c =>
      query
        .build(
          """
            SELECT
              utc.user_id,
              u.name AS user_name,
              u.avatar_url AS user_avatar_url,
              utc.activity AS user_score,
              ROW_NUMBER() OVER(ORDER BY utc.activity DESC) AS user_ranking
            FROM
              user_top_challenges utc
            JOIN
              users u ON u.id = utc.user_id
            """
        )
        .as(this.userLeaderboardParser(fetchedUserId => List()).*)
    }
  }

  def queryUserChallengeLeaderboardWithRank(
      userId: Int,
      query: Query,
      rankQuery: Query
  )(implicit c: Option[Connection] = None): List[LeaderboardUser] = {
    withMRConnection { implicit c =>
      query.build(s"""
          WITH ranked AS (
              SELECT
                  utc.user_id,
                  u.name AS user_name,
                  u.avatar_url AS user_avatar_url,
                  utc.activity AS user_score,
                  ROW_NUMBER() OVER (ORDER BY utc.activity DESC) AS user_ranking
              FROM user_top_challenges utc
              JOIN users u ON u.id = utc.user_id
            ${rankQuery.sql()}
          ),
          user_rank AS (
              SELECT user_ranking
              FROM ranked
              WHERE user_id = ${userId}
          )
          SELECT
              r.user_id as user_id,
              r.user_name AS user_name,
              r.user_avatar_url AS user_avatar_url,
              r.user_score AS user_score,
              r.user_ranking AS user_ranking
          FROM ranked r
      """).as(this.userLeaderboardParser(fetchedUserId => List()).*)
    }
  }

  /**
    * Queries the user_top_challenges table to retrieve leaderboard data for a specific project
    *
    * @param query The query object containing parameters for filtering and sorting
    * @return List of LeaderboardUsers representing the project leaderboard
    */
  def queryProjectLeaderboard(query: Query): List[LeaderboardUser] = {
    withMRConnection { implicit c =>
      query
        .build(
          s"""
          SELECT
            u.id AS user_id,
            u.name AS user_name,
            u.avatar_url AS user_avatar_url,
            SUM(utc.activity) AS user_score,
            ROW_NUMBER() OVER (ORDER BY SUM(utc.activity) DESC) AS user_ranking
          FROM
            users u
          JOIN
            user_top_challenges utc ON u.id = utc.user_id
          JOIN
            challenges c ON c.id = utc.challenge_id
          JOIN
            projects p ON p.id = c.parent_id
          """
        )
        .as(this.userLeaderboardParser(fetchedUserId => List()).*)
    }
  }

  /**
    * Queries the user_leaderboard table with ranking sql
    *
    * @param query - query parameters to execute
    * @param rankQuery - query to fetch ranking
    * @param getTopChallengesBlock - function to return the top challenges for a user id
    * @return List of LeaderboardUsers
   **/
  def queryUserLeaderboardWithRank(
      query: Query,
      rankQuery: Query,
      getTopChallengesBlock: Long => List[LeaderboardChallenge]
  ): List[LeaderboardUser] = {
    withMRConnection { implicit c =>
      query
        .build(
          s"""
          WITH rankVariable (rankNum) as (
            SELECT user_ranking FROM user_leaderboard ${rankQuery.sql()})

          SELECT *,
                COALESCE(user_leaderboard.completed_tasks, 0) as completed_tasks,
                COALESCE(user_leaderboard.avg_time_spent, 0) as avg_time_spent
          FROM user_leaderboard, rankVariable
          """
        )
        .as(this.userLeaderboardParser(getTopChallengesBlock).*)
    }
  }

  /**
    * Queries user_top_challenges
    *
    * @param query
    * @return List of LeaderboardChallenges
    */
  def queryLeaderboardChallenges(query: Query): List[LeaderboardChallenge] = {
    withMRConnection { implicit c =>
      query
        .build(
          """SELECT challenge_id, challenge_name, activity, challenges.status 
             FROM user_top_challenges
             JOIN challenges ON challenges.id = user_top_challenges.challenge_id"""
        )
        .as(this.leaderboardChallengeParser.*)
    }
  }
}

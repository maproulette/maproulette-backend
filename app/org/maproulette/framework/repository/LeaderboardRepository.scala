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
      get[Int]("activity") map {
      case id ~ name ~ activity => {
        new LeaderboardChallenge(id, name, activity)
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
      get[Int]("completed_tasks") ~
      get[Long]("avg_time_spent") ~
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
          "SELECT challenge_id, challenge_name, activity FROM user_top_challenges"
        )
        .as(this.leaderboardChallengeParser.*)
    }
  }
}

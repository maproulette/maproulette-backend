/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.framework.model.{User, LeaderboardUser, LeaderboardChallenge, Task}
import org.maproulette.framework.mixins.LeaderboardMixin
import org.maproulette.framework.repository.{LeaderboardRepository, ChallengeRepository}
import org.maproulette.framework.psql._
import org.maproulette.framework.psql.filter._
import org.maproulette.session.SearchLeaderboardParameters
import org.maproulette.utils.BoundingBoxFinder

/**
  * Service layer for Leaderboard
  *
  * @author krotstan
  */
@Singleton
class LeaderboardService @Inject() (
    repository: LeaderboardRepository,
    challengeRepository: ChallengeRepository,
    config: Config,
    boundingBoxFinder: BoundingBoxFinder
) extends LeaderboardMixin {

  /**
    * Gets leaderboard of top ranking reviewers based on review activity
    * over the given period.
    *
    * If projectFilter or challengeFilter are given, activity will be limited to
    * those projects and/or challenges.
    *
    * @param params             SearchLeaderboardParameters
    * @param limit             limit the number of returned users
    * @param offset            paging, starting at 0
    * @return Returns list of leaderboard users
    */
  def getReviewerLeaderboard(
      params: SearchLeaderboardParameters,
      limit: Int = Config.DEFAULT_LIST_SIZE,
      offset: Int = 0
  ): List[LeaderboardUser] = {
    val (start, end) = this.setupDates(params.monthDuration, params.start, params.end)

    val projectFilter   = params.projectFilter
    val challengeFilter = params.challengeFilter

    val challengeList =
      if (projectFilter != None)
        // Let's determine all the challenges that are in these projects
        // to make our query faster.
        this.challengeRepository.findRelevantChallenges(projectFilter)
      else challengeFilter

    val query = Query.simple(
      List(
        DateParameter(
          "reviewed_at",
          start,
          end,
          Operator.BETWEEN,
          table = Some("task_review_history"),
          useValueDirectly = true
        ),
        CustomParameter("users.id = task_review_history.reviewed_by"),
        CustomParameter("tasks.id = task_review_history.task_id"),
        FilterParameter.conditional(
          "parent_id",
          challengeList.getOrElse(List()).mkString(","),
          Operator.IN,
          includeOnlyIfTrue = challengeList.getOrElse(List()).nonEmpty,
          useValueDirectly = true,
          table = Some("tasks")
        )
      ),
      grouping = Grouping(
        GroupField("reviewed_by", Some("task_review_history")),
        GroupField(User.FIELD_ID, Some("users"))
      ),
      order = Order(
        List(
          OrderField("user_score", Order.DESC, table = Some("")),
          OrderField("reviewed_by", Order.ASC, table = Some("task_review_history"))
        )
      ),
      base = s"""
            SELECT users.id as user_id, users.name as user_name, users.avatar_url as user_avatar_url, ${this
        .reviewScoreSumSQL(config)} AS user_score,
                   ${this.reviewSumSQL()} AS completed_tasks, ${this
        .reviewTimeSpentSQL()} AS avg_time_spent,
                   ROW_NUMBER() OVER( ORDER BY ${this
        .reviewScoreSumSQL(config)} DESC, task_review_history.reviewed_by ASC) as user_ranking,
        ${this.reviewStatusSumSQL(Task.REVIEW_STATUS_APPROVED)} AS reviews_approved,
        ${this
        .reviewStatusSumSQL(Task.REVIEW_STATUS_APPROVED_WITH_REVISIONS)} AS reviews_approved_with_revisions,
        ${this
        .reviewStatusSumSQL(Task.REVIEW_STATUS_APPROVED_WITH_FIXES_AFTER_REVISIONS)} AS reviews_approved_with_fixes_after_revisions,
        ${this.reviewStatusSumSQL(Task.REVIEW_STATUS_ASSISTED)} AS reviews_assisted,
        ${this.reviewStatusSumSQL(Task.REVIEW_STATUS_REJECTED)} AS reviews_rejected,
        ${this.reviewStatusSumSQL(Task.REVIEW_STATUS_DISPUTED)} AS reviews_disputed,
        ${this.additionalReviewsSumSQL()} AS additional_reviews
            FROM users, task_review_history, tasks
          """,
      paging = Paging(limit, offset)
    )

    this.repository.query(query, fetchedUserId => List())
  }

  /**
    * Gets leaderboard of top-scoring users based on task completion activity
    * over the given period. Scoring for each completed task is based on status
    * assigned to the task (status point values are configurable). Users are
    * returned in descending order with top scores first; ties are broken by
    * OSM user id with the lowest/earliest ids being ranked ahead of
    * higher/later ids. Also included with each user are their top challenges
    * (by amount of activity).
    *
    * If the optional userFilter, projectFilter or challengeFilter are given,
    * activity will be limited to those users, projects and/or challenges.
    *
    * @param params             SearchLeaderboardParameters
    * @param limit             limit the number of returned users
    * @param offset            paging, starting at 0
    * @return Returns list of leaderboard users with scores
    */
  def getMapperLeaderboard(
      params: SearchLeaderboardParameters,
      limit: Int = Config.DEFAULT_LIST_SIZE,
      offset: Int = 0
  ): List[LeaderboardUser] = {
    // We can attempt to use the pre-built user_leaderboard table if we have no user,
    // no project and no challenge filters (and have a specified monthDuration).
    if (params.userFilter == None && params.projectFilter == None && params.challengeFilter == None &&
        params.onlyEnabled && params.monthDuration != None &&
        (params.countryCodeFilter == None || params.countryCodeFilter.toList.head.length == 1)) {
      val result = this.repository.queryUserLeaderboard(
        Query.simple(
          this.basicFilters(params.monthDuration, params.countryCodeFilter),
          paging = Paging(limit, offset),
          order = Order(List(OrderField("user_ranking", Order.ASC, table = Some(""))))
        ),
        fetchedUserId => this.getUserTopChallenges(fetchedUserId, params)
      )

      if (result.length > 0) {
        return result
      }
    }

    val (startDate, endDate) = this.setupDates(params.monthDuration, params.start, params.end)

    val query = this
      .leaderboardWithRankSQL(
        params.userFilter,
        params.projectFilter,
        params.challengeFilter,
        params.countryCodeFilter,
        startDate,
        endDate
      )
      .copy(paging = Paging(limit, offset))

    this.repository.query(
      query,
      fetchedUserId => this.getUserTopChallenges(fetchedUserId, params)
    )
  }

  /**
    * Gets leaderboard rank for a user based on task completion activity
    * over the given period. Scoring for each completed task is based on status
    * assigned to the task (status point values are configurable). Also included
    * is the user's top challenges (by amount of activity).
    *
    * If the optional projectFilter or challengeFilter are given,
    * activity will be limited to those projects and/or challenges.
    *
    * @param userId            user id
    * @param params             SearchLeaderboardParameters
    * @param onlyEnabled       only enabled in user top challenges (doesn't affect scoring)
    * @return Returns leaderboard for user with score
    */
  def getLeaderboardForUser(
      userId: Long,
      params: SearchLeaderboardParameters,
      bracket: Int = 0
  ): List[LeaderboardUser] = {
    // We can attempt to use the pre-built user_leaderboard table if we have no user,
    // no project and no challenge filters (and have a specified monthDuration).
    if (params.projectFilter == None && params.challengeFilter == None && params.onlyEnabled && params.monthDuration != None &&
        (params.countryCodeFilter == None || params.countryCodeFilter.toList.head.length == 1)) {
      val result = this.repository.queryUserLeaderboardWithRank(
        Query.simple(
          CustomParameter(
            s"user_ranking BETWEEN (rankNum - ${bracket}) AND (rankNum + ${bracket})"
          ) :: this.basicFilters(params.monthDuration, params.countryCodeFilter)
        ),
        Query.simple(
          BaseParameter(
            "user_id",
            userId,
            Operator.EQ,
            useValueDirectly = true
          ) :: this.basicFilters(params.monthDuration, params.countryCodeFilter)
        ),
        fetchedUserId => this.getUserTopChallenges(fetchedUserId, params)
      )

      if (result.length > 0) {
        return result
      }
    }

    val (startDate, endDate) = this.setupDates(params.monthDuration, params.start, params.end)

    val rankQuery = this.leaderboardWithRankSQL(
      None,
      params.projectFilter,
      params.challengeFilter,
      params.countryCodeFilter,
      startDate,
      endDate
    )

    this.repository.queryWithRank(
      userId,
      Query.simple(
        List(
          CustomParameter(s"user_ranking BETWEEN (rankNum - ${bracket}) AND (rankNum + ${bracket})")
        )
      ),
      rankQuery,
      fetchedUserId => this.getUserTopChallenges(fetchedUserId, params)
    )
  }

  /**
    * Gets the top challenges by activity for the given user over the given period.
    * Challenges are in descending order by amount of activity, with ties broken
    * by the challenge id with the lowest/earliest ids being ranked ahead of
    * higher/later ids.
    *
    * @param userId            the id of the user
    * @param params             SearchLeaderboardParameters
    * @param limit             limit the number of returned challenges
    * @param offset            paging, starting at 0
    * @return Returns list of leaderboard challenges
    */
  def getUserTopChallenges(
      userId: Long,
      params: SearchLeaderboardParameters,
      limit: Int = Config.DEFAULT_LIST_SIZE,
      offset: Int = 0
  ): List[LeaderboardChallenge] = {
    // We can attempt to use the pre-built top challenges table if we have no user,
    // no project and no challenge filters (and have a specified monthDuration).
    if (params.projectFilter == None && params.challengeFilter == None && params.onlyEnabled && params.monthDuration != None &&
        (params.countryCodeFilter == None || params.countryCodeFilter.toList.head.length == 1)) {
      val result = this.repository.queryLeaderboardChallenges(
        Query.simple(
          BaseParameter(
            "user_id",
            userId,
            Operator.EQ,
            useValueDirectly = true,
            table = Some("")
          ) :: this.basicFilters(params.monthDuration, params.countryCodeFilter),
          paging = Paging(limit, offset),
          order = Order(
            List(
              OrderField("activity", Order.DESC, table = Some("")),
              OrderField("challenge_id", Order.ASC, table = Some(""))
            )
          )
        )
      )

      if (result.length > 0) {
        return result
      }
    }

    val (startDate, endDate)                = setupDates(params.monthDuration, params.start, params.end)
    val (boundingSearch, taskTableIfNeeded) = setupBoundingSearch(params.countryCodeFilter)

    var challengeList =
      if (params.projectFilter != None)
        // Let's determine all the challenges that are in these projects
        // to make our query faster.
        this.challengeRepository.findRelevantChallenges(params.projectFilter)
      else params.challengeFilter

    val query = Query
      .simple(
        List(
          BaseParameter(
            "id",
            userId,
            Operator.EQ,
            table = Some("u")
          ),
          CustomParameter("sa.osm_user_id = u.osm_id"),
          CustomParameter(boundingSearch),
          CustomParameter("sa.challenge_id = c.id"),
          FilterParameter.conditional(
            "challenge_id",
            challengeList.getOrElse(List()).mkString(","),
            Operator.IN,
            includeOnlyIfTrue = challengeList.getOrElse(List()).nonEmpty,
            useValueDirectly = true,
            table = Some("sa")
          )
        ),
        grouping = Grouping(
          GroupField("challenge_id", table = Some("sa")),
          GroupField("name", table = Some("c"))
        ),
        paging = Paging(limit, offset),
        order = Order(
          List(
            OrderField("activity", Order.DESC, table = Some("")),
            OrderField("challenge_id", Order.ASC, table = Some("sa"))
          )
        ),
        base = s"""
            SELECT sa.challenge_id as challenge_id, c.name as challenge_name, count(sa.challenge_id) as activity
                FROM status_actions sa, challenges c, projects p, users u
                ${taskTableIfNeeded}
          """
      )
      .addFilterGroup(
        FilterGroup(
          List(
            ConditionalFilterParameter(
              CustomParameter("p.id = sa.project_id"),
              includeOnlyIfTrue = params.onlyEnabled
            ),
            ConditionalFilterParameter(
              BaseParameter("enabled", None, Operator.BOOL, table = Some("c")),
              includeOnlyIfTrue = params.onlyEnabled
            ),
            ConditionalFilterParameter(
              BaseParameter("enabled", None, Operator.BOOL, table = Some("p")),
              includeOnlyIfTrue = params.onlyEnabled
            )
          )
        )
      )

    this.repository.queryLeaderboardChallenges(query)
  }

  /**
    * Returns the SQL to fetch the ordered leaderboard data with rankings. Can be filtered
    * by a list of projects, challenges, users and start/end date.
    **/
  private def leaderboardWithRankSQL(
      userFilter: Option[List[Long]] = None,
      projectFilter: Option[List[Long]] = None,
      challengeFilter: Option[List[Long]] = None,
      countryCodeFilter: Option[List[String]] = None,
      start: DateTime,
      end: DateTime
  ): Query = {
    val (boundingSearch, taskTableIfNeeded) = setupBoundingSearch(countryCodeFilter)
    val challengeList =
      if (projectFilter != None)
        // Let's determine all the challenges that are in these projects
        // to make our query faster.
        this.challengeRepository.findRelevantChallenges(projectFilter)
      else challengeFilter

    Query.simple(
      List(
        DateParameter(
          "created",
          start,
          end,
          Operator.BETWEEN,
          table = Some("sa"),
          useValueDirectly = true
        ),
        CustomParameter("sa.old_status <> sa.status"),
        CustomParameter("users.osm_id = sa.osm_user_id"),
        CustomParameter("tasks.id = sa.task_id"),
        CustomParameter(boundingSearch),
        BaseParameter(
          "leaderboard_opt_out",
          None,
          Operator.BOOL,
          negate = true,
          useValueDirectly = true,
          table = Some("users")
        ),
        FilterParameter.conditional(
          User.FIELD_ID,
          userFilter.getOrElse(List()).mkString(","),
          Operator.IN,
          includeOnlyIfTrue = userFilter.getOrElse(List()).nonEmpty,
          useValueDirectly = true,
          table = Some("users")
        ),
        FilterParameter.conditional(
          "challenge_id",
          challengeList.getOrElse(List()).mkString(","),
          Operator.IN,
          includeOnlyIfTrue = challengeList.getOrElse(List()).nonEmpty,
          useValueDirectly = true,
          table = Some("sa")
        )
      ),
      grouping =
        Grouping(GroupField("osm_user_id", Some("sa")), GroupField(User.FIELD_ID, Some("users"))),
      order = Order(
        List(
          OrderField("user_score", Order.DESC, table = Some("")),
          OrderField("osm_user_id", Order.ASC, table = Some("sa"))
        )
      ),
      base = s"""
          SELECT users.id as user_id, users.name as user_name, users.avatar_url as user_avatar_url, ${this
        .scoreSumSQL(config)} AS user_score,
                 ${this.tasksSumSQL()} AS completed_tasks, ${this.timeSpentSQL()} AS avg_time_spent,
                 ROW_NUMBER() OVER( ORDER BY ${this
        .scoreSumSQL(config)} DESC, sa.osm_user_id ASC) as user_ranking
          FROM status_actions sa, users, tasks
          $taskTableIfNeeded
        """
    )
  }

  // Returns basic filters for monthDuration and countryCode
  private def basicFilters(
      monthDuration: Option[Int],
      countryCodeFilter: Option[List[String]]
  ): List[Parameter[_]] = {
    val countryCode =
      if (countryCodeFilter == None) null
      else countryCodeFilter.toList.head.head

    List(
      BaseParameter(
        "month_duration",
        monthDuration.get,
        Operator.EQ,
        useValueDirectly = true,
        table = Some("")
      ),
      FilterParameter.conditional(
        "country_code",
        s"'${countryCode}'",
        Operator.EQ,
        useValueDirectly = true,
        includeOnlyIfTrue = countryCodeFilter != None,
        table = Some("")
      ),
      FilterParameter.conditional(
        "country_code",
        None,
        Operator.NULL,
        useValueDirectly = true,
        includeOnlyIfTrue = countryCodeFilter == None,
        table = Some("")
      )
    )
  }

  // Setups start and end dates based on monthDuration
  private def setupDates(
      monthDuration: Option[Int],
      start: Option[DateTime],
      end: Option[DateTime]
  ): (DateTime, DateTime) = {
    var startDate = start.getOrElse(new DateTime())
    var endDate   = end.getOrElse(new DateTime())
    if (monthDuration != None) {
      endDate = new DateTime()
      if (monthDuration.get == -1) {
        startDate = new DateTime(2000, 1, 1, 12, 0, 0, 0)
      } else if (monthDuration.get == 0) {
        startDate = new DateTime().withDayOfMonth(1)
      } else {
        startDate = new DateTime().minusMonths(monthDuration.get)
      }
    }

    (startDate, endDate)
  }

  // Sets up the correct bounding box search for the given country code
  private def setupBoundingSearch(countryCodeFilter: Option[List[String]]): (String, String) = {
    var taskTableIfNeeded = ""
    val boundingSearch = countryCodeFilter match {
      case Some(ccList) if ccList.nonEmpty =>
        taskTableIfNeeded = ", tasks t"
        val boundingBoxes = ccList.map { cc =>
          s"""
            ST_Intersects(t.location, ST_MakeEnvelope(
              ${boundingBoxFinder.boundingBoxforCountry(cc)}, 4326))
           """
        }
        "t.id = sa.task_id AND (" + boundingBoxes.mkString(" OR ") + ") "
      case _ => ""
    }

    (boundingSearch, taskTableIfNeeded)
  }
}

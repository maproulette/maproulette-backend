/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.jobs.utils

import java.time.{LocalDate, Period}
import java.time.format.DateTimeFormatter

import org.maproulette.Config
import org.maproulette.framework.mixins.LeaderboardMixin

/**
  *
  *
  * @author krotstan
  */
object LeaderboardHelper extends LeaderboardMixin {

  /**
    * Returns the SQL to rebuild the Challenges Leaderboard table with the
    * given dates
    *
    * @param monthDuration - The number of months to fetch (-1 indicates all time)
    */
  def rebuildChallengesLeaderboardSQL(monthDuration: Int, config: Config): String = {
    val timeClause = monthDuration match {
      case -1 => ""
      case default =>
        val today = LocalDate.now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val startMonth = LocalDate.now
          .minus(Period.ofMonths(monthDuration))
          .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        s"""sa.created::DATE BETWEEN '$startMonth' AND '$today' AND"""
    }

    s"""INSERT INTO user_leaderboard
        (month_duration, user_id, user_name, user_avatar_url, user_ranking, user_score,
         completed_tasks, avg_time_spent)
        SELECT $monthDuration, users.id, users.name, users.avatar_url,
                ROW_NUMBER() OVER( ORDER BY ${this.scoreSumSQL(config)} DESC, sa.osm_user_id ASC),
                ${this.scoreSumSQL(config)} AS score,
                ${this.tasksSumSQL()} AS completed_tasks,
                ${this.timeSpentSQL()} AS avg_time_spent
                FROM status_actions sa, users, tasks
                WHERE $timeClause
                      sa.old_status <> sa.status AND
                      users.osm_id = sa.osm_user_id AND
                      users.leaderboard_opt_out = FALSE AND
                      tasks.id = sa.task_id
                GROUP BY sa.osm_user_id, users.id
                ORDER BY score DESC, sa.osm_user_id ASC"""
  }

  /**
    * Returns the SQL to rebuild the Challenges Leaderboard table with the
    * given dates
    *
    * @param monthDuration - The number of months to fetch (-1 indicates all time)
    * @param countryCode - The countryCode corresponding to the bounding box
    * @param boundingBox - The boundingBox to search in
    */
  def rebuildChallengesLeaderboardSQLCountry(
      monthDuration: Int,
      countryCode: String,
      boundingBox: String,
      config: Config
  ): String = {
    val timeClause = monthDuration match {
      case -1 => ""
      case default =>
        val today = LocalDate.now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val startMonth = LocalDate.now
          .minus(Period.ofMonths(monthDuration))
          .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        s"""sa.created::DATE BETWEEN '$startMonth' AND '$today' AND"""
    }

    s"""INSERT INTO user_leaderboard
        (month_duration, country_code, user_id, user_name, user_avatar_url, user_ranking,
         user_score, completed_tasks, avg_time_spent)
        SELECT $monthDuration, '${countryCode}', users.id, users.name, users.avatar_url,
                ROW_NUMBER() OVER( ORDER BY ${this.scoreSumSQL(config)} DESC, sa.osm_user_id ASC),
                ${this.scoreSumSQL(config)} AS score,
                ${this.tasksSumSQL()} AS completed_tasks,
                ${this.timeSpentSQL()} AS avg_time_spent
                FROM status_actions sa, users, tasks
                WHERE $timeClause
                      sa.old_status <> sa.status AND
                      users.osm_id = sa.osm_user_id AND
                      users.leaderboard_opt_out = FALSE AND
                      tasks.id = sa.task_id AND
                      ST_Intersects(tasks.location, ST_MakeEnvelope($boundingBox, 4326))
                GROUP BY sa.osm_user_id, users.id
                ORDER BY score DESC, sa.osm_user_id ASC"""
  }

  /**
    * Returns the SQL to rebuild the Top Challenges table with the
    * given dates
    *
    * @param start_date
    * @param end_date
    */
  def rebuildTopChallengesSQL(monthDuration: Int, config: Config): String = {
    val timeClause = monthDuration match {
      case -1 => ""
      case default =>
        val today = LocalDate.now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val startMonth = LocalDate.now
          .minus(Period.ofMonths(monthDuration))
          .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        s"""sa.created::DATE BETWEEN '$startMonth' AND '$today' AND"""
    }

    s"""INSERT INTO user_top_challenges
        (month_duration, user_id, challenge_id, challenge_name, activity)
        SELECT $monthDuration, u.id, sa.challenge_id, c.name, count(sa.challenge_id) as activity
          FROM status_actions sa, challenges c, projects p, users u
          WHERE $timeClause
            sa.osm_user_id = u.osm_id AND sa.challenge_id = c.id AND
            p.id = sa.project_id AND c.enabled = TRUE and p.enabled = TRUE
          GROUP BY sa.challenge_id, c.name, u.id"""
  }

  /**
    * Returns the SQL to rebuild the Top Challenges table with the
    * given dates
    *
    * @param start_date
    * @param end_date
    */
  def rebuildTopChallengesSQLCountry(
      monthDuration: Int,
      countryCode: String,
      boundingBox: String,
      config: Config
  ): String = {
    val timeClause = monthDuration match {
      case -1 => ""
      case default =>
        val today = LocalDate.now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val startMonth = LocalDate.now
          .minus(Period.ofMonths(monthDuration))
          .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        s"""sa.created::DATE BETWEEN '$startMonth' AND '$today' AND"""
    }

    s"""INSERT INTO user_top_challenges
        (month_duration, country_code, user_id, challenge_id, challenge_name, activity)
        SELECT $monthDuration, '$countryCode', u.id, sa.challenge_id, c.name, count(sa.challenge_id) as activity
          FROM status_actions sa, challenges c, projects p, users u, tasks
          WHERE $timeClause
            sa.osm_user_id = u.osm_id AND sa.challenge_id = c.id AND
            p.id = sa.project_id AND c.enabled = TRUE and p.enabled = TRUE AND
            tasks.id = sa.task_id AND
            ST_Intersects(tasks.location, ST_MakeEnvelope($boundingBox, 4326))
          GROUP BY sa.challenge_id, c.name, u.id"""
  }
}

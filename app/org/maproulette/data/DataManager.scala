/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.data

import anorm.SqlParser._
import anorm._
import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.framework.model.Task
import org.maproulette.framework.service.ServiceManager
import org.maproulette.models.utils.{AND, DALHelper, WHERE}
import org.maproulette.models.dal.mixin.SearchParametersMixin
import org.maproulette.session.SearchParameters
import org.maproulette.utils.BoundingBoxFinder
import play.api.Application
import play.api.db.Database

case class ActionSummary(
    total: Int,
    available: Int,
    fixed: Int,
    falsePositive: Int,
    skipped: Int,
    deleted: Int,
    alreadyFixed: Int,
    tooHard: Int,
    answered: Int,
    validated: Int,
    disabled: Int,
    avgTimeSpent: Double,
    tasksWithTime: Int
) {
  def percentComplete: Double = (((trueAvailable / total) * 100) - 100) * -1

  // available in the database means it is created state, available in the UI, means that it is in state
  // AVAILABLE, SKIPPED or CAN'T COMPLETE (Formerly TOO HARD)
  def trueAvailable: Double = available + skipped + deleted

  def percentage(value: Double): Double = (value / total) * 100

  def values(): List[Int] =
    List(
      total,
      available,
      fixed,
      falsePositive,
      skipped,
      deleted,
      alreadyFixed,
      tooHard,
      answered,
      validated,
      disabled
    )
}

case class ChallengeSummary(id: Long, name: String, actions: ActionSummary)

case class ChallengeActivity(date: DateTime, status: Int, statusName: String, count: Int)

case class RawActivity(
    date: DateTime,
    osmUserId: Long,
    osmUsername: String,
    projectId: Long,
    projectName: String,
    challengeId: Long,
    challengeName: String,
    taskId: Long,
    oldStatus: Int,
    status: Int
)

/**
  * @author cuthbertm
  */
@Singleton
class DataManager @Inject() (
    config: Config,
    db: Database,
    boundingBoxFinder: BoundingBoxFinder,
    serviceManager: ServiceManager
)(
    implicit application: Application
) extends DALHelper
    with SearchParametersMixin {

  /**
    * Gets the summarized challenge activity
    *
    * @param projectList The projects to filter by default None, will assume all projects
    * @param challengeId The challenge to filter by default None, if set will ignore the projects parameter
    * @return
    */
  def getChallengeSummary(
      projectList: Option[List[Long]] = None,
      challengeId: Option[Long] = None,
      priority: Option[List[Int]] = None,
      onlyEnabled: Boolean = false,
      params: Option[SearchParameters] = None
  ): List[ChallengeSummary] = {
    this.db.withConnection { implicit c =>
      val parser = for {
        id             <- int("tasks.parent_id")
        name           <- str("challenges.name")
        total          <- int("total")
        available      <- int("available")
        fixed          <- int("fixed")
        falsePositive  <- int("false_positive")
        skipped        <- int("skipped")
        deleted        <- int("deleted")
        alreadyFixed   <- int("already_fixed")
        tooHard        <- int("too_hard")
        answered       <- int("answered")
        validated      <- int("validated")
        disabled       <- int("disabled")
        totalTimeSpent <- int("totalTimeSpent")
        tasksWithTime  <- int("tasksWithTime")
      } yield ChallengeSummary(
        id,
        name,
        ActionSummary(
          total,
          available,
          fixed,
          falsePositive,
          skipped,
          deleted,
          alreadyFixed,
          tooHard,
          answered,
          validated,
          disabled,
          if (tasksWithTime > 0) (totalTimeSpent / tasksWithTime) else 0,
          tasksWithTime
        )
      )
      val searchParams = SearchParameters.withDefaultAllTaskStatuses(
        params match {
          case Some(p) => p
          case None    => new SearchParameters()
        }
      )

      val challengeFilter = challengeId match {
        case Some(id) if id != -1 => s"AND tasks.parent_id = $id"
        case _                    => buildProjectSearch(projectList, "c.parent_id", "c.id")
      }
      val priorityFilter = priority match {
        case Some(p) =>
          val invert =
            if (searchParams.invertFields.getOrElse(List()).contains("priority")) "NOT" else ""
          s"AND tasks.priority ${invert} IN (${p.mkString(",")})"
        case None => ""
      }

      val searchFilters = new StringBuilder(s"1=1 $challengeFilter $priorityFilter")

      // We deliberately ignore user filters here (owner, mapper, reviewer),
      // since this endpoint can be called by any logged in user.
      this.paramsTaskStatus(searchParams, searchFilters)
      this.paramsTaskReviewStatus(searchParams, searchFilters)
      this.paramsTaskId(searchParams, searchFilters)

      // The percentage columns are a bit of a hack simply so that we can order by the percentages.
      // It won't decrease performance as this is simple basic math calculations, but it certainly
      // isn't pretty
      val query =
        s"""SELECT tasks.parent_id, c.name,
              COUNT(tasks.completed_time_spent) as tasksWithTime,
              COALESCE(SUM(tasks.completed_time_spent), 0) as totalTimeSpent,
              SUM(CASE WHEN (tasks.status != 4 AND tasks.status != 9) THEN 1 ELSE 0 END) as total,
              SUM(CASE tasks.status WHEN 0 THEN 1 ELSE 0 END) as available,
              SUM(CASE tasks.status WHEN 1 THEN 1 ELSE 0 END) as fixed,
              SUM(CASE tasks.status WHEN 2 THEN 1 ELSE 0 END) as false_positive,
              SUM(CASE tasks.status WHEN 3 THEN 1 ELSE 0 END) as skipped,
              SUM(CASE tasks.status WHEN 4 THEN 1 ELSE 0 END) as deleted,
              SUM(CASE tasks.status WHEN 5 THEN 1 ELSE 0 END) as already_fixed,
              SUM(CASE tasks.status WHEN 6 THEN 1 ELSE 0 END) AS too_hard,
              SUM(CASE tasks.status WHEN 7 THEN 1 ELSE 0 END) AS answered,
              SUM(CASE tasks.status WHEN 8 THEN 1 ELSE 0 END) AS validated,
              SUM(CASE tasks.status WHEN 9 THEN 1 ELSE 0 END) AS disabled
            FROM tasks
            INNER JOIN challenges c ON c.id = tasks.parent_id
            INNER JOIN projects p ON p.id = c.parent_id
            WHERE ${if (onlyEnabled) {
          "c.enabled = true AND p.enabled = true AND"
        } else {
          ""
        }}
              ${searchFilters.toString()}
            GROUP BY tasks.parent_id, c.name
        """

      SQL(query).as(parser.*)
    }
  }

  /**
    * Gets the project activity (default will get data for all projects) grouped by timed action
    *
    * @param projectList The projects to filter by default None, will assume all projects
    * @param challengeId The challenge to filter by default None, if set will ignore the projects parameter
    * @param start       The start date to filter by, default None will take all values. If start set and
    *                    end not set, then will go from start till current day.
    * @param end         The end date to filter by, default None will take all values. If start not set and
    *                    end is set, then will ignore the end date
    */
  def getChallengeActivity(
      projectList: Option[List[Long]] = None,
      challengeId: Option[Long] = None,
      start: Option[DateTime] = None,
      end: Option[DateTime] = None,
      priority: Option[Int] = None
  ): List[ChallengeActivity] = {
    this.db.withConnection { implicit c =>
      val parser = for {
        seriesDate <- get[DateTime]("series_date")
        status     <- int("status")
        count      <- int("count")
      } yield ChallengeActivity(
        seriesDate,
        status,
        Task.getStatusName(status).getOrElse("Unknown"),
        count
      )
      val challengeProjectFilter = challengeId match {
        case Some(id) => s"AND challenge_id = $id"
        case None     => buildProjectSearch(projectList, "project_id", "c.id")
      }
      val dates = this.getDates(start, end)
      SQL"""
          SELECT series_date,
            CASE WHEN sa.status IS NULL THEN 0 ELSE sa.status END AS status,
            CASE WHEN count IS NULL THEN 0 ELSE count END AS count
          FROM (SELECT CURRENT_DATE + i AS series_date
                FROM generate_series(date '#${dates._1}' - CURRENT_DATE, date '#${dates._2}' - CURRENT_DATE) i) d
          LEFT JOIN (
            SELECT sa.created::date, sa.status, COUNT(sa.status) AS count
            FROM status_actions sa
            #${this.getEnabledPriorityClause(challengeId.isEmpty, false, start, end, priority)}
            AND sa.status IN (0, 1, 2, 3, 5, 6) AND old_status != sa.status
            #$challengeProjectFilter
            GROUP BY sa.created::date, sa.status
            ORDER BY sa.created::date, sa.status ASC
          ) sa ON d.series_date = sa.created""".as(parser.*)
    }
  }

  private def getEnabledPriorityClause(
      onlyEnabled: Boolean = true,
      isSurvey: Boolean = true,
      start: Option[DateTime] = None,
      end: Option[DateTime] = None,
      priority: Option[Int] = None,
      ignoreDates: Boolean = false
  ): String = {
    val priorityClauses = priority match {
      case Some(p) => ("INNER JOIN tasks t ON t.id = sa.task_id", s"AND t.priority = $p")
      case None    => ("", "")
    }
    if (onlyEnabled) {
      s"""|INNER JOIN challenges c ON c.id = sa.${if (isSurvey) {
           "survey_id"
         } else {
           "challenge_id"
         }}
          |${priorityClauses._1}
          |INNER JOIN projects p ON p.id = c.parent_id
          |WHERE c.enabled = true and p.enabled = true
          |${priorityClauses._2}
          |${if (!ignoreDates) {
           getDateClause("sa.created", start, end)(Some(AND()))
         } else {
           ""
         }}
       """.stripMargin
    } else if (!ignoreDates) {
      s"""
         |${priorityClauses._1}
         |${getDateClause("sa.created", start, end)(Some(WHERE()))}
         |${priorityClauses._2}
       """.stripMargin
    } else {
      s"""
         |${priorityClauses._1}
         |WHERE 1=1 ${priorityClauses._2}
       """.stripMargin
    }
  }

  private def buildProjectSearch(
      projectList: Option[List[Long]] = None,
      projectColumn: String,
      challengeColumn: String
  ): String = {
    projectList match {
      case Some(idList) if idList.length > 0 =>
        val project = this.serviceManager.project.retrieve(idList.head).get
        project.isVirtual match {
          case Some(false) =>
            s"AND $projectColumn IN (${idList.mkString(",")})"
          case Some(true) =>
            s"""AND ($projectColumn IN (${idList.mkString(",")})
                   OR 1 IN (SELECT 1 FROM unnest(ARRAY[${idList.mkString(",")}]) AS pIds
                       WHERE pIds IN (SELECT vp.project_id FROM virtual_project_challenges vp
                                      WHERE vp.challenge_id = ${challengeColumn})))"""
        }
      case Some(idList) if idList.nonEmpty =>
        s"""AND ($projectColumn IN (${idList.mkString(",")})
                 OR 1 IN (SELECT 1 FROM unnest(ARRAY[${idList.mkString(",")}]) AS pIds
                     WHERE pIds IN (SELECT vp.project_id FROM virtual_project_challenges vp
                                    WHERE vp.challenge_id = ${challengeColumn})))"""
      case _ => ""
    }
  }

  /**
    * Gets the raw activity from the status action logs, it joins the user, projects and challenges
    * table to get the names for the various objects
    *
    * @param userFilter      A filter for users
    * @param projectFilter   A filter for projects
    * @param challengeFilter A filter for challenges
    * @param start           A filter for the start date
    * @param end             A filter for the end date
    * @return Returns a list of activity
    */
  def getRawActivity(
      userFilter: Option[List[Long]] = None,
      projectFilter: Option[List[Long]] = None,
      challengeFilter: Option[List[Long]] = None,
      start: Option[DateTime] = None,
      end: Option[DateTime] = None
  ): List[RawActivity] = {
    this.db.withConnection { implicit c =>
      val parser = for {
        date          <- get[DateTime]("status_actions.created")
        osmUserId     <- long("status_actions.osm_user_id")
        osmUsername   <- str("users.name")
        projectId     <- long("status_actions.project_id")
        projectName   <- str("projects.name")
        challengeId   <- long("status_actions.challenge_id")
        challengeName <- str("challenges.name")
        taskId        <- long("status_actions.task_id")
        oldStatus     <- int("status_actions.old_status")
        status        <- int("status_actions.status")
      } yield RawActivity(
        date,
        osmUserId,
        osmUsername,
        projectId,
        projectName,
        challengeId,
        challengeName,
        taskId,
        oldStatus,
        status
      )

      var challengeList = challengeFilter
      if (projectFilter != None) {
        challengeList = findRelevantChallenges(projectFilter)
      }

      SQL"""
         SELECT sa.created, sa.osm_user_id, u.name, sa.project_id, p.name, sa.challenge_id,
                 c.name, sa.task_id, sa.old_status, sa.status
         FROM status_actions sa
         LEFT JOIN users u ON u.osm_id = sa.osm_user_id
         INNER JOIN projects p ON p.id = sa.project_id
         INNER JOIN challenges c ON c.id = sa.challenge_id
         WHERE #${getDateClause("sa.created", start, end)}
         #${getLongListFilter(challengeList, "sa.challenge_id")}
         #${getLongListFilter(userFilter, "sa.osm_user_id")}
         """.as(parser.*)
    }
  }

  // @deprecated
  // This method is now in challengeRepository
  private def findRelevantChallenges(projectList: Option[List[Long]]): Option[List[Long]] = {
    this.db.withConnection { implicit c =>
      // Let's determine all the challenges that are in these projects
      // to make our query faster.
      implicit val conjunction = Some(WHERE())
      val projectChallengeQuery =
        s"""SELECT id FROM challenges
         ${getLongListFilter(projectList, "parent_id")} OR id IN
          (SELECT challenge_id FROM virtual_project_challenges vp
           ${getLongListFilter(projectList, "vp.project_id")})
         """
      Some(SQL(projectChallengeQuery).as(long("id").*))
    }
  }

  def getPropertyKeys(challengeId: Long): List[String] = {
    db.withConnection { implicit c =>
      val parser = for {
        key <- get[String]("key")
      } yield key

      SQL"""SELECT DISTINCT json_object_keys((features->>'properties')::JSON) as key
          FROM tasks, jsonb_array_elements(geojson->'features') features
          WHERE parent_id=#${challengeId}
       """.as(parser.*)
    }
  }
}

object DataManager {
  val PREVIOUS_WEEK = -7
}

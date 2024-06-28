/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.data

import java.sql.Connection
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
import play.api.libs.json.{JsObject, Json}

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

/**
  * Handles the summary data for the users
  *
  * @param distinctAllUsers              This is all the distinct users regardless of the date range
  * @param distinctTotalUsers            All the distinct users within the supplied date range
  * @param avgUsersPerChallenge          Average number of users per challenge within the date range
  * @param activeUsers                   Active users (2 or more edits in last 2 days)
  * @param avgActionsPerUser             Average number of actions taken by a user
  * @param avgActionsPerChallengePerUser Average number of actions taken by user per challenge
  */
case class UserSummary(
    distinctAllUsers: Int,
    distinctTotalUsers: Int,
    avgUsersPerChallenge: Double,
    activeUsers: Double,
    avgActionsPerUser: ActionSummary,
    avgActionsPerChallengePerUser: ActionSummary
)

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
  private def getDistinctUsers(
      projectFilter: String,
      survey: Boolean = false,
      onlyEnabled: Boolean = true,
      start: Option[DateTime] = None,
      end: Option[DateTime] = None,
      priority: Option[Int]
  )(implicit c: Connection): Int = {
    SQL"""SELECT COUNT(DISTINCT osm_user_id) AS count
             FROM #${if (survey) {
      "survey_answers sa"
    } else {
      "status_actions sa"
    }}
             #${this.getEnabledPriorityClause(onlyEnabled, survey, start, end, priority)}
             #$projectFilter""".as(get[Option[Int]]("count").single).getOrElse(0)
  }

  private def getDistinctUsersPerChallenge(
      projectFilter: String,
      survey: Boolean = false,
      onlyEnabled: Boolean = true,
      start: Option[DateTime] = None,
      end: Option[DateTime] = None,
      priority: Option[Int]
  )(implicit c: Connection): Double = {
    SQL"""SELECT AVG(count) AS count FROM (
            SELECT #${if (survey) {
      "survey_id"
    } else {
      "challenge_id"
    }}, COUNT(DISTINCT osm_user_id) AS count
            FROM #${if (survey) {
      "survey_answers sa"
    } else {
      "status_actions sa"
    }}
            #${this.getEnabledPriorityClause(onlyEnabled, survey, start, end, priority)} #$projectFilter
            GROUP BY #${if (survey) {
      "survey_id"
    } else {
      "challenge_id"
    }}
          ) as t""".as(get[Option[Double]]("count").single).getOrElse(0d)
  }

  private def getActiveUsers(
      projectFilter: String,
      survey: Boolean = false,
      onlyEnabled: Boolean = true,
      start: Option[DateTime] = None,
      end: Option[DateTime] = None,
      priority: Option[Int]
  )(implicit c: Connection): Int = {
    SQL"""SELECT COUNT(DISTINCT osm_user_id) AS count
          FROM #${if (survey) {
      "survey_answers sa"
    } else {
      "status_actions sa"
    }}
          #${this.getEnabledPriorityClause(onlyEnabled, survey, start, end, priority)}  #$projectFilter
          AND sa.created::date BETWEEN current_date - INTERVAL '2 days' AND current_date"""
      .as(get[Option[Int]]("count").single)
      .getOrElse(0)
  }

  def getUserChallengeSummary(
      projectList: Option[List[Long]] = None,
      challengeId: Option[Long] = None,
      start: Option[DateTime] = None,
      end: Option[DateTime] = None,
      priority: Option[Int]
  ): UserSummary = {
    this.db.withConnection { implicit c =>
      val challengeProjectFilter = challengeId match {
        case Some(id) => s"AND sa.challenge_id = $id"
        case None     => buildProjectSearch(projectList, "sa.project_id", "sa.challenge_id")
      }
      val actionParser = for {
        available     <- get[Option[Int]]("available")
        fixed         <- get[Option[Int]]("fixed")
        falsePositive <- get[Option[Int]]("false_positive")
        skipped       <- get[Option[Int]]("skipped")
        deleted       <- get[Option[Int]]("deleted")
        alreadyFixed  <- get[Option[Int]]("already_fixed")
        tooHard       <- get[Option[Int]]("too_hard")
        answered      <- get[Option[Int]]("answered")
        validated     <- get[Option[Int]]("validated")
        disabled      <- get[Option[Int]]("disabled")
      } yield ActionSummary(
        0,
        available.getOrElse(0),
        fixed.getOrElse(0),
        falsePositive.getOrElse(0),
        skipped.getOrElse(0),
        deleted.getOrElse(0),
        alreadyFixed.getOrElse(0),
        tooHard.getOrElse(0),
        answered.getOrElse(0),
        validated.getOrElse(0),
        disabled.getOrElse(0),
        0,
        0
      )

      val perUser =
        SQL"""SELECT AVG(available) AS available, AVG(fixed) AS fixed, AVG(false_positive) AS false_positive,
                                  AVG(skipped) AS skipped, AVG(deleted) AS deleted, AVG(already_fixed) AS already_fixed,
                                  AVG(too_hard) AS too_hard, AVG(answered) AS answered, AVG(validated) AS validated,
                                  AVG(disabled) AS disabled
                              FROM (
                                SELECT sa.osm_user_id,
                                    SUM(CASE sa.status WHEN 0 THEN 1 ELSE 0 END) AS available,
                                    SUM(CASE sa.status WHEN 1 THEN 1 ELSE 0 END) AS fixed,
                                    SUM(CASE sa.status WHEN 2 THEN 1 ELSE 0 END) AS false_positive,
                                    SUM(CASE sa.status WHEN 3 THEN 1 ELSE 0 END) AS skipped,
                                    SUM(CASE sa.status WHEN 4 THEN 1 ELSE 0 END) AS deleted,
                                    SUM(CASE sa.status WHEN 5 THEN 1 ELSE 0 END) AS already_fixed,
                                    SUM(CASE sa.status WHEN 6 THEN 1 ELSE 0 END) AS too_hard,
                                    SUM(CASE sa.status WHEN 7 THEN 1 ELSE 0 END) AS answered,
                                    SUM(CASE sa.status WHEN 8 THEN 1 ELSE 0 END) AS validated,
                                    SUM(CASE sa.status WHEN 9 THEN 1 ELSE 0 END) AS disabled
                                FROM status_actions sa
                                #${this.getEnabledPriorityClause(
          challengeId.isEmpty,
          false,
          start,
          end,
          priority
        )}
                                #$challengeProjectFilter
                                GROUP BY sa.osm_user_id
                              ) AS t""".as(actionParser.*).head
      val perChallenge =
        SQL"""SELECT AVG(available) AS available, AVG(fixed) AS fixed, AVG(false_positive) AS false_positive,
                                        AVG(skipped) AS skipped, AVG(deleted) AS deleted, AVG(already_fixed) AS already_fixed,
                                        AVG(too_hard) AS too_hard, AVG(answered) AS answered, AVG(validated) AS validated,
                                        AVG(disabled) AS disabled
                                  FROM (
                                    SELECT osm_user_id, challenge_id,
                                        SUM(CASE sa.status WHEN 0 THEN 1 ELSE 0 END) AS available,
                                        SUM(CASE sa.status WHEN 1 THEN 1 ELSE 0 END) AS fixed,
                                        SUM(CASE sa.status WHEN 2 THEN 1 ELSE 0 END) AS false_positive,
                                        SUM(CASE sa.status WHEN 3 THEN 1 ELSE 0 END) AS skipped,
                                        SUM(CASE sa.status WHEN 4 THEN 1 ELSE 0 END) AS deleted,
                                        SUM(CASE sa.status WHEN 5 THEN 1 ELSE 0 END) AS already_fixed,
                                        SUM(CASE sa.status WHEN 6 THEN 1 ELSE 0 END) AS too_hard,
                                        SUM(CASE sa.status WHEN 7 THEN 1 ELSE 0 END) AS answered,
                                        SUM(CASE sa.status WHEN 8 THEN 1 ELSE 0 END) AS validated,
                                        SUM(CASE sa.status WHEN 9 THEN 1 ELSE 0 END) AS disabled
                                    FROM status_actions sa
                                    #${this.getEnabledPriorityClause(
          challengeId.isEmpty,
          false,
          start,
          end,
          priority
        )}
                                    #$challengeProjectFilter
                                    GROUP BY osm_user_id, challenge_id
                                  ) AS t""".as(actionParser.*).head
      val allUsers =
        SQL"""SELECT count(DISTINCT osm_user_id) FROM status_actions sa
              #${this.getEnabledPriorityClause(
          challengeId.isEmpty,
          false,
          None,
          None,
          priority,
          true
        )}
              #$challengeProjectFilter
           """.as(int(1).single)
      UserSummary(
        allUsers,
        this.getDistinctUsers(
          challengeProjectFilter,
          false,
          challengeId.isEmpty,
          start,
          end,
          priority
        ),
        this.getDistinctUsersPerChallenge(
          challengeProjectFilter,
          false,
          challengeId.isEmpty,
          start,
          end,
          priority
        ),
        this
          .getActiveUsers(challengeProjectFilter, false, challengeId.isEmpty, start, end, priority),
        perUser,
        perChallenge
      )
    }
  }

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
      virtualChallengeId: Option[Long] = None,
      limit: Int = (-1),
      offset: Int = 0,
      orderColumn: Option[String] = None,
      orderDirection: String = "ASC",
      searchString: String = "",
      priority: Option[List[Int]] = None,
      onlyEnabled: Boolean = false,
      params: Option[SearchParameters] = None
  ): List[ChallengeSummary] = {
    this.db.withConnection { implicit c =>
      val virtualParams = virtualChallengeId.flatMap { id =>
        if (id != 0) {
          val result =
            SQL("SELECT search_parameters FROM virtual_challenges WHERE id = {virtualChallengeId}")
              .on("virtualChallengeId" -> id)
              .as(SqlParser.str("search_parameters").singleOpt)
          result.map(Json.parse).flatMap(_.validate[SearchParameters].asOpt)
        } else {
          None
        }
      }

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
        if (virtualParams.isDefined) {
          virtualParams match {
            case Some(p) => p
            case None    => new SearchParameters()
          }
        } else {
          params match {
            case Some(p) => p
            case None    => new SearchParameters()
          }
        }
      )

      val challengeFilter = virtualParams match {
        case Some(vp) =>
          vp.challengeParams.challengeIds match {
            case Some(idList) => s"AND tasks.parent_id IN (${idList.mkString(", ")})"
            case _            => ""
          }
        case None =>
          challengeId match {
            case Some(id) if id != -1 => s"AND tasks.parent_id = $id"
            case _                    => buildProjectSearch(projectList, "c.parent_id", "c.id")
          }
      }

      val priorityFilter = priority match {
        case Some(p) =>
          val invert =
            if (searchParams.invertFields.getOrElse(List()).contains("priority")) "NOT" else ""
          s"AND tasks.priority ${invert} IN (${p.mkString(",")})"
        case None => ""
      }

      val searchFilters = new StringBuilder(
        s"1=1 $challengeFilter $priorityFilter ${if (searchString != "") searchField("c.name")
        else ""}"
      )

      this.paramsTaskStatus(searchParams, searchFilters)
      this.paramsTaskReviewStatus(searchParams, searchFilters)
      this.paramsTaskId(searchParams, searchFilters)
      this.paramsOwner(searchParams, searchFilters)
      this.paramsReviewer(searchParams, searchFilters)
      this.paramsMapper(searchParams, searchFilters)
      this.paramsBoundingGeometries(searchParams, searchFilters)

      // The percentage columns are a bit of a hack simply so that we can order by the percentages.
      // It won't decrease performance as this is simple basic math calculations, but it certainly
      // isn't pretty
      val query =
        s"""SELECT tasks.parent_id, c.name,
              COUNT(tasks.completed_time_spent) as tasksWithTime,
              COALESCE(SUM(tasks.completed_time_spent), 0) as totalTimeSpent,
              SUM(CASE WHEN tasks.status != 4 THEN 1 ELSE 0 END) as total,
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
            ${this.order(orderColumn, orderDirection)}
            LIMIT ${this.sqlLimit(limit)} OFFSET {offset}
        """

      SQL(query)
        .on(Symbol("ss") -> this.search(searchString), Symbol("offset") -> offset)
        .as(parser.*)
    }
  }

  /**
    * Should be used in conjunction with challenge summary to retrieve the total number of challenges that
    * are set for the particular summary query
    *
    * @param projectList  The projects that are being used to filter the results, optional
    * @param challengeId  The challenge used to filter the results, optional
    * @param searchString The search string that was applied to the query
    * @return A integer value which is the total challenges included in the results
    * @deprecated("This method does not support virtual projects.", "05-23-2019")
    */
  def getTotalSummaryCount(
      projectList: Option[List[Long]] = None,
      challengeId: Option[Long] = None,
      searchString: String = ""
  ): Int = {
    this.db.withConnection { implicit c =>
      val challengeFilter = challengeId match {
        case Some(id) if id != -1 => s"AND id = $id"
        case _                    => getLongListFilter(projectList, "c.parent_id")
      }
      val query =
        s"""SELECT COUNT(*) AS total FROM challenges c
                      INNER JOIN projects p ON p.id = c.parent_id
                      WHERE ${if (challengeId.isEmpty) {
          "c.enabled = true AND p.enabled = true AND"
        } else {
          ""
        }}
                      1=1 $challengeFilter ${this.searchField("c.name")}"""
      SQL(query).on(Symbol("ss") -> this.search(searchString)).as(int("total").single)
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

  /**
    * Gets the most recent activity entries for each challenge, regardless of date.
    *
    * @param projectFilter   restrict to specified projects
    * @param challengeFilter restrict to specified challenges
    * @param entries         the number of most recent activity entries per challenge. Defaults to 1.
    * @return most recent activity entries for each challenge
    */
  def getLatestChallengeActivity(
      projectFilter: Option[List[Long]] = None,
      challengeFilter: Option[List[Long]] = None,
      entries: Int = 1
  ): List[RawActivity] = {
    db.withConnection { implicit c =>
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

      // Let's determine all the challenges that are in these projects
      // to make our query faster.
      if (projectFilter != None) {
        challengeList = findRelevantChallenges(projectFilter)
      }

      SQL"""SELECT sa.*, challenges.name, projects.name, users.name FROM challenges, projects, users
            JOIN LATERAL (
              SELECT * FROM status_actions
              WHERE challenge_id = challenges.id
              AND old_status <> status
              ORDER BY created DESC
              LIMIT ${entries}
            ) sa ON true
            WHERE challenges.parent_id = projects.id
            AND users.osm_id = sa.osm_user_id
            #${getLongListFilter(challengeList, "challenges.id")}
      """.as(parser.*)
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

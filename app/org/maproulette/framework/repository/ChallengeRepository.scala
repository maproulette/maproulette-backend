/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection
import anorm.SqlParser._
import anorm.{RowParser, SQL, ~}

import javax.inject.{Inject, Singleton}
import org.apache.commons.lang3.StringUtils
import org.joda.time.DateTime
import org.maproulette.framework.model._
import org.maproulette.framework.psql.{GroupField, Grouping, OR, Query}
import org.maproulette.framework.psql.filter._
import play.api.db.Database

/**
  * The challenge repository handles all the querying with the databases related to challenge objects
  *
  * @author mcuthbert
  */
@Singleton
class ChallengeRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = Challenge.TABLE

  /**
    * Query function that allows a user to build their own query against the Challenge table
    *
    * @param query The query to execute
    * @param c An implicit connection
    * @return A list of returned Challenges
    */
  def query(query: Query)(implicit c: Option[Connection] = None): List[Challenge] = {
    withMRConnection { implicit c =>
      query
        .build(
          s"""
          |SELECT ${ChallengeRepository.standardColumns}
          |FROM challenges
          |LEFT OUTER JOIN virtual_project_challenges
          |ON challenges.id = virtual_project_challenges.challenge_id
          """.stripMargin,
          Grouping(GroupField(Challenge.FIELD_ID))
        )
        .as(ChallengeRepository.parser.*)
    }
  }

  /**
    * Returns all the challenge ids that are in the given projects
    * @param projectList - ids of projects (can also include virtual projects)
    * @return list of challenge ids
    */
  def findRelevantChallenges(projectList: Option[List[Long]]): Option[List[Long]] = {
    withMRConnection { implicit c =>
      Some(
        Query
          .simple(
            List(
              BaseParameter(
                Challenge.FIELD_PARENT_ID,
                projectList.getOrElse(List()),
                Operator.IN
              ),
              SubQueryFilter(
                Challenge.FIELD_ID,
                Query.simple(
                  List(
                    BaseParameter(
                      "project_id",
                      projectList.getOrElse(List()),
                      Operator.IN
                    )
                  ),
                  "SELECT challenge_id FROM virtual_project_challenges vp"
                )
              )
            ),
            key = OR()
          )
          .build("SELECT id FROM challenges")
          .as(long("id").*)
      )
    }
  }

  /**
    * Retrieve all challenges that are not yet deleted or archived
    * @param archived: include or exclude archived challenges
    * @return list of challenges
    */
  def activeChallenges(
      archived: Boolean = false
  )(implicit c: Option[Connection] = None): List[ArchivableChallenge] = {
    withMRConnection { implicit c =>
      var archiveQuery = "";

      if (!archived) {
        archiveQuery = " c.is_archived = false AND";
      }

      SQL(
        s"""
           |SELECT c.id, c.created, c.name, c.deleted, c.is_archived
           |FROM challenges as c
           |WHERE${archiveQuery} c.deleted = false
        """.stripMargin
      ).as(ChallengeRepository.archivedChallengesParser.*)
    }
  }

  /**
    * Retrieve all tasks by challenge ID
    * @return list of tasks
    */
  def getTasksByParentId(id: Long)(implicit c: Option[Connection] = None): List[ArchivableTask] = {
    withMRConnection { implicit c =>
      SQL(
        s"""
           |SELECT id, modified, status
           |FROM tasks
           |WHERE parent_id = ${id}
        """.stripMargin
      ).as(ChallengeRepository.archivedTaskParser.*)
    }
  }

  /**
    * Update challenge archive status
    * @param challengeId id of challenge
    * @param archiving boolean indicating whether to archive or unarchive the challenge
    * @param systemArchive boolean indicating if the system scheduler is performing the event
    */
  def archiveChallenge(
      challengeId: Long,
      archiving: Boolean = true,
      systemArchive: Boolean = false
  )(implicit c: Option[Connection] = None): Boolean = {
    this.withMRConnection { implicit c =>
      var systemArchiveStatement = s", system_archived_at = NULL";
      if (systemArchive && archiving) {
        systemArchiveStatement = s", system_archived_at = '${DateTime.now()}'"
      }

      val query = s"""
         |UPDATE CHALLENGES
         |	SET is_archived = ${archiving}${systemArchiveStatement}
         |	WHERE id = ${challengeId}
        """.stripMargin

      SQL(query).execute()

      archiving
    }
  }

  /**
    * Update challenge completion metrics
    * @param challengeId
    * @param tasksRemaining
    * @param completionPercentage
    */
  def updateChallengeCompletionMetrics(
      challengeId: Long,
      tasksRemaining: Integer,
      completionPercentage: Integer
  )(implicit c: Option[Connection] = None): Unit = {
    withMRConnection { implicit c =>
      SQL(
        s"""
           |UPDATE CHALLENGES
           |	SET tasks_remaining = ${tasksRemaining}, completion_percentage = ${completionPercentage}
           |	WHERE id = ${challengeId}
        """.stripMargin
      ).execute()
    }
  }
}

object ChallengeRepository {
  val standardColumns: String =
    "challenges.*, ST_AsGeoJSON(location) AS locationJSON, ST_AsGeoJSON(bounding) AS boundingJSON, ARRAY_REMOVE(ARRAY_AGG(virtual_project_challenges.project_id), NULL) AS virtual_parent_ids"

  /**
    * The row parser for Anorm to enable the object to be read from the retrieved row directly
    * to the Challenge object.
    */
  val parser: RowParser[Challenge] = {
    get[Long]("challenges.id") ~
      get[String]("challenges.name") ~
      get[DateTime]("challenges.created") ~
      get[DateTime]("challenges.modified") ~
      get[Option[String]]("challenges.description") ~
      get[Option[String]]("challenges.info_link") ~
      get[Long]("challenges.owner_id") ~
      get[Long]("challenges.parent_id") ~
      get[String]("challenges.instruction") ~
      get[Int]("challenges.difficulty") ~
      get[Option[String]]("challenges.blurb") ~
      get[Boolean]("challenges.enabled") ~
      get[Boolean]("challenges.featured") ~
      get[Int]("challenges.cooperative_type") ~
      get[Option[Int]]("challenges.popularity") ~
      get[Option[String]]("challenges.checkin_comment") ~
      get[Option[String]]("challenges.checkin_source") ~
      get[Option[String]]("challenges.overpass_ql") ~
      get[Option[String]]("challenges.overpass_target_type") ~
      get[Option[String]]("challenges.remote_geo_json") ~
      get[Option[Int]]("challenges.status") ~
      get[Option[String]]("challenges.status_message") ~
      get[Int]("challenges.default_priority") ~
      get[Option[String]]("challenges.high_priority_rule") ~
      get[Option[String]]("challenges.medium_priority_rule") ~
      get[Option[String]]("challenges.low_priority_rule") ~
      get[Int]("challenges.default_zoom") ~
      get[Int]("challenges.min_zoom") ~
      get[Int]("challenges.max_zoom") ~
      get[Option[Int]]("challenges.default_basemap") ~
      get[Option[String]]("challenges.default_basemap_id") ~
      get[Option[String]]("challenges.custom_basemap") ~
      get[Boolean]("challenges.updatetasks") ~
      get[Option[String]]("challenges.exportable_properties") ~
      get[Option[String]]("challenges.osm_id_property") ~
      get[Option[String]]("challenges.task_bundle_id_property") ~
      get[Option[String]]("challenges.preferred_tags") ~
      get[Option[String]]("challenges.preferred_review_tags") ~
      get[Boolean]("challenges.limit_tags") ~
      get[Boolean]("challenges.limit_review_tags") ~
      get[Option[String]]("challenges.task_styles") ~
      get[Option[DateTime]]("challenges.last_task_refresh") ~
      get[Option[DateTime]]("challenges.data_origin_date") ~
      get[Boolean]("challenges.requires_local") ~
      get[Option[String]]("locationJSON") ~
      get[Option[String]]("boundingJSON") ~
      get[Boolean]("deleted") ~
      get[Option[List[Long]]]("virtual_parent_ids") ~
      get[Boolean]("challenges.is_archived") ~
      get[Int]("challenges.review_setting") ~
      get[Option[DateTime]]("challenges.system_archived_at") map {
      case id ~ name ~ created ~ modified ~ description ~ infoLink ~ ownerId ~ parentId ~ instruction ~
            difficulty ~ blurb ~ enabled ~ featured ~ cooperativeType ~ popularity ~ checkin_comment ~
            checkin_source ~ overpassql ~ overpassTargetType ~ remoteGeoJson ~ status ~ statusMessage ~ defaultPriority ~ highPriorityRule ~
            mediumPriorityRule ~ lowPriorityRule ~ defaultZoom ~ minZoom ~ maxZoom ~ defaultBasemap ~ defaultBasemapId ~
            customBasemap ~ updateTasks ~ exportableProperties ~ osmIdProperty ~ taskBundleIdProperty ~ preferredTags ~ preferredReviewTags ~
            limitTags ~ limitReviewTags ~ taskStyles ~ lastTaskRefresh ~ dataOriginDate ~ requiresLocal ~ location ~ bounding ~
            deleted ~ virtualParents ~ isArchived ~ reviewSetting ~ systemArchivedAt =>
        val hpr = highPriorityRule match {
          case Some(c) if StringUtils.isEmpty(c) || StringUtils.equals(c, "{}") => None
          case r                                                                => r
        }
        val mpr = mediumPriorityRule match {
          case Some(c) if StringUtils.isEmpty(c) || StringUtils.equals(c, "{}") => None
          case r                                                                => r
        }
        val lpr = lowPriorityRule match {
          case Some(c) if StringUtils.isEmpty(c) || StringUtils.equals(c, "{}") => None
          case r                                                                => r
        }
        new Challenge(
          id,
          name,
          created,
          modified,
          description,
          deleted,
          infoLink,
          ChallengeGeneral(
            ownerId,
            parentId,
            instruction,
            difficulty,
            blurb,
            enabled,
            featured,
            cooperativeType,
            popularity,
            checkin_comment.getOrElse(""),
            checkin_source.getOrElse(""),
            virtualParents,
            requiresLocal
          ),
          ChallengeCreation(overpassql, remoteGeoJson, overpassTargetType),
          ChallengePriority(defaultPriority, hpr, mpr, lpr),
          ChallengeExtra(
            defaultZoom,
            minZoom,
            maxZoom,
            defaultBasemap,
            defaultBasemapId,
            customBasemap,
            updateTasks,
            exportableProperties,
            osmIdProperty,
            preferredTags,
            preferredReviewTags,
            limitTags,
            limitReviewTags,
            taskStyles,
            taskBundleIdProperty,
            isArchived,
            reviewSetting,
            systemArchivedAt
          ),
          status,
          statusMessage,
          lastTaskRefresh,
          dataOriginDate,
          location,
          bounding
        )
    }
  }

  /**
    * The row parser for archive-related chalenge data
    */
  val archivedChallengesParser: RowParser[ArchivableChallenge] = {
    get[Long]("challenges.id") ~
      get[DateTime]("challenges.created") ~
      get[String]("challenges.name") ~
      get[Boolean]("challenges.deleted") ~
      get[Boolean]("challenges.is_archived") map {
      case id ~ created ~ name ~ deleted ~ isArchived =>
        new ArchivableChallenge(
          id,
          created,
          name,
          deleted,
          isArchived
        )
    }
  }

  /**
    * The row parser for archive-related task data
    */
  val archivedTaskParser: RowParser[ArchivableTask] = {
    get[Long]("tasks.id") ~
      get[DateTime]("tasks.modified") ~
      get[Long]("tasks.status") map {
      case id ~ modified ~ status =>
        new ArchivableTask(
          id,
          modified,
          status
        )
    }
  }
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection

import anorm.SqlParser._
import anorm._
import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import org.maproulette.data.Actions
import org.maproulette.framework.model._
import org.maproulette.framework.psql._
import org.maproulette.framework.psql.filter._
import org.maproulette.framework.service.GrantService
import org.maproulette.session.SearchParameters
import org.maproulette.utils.Readers
import play.api.db.Database
import play.api.libs.json.Json

/**
  * Repository to handle all database actionns related to Projects, no business logic should be
  * found in this class.
  *
  * @author mcuthbert
  */
@Singleton
class ProjectRepository @Inject() (override val db: Database, grantService: GrantService)
    extends RepositoryMixin {
  implicit val baseTable: String = Project.TABLE

  /**
    * Finds 0 or more projects that match the filter criteria
    *
    * @param query The psql query object containing all the filtering, paging and ordering information
    * @param c An implicit connection, that defaults to None
    * @return The list of projects that match the filter criteria
    */
  def query(query: Query)(implicit c: Option[Connection] = None): List[Project] = {
    this.withMRTransaction { implicit c =>
      query.build("SELECT * FROM projects").as(this.parser.*)
    }
  }

  /**
    * For a given id returns the project
    *
    * @param id The id of the project you are looking for
    * @param c An implicit connection, defaults to none and one will be created automatically
    * @return None if not found, otherwise the Project
    */
  def retrieve(id: Long)(implicit c: Option[Connection] = None): Option[Project] = {
    this.withMRTransaction { implicit c =>
      Query
        .simple(List(BaseParameter(Project.FIELD_ID, id)))
        .build("SELECT * FROM projects")
        .as(this.parser.*)
        .headOption
    }
  }

  private def parser: RowParser[Project] =
    ProjectRepository.parser(id =>
      this.grantService.retrieveGrantsOn(GrantTarget.project(id), User.superUser)
    )

  /**
    * Inserts a project into the database
    *
    * @param project The project to insert into the database. The project will failed to be inserted
    *                if a project with the same name already exists. If the id field is set on the
    *                provided project it will be ignored.
    * @param c An implicit connection, that defaults to None
    * @return The project that was inserted now with the generated id
    */
  def create(project: Project)(implicit c: Option[Connection] = None): Project = {
    this.withMRTransaction { implicit c =>
      SQL("""INSERT INTO projects (name, owner_id, display_name, description, enabled, is_virtual, featured, require_confirmation)
              VALUES ({name}, {ownerId}, {displayName}, {description}, {enabled}, {virtual}, {featured}, {requireConfirmation})
              RETURNING *""")
        .on(
          Symbol("name")        -> project.name,
          Symbol("ownerId")     -> project.owner,
          Symbol("displayName") -> project.displayName,
          Symbol("description") -> project.description.getOrElse(""),
          Symbol("enabled")     -> project.enabled,
          Symbol("virtual")     -> project.isVirtual.getOrElse(false),
          Symbol("featured")    -> project.featured,
          Symbol("requireConfirmation") -> project.requireConfirmation
        )
        .as(this.parser.*)
        .head
    }
  }

  /**
    * Updates a project in the database based on the provided project object
    *
    * @param project The properties of the project to update
    * @param c An implicit connection, that defaults to None
    * @return The project that was updated
    */
  def update(project: Project)(implicit c: Option[Connection] = None): Option[Project] = {
    this.withMRTransaction { implicit c =>
      SQL("""UPDATE projects SET
           name = {name},
           owner_id = {ownerId},
           display_name = {displayName},
           description = {description},
           enabled = {enabled},
           is_virtual = {virtual},
           featured = {featured},
           is_archived = {isArchived},
           require_confirmation = {requireConfirmation}
           WHERE id = {id}
           RETURNING *
        """)
        .on(
          Symbol("name")           -> project.name,
          Symbol("ownerId")        -> project.owner,
          Symbol("displayName")    -> project.displayName,
          Symbol("description")    -> project.description,
          Symbol("enabled")        -> project.enabled,
          Symbol("virtual")        -> project.isVirtual,
          Symbol("featured")       -> project.featured,
          Symbol("isArchived")     -> project.isArchived,
          Symbol("requireConfirmation") -> project.requireConfirmation,
          Symbol("id")             -> project.id
        )
        .as(this.parser.*)
        .headOption
    }
  }

  /**
    * Deletes a project from the database, by default it simply sets the project to deleted.
    *
    * @param id The id of the project to delete
    * @param immediate Defaults to false, if set to true it will execute the delete immediately otherwise it will simply update the deleted flag on the project object
    * @param c An implicit conectioo, defaults to None
    * @return
    */
  def delete(id: Long, immediate: Boolean = false)(
      implicit c: Option[Connection] = None
  ): Boolean = {
    this.withMRTransaction { implicit c =>
      if (immediate) {
        Query
          .simple(List(BaseParameter(Project.FIELD_ID, id)))
          .build("DELETE FROM projects")
          .execute()
      } else {
        SQL("""UPDATE projects SET deleted = true WHERE id = {id}""")
          .on(Symbol("id") -> id)
          .execute()
      }
    }
  }

  def getSearchedClusteredPoints(
      params: SearchParameters,
      paging: Paging = Paging(),
      featured: Boolean = false
  )(implicit c: Option[Connection] = None): List[ClusteredPoint] = {
    this.withMRTransaction { implicit c =>
      val tagsEnabled = params.challengeParams.challengeTags.isDefined && params.challengeParams.challengeTags.get.nonEmpty
      val bounds = params.location match {
        case Some(location) =>
          s"challenges.location @ ST_MakeEnvelope(${location.left}, ${location.bottom}, ${location.right}, ${location.top}, 4326)"
        case None => ""
      }

      val tagQuery = if (tagsEnabled) {
        """
          |INNER JOIN tags_on_challenges ON tags_on_challenges.challenge_id = challenges.id
          |INNER JOIN tags ON tags.id = tags_on_challenges.tag_id
          """.stripMargin
      } else {
        ""
      }

      val baseQuery =
        s"""
          SELECT challenges.id, users.osm_id, users.name, challenges.name, challenges.parent_id,
                  |projects.name, challenges.blurb, ST_AsGeoJSON(challenges.location) AS location,
                  |ST_AsGeoJSON(challenges.bounding) AS bounding, challenges.difficulty,
                  |challenges.last_updated
          |FROM challenges
          |INNER JOIN projects ON projects.id = challenges.parent_id
          |INNER JOIN users ON users.osm_id = challenges.owner_id
          |$tagQuery""".stripMargin
      val tagFilterString =
        params.challengeParams.challengeTags.getOrElse(List.empty).mkString("%(", "|", ")%")

      Query
        .simple(
          List(
            BaseParameter(
              "location",
              null,
              Operator.NULL,
              negate = true,
              table = Some(Challenge.TABLE)
            ),
            SubQueryFilter(
              "",
              Query.simple(
                List(
                  BaseParameter("parent_id", "challenges.id", useValueDirectly = true),
                  BaseParameter(
                    "status",
                    List(Task.STATUS_CREATED, Task.STATUS_SKIPPED, Task.STATUS_TOO_HARD),
                    Operator.IN
                  )
                ),
                "SELECT id FROM tasks",
                paging = Paging(1)
              ),
              operator = Operator.EXISTS
            ),
            FilterParameter.conditional(
              "featured",
              true,
              includeOnlyIfTrue = featured,
              table = Some(Challenge.TABLE)
            ),
            BaseParameter(
              "name",
              SQLUtils.search(params.challengeParams.challengeSearch.getOrElse("")),
              Operator.ILIKE,
              table = Some(Challenge.TABLE)
            ),
            BaseParameter(
              "name",
              SQLUtils.search(params.projectSearch.getOrElse("")),
              Operator.ILIKE
            ),
            FilterParameter.conditional(
              "enabled",
              params.enabledChallenge,
              includeOnlyIfTrue = params.enabledChallenge,
              table = Some(Challenge.TABLE)
            ),
            FilterParameter.conditional(
              "enabled",
              params.enabledProject,
              includeOnlyIfTrue = params.enabledProject
            ),
            BaseParameter("deleted", false, table = Some(Challenge.TABLE)),
            BaseParameter("deleted", false),
            FilterParameter.conditional(
              "parent_id",
              params.projectIds.getOrElse(List.empty),
              includeOnlyIfTrue = params.projectIds.getOrElse(List.empty).nonEmpty,
              table = Some(Challenge.TABLE)
            ),
            ConditionalFilterParameter(CustomParameter(bounds), params.location.isDefined),
            FilterParameter.conditional(
              "name",
              tagFilterString,
              Operator.SIMILAR_TO,
              includeOnlyIfTrue = tagsEnabled,
              table = Some(Tag.TABLE)
            )
          ),
          baseQuery,
          paging = paging
        )
        .build()
        .as(ProjectRepository.pointParser.*)
    }
  }

  def getClusteredPoints(
      projectId: Option[Long] = None,
      challengeIds: List[Long] = List.empty,
      enabledOnly: Boolean = true,
      paging: Paging = Paging()
  )(implicit c: Option[Connection] = None): List[ClusteredPoint] = {
    this.withMRTransaction { implicit c =>
      Query
        .simple(
          List(
            BaseParameter(
              "location",
              null,
              Operator.NULL,
              negate = true,
              table = Some(Challenge.TABLE)
            ),
            SubQueryFilter(
              "",
              Query.simple(
                List(
                  BaseParameter("parent_id", "challenges.id", useValueDirectly = true),
                  BaseParameter(
                    "status",
                    List(Task.STATUS_CREATED, Task.STATUS_SKIPPED, Task.STATUS_TOO_HARD),
                    Operator.IN
                  )
                ),
                "SELECT id FROM tasks",
                paging = Paging(1)
              ),
              operator = Operator.EXISTS
            ),
            FilterParameter.conditional(
              "enabled",
              enabledOnly,
              includeOnlyIfTrue = enabledOnly,
              table = Some(Challenge.TABLE)
            ),
            FilterParameter.conditional("enabled", enabledOnly, includeOnlyIfTrue = enabledOnly),
            BaseParameter("deleted", false, table = Some(Challenge.TABLE)),
            BaseParameter("deleted", false),
            FilterParameter.conditional(
              "parent_id",
              projectId.getOrElse(-1),
              includeOnlyIfTrue = projectId.isDefined,
              table = Some(Challenge.TABLE)
            ),
            FilterParameter.conditional(
              "id",
              challengeIds,
              Operator.IN,
              includeOnlyIfTrue = challengeIds.nonEmpty,
              table = Some(Challenge.TABLE)
            )
          ),
          """SELECT challenges.id, users.osm_id, users.name, challenges.name,
                    |challenges.parent_id, projects.name, challenges.blurb,
                    |ST_AsGeoJSON(challenges.location) AS location, ST_AsGeoJSON(challenges.bounding) AS bounding,
                    |challenges.difficulty, challenges.last_updated
              |FROM challenges
              |INNER JOIN projects ON projects.id = challenges.parent_id
              |INNER JOIN users ON users.osm_id = challenges.owner_id""".stripMargin,
          paging = paging
        )
        .build()
        .as(ProjectRepository.pointParser.*)
    }
  }
}

object ProjectRepository extends Readers {
  val pointParser = {
    long("challenges.id") ~
      int("users.osm_id") ~
      str("users.name") ~
      str("challenges.name") ~
      int("challenges.parent_id") ~
      str("projects.name") ~
      get[Option[String]]("challenges.blurb") ~
      str("location") ~
      str("bounding") ~
      get[DateTime]("challenges.last_updated") ~
      int("challenges.difficulty") map {
      case id ~ osm_id ~ username ~ name ~ parentId ~ parentName ~ blurb ~ location ~ bounding ~ modified ~ difficulty =>
        val locationJSON = Json.parse(location)
        val coordinates  = (locationJSON \ "coordinates").as[List[Double]]
        val point        = Point(coordinates(1), coordinates.head)
        val pointReview  = PointReview(None, None, None, None, None, None, None, None, None)
        val boundingJSON = Json.parse(bounding)
        ClusteredPoint(
          id,
          osm_id,
          username,
          name,
          parentId,
          parentName,
          point,
          boundingJSON,
          blurb.getOrElse(""),
          modified,
          difficulty,
          -1,
          Actions.ITEM_TYPE_CHALLENGE,
          None,
          None,
          None,
          None,
          pointReview,
          -1,
          None,
          None
        )
    }
  }

  // The anorm row parser for the Project to map database records directly to Project objects
  def parser(grantFunc: (Long) => List[Grant]): RowParser[Project] = {
    get[Long]("projects.id") ~
      get[Long]("projects.owner_id") ~
      get[String]("projects.name") ~
      get[DateTime]("projects.created") ~
      get[DateTime]("projects.modified") ~
      get[Option[String]]("projects.description") ~
      get[Boolean]("projects.enabled") ~
      get[Option[String]]("projects.display_name") ~
      get[Boolean]("projects.deleted") ~
      get[Boolean]("projects.is_virtual") ~
      get[Boolean]("projects.featured") ~
      get[Boolean]("projects.is_archived") ~
      get[Boolean]("projects.require_confirmation") map {
      case id ~ ownerId ~ name ~ created ~ modified ~ description ~ enabled ~ displayName ~ deleted ~ isVirtual ~ featured ~ isArchived ~ requireConfirmation =>
        new Project(
          id,
          ownerId,
          name,
          created,
          modified,
          description,
          grantFunc.apply(id),
          enabled,
          displayName,
          deleted,
          Some(isVirtual),
          featured,
          isArchived,
          requireConfirmation
        )
    }
  }
}

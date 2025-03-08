/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection

import anorm.SqlParser.get
import anorm.{RowParser, ~}
import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model._
import org.maproulette.framework.psql.Query
import play.api.db.Database

/**
  * The challenge repository handles all the querying with the databases related to
  * challenge list objects
  *
  * @author krotstan
  */
@Singleton
class ChallengeListingRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = Challenge.TABLE

  /**
    * Query function that allows a user to build their own query against the Challenge table
    *
    * @param query The query to execute
    * @param c An implicit connection
    * @return A list of returned Challenges
    */
  def query(query: Query)(implicit c: Option[Connection] = None): List[ChallengeListing] = {
    withMRConnection { implicit c =>
      query
        .build(
          s"""SELECT ${ChallengeListingRepository.standardColumns} FROM challenges
              |INNER JOIN projects ON projects.id = challenges.parent_id
              |INNER JOIN tasks ON tasks.parent_id = challenges.id
              |LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id
              |LEFT OUTER JOIN virtual_project_challenges ON challenges.id = virtual_project_challenges.challenge_id
            """.stripMargin
        )
        .as(ChallengeListingRepository.parser.*)
    }
  }
}

object ChallengeListingRepository {
  val standardColumns: String =
    """challenges.id, 
      |challenges.parent_id, 
      |challenges.name, 
      |challenges.enabled, 
      |array_remove(array_agg(virtual_project_challenges.project_id), NULL) AS virtual_parent_ids,
      |challenges.status,
      |challenges.is_archived,
      |projects.display_name AS parent_name""".stripMargin

  /**
    * The row parser for Anorm to enable the object to be read from the retrieved row directly
    * to the ChallengeListing object.
    */
  val parser: RowParser[ChallengeListing] = {
    get[Long]("challenges.id") ~
      get[Long]("challenges.parent_id") ~
      get[String]("challenges.name") ~
      get[Boolean]("challenges.enabled") ~
      get[Option[Array[Long]]]("virtual_parent_ids") ~
      get[Option[Int]]("challenges.status") ~
      get[Boolean]("challenges.is_archived") ~
      get[Option[String]]("parent_name") map {
      case id ~ parent ~ name ~ enabled ~ virtualParents ~ status ~ isArchived ~ parentName =>
        ChallengeListing(id, parent, name, enabled, virtualParents, status, isArchived, parentName)
    }
  }
}

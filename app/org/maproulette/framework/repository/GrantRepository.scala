/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection

import anorm._
import anorm.SqlParser._
import javax.inject.{Inject, Singleton}
import org.maproulette.exception.InvalidException
import org.maproulette.framework.model.{Grant, Grantee, GrantTarget}
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.BaseParameter
import org.maproulette.data._
import play.api.db.Database

/**
  * @author nrotstan
  */
@Singleton
class GrantRepository @Inject() (val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = Grant.TABLE

  /**
    * Retrieves a single Grant matching the given id
    *
    * @param id The id for the grant
    * @param c An implicit connection, defaults to None
    * @return The grant matching the id, None if not found
    */
  def retrieve(id: Long)(implicit c: Option[Connection] = None): Option[Grant] = {
    this.withMRTransaction { implicit c =>
      Query
        .simple(List(BaseParameter(Grant.FIELD_ID, id)))
        .build("SELECT * FROM grants")
        .as(GrantRepository.parser.*)
        .headOption
    }
  }

  /**
    * Finds a list of grants matching the criteria given by the psqlQuery
    *
    * @param query The psqlQuery including filter, paging and ordering
    * @param c An implicit connection, defaults to None
    * @return A list of grants matching the psqlQuery criteria
    */
  def query(query: Query)(implicit c: Option[Connection] = None): List[Grant] = {
    this.withMRTransaction { implicit c =>
      query.build("SELECT * FROM grants").as(GrantRepository.parser.*)
    }
  }

  /**
    * Inserts a grant into the database
    *
    * @param grant The grant to insert into the database. If id is set on the object it will be ignored.
    * @param c An implicit connection, defaults to None
    * @return A list of grants matching the psqlQuery criteria
    */
  def create(grant: Grant)(implicit c: Option[Connection] = None): Option[Grant] = {
    this.withMRTransaction { implicit c =>
      SQL(
        """INSERT INTO grants (name, grantee_id, grantee_type, role, object_id, object_type)
          VALUES ({name}, {granteeId}, {granteeType}, {role}, {objectId}, {objectType}) RETURNING *"""
      ).on(
          Symbol("name")        -> grant.name,
          Symbol("granteeId")   -> grant.grantee.granteeId,
          Symbol("granteeType") -> grant.grantee.granteeType.typeId,
          Symbol("role")        -> grant.role,
          Symbol("objectId")    -> grant.target.objectId,
          Symbol("objectType")  -> grant.target.objectType.typeId
        )
        .as(GrantRepository.parser.*)
        .headOption
    }
  }

  /**
    * Throws an exception since grants cannot be updated
    *
    * @param grant The grant to be updated
    * @param c An implicit connection, that defaults to None
    */
  def update(grant: Grant)(implicit c: Option[Connection] = None): Grant = {
    throw new InvalidException("Grants cannot be updated")
  }

  /**
    * Deletes a grant from the database
    *
    * @param id The id for the grant
    * @param c An implicit connection, that defaults to None
    * @return true if successfully deleted
    */
  def delete(id: Long)(implicit c: Option[Connection] = None): Boolean = {
    this.withMRTransaction { implicit c =>
      Query
        .simple(List(BaseParameter(Grant.FIELD_ID, id)))
        .build("DELETE FROM grants")
        .execute()
    }
  }

  /**
    * Deletes grants matching the criteria given by the psqlQuery
    *
    * @param query The psqlQuery including filters
    * @param c An implicit connection, defaults to None
    */
  def deleteGrants(query: Query)(implicit c: Option[Connection] = None): Boolean = {
    this.withMRTransaction { implicit c =>
      query.build("DELETE FROM grants").execute()
    }
  }
}

object GrantRepository {
  // The anorm row parser to convert grant records from the database to grant objects
  def parser: RowParser[Grant] = {
    get[Long]("grants.id") ~
      get[String]("grants.name") ~
      get[Long]("grants.grantee_id") ~
      get[Int]("grants.grantee_type") ~
      get[Int]("grants.role") ~
      get[Long]("grants.object_id") ~
      get[Int]("grants.object_type") map {
      case id ~ name ~ granteeId ~ granteeType ~ role ~ objectId ~ objectType =>
        new Grant(
          id,
          name,
          new Grantee(Actions.getItemType(granteeType).get, granteeId),
          role,
          new GrantTarget(Actions.getItemType(objectType).get, objectId)
        )
    }
  }
}

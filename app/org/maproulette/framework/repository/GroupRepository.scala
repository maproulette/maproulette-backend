/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection

import anorm._
import javax.inject.Inject
import org.maproulette.framework.model.Group
import org.maproulette.framework.repository.{GroupMemberRepository}
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.{BaseParameter, Operator, FilterGroup}
import play.api.db.Database

/**
  * Repository to handle all the database queries for the Group object
  *
  * @author nrotstan
  */
class GroupRepository @Inject() (
    override val db: Database,
    groupMemberRepository: GroupMemberRepository
) extends RepositoryMixin {
  implicit val baseTable: String = Group.TABLE

  /**
    * Finds 0 or more groups that match the filter criteria
    *
    * @param query The psql query object containing all the filtering, paging and ordering information
    * @param c An implicit connection, that defaults to None
    * @return The list of groups that match the filter criteria
    */
  def query(query: Query)(implicit c: Option[Connection] = None): List[Group] = {
    withMRConnection { implicit c =>
      query.build(s"SELECT * FROM groups").as(GroupRepository.parser.*)
    }
  }

  /**
    * For a given id returns the group
    *
    * @param id The id of the group you are looking for
    * @param c An implicit connection, defaults to none and one will be created automatically
    * @return None if not found, otherwise the Group
    */
  def retrieve(id: Long)(implicit c: Option[Connection] = None): Option[Group] = {
    this.withMRTransaction { implicit c =>
      Query
        .simple(List(BaseParameter(Group.FIELD_ID, id)))
        .build("SELECT * FROM groups")
        .as(GroupRepository.parser.*)
        .headOption
    }
  }

  /**
    * Retrieve all groups matching the ids
    */
  def list(ids: List[Long], query: Query = Query.empty): List[Group] = {
    if (ids.isEmpty) {
      return List()
    }

    this
      .query(
        query.addFilterGroup(
          FilterGroup(
            List(BaseParameter(Group.FIELD_ID, ids, Operator.IN))
          )
        )
      )
  }

  /**
    * Insert a group into the database
    *
    * @param group The group to insert into the database. The group will fail to be inserted
    *             if a group with the same name already exists. If the id field is set on the
    *             provided group it will be ignored
    * @param c An implicit connection, that defaults to None
    * @return The group that was inserted now with the generated id or None
    */
  def create(group: Group)(implicit c: Option[Connection] = None): Option[Group] = {
    this.withMRTransaction { implicit c =>
      SQL(
        """
        |INSERT INTO groups (name, description, avatar_url, group_type)
        |VALUES ({name}, {description}, {avatarURL}, {groupType})
        |RETURNING *
        """.stripMargin
      ).on(
          Symbol("name")        -> group.name,
          Symbol("description") -> group.description,
          Symbol("avatarURL")   -> group.avatarURL,
          Symbol("groupType")   -> group.groupType
        )
        .as(GroupRepository.parser.*)
        .headOption
    }
  }

  /**
    * Updates an existing Group
    *
    * @param group The properties of the Group to update
    * @param c    Implicit provided optional connection
    * @return The updated group
    */
  def update(group: Group)(
      implicit c: Option[Connection] = None
  ): Option[Group] = {
    withMRTransaction { implicit c =>
      SQL(
        """
        |UPDATE groups
        |SET name = {name}, description = {description}, avatar_url={avatarURL}
        |WHERE id = {id}
        |RETURNING *
        """.stripMargin
      ).on(
          Symbol("id")          -> group.id,
          Symbol("name")        -> group.name,
          Symbol("description") -> group.description,
          Symbol("avatarURL")   -> group.avatarURL
        )
        .as(GroupRepository.parser.*)
        .headOption
    }
  }

  /**
    * Deletes a Group from the database
    *
    * @param group The group to delete
    * @param c    Implicit provided optional connection
    */
  def delete(group: Group)(
      implicit c: Option[Connection] = None
  ): Boolean = {
    withMRConnection { implicit c =>
      Query
        .simple(List(BaseParameter(Group.FIELD_ID, group.id)))
        .build("DELETE FROM groups")
        .execute()
    }
  }
}

object GroupRepository {
  // The anorm row parser mapping database records to Group objects
  val parser: RowParser[Group] = Macro.parser[Group](
    "groups.id",
    "groups.name",
    "groups.description",
    "groups.avatar_url",
    "groups.group_type",
    "groups.created",
    "groups.modified"
  )
}

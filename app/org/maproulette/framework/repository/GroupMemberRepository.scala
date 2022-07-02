/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection

import anorm.Macro.ColumnNaming
import anorm._
import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model.{Group, GroupMember, MemberObject}
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter._
import play.api.db.Database

/**
  * @author nrotstan
  */
@Singleton
class GroupMemberRepository @Inject() (
    override val db: Database
) extends RepositoryMixin {
  implicit val baseTable: String = GroupMember.TABLE

  /**
    * Finds 0 or more group members that match the filter criteria
    *
    * @param query The psql query object containing all the filtering, paging and ordering information
    * @param c An implicit connection, that defaults to None
    * @return The list of group members that match the filter criteria
    */
  def query(query: Query)(implicit c: Option[Connection] = None): List[GroupMember] = {
    withMRConnection { implicit c =>
      query.build(s"SELECT * FROM group_members").as(GroupMemberRepository.parser.*)
    }
  }

  /**
    * Gets all the members with matching ids
    *
    * @param ids The ids of desired group members
    * @param query query object containing any additional filtering, paging and ordering information
    * @return A list of members
    */
  def list(ids: List[Long], query: Query = Query.empty): List[GroupMember] = {
    this.query(
      query.addFilterGroup(
        FilterGroup(
          List(BaseParameter(GroupMember.FIELD_ID, ids, Operator.IN))
        )
      )
    )
  }

  /**
    * Gets all the members that belong to a Group
    *
    * @param group The group for which members are desired
    * @param query query object containing any additional filtering, paging and ordering information
    * @return A list of members that belong to the group
    */
  def getGroupMembers(group: Group, query: Query = Query.empty): List[GroupMember] = {
    this.getGroupMembersForGroupIds(List(group.id), query)
  }

  /**
    * Gets all the members that belong to any of the matching Group ids
    *
    * @param id The ids of groups for which members are desired
    * @param query query object containing any additional filtering, paging and ordering information
    * @return A list of members that belong to the group
    */
  def getGroupMembersForGroupIds(ids: List[Long], query: Query = Query.empty): List[GroupMember] = {
    this.query(
      query.addFilterGroup(
        FilterGroup(
          List(BaseParameter(GroupMember.FIELD_GROUP_ID, ids, Operator.IN))
        )
      )
    )
  }

  /**
    * Gets GroupMember instances representing all group memberships for the
    * given member
    *
    * @param member The member for which group memberships are desired
    * @return A list of memberships that belong to the member
    */
  def getMemberships(member: MemberObject): List[GroupMember] = {
    this.query(
      Query.simple(
        List(
          BaseParameter(GroupMember.FIELD_MEMBER_TYPE, member.objectType),
          BaseParameter(GroupMember.FIELD_MEMBER_ID, member.objectId)
        )
      )
    )
  }

  /**
    * Retrieves GroupMember instances representing all group memberships for all
    * the given member ids of the same member type
    *
    * @param memberType The type of the members (must all be the same)
    * @param memberIds The member ids of the members
    * @return A list of memberships belonging to the members
    */
  def getMembershipsForMembers(memberType: Int, memberIds: List[Long]): List[GroupMember] = {
    this.query(
      Query.simple(
        List(
          BaseParameter(GroupMember.FIELD_MEMBER_TYPE, memberType),
          BaseParameter(GroupMember.FIELD_MEMBER_ID, memberIds, Operator.IN)
        )
      )
    )
  }

  /**
    * Gets all the groups to which a member belongs
    *
    * @param member The member for which groups are desired
    * @param query query object containing any additional filtering, paging and ordering information
    * @return A list of groups to which the member belongs
    */
  def getMemberGroups(member: MemberObject, query: Query = Query.empty): List[Group] = {
    withMRTransaction { implicit c =>
      query
        .addFilterGroup(
          FilterGroup(
            List(
              BaseParameter(GroupMember.FIELD_MEMBER_TYPE, member.objectType),
              BaseParameter(GroupMember.FIELD_MEMBER_ID, member.objectId)
            )
          )
        )
        .build(
          """
        |SELECT groups.* FROM groups
        |INNER JOIN group_members ON group_members.group_id = groups.id
        """.stripMargin
        )
        .as(GroupRepository.parser.*)
    }
  }

  /**
    * Add a new member to a group
    *
    * @param group  The group to which to add the member
    * @param member The new member to be added to the group
    * @param c      Implicit provided optional connection
    * @return       The new GroupMember or None
    */
  def addGroupMember(group: Group, member: MemberObject, status: Int = GroupMember.STATUS_MEMBER)(
      implicit c: Option[Connection] = None
  ): Option[GroupMember] = {
    this.withMRTransaction { implicit c =>
      SQL(
        """
        |INSERT INTO group_members (group_id, member_type, member_id, status)
        |VALUES ({groupId}, {memberType}, {memberId}, {status})
        |RETURNING *
        """.stripMargin
      ).on(
          Symbol("groupId")    -> group.id,
          Symbol("memberType") -> member.objectType,
          Symbol("memberId")   -> member.objectId,
          Symbol("status")     -> status
        )
        .as(GroupMemberRepository.parser.*)
        .headOption
    }
  }

  /**
    * Update a group member. Note that status is the only updateable field and
    * others will be ignored
    *
    * @param groupMember The updated GroupMember data
    * @param c           Implicit provided optional connection
    * @return            The updated GroupMember
    */
  def updateGroupMember(groupMember: GroupMember)(
      implicit c: Option[Connection] = None
  ): Option[GroupMember] = {
    withMRTransaction { implicit c =>
      SQL(
        """
        |UPDATE group_members
        |SET status = {status}
        |WHERE id = {id}
        |RETURNING *
        """.stripMargin
      ).on(
          Symbol("id")     -> groupMember.id,
          Symbol("status") -> groupMember.status
        )
        .as(GroupMemberRepository.parser.*)
        .headOption
    }
  }

  /**
    * Retrieve a single GroupMember representation for the given group and member,
    * or None if not found
    *
    * @param group  The group to which the member belongs
    * @param member The member for which a GroupMember is desired
    */
  def getGroupMember(group: Group, member: MemberObject)(
      implicit c: Option[Connection] = None
  ): Option[GroupMember] = {
    this
      .query(
        Query.simple(
          List(
            BaseParameter(GroupMember.FIELD_GROUP_ID, group.id),
            BaseParameter(GroupMember.FIELD_MEMBER_TYPE, member.objectType),
            BaseParameter(GroupMember.FIELD_MEMBER_ID, member.objectId)
          )
        )
      )
      .headOption
  }

  /**
    * Delete a member from a group
    *
    * @param group  The group to which the member belongs
    * @param member The member to be removed
    */
  def deleteGroupMember(group: Group, member: MemberObject)(
      implicit c: Option[Connection] = None
  ): Boolean = {
    this.delete(
      Query.simple(
        List(
          BaseParameter(GroupMember.FIELD_GROUP_ID, group.id),
          BaseParameter(GroupMember.FIELD_MEMBER_TYPE, member.objectType),
          BaseParameter(GroupMember.FIELD_MEMBER_ID, member.objectId)
        )
      )
    )
  }

  /**
    * Delete group members
    *
    * @param query The psql query object containing all the filtering, paging and ordering information
    * @param c An implicit connection, that defaults to None
    */
  def delete(query: Query)(implicit c: Option[Connection] = None): Boolean = {
    withMRConnection { implicit c =>
      query.build(s"DELETE FROM group_members").execute()
    }
  }

  /**
    * Delete a member from multiple groups that all share the same group type
    *
    * @param member    The member to delete
    * @param groupType The type of group from which member is to be deleted
    */
  def deleteGroupMemberAcrossGroupType(member: MemberObject, groupType: Int): Boolean = {
    withMRConnection { implicit c =>
      Query
        .simple(
          List(
            BaseParameter(GroupMember.FIELD_GROUP_ID, "groups.id", useValueDirectly = true),
            BaseParameter(Group.FIELD_GROUP_TYPE, groupType, table = Some(Group.TABLE)),
            BaseParameter(GroupMember.FIELD_MEMBER_TYPE, member.objectType),
            BaseParameter(GroupMember.FIELD_MEMBER_ID, member.objectId)
          )
        )
        .build("DELETE FROM group_members USING groups")
        .execute()
    }
  }
}

object GroupMemberRepository {
  // The anorm row parser to convert group_member records from the database to GroupMember objects
  val parser: RowParser[GroupMember] = Macro.namedParser[GroupMember](ColumnNaming.SnakeCase)
}

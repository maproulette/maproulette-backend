/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service


import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model.{Group, GroupMember, MemberObject}
import org.maproulette.data.{ItemType}
import org.maproulette.framework.psql._
import org.maproulette.framework.psql.filter._
import org.maproulette.framework.psql.Query
import org.maproulette.framework.repository.{GroupRepository, GroupMemberRepository}
import org.maproulette.permissions.Permission

/**
  * Service for handling Group requests
  *
  * @author nrotstan
  */
@Singleton
class GroupService @Inject() (
    repository: GroupRepository,
    groupMemberRepository: GroupMemberRepository,
    grantService: GrantService,
    permission: Permission
) extends ServiceMixin[Group] {

  /**
    * Finds 0 or more groups that match the filter criteria
    *
    * @param query The psql query object containing all the filtering, paging and ordering information
    * @return The list of groups that match the filter criteria
    */
  override def query(query: Query): List[Group] = this.repository.query(query)

  /**
    * Retrieves a single group based on an id
    *
    * @param id The id of the group
    */
  def retrieve(id: Long): Option[Group] = this.repository.retrieve(id)

  /**
    * Retrieves all groups matching the ids and optional query
    */
  def list(ids: List[Long], query: Query = Query.empty): List[Group] =
    this.repository.list(ids, query)

  /**
    * Retrieves a single group with an exact name
    *
    * @param name The name of the group
    * @param query query object containing additional filtering, paging and ordering information
    * @return The matching group or None if no exact match
    */
  def retrieveByName(name: String, query: Query = Query.empty): Option[Group] = {
    this
      .query(
        query.addFilterGroup(
          FilterGroup(
            List(BaseParameter(Group.FIELD_NAME, name))
          )
        )
      )
      .headOption
  }

  /**
    * Search for groups matching the given search criteria
    *
    * @param nameFragment Group name fragment to match
    * @param query query object containing additional filtering, paging and ordering information
    */
  def search(nameFragment: String, query: Query = Query.empty): List[Group] = {
    this.query(
      query.addFilterGroup(
        FilterGroup(
          List(
            BaseParameter(Group.FIELD_NAME, SQLUtils.search(nameFragment), Operator.ILIKE)
          )
        )
      )
    )
  }

  /**
    * Create a new Group
    *
    * @param group The group to create
    * @return The newly created group
    */
  def create(group: Group): Option[Group] = this.repository.create(group)

  /**
    * Retrieve group members with matching ids
    *
    * @param ids The ids of desired group members
    */
  def listGroupMembers(ids: List[Long]): List[GroupMember] =
    this.groupMemberRepository.list(ids)

  /**
    * Retrieve members of the given group
    *
    * @param group The group for which members are desired
    * @param query query object containing any additional filtering, paging and ordering information
    */
  def groupMembers(group: Group, query: Query = Query.empty): List[GroupMember] =
    this.groupMemberRepository.getGroupMembers(group, query)

  /**
    * Convenience method for retrieving group members matching the given type
    *
    * @param groupId The group for which members are desired
    * @param type:   The type of desired members
    */
  def membersOfType(group: Group, memberType: ItemType): List[GroupMember] =
    this
      .groupMembers(
        group,
        Query.simple(
          List(BaseParameter(GroupMember.FIELD_MEMBER_TYPE, memberType.typeId))
        )
      )

  /**
    * Retrieve members of the groups matching the given ids
    *
    * @param group The ids of groups for which members are desired
    */
  def groupMembersForGroupIds(ids: List[Long]): List[GroupMember] =
    this.groupMemberRepository.getGroupMembersForGroupIds(ids)

  /**
    * Add a member to a group
    *
    * @param group  The group on which to add the new member
    * @param member The member to add to the group
    * @return       The new GroupMember or None on failure
    */
  def addGroupMember(
      group: Group,
      member: MemberObject,
      status: Int = GroupMember.STATUS_MEMBER
  ): Option[GroupMember] =
    this.groupMemberRepository.addGroupMember(group, member, status)

  /**
    * Retrieve a GroupMember representing the member object
    *
    * @param group      The desired group
    * @param member     The member to retrieve
    */
  def getGroupMember(group: Group, member: MemberObject): Option[GroupMember] =
    this.groupMemberRepository.getGroupMember(group, member)

  /**
    * Update the status of a group member
    *
    * @param group  The group to which the member belongs
    * @param member The member to update
    * @param status The new status of the group member
    */
  def updateGroupMemberStatus(
      group: Group,
      member: MemberObject,
      status: Int
  ): Option[GroupMember] = {
    this.getGroupMember(group, member) match {
      case Some(groupMember) =>
        this.groupMemberRepository.updateGroupMember(groupMember.copy(status = status))
      case None => None
    }
  }

  /**
    * Remove a member from a group
    *
    * @param group      The group from which to remove the member
    * @param member     The member to remove
    */
  def removeGroupMember(group: Group, member: MemberObject): Boolean =
    this.groupMemberRepository.deleteGroupMember(group, member)

  /**
    * Remove all members from a group
    *
    * @param group The group from which all members are to be removed
    */
  def clearGroupMembers(group: Group): Boolean =
    this.groupMemberRepository.delete(
      Query.simple(
        List(BaseParameter(GroupMember.FIELD_GROUP_ID, group.id))
      )
    )

  /**
    * Remove a member from multiple groups that all share the same group type
    *
    * @param member    The member to delete
    * @param groupType The type of group from which member is to be deleted
    */
  def removeGroupMemberAcrossGroupType(member: MemberObject, groupType: Int): Boolean =
    this.groupMemberRepository.deleteGroupMemberAcrossGroupType(member, groupType)

  /**
    * Retrieve a list of all the Groups of which the given member object has
    * membership
    *
    * @param member The member object for which groups are desired
    */
  def memberGroups(member: MemberObject, query: Query = Query.empty): List[Group] =
    this.groupMemberRepository.getMemberGroups(member, query)

  /**
    * Retrieve GroupMember objects representing the given member's membership in
    * each of the groups in which they are members
    */
  def memberships(member: MemberObject): List[GroupMember] =
    this.groupMemberRepository.getMemberships(member)

  /**
    * Retrieve GroupMember instances representing all group memberships for all
    * the given member ids of the same member type
    */
  def getMembershipsForMembers(memberType: Int, memberIds: List[Long]): List[GroupMember] =
    this.groupMemberRepository.getMembershipsForMembers(memberType, memberIds)

  /**
    * Determines if a member has membership in a group
    *
    * @param group  The group
    * @param member The candidate member to test for membership
    */
  def isGroupMember(group: Group, member: MemberObject): Boolean =
    this.groupMembers(group).exists { m =>
      m.asMemberObject() == member
    }

  /**
    * Update a group
    *
    * @param group The latest group data
    */
  def updateGroup(group: Group): Option[Group] = this.repository.update(group)

  /**
    * Delete a group from the database
    *
    * @param group The group to delete
    * @return Boolean if delete was successful
    */
  def deleteGroup(group: Group): Boolean = this.repository.delete(group)
}

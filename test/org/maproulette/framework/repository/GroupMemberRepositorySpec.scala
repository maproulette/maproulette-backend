/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework

import org.maproulette.framework.model.{Group, GroupMember, MemberObject, User}
import org.maproulette.data.{UserType, GroupType}
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.BaseParameter
import org.maproulette.framework.repository.GroupMemberRepository
import org.maproulette.framework.service.GroupService
import org.maproulette.framework.util.{FrameworkHelper, GroupTag}
import play.api.Application

/**
  * @author nrotstan
  */
class GroupMemberRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  val repository: GroupMemberRepository =
    this.application.injector.instanceOf(classOf[GroupMemberRepository])
  val groupService: GroupService = this.serviceManager.group

  var defaultGroup: Group = null
  var randomUser: User    = null

  "GroupMemberRepository" should {
    "perform a basic query" taggedAs (GroupTag) in {
      val groupMembers =
        this.repository
          .query(
            Query.simple(List(BaseParameter(GroupMember.FIELD_GROUP_ID, this.defaultGroup.id)))
          )

      groupMembers.size mustEqual 1
      groupMembers.head.groupId mustEqual this.defaultGroup.id
      groupMembers.head.memberType mustEqual UserType().typeId
      groupMembers.head.memberId mustEqual this.defaultUser.id
    }

    "add a group member" taggedAs GroupTag in {
      val anotherGroup =
        this.groupService
          .create(this.getTestGroup("GroupMemberRepositorySpec_AddGroupMemberTest Group"))
          .get

      val createdGroupMember = this.repository
        .addGroupMember(anotherGroup, MemberObject.user(this.randomUser.id))

      createdGroupMember.get.groupId mustEqual anotherGroup.id
      createdGroupMember.get.memberType mustEqual UserType().typeId
      createdGroupMember.get.memberId mustEqual this.randomUser.id
      createdGroupMember.get.status mustEqual GroupMember.STATUS_MEMBER

      val retrievedGroupMember = this.repository
        .query(
          Query.simple(
            List(
              BaseParameter(GroupMember.FIELD_GROUP_ID, anotherGroup.id),
              BaseParameter(GroupMember.FIELD_MEMBER_TYPE, UserType().typeId),
              BaseParameter(GroupMember.FIELD_MEMBER_ID, this.randomUser.id)
            )
          )
        )
        .head

      retrievedGroupMember.groupId mustEqual anotherGroup.id
      retrievedGroupMember.memberType mustEqual UserType().typeId
      retrievedGroupMember.memberId mustEqual this.randomUser.id
    }

    "update only status on a group member" taggedAs GroupTag in {
      val anotherGroup =
        this.groupService
          .create(this.getTestGroup("GroupMemberRepositorySpec_UpdateGroupMemberTest Group"))
          .get

      val createdGroupMember = this.repository
        .addGroupMember(anotherGroup, MemberObject.user(this.randomUser.id), 1)

      createdGroupMember.get.status mustEqual 1
      val updatedGroupMember = this.repository
        .updateGroupMember(createdGroupMember.get.copy(status = 2, memberType = GroupType().typeId))

      // Only status should have changed
      updatedGroupMember.get.status mustEqual 2
      updatedGroupMember.get.memberType mustEqual UserType().typeId
      updatedGroupMember.get.memberId mustEqual this.randomUser.id
      updatedGroupMember.get.groupId mustEqual anotherGroup.id
    }

    "retrieve members on a group" taggedAs GroupTag in {
      val createdGroupMember = this.repository
        .addGroupMember(this.defaultGroup, MemberObject.user(this.randomUser.id))

      val members = this.repository.getGroupMembers(this.defaultGroup)
      members.size mustEqual 2
      Seq(this.defaultUser.id, this.randomUser.id) must contain(members.head.memberId)
      Seq(this.defaultUser.id, this.randomUser.id) must contain(members(1).memberId)
    }

    "retrieve all group memberships for a member" taggedAs GroupTag in {
      val anotherUser = this.serviceManager.user.create(
        this.getTestUser(99913597, "RetrieveMembershipsOUser"),
        User.superUser
      )
      val anotherGroup =
        this.serviceManager.group
          .create(this.getTestGroup("GroupMemberRepositorySpec_RetrieveMembershipsTest Group"))
          .get

      this.repository.addGroupMember(this.defaultGroup, MemberObject.user(anotherUser.id))
      this.repository.addGroupMember(anotherGroup, MemberObject.user(anotherUser.id))

      val memberships = this.repository.getMemberships(MemberObject.user(anotherUser.id))
      memberships.size mustEqual 2

      memberships.head.memberType mustEqual UserType().typeId
      memberships.head.memberId mustEqual anotherUser.id
      Seq(this.defaultGroup.id, anotherGroup.id) must contain(memberships.head.groupId)

      memberships(1).memberType mustEqual UserType().typeId
      memberships(1).memberId mustEqual anotherUser.id
      Seq(this.defaultGroup.id, anotherGroup.id) must contain(memberships(1).groupId)
    }

    "retrieve groups a member belongs to" taggedAs GroupTag in {
      val anotherUser = this.serviceManager.user.create(
        this.getTestUser(99912345, "RetrieveMemberGroupsOUser"),
        User.superUser
      )
      val anotherGroup =
        this.serviceManager.group
          .create(this.getTestGroup("GroupMemberRepositorySpec_RetrieveMemberGroupsTest Group"))
          .get

      this.repository.addGroupMember(this.defaultGroup, MemberObject.user(anotherUser.id))
      this.repository.addGroupMember(anotherGroup, MemberObject.user(anotherUser.id))

      val userGroups = this.repository.getMemberGroups(MemberObject.user(anotherUser.id))
      userGroups.size mustEqual 2
      Seq(this.defaultGroup.id, anotherGroup.id) must contain(userGroups.head.id)
      Seq(this.defaultGroup.id, anotherGroup.id) must contain(userGroups(1).id)
    }

    "delete a group member" taggedAs GroupTag in {
      val anotherUser = this.serviceManager.user.create(
        this.getTestUser(99924680, "DeleteGroupMemberOUser"),
        User.superUser
      )
      val anotherGroup =
        this.serviceManager.group
          .create(this.getTestGroup("GroupMemberRepositorySpec_DeleteGroupMemberTest Group"))
          .get
      val newGroupMember = this.repository
        .addGroupMember(anotherGroup, MemberObject.user(anotherUser.id))
        .get

      this.repository.deleteGroupMember(anotherGroup, MemberObject.user(anotherUser.id))

      val retrievedGroupMember = this.repository
        .getGroupMember(anotherGroup, MemberObject.user(anotherUser.id))
      retrievedGroupMember mustEqual None
    }

    "delete group members with query" taggedAs GroupTag in {
      val anotherUser = this.serviceManager.user.create(
        this.getTestUser(99935791, "DeleteGroupMemberQueryOUser"),
        User.superUser
      )
      val anotherGroup =
        this.serviceManager.group
          .create(this.getTestGroup("GroupMemberRepositorySpec_DeleteGroupMemberQueryTest Group"))
          .get
      val newGroupMember = this.repository
        .addGroupMember(anotherGroup, MemberObject.user(anotherUser.id))
        .get

      this.repository.delete(
        Query.simple(
          List(
            BaseParameter(GroupMember.FIELD_GROUP_ID, newGroupMember.groupId),
            BaseParameter(GroupMember.FIELD_MEMBER_ID, newGroupMember.memberId)
          )
        )
      )

      val retrievedGroupMember = this.repository
        .getGroupMember(anotherGroup, MemberObject.user(anotherUser.id))
      retrievedGroupMember mustEqual None
    }

    "delete a group member across groups of the same type" taggedAs GroupTag in {
      val anotherUser = this.serviceManager.user.create(
        this.getTestUser(99913518, "DeleteAcrossGroupTypeOUser"),
        User.superUser
      )

      val firstGroup =
        this.serviceManager.group
          .create(
            this.getTestGroup(
              "GroupMemberRepositorySpec_DeleteAcrossGroupType Group1",
              groupType = 123456
            )
          )
          .get

      val secondGroup =
        this.serviceManager.group
          .create(
            this.getTestGroup(
              "GroupMemberRepositorySpec_DeleteAcrossGroupType Group2",
              groupType = 123456
            )
          )
          .get

      this.repository.addGroupMember(this.defaultGroup, MemberObject.user(anotherUser.id))
      this.repository.addGroupMember(firstGroup, MemberObject.user(anotherUser.id))
      this.repository.addGroupMember(secondGroup, MemberObject.user(anotherUser.id))
      this.repository.getMemberships(MemberObject.user(anotherUser.id)).size mustEqual 3

      this.repository.deleteGroupMemberAcrossGroupType(MemberObject.user(anotherUser.id), 123456)

      val memberships = this.repository.getMemberships(MemberObject.user(anotherUser.id))
      memberships.size mustEqual 1
      memberships.head.memberType mustEqual UserType().typeId
      memberships.head.memberId mustEqual anotherUser.id
      this.defaultGroup.id mustEqual memberships.head.groupId
    }
  }

  override implicit val projectTestName: String = "GroupMemberRepositorySpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    randomUser = this.serviceManager.user.create(
      this.getTestUser(12345, "RandomOUser"),
      User.superUser
    )

    defaultGroup = this.serviceManager.group
      .create(this.getTestGroup("GroupMemberRepositorySpec_Test Group"))
      .get

    this.serviceManager.group.addGroupMember(
      defaultGroup,
      MemberObject.user(this.defaultUser.id)
    )
  }

  protected def getTestGroup(name: String, groupType: Int = Group.GROUP_TYPE_STANDARD): Group = {
    Group(
      -1,
      name,
      Some("A test group"),
      Some("http://www.gravatar.com/avatar/?d=identicon"),
      groupType
    )
  }
}

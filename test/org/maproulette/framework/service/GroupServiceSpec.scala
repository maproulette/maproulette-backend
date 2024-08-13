/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import org.maproulette.framework.model.{Group, MemberObject, User}
import org.maproulette.data.{UserType}
import org.maproulette.framework.util.{FrameworkHelper, GroupTag}
import play.api.Application

/**
  * @author nrotstan
  */
class GroupServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val service: GroupService = this.serviceManager.group

  var defaultGroup: Group = null
  var randomUser: User    = null
  var anotherUser: User   = null

  "GroupService" should {
    "create a new group" taggedAs GroupTag in {
      val group = this.service.create(this.getTestGroup("GroupServiceSpec_Group B")).get
      group.name mustEqual "GroupServiceSpec_Group B"
    }

    "add a member to a group" taggedAs GroupTag in {
      this.service.addGroupMember(this.defaultGroup, MemberObject.user(this.defaultUser.id))
      this.service.addGroupMember(this.defaultGroup, MemberObject.user(this.randomUser.id))

      val allMembers = this.service.groupMembers(this.defaultGroup)
      allMembers.size mustEqual 2
      Seq(this.defaultUser.id, this.randomUser.id) must contain(allMembers.head.memberId)
      Seq(this.defaultUser.id, this.randomUser.id) must contain(allMembers(1).memberId)
    }

    "retrieve a group by id" taggedAs GroupTag in {
      val group = this.service.retrieve(this.defaultGroup.id)
      group.get.id mustEqual this.defaultGroup.id
    }

    "retrieve a group by exact name" taggedAs GroupTag in {
      val group = this.service.retrieveByName("GroupServiceSpec_Group A")
      group.get.id mustEqual this.defaultGroup.id
    }

    "perform a basic search for groups" taggedAs GroupTag in {
      val groupsWithA = this.service.search("Group A")
      val groupsWithB = this.service.search("Group B")
      val matchNone   = this.service.search("Nothing")
      val matchAll    = this.service.search("GroupServiceSpec")

      groupsWithA.size mustEqual 1
      groupsWithA.head.id mustEqual this.defaultGroup.id

      groupsWithB.size mustEqual 1
      val groupB = groupsWithB.head
      groupB.name mustEqual "GroupServiceSpec_Group B"

      matchNone.size mustEqual 0

      matchAll.size mustEqual 2
      Seq(this.defaultGroup.id, groupB.id) must contain(matchAll.head.id)
      Seq(this.defaultGroup.id, groupB.id) must contain(matchAll(1).id)
    }

    "retrieve all members of a group" taggedAs GroupTag in {
      val anotherGroup =
        this.service.create(this.getTestGroup("GroupServiceSpec_RetrieveAllMembersTest Group")).get

      this.service.addGroupMember(anotherGroup, MemberObject.user(this.randomUser.id))
      this.service.addGroupMember(anotherGroup, MemberObject.user(this.anotherUser.id))

      val allMembers = this.service.groupMembers(anotherGroup)
      allMembers.size mustEqual 2
      Seq(this.randomUser.id, this.anotherUser.id) must contain(allMembers.head.memberId)
      Seq(this.randomUser.id, this.anotherUser.id) must contain(allMembers(1).memberId)
    }

    "determine if a user is a member of a group" taggedAs GroupTag in {
      val anotherGroup =
        this.service.create(this.getTestGroup("GroupServiceSpec_IsGroupMemberTest Group")).get

      this.service.addGroupMember(anotherGroup, MemberObject.user(this.randomUser.id))
      this.service.addGroupMember(anotherGroup, MemberObject.user(this.anotherUser.id))

      this.service.isGroupMember(anotherGroup, MemberObject.user(this.randomUser.id)) mustEqual true
      this.service
        .isGroupMember(anotherGroup, MemberObject.user(this.anotherUser.id)) mustEqual true
      this.service
        .isGroupMember(anotherGroup, MemberObject.user(this.defaultUser.id)) mustEqual false
    }

    "retrieve an individual member of a group" taggedAs GroupTag in {
      val anotherGroup =
        this.service.create(this.getTestGroup("GroupServiceSpec_RetrieveGroupMemberTest Group")).get

      this.service.addGroupMember(anotherGroup, MemberObject.user(this.randomUser.id))
      this.service.addGroupMember(anotherGroup, MemberObject.user(this.anotherUser.id))

      val member =
        this.service.getGroupMember(anotherGroup, MemberObject.user(this.randomUser.id))

      member.get.groupId mustEqual anotherGroup.id
      member.get.memberType mustEqual UserType().typeId
      member.get.memberId mustEqual randomUser.id
    }

    "remove a member from a group" taggedAs GroupTag in {
      val anotherGroup =
        this.service.create(this.getTestGroup("GroupServiceSpec_RemoveGroupMemberTest Group")).get

      this.service.addGroupMember(anotherGroup, MemberObject.user(this.randomUser.id))
      this.service.addGroupMember(anotherGroup, MemberObject.user(this.anotherUser.id))

      this.service.removeGroupMember(anotherGroup, MemberObject.user(this.randomUser.id))
      val remainingMembers = this.service.groupMembers(anotherGroup)

      remainingMembers.size mustEqual 1
      remainingMembers.head.memberId mustEqual this.anotherUser.id
    }

    "retrieve all groups to which a member belongs" taggedAs GroupTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(24680, "RetrieveAllMemberGroupsOUser"),
        User.superUser
      )
      val anotherGroup =
        this.service
          .create(this.getTestGroup("GroupServiceSpec_RetrieveMemberGroupsTest Group"))
          .get

      this.service.addGroupMember(anotherGroup, MemberObject.user(freshUser.id))
      this.service.addGroupMember(this.defaultGroup, MemberObject.user(freshUser.id))

      val allGroups = this.service.memberGroups(MemberObject.user(freshUser.id))
      allGroups.size mustEqual 2
      Seq(this.defaultGroup.id, anotherGroup.id) must contain(allGroups.head.id)
      Seq(this.defaultGroup.id, anotherGroup.id) must contain(allGroups(1).id)
    }
  }

  override implicit val projectTestName: String = "GroupServiceSpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    randomUser = this.serviceManager.user.create(
      this.getTestUser(12345, "RandomOUser"),
      User.superUser
    )
    anotherUser = this.serviceManager.user.create(
      this.getTestUser(98765, "AnotherUser"),
      User.superUser
    )
    defaultGroup =
      this.serviceManager.group.create(this.getTestGroup("GroupServiceSpec_Group A")).get
  }

  protected def getTestGroup(name: String): Group = {
    Group(
      -1,
      name,
      Some("A test group"),
      Some("http://www.gravatar.com/avatar/?d=identicon")
    )
  }
}

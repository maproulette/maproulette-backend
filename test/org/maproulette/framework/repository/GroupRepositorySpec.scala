/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework

import org.maproulette.framework.model.{Group, User}
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.BaseParameter
import org.maproulette.framework.repository.{GroupRepository}
import org.maproulette.framework.service.GroupService
import org.maproulette.framework.util.{FrameworkHelper, GroupTag}
import play.api.Application

/**
  * @author nrotstan
  */
class GroupRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  val repository: GroupRepository = this.application.injector.instanceOf(classOf[GroupRepository])
  val service: GroupService       = this.serviceManager.group

  var defaultGroup: Group = null
  var randomUser: User    = null

  "GroupRepository" should {
    "perform a basic query" taggedAs (GroupTag) in {
      val groups =
        this.repository
          .query(Query.simple(List(BaseParameter(Group.FIELD_ID, this.defaultGroup.id))))
      groups.size mustEqual 1
      groups.head.id mustEqual this.defaultGroup.id
    }

    "create a group" taggedAs GroupTag in {
      val createdGroup   = this.repository.create(Group(-1, "RandomCreateGroup")).get
      val retrievedGroup = this.repository.retrieve(createdGroup.id)
      retrievedGroup.get mustEqual createdGroup
    }

    "update a group" taggedAs GroupTag in {
      val randomGroup = this.repository.create(Group(-1, "RandomGroup")).get
      this.repository.update(
        Group(randomGroup.id, "UpdatedName", Some("Updated description"), Some("new_avatar_url"))
      )
      val group = this.service.retrieve(randomGroup.id)
      group.get.name mustEqual "UpdatedName"
      group.get.description.get mustEqual "Updated description"
      group.get.avatarURL.get mustEqual "new_avatar_url"
    }

    "delete a group" taggedAs GroupTag in {
      val group = this.repository.create(Group(-1, "RandomDeleteGroup")).get
      this.repository.delete(group)
      this.service.retrieve(group.id) mustEqual None
    }
  }

  override implicit val projectTestName: String = "GroupRepositorySpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    randomUser = this.serviceManager.user.create(
      this.getTestUser(12345, "RandomOUser"),
      User.superUser
    )
    defaultGroup =
      this.serviceManager.group.create(this.getTestGroup("GroupRepositorySpec_Test Group")).get
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

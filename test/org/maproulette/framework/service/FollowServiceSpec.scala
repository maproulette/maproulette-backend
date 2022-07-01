/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import org.maproulette.framework.model._
import org.maproulette.framework.util.{FrameworkHelper, UserTag}
import org.maproulette.exception.{InvalidException}
import org.scalatest.matchers.should.Matchers._
import play.api.libs.json.Json
import play.api.Application

/**
  * @author nrotstan
  */
class FollowServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val userService: UserService     = this.serviceManager.user
  val followService: FollowService = this.serviceManager.follow

  "FollowService" should {
    "retrieve a user's Following group, creating it if necessary" taggedAs UserTag in {
      val insertedUser =
        this.userService.create(this.getTestUser(288811111, "FollowingGroupTest"), User.superUser)

      insertedUser.followingGroupId mustEqual None
      val followingGroup = this.followService.getFollowingGroup(insertedUser, User.superUser)
      followingGroup.groupType mustEqual Group.GROUP_TYPE_FOLLOWING
      followingGroup.name mustEqual s"User ${insertedUser.id} Following"

      this.userService
        .retrieve(insertedUser.id)
        .get
        .followingGroupId
        .get mustEqual followingGroup.id

      val groupAgain = this.followService.getFollowingGroup(insertedUser, User.superUser)
      groupAgain.id mustEqual followingGroup.id
    }

    "retrieve a user's Followers group, creating it if necessary" taggedAs UserTag in {
      val insertedUser =
        this.userService.create(this.getTestUser(288811112, "FollowersGroupTest"), User.superUser)

      insertedUser.followersGroupId mustEqual None
      val followersGroup = this.followService.getFollowersGroup(insertedUser, User.superUser)
      followersGroup.groupType mustEqual Group.GROUP_TYPE_FOLLOWERS
      followersGroup.name mustEqual s"User ${insertedUser.id} Followers"

      this.userService
        .retrieve(insertedUser.id)
        .get
        .followersGroupId
        .get mustEqual followersGroup.id

      val groupAgain = this.followService.getFollowersGroup(insertedUser, User.superUser)
      groupAgain.id mustEqual followersGroup.id
    }

    "follow a user" taggedAs UserTag in {
      val firstUser =
        this.userService.create(this.getTestUser(288811113, "FollowUserTest1"), User.superUser)
      val secondUser =
        this.userService.create(this.getTestUser(288811114, "FollowUserTest2"), User.superUser)
      val thirdUser =
        this.userService.create(this.getTestUser(288811115, "FollowUserTest3"), User.superUser)

      this.followService.follow(firstUser, secondUser, User.superUser)
      val followers = this.followService.getUserFollowers(secondUser.id, User.superUser)
      followers.size mustEqual 1
      followers.head.user.id mustEqual firstUser.id

      val followed = this.followService.getUsersFollowedBy(firstUser.id, User.superUser)
      followed.size mustEqual 1
      followed.head.id mustEqual secondUser.id

      this.followService.follow(thirdUser, firstUser, User.superUser)
      this.followService.follow(thirdUser, secondUser, User.superUser)

      this.followService.getUserFollowers(firstUser.id, User.superUser).size mustEqual 1
      this.followService.getUserFollowers(secondUser.id, User.superUser).size mustEqual 2
      this.followService.getUserFollowers(thirdUser.id, User.superUser).size mustEqual 0
    }

    "stop following a user" taggedAs UserTag in {
      val firstUser =
        this.userService.create(this.getTestUser(288811116, "UnfollowUserTest1"), User.superUser)
      val secondUser =
        this.userService.create(this.getTestUser(288811117, "UnfollowUserTest2"), User.superUser)
      val thirdUser =
        this.userService.create(this.getTestUser(288811118, "UnfollowUserTest3"), User.superUser)

      this.followService.follow(firstUser, secondUser, User.superUser)
      this.followService.follow(thirdUser, firstUser, User.superUser)
      this.followService.follow(thirdUser, secondUser, User.superUser)

      this.followService.getUserFollowers(firstUser.id, User.superUser).size mustEqual 1
      this.followService.getUserFollowers(secondUser.id, User.superUser).size mustEqual 2
      this.followService.getUserFollowers(thirdUser.id, User.superUser).size mustEqual 0

      this.followService.stopFollowing(firstUser, secondUser, User.superUser)
      this.followService.getUserFollowers(secondUser.id, User.superUser).size mustEqual 1

      this.followService.stopFollowing(thirdUser, secondUser, User.superUser)
      this.followService.getUserFollowers(secondUser.id, User.superUser).size mustEqual 0
    }

    "not allow following a user who has opted out" taggedAs UserTag in {
      val insertedUser = this.userService
        .update(
          this.userService
            .create(this.getTestUser(288811119, "FollowOptOutUserTest"), User.superUser)
            .id,
          Json.parse(s"""{"settings": {"allowFollowing": false}}"""),
          User.superUser
        )
        .get

      an[InvalidException] should be thrownBy
        this.followService.follow(this.defaultUser, insertedUser, User.superUser)

      val updatedUser = this.userService.update(
        insertedUser.id,
        Json.parse(s"""{"settings": {"allowFollowing": true}}"""),
        User.superUser
      )

      this.followService.follow(this.defaultUser, updatedUser.get, User.superUser)
      this.followService.getUserFollowers(insertedUser.id, User.superUser).size mustEqual 1
    }

    "block and unblock a follower" taggedAs UserTag in {
      val firstUser =
        this.userService.create(this.getTestUser(288811120, "BlockFollowerTest1"), User.superUser)
      val secondUser =
        this.userService.create(this.getTestUser(288811121, "BlockFollowerTest2"), User.superUser)

      this.followService.follow(secondUser, firstUser, User.superUser)
      this.followService
        .getUserFollowers(firstUser.id, User.superUser)
        .head
        .status mustEqual Follower.STATUS_FOLLOWING

      this.followService.blockFollower(secondUser.id, firstUser, User.superUser)
      this.followService
        .getUserFollowers(firstUser.id, User.superUser)
        .head
        .status mustEqual Follower.STATUS_BLOCKED

      this.followService.unblockFollower(secondUser.id, firstUser, User.superUser)
      this.followService
        .getUserFollowers(firstUser.id, User.superUser)
        .head
        .status mustEqual Follower.STATUS_FOLLOWING
    }

    "remove all existing followers when opting out of following" taggedAs UserTag in {
      val firstUser =
        this.userService
          .create(this.getTestUser(288811122, "OptOutWithFollowersTest1"), User.superUser)
      val secondUser =
        this.userService
          .create(this.getTestUser(288811123, "OptOutWithFollowersTest2"), User.superUser)

      this.followService.follow(secondUser, firstUser, User.superUser)
      this.followService.getUserFollowers(firstUser.id, User.superUser).size mustEqual 1
      this.followService.getUsersFollowedBy(secondUser.id, User.superUser).size mustEqual 1

      this.userService.update(
        firstUser.id,
        Json.parse(s"""{"settings": {"allowFollowing": false}}"""),
        User.superUser
      )
      this.followService.getUserFollowers(firstUser.id, User.superUser).isEmpty mustEqual true
      this.followService.getUsersFollowedBy(secondUser.id, User.superUser).isEmpty mustEqual true
    }
  }

  override implicit val projectTestName: String = "FollowServiceSpecProject"
}

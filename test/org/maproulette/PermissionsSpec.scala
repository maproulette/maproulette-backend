/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette

import org.maproulette.data._
import org.maproulette.framework.model.User
import org.maproulette.utils.TestSpec
import org.scalatest.BeforeAndAfterAll

/**
  * @author mcuthbert
  */
class PermissionsSpec extends TestSpec with BeforeAndAfterAll {

  implicit val id: Long = 1L

  override def beforeAll(): Unit = {
    this.setupMocks()
  }

  "Permissions" should {
    // Everybody can read any project
    "Read access on projects" in {
      permission.hasReadAccess(ProjectType(), User.guestUser)
      permission.hasReadAccess(ProjectType(), this.userService.retrieve(1).get)
      permission.hasReadAccess(ProjectType(), this.userService.retrieve(2).get)
      permission.hasReadAccess(ProjectType(), this.userService.retrieve(3).get)
      permission.hasReadAccess(ProjectType(), this.userService.retrieve(100).get)
    }

    // Everybody can read any challenge
    "Read access on challenges" in {
      permission.hasReadAccess(ChallengeType(), User.guestUser)
      permission.hasReadAccess(ChallengeType(), this.userService.retrieve(1).get)
      permission.hasReadAccess(ChallengeType(), this.userService.retrieve(2).get)
      permission.hasReadAccess(ChallengeType(), this.userService.retrieve(3).get)
      permission.hasReadAccess(ChallengeType(), this.userService.retrieve(100).get)
    }

    // Everybody can read any task
    "Read access on Tasks" in {
      permission.hasReadAccess(TaskType(), User.guestUser)(1)
      permission.hasReadAccess(TaskType(), this.userService.retrieve(1).get)
      permission.hasReadAccess(TaskType(), this.userService.retrieve(2).get)
      permission.hasReadAccess(TaskType(), this.userService.retrieve(3).get)
    }

    // Only Super users and the actual user can read user information
    "Read access on Users" in {
      an[IllegalAccessException] should be thrownBy permission.hasReadAccess(
        UserType(),
        User.guestUser
      )
      permission.hasReadAccess(UserType(), User.superUser)
      permission.hasReadAccess(UserType(), this.userService.retrieve(1).get)
      an[IllegalAccessException] should be thrownBy permission.hasReadAccess(
        UserType(),
        this.userService.retrieve(2).get
      )
      an[IllegalAccessException] should be thrownBy permission.hasReadAccess(
        UserType(),
        this.userService.retrieve(3).get
      )
    }

    // Everybody can read any tag
    "Read access on Tags" in {
      permission.hasReadAccess(TagType(), User.guestUser)
      permission.hasReadAccess(TagType(), this.userService.retrieve(1).get)
      permission.hasReadAccess(TagType(), this.userService.retrieve(2).get)
      permission.hasReadAccess(TagType(), this.userService.retrieve(3).get)
    }

    // Everybody can read virtual challenges
    "Read access on Virtual Challenges" in {
      permission.hasReadAccess(VirtualChallengeType(), User.guestUser)
      permission.hasReadAccess(VirtualChallengeType(), this.userService.retrieve(1).get)
      permission.hasReadAccess(VirtualChallengeType(), this.userService.retrieve(2).get)
      permission.hasReadAccess(VirtualChallengeType(), this.userService.retrieve(3).get)
      permission.hasReadAccess(VirtualChallengeType(), this.userService.retrieve(100).get)
    }

    // Only grantees and superusers can read grants
    "Read access on Grants" in {
      an[IllegalAccessException] should be thrownBy permission.hasReadAccess(
        GrantType(),
        User.guestUser
      )

      // Grantee
      permission.hasReadAccess(GrantType(), this.userService.retrieve(1).get)

      an[IllegalAccessException] should be thrownBy permission.hasReadAccess(
        GrantType(),
        this.userService.retrieve(2).get
      )
      an[IllegalAccessException] should be thrownBy permission.hasReadAccess(
        GrantType(),
        this.userService.retrieve(3).get
      )

      permission.hasReadAccess(GrantType(), User.superUser)
    }

    // Users with Admin and Write roles should have write access to projects
    "Write access in Projects" in {
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        ProjectType(),
        User.guestUser
      )
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        ProjectType(),
        this.userService.retrieve(3).get
      )
      permission.hasWriteAccess(ProjectType(), User.superUser)
      permission.hasWriteAccess(ProjectType(), this.userService.retrieve(2).get)
      permission.hasWriteAccess(ProjectType(), this.userService.retrieve(1).get)
    }

    // Users with Admin and Write roles should have write access to challenges
    "Write access in Challenges" in {
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        ChallengeType(),
        User.guestUser
      )
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        ChallengeType(),
        this.userService.retrieve(3).get
      )

      // Even an owner needs to be granted the proper role
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        ChallengeType(),
        this.userService.retrieve(100).get
      )

      permission.hasWriteAccess(ChallengeType(), this.userService.retrieve(2).get)
      permission.hasWriteAccess(ChallengeType(), this.userService.retrieve(1).get)
      permission.hasWriteAccess(ChallengeType(), User.superUser)
    }

    // Users with Admin and Write roles should have write access to tasks
    "Write access in Tasks" in {
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        TaskType(),
        User.guestUser
      )
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        TaskType(),
        this.userService.retrieve(3L).get
      )

      // Even an owner needs to be granted the proper role
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        TaskType(),
        this.userService.retrieve(100L).get
      )

      permission.hasWriteAccess(TaskType(), this.userService.retrieve(2L).get)
      permission.hasWriteAccess(TaskType(), this.userService.retrieve(1L).get)
      permission.hasWriteAccess(TaskType(), User.superUser)
    }

    // Only super users and the actual user have
    "Write access in Users" in {
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        UserType(),
        User.guestUser
      )
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        UserType(),
        this.userService.retrieve(3L).get
      )
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        UserType(),
        this.userService.retrieve(2L).get
      )
      permission.hasWriteAccess(UserType(), this.userService.retrieve(1L).get)
      permission.hasWriteAccess(UserType(), User.superUser)
    }

    "Write access in Tags" in {
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        TaskType(),
        User.guestUser
      )
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        TaskType(),
        this.userService.retrieve(3L).get
      )
      permission.hasWriteAccess(TaskType(), this.userService.retrieve(2L).get)
      permission.hasWriteAccess(TaskType(), this.userService.retrieve(1L).get)
    }

    "Write access in Virtual Challenges" in {
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        VirtualChallengeType(),
        User.guestUser
      )
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        VirtualChallengeType(),
        this.userService.retrieve(3L).get
      )
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        VirtualChallengeType(),
        this.userService.retrieve(2L).get
      )
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        VirtualChallengeType(),
        this.userService.retrieve(1L).get
      )
      permission.hasWriteAccess(VirtualChallengeType(), User.superUser)
      permission.hasWriteAccess(VirtualChallengeType(), this.userService.retrieve(100L).get)
    }

    // Only superusers can write to grants
    "Write access in Grants" in {
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        GrantType(),
        User.guestUser
      )
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        GrantType(),
        this.userService.retrieve(1L).get
      )
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        GrantType(),
        this.userService.retrieve(2L).get
      )
      an[IllegalAccessException] should be thrownBy permission.hasWriteAccess(
        GrantType(),
        this.userService.retrieve(3L).get
      )

      permission.hasWriteAccess(GrantType(), User.superUser)
    }

    "Admin access for Projects" in {
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        ProjectType(),
        User.guestUser
      )
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        ProjectType(),
        this.userService.retrieve(3L).get
      )
      permission.hasAdminAccess(ProjectType(), User.superUser)
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        ProjectType(),
        this.userService.retrieve(2L).get
      )
      permission.hasAdminAccess(ProjectType(), this.userService.retrieve(1L).get)
    }

    "Admin access for Challenges" in {
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        ChallengeType(),
        User.guestUser
      )
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        ChallengeType(),
        this.userService.retrieve(3L).get
      )
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        ChallengeType(),
        this.userService.retrieve(2L).get
      )

      // Even an owner needs to be granted the proper role
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        ChallengeType(),
        this.userService.retrieve(100L).get
      )

      permission.hasAdminAccess(ChallengeType(), this.userService.retrieve(1L).get)
      permission.hasAdminAccess(ChallengeType(), User.superUser)
    }

    "Admin access for Tasks" in {
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        TaskType(),
        User.guestUser
      )
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        TaskType(),
        this.userService.retrieve(3L).get
      )
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        TaskType(),
        this.userService.retrieve(2L).get
      )

      // Even an owner needs to be granted the proper role
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        ChallengeType(),
        this.userService.retrieve(100L).get
      )
      permission.hasAdminAccess(TaskType(), this.userService.retrieve(1L).get)
      permission.hasAdminAccess(TaskType(), User.superUser)
    }

    "Admin access for Users" in {
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        UserType(),
        User.guestUser
      )
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        UserType(),
        this.userService.retrieve(3L).get
      )
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        UserType(),
        this.userService.retrieve(2L).get
      )
      permission.hasAdminAccess(UserType(), this.userService.retrieve(1L).get)
      permission.hasAdminAccess(UserType(), User.superUser)
    }

    "Admin access for Tags" in {
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        TaskType(),
        User.guestUser
      )
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        TaskType(),
        this.userService.retrieve(3L).get
      )
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        TaskType(),
        this.userService.retrieve(2L).get
      )
      permission.hasAdminAccess(TaskType(), this.userService.retrieve(1L).get)
    }

    "Admin access for Virtual Challenges" in {
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        VirtualChallengeType(),
        User.guestUser
      )
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        VirtualChallengeType(),
        this.userService.retrieve(3L).get
      )
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        VirtualChallengeType(),
        this.userService.retrieve(2L).get
      )
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        VirtualChallengeType(),
        this.userService.retrieve(1L).get
      )
      permission.hasAdminAccess(VirtualChallengeType(), User.superUser)
      permission.hasAdminAccess(VirtualChallengeType(), this.userService.retrieve(100L).get)
    }

    // Only superusers can adminstrate grants
    "Admin access for Grants" in {
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        GrantType(),
        User.guestUser
      )
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        GrantType(),
        this.userService.retrieve(1L).get
      )
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        GrantType(),
        this.userService.retrieve(2L).get
      )
      an[IllegalAccessException] should be thrownBy permission.hasAdminAccess(
        GrantType(),
        this.userService.retrieve(3L).get
      )

      permission.hasAdminAccess(GrantType(), User.superUser)
    }
  }
}

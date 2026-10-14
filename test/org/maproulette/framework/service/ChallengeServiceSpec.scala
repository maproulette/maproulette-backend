/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import org.maproulette.exception.{InvalidException, NotFoundException}
import org.maproulette.framework.model.{Challenge, Grant, User}
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.BaseParameter
import org.maproulette.framework.util.{ChallengeTag, FrameworkHelper}
import org.maproulette.permissions.Permission
import play.api.Application

/**
  * @author mcuthbert
  */
class ChallengeServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val service: ChallengeService = this.serviceManager.challenge

  var outsider: User = null

  "ChallengeService" should {
    "make a basic query" taggedAs ChallengeTag in {
      val challenges = this.service.query(
        Query.simple(List(BaseParameter(Challenge.FIELD_ID, this.defaultChallenge.id)))
      )
      challenges.size mustEqual 1
      challenges.head.id mustEqual this.defaultChallenge.id
    }

    "list no managers for a challenge nobody has been granted a role on" taggedAs ChallengeTag in {
      this.service.challengeManagers(this.defaultChallenge.id, User.superUser) mustEqual List.empty
    }

    "grant a user a role on a single challenge" taggedAs ChallengeTag in {
      val challenge = this.freshChallenge("grantRole")
      this.service
        .addUserToChallenge(challenge.id, outsider.id, Grant.ROLE_WRITE_ACCESS, User.superUser)

      val managers = this.service.challengeManagers(challenge.id, User.superUser)
      managers.size mustEqual 1
      managers.head.userId mustEqual outsider.id
      managers.head.role mustEqual Grant.ROLE_WRITE_ACCESS
    }

    "hold one role per user, replacing any earlier one" taggedAs ChallengeTag in {
      val challenge = this.freshChallenge("replaceRole")
      this.service
        .addUserToChallenge(challenge.id, outsider.id, Grant.ROLE_WRITE_ACCESS, User.superUser)
      this.service
        .addUserToChallenge(challenge.id, outsider.id, Grant.ROLE_ADMIN, User.superUser)

      val managers = this.service.challengeManagers(challenge.id, User.superUser)
      managers.size mustEqual 1
      managers.head.role mustEqual Grant.ROLE_ADMIN
    }

    "clear a user's role on a challenge" taggedAs ChallengeTag in {
      val challenge = this.freshChallenge("clearRole")
      this.service
        .addUserToChallenge(challenge.id, outsider.id, Grant.ROLE_ADMIN, User.superUser)
      this.service.removeUserFromChallenge(challenge.id, outsider.id, User.superUser)

      this.service.challengeManagers(challenge.id, User.superUser) mustEqual List.empty
    }

    "let a granted user write to the challenge they were granted" taggedAs ChallengeTag in {
      val challenge  = this.freshChallenge("grantedWrite")
      val permission = this.application.injector.instanceOf(classOf[Permission])

      // Before the grant the outsider is just that, and the parent project
      // gives them nothing.
      intercept[IllegalAccessException] {
        permission.hasObjectWriteAccess(challenge, this.freshUser(outsider))
      }

      this.service
        .addUserToChallenge(challenge.id, outsider.id, Grant.ROLE_WRITE_ACCESS, User.superUser)
      permission.hasObjectWriteAccess(challenge, this.freshUser(outsider))
    }

    "not let a write-access grant stand in for admin on the challenge" taggedAs ChallengeTag in {
      val challenge  = this.freshChallenge("writeIsNotAdmin")
      val permission = this.application.injector.instanceOf(classOf[Permission])

      this.service
        .addUserToChallenge(challenge.id, outsider.id, Grant.ROLE_WRITE_ACCESS, User.superUser)
      intercept[IllegalAccessException] {
        permission
          .hasObjectWriteAccess(challenge, this.freshUser(outsider), Grant.ROLE_ADMIN)
      }
    }

    "keep a grant on one challenge from reaching another" taggedAs ChallengeTag in {
      val granted    = this.freshChallenge("grantedOne")
      val other      = this.freshChallenge("grantedOther")
      val permission = this.application.injector.instanceOf(classOf[Permission])

      this.service
        .addUserToChallenge(granted.id, outsider.id, Grant.ROLE_ADMIN, User.superUser)
      intercept[IllegalAccessException] {
        permission.hasObjectWriteAccess(other, this.freshUser(outsider))
      }
    }

    "refuse a role that is not one of the grantable ones" taggedAs ChallengeTag in {
      val challenge = this.freshChallenge("badRole")
      intercept[InvalidException] {
        this.service.addUserToChallenge(challenge.id, outsider.id, 99, User.superUser)
      }
    }

    "refuse to grant a role to a user who does not exist" taggedAs ChallengeTag in {
      val challenge = this.freshChallenge("noSuchUser")
      intercept[NotFoundException] {
        this.service.addUserToChallenge(challenge.id, -1000, Grant.ROLE_ADMIN, User.superUser)
      }
    }

    "refuse to manage a challenge that does not exist" taggedAs ChallengeTag in {
      intercept[NotFoundException] {
        this.service.challengeManagers(-1000, User.superUser)
      }
    }
  }

  /** A challenge of this suite's own, so grants in one test cannot leak into another. */
  private def freshChallenge(label: String): Challenge =
    this.challengeDAL.insert(
      this.getTestChallenge(s"ChallengeServiceSpec_$label"),
      User.superUser
    )

  /**
    * Re-read a user so their grants reflect what has been granted during the
    * test rather than whatever the cached copy was created with.
    */
  private def freshUser(user: User): User = this.serviceManager.user.retrieve(user.id).get

  override implicit val projectTestName: String = "ChallengeServiceSpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    outsider = this.serviceManager.user.create(
      this.getTestUser(33312345, "ChallengeOutsider"),
      User.superUser
    )
  }
}

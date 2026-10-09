/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import org.maproulette.exception.{InvalidException}
import org.maproulette.framework.model.{Grant, Grantee, GrantTarget, MemberObject, User, Project}
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.BaseParameter
import org.maproulette.framework.service.GrantService
import org.maproulette.data._
import org.maproulette.framework.util.{FrameworkHelper, GrantTag}
import play.api.Application

/**
  * @author nrotstan
  */
class GrantServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val repository: GrantRepository = this.application.injector.instanceOf(classOf[GrantRepository])
  val service: GrantService       = this.application.injector.instanceOf(classOf[GrantService])

  var defaultGrant: Grant = null
  var randomUser: User    = null

  "GrantService" should {
    "perform a basic query" taggedAs GrantTag in {
      val grants = this.service.query(
        Query.simple(List(BaseParameter(Grant.FIELD_ID, this.defaultGrant.id))),
        User.superUser
      )
      grants.size mustEqual 1
      grants.head.id mustEqual this.defaultGrant.id
    }

    "not allow querying without specifying a user" taggedAs GrantTag in {
      intercept[IllegalAccessException] {
        this.service.query(
          Query.simple(List(BaseParameter(Grant.FIELD_ID, this.defaultGrant.id)))
        )
      }
    }

    "not allow querying by non-superuser" taggedAs GrantTag in {
      intercept[IllegalAccessException] {
        this.service.query(
          Query.simple(List(BaseParameter(Grant.FIELD_ID, this.defaultGrant.id))),
          User.guestUser
        )
      }

      intercept[IllegalAccessException] {
        this.service.query(
          Query.simple(List(BaseParameter(Grant.FIELD_ID, this.defaultGrant.id))),
          this.defaultUser
        )
      }
    }

    "retrieve a grant" taggedAs GrantTag in {
      val grant = this.service.retrieve(this.defaultGrant.id, User.superUser)
      grant.get mustEqual this.defaultGrant

      val nonGrant = this.service.retrieve(2345L, User.superUser)
      nonGrant.isEmpty mustEqual true
    }

    "not allow retrieval of grant by non-superuser" taggedAs GrantTag in {
      intercept[IllegalAccessException] {
        this.service.retrieve(this.defaultGrant.id, User.guestUser)
      }

      intercept[IllegalAccessException] {
        this.service.retrieve(this.defaultGrant.id, this.defaultUser)
      }
    }

    "retrieve all grants to a grantee" taggedAs GrantTag in {
      val anotherProject =
        this.createProjectStructure("retrieveGrantsToProject", "retrieveGrantsToProject")
      val anotherGrant = this.repository
        .create(this.setupProjectGrant(this.randomUser, Grant.ROLE_WRITE_ACCESS, anotherProject))

      val grants = this.service.retrieveGrantsTo(Grantee.user(this.randomUser.id), User.superUser)
      grants.size mustEqual 1
      grants.head.grantee.granteeId mustEqual this.randomUser.id

      val nonGrants = this.service.retrieveGrantsTo(Grantee.user(2345L), User.superUser)
      nonGrants.isEmpty mustEqual true
    }

    "not allow retrieval of all grants to a grantee by non-superuser" taggedAs GrantTag in {
      intercept[IllegalAccessException] {
        this.service.retrieveGrantsTo(Grantee.user(this.defaultUser.id), User.guestUser)
      }

      intercept[IllegalAccessException] {
        this.service.retrieveGrantsTo(Grantee.user(this.defaultUser.id), this.defaultUser)
      }
    }

    "retrieve all grants to multiple grantees of same type" taggedAs GrantTag in {
      val anotherProject =
        this.createProjectStructure(
          "retrieveMultipleGranteesProject",
          "retrieveMultipleGranteesProject"
        )
      val anotherGrant = this.repository
        .create(this.setupProjectGrant(this.randomUser, Grant.ROLE_WRITE_ACCESS, anotherProject))

      val grants = this.service.retrieveGrantsTo(
        UserType(),
        List(this.randomUser.id, this.defaultUser.id),
        User.superUser
      )

      // We should get at least one grant for each user
      val randomUserGrants  = grants.filter(_.grantee.granteeId == this.randomUser.id)
      val defaultUserGrants = grants.filter(_.grantee.granteeId == this.defaultUser.id)

      randomUserGrants.isEmpty mustEqual false
      defaultUserGrants.isEmpty mustEqual false

      val nonGrants = this.service.retrieveGrantsTo(UserType(), List(2345L), User.superUser)
      nonGrants.isEmpty mustEqual true
    }

    "retrieve all grants on a grant target" taggedAs GrantTag in {
      val anotherProject =
        this.createProjectStructure("retrieveGrantsOnProject", "retrieveGrantsOnProject")
      val anotherGrant = this.repository
        .create(this.setupProjectGrant(randomUser, Grant.ROLE_WRITE_ACCESS, anotherProject))

      val grants =
        this.service.retrieveGrantsOn(GrantTarget.project(anotherProject.id), User.superUser)
      grants.size mustEqual 2

      val nonGrants = this.service.retrieveGrantsOn(GrantTarget.project(2345L), User.superUser)
      nonGrants.isEmpty mustEqual true
    }

    "not allow retrieval of all grants on a grant target by non-superuser" taggedAs GrantTag in {
      intercept[IllegalAccessException] {
        this.service.retrieveGrantsOn(GrantTarget.project(this.defaultProject.id), User.guestUser)
      }

      intercept[IllegalAccessException] {
        this.service.retrieveGrantsOn(GrantTarget.project(this.defaultProject.id), this.defaultUser)
      }
    }

    "retrieve grants matching a set of filters" taggedAs GrantTag in {
      val anotherProject = this
        .createProjectStructure("retrieveMatchingGrantsProject", "retrieveMatchingGrantsProject")
      val anotherGrant = this.repository
        .create(this.setupProjectGrant(randomUser, Grant.ROLE_WRITE_ACCESS, anotherProject))

      val onGrantee =
        service.retrieveMatchingGrants(
          grantee = Some(List(Grantee.user(randomUser.id))),
          user = User.superUser
        )
      onGrantee.isEmpty mustEqual false
      onGrantee.forall { grant =>
        grant.grantee.granteeId == randomUser.id
      } mustEqual true

      val onTarget = service.retrieveMatchingGrants(
        target = Some(GrantTarget.project(anotherProject.id)),
        user = User.superUser
      )
      onTarget.isEmpty mustEqual false
      onTarget.forall { grant =>
        grant.target.objectId == anotherProject.id
      } mustEqual true

      val onRole = service.retrieveMatchingGrants(
        role = Some(Grant.ROLE_WRITE_ACCESS),
        user = User.superUser
      )
      onRole.isEmpty mustEqual false
      onRole.forall { grant =>
        grant.role == Grant.ROLE_WRITE_ACCESS
      } mustEqual true

      val onGranteeAndTarget = service.retrieveMatchingGrants(
        grantee = Some(List(Grantee.user(randomUser.id))),
        target = Some(GrantTarget.project(anotherProject.id)),
        user = User.superUser
      )
      onGranteeAndTarget.isEmpty mustEqual false
      onGranteeAndTarget.forall { grant =>
        grant.grantee.granteeId == randomUser.id && grant.target.objectId == anotherProject.id
      } mustEqual true

      val onGranteeAndRoleAndTarget = service.retrieveMatchingGrants(
        grantee = Some(List(Grantee.user(randomUser.id))),
        role = Some(Grant.ROLE_WRITE_ACCESS),
        target = Some(GrantTarget.project(anotherProject.id)),
        user = User.superUser
      )
      onGranteeAndRoleAndTarget.isEmpty mustEqual false
      onGranteeAndRoleAndTarget.forall { grant =>
        grant.grantee.granteeId == randomUser.id &&
        grant.role == Grant.ROLE_WRITE_ACCESS &&
        grant.target.objectId == anotherProject.id
      } mustEqual true

      val noMatches = service.retrieveMatchingGrants(
        role = Some(Grant.ROLE_READ_ONLY), // shouldn't exist
        target = Some(GrantTarget.project(anotherProject.id)),
        user = User.superUser
      )
      noMatches.isEmpty mustEqual true
    }

    "not allow retrieval of matching grants by non-superuser" taggedAs GrantTag in {
      intercept[IllegalAccessException] {
        this.service.retrieveMatchingGrants(
          target = Some(GrantTarget.project(this.defaultProject.id)),
          user = User.guestUser
        )
      }

      intercept[IllegalAccessException] {
        this.service.retrieveMatchingGrants(
          target = Some(GrantTarget.project(this.defaultProject.id)),
          user = this.defaultUser
        )
      }
    }

    "allow deletion of grants" taggedAs GrantTag in {
      val anotherProject = this.createProjectStructure("deleteGrantProject", "deleteGrantProject")
      val anotherGrant = this.repository
        .create(this.setupProjectGrant(randomUser, Grant.ROLE_WRITE_ACCESS, anotherProject))

      this.service.deleteGrant(anotherGrant.get, User.superUser)
      this.service.retrieve(anotherGrant.get.id, User.superUser) mustEqual None
    }

    "not allow deletion of grants by non-superuser" taggedAs GrantTag in {
      intercept[IllegalAccessException] {
        this.service.deleteGrant(this.defaultGrant, User.guestUser)
      }

      intercept[IllegalAccessException] {
        this.service.deleteGrant(this.defaultGrant, this.defaultUser)
      }
    }

    "delete all grants to a grantee" taggedAs GrantTag in {
      val anotherProject =
        this.createProjectStructure("deleteGrantsToProject", "deleteGrantsToProject")
      val anotherGrant = this.repository
        .create(this.setupProjectGrant(this.randomUser, Grant.ROLE_WRITE_ACCESS, anotherProject))

      this.service.deleteGrantsTo(Grantee.user(this.randomUser.id), User.superUser)
      this.service.retrieve(anotherGrant.get.id, User.superUser) mustEqual None
    }

    "not allow deletion of all grants to a grantee by non-superuser" taggedAs GrantTag in {
      intercept[IllegalAccessException] {
        this.service.deleteGrantsTo(Grantee.user(this.defaultUser.id), User.guestUser)
      }

      intercept[IllegalAccessException] {
        this.service.deleteGrantsTo(Grantee.user(this.defaultUser.id), this.defaultUser)
      }
    }

    "delete all grants on a grant target" taggedAs GrantTag in {
      val anotherProject =
        this.createProjectStructure("deleteGrantsOnProject", "deleteGrantsOnProject")
      val anotherGrant = this.repository
        .create(this.setupProjectGrant(this.randomUser, Grant.ROLE_WRITE_ACCESS, anotherProject))

      this.service.deleteGrantsOn(GrantTarget.project(anotherProject.id), User.superUser)
      this.service.retrieve(anotherGrant.get.id, User.superUser) mustEqual None
    }

    "not allow deletion of all grants on a grant target by non-superuser" taggedAs GrantTag in {
      intercept[IllegalAccessException] {
        this.service.deleteGrantsOn(GrantTarget.project(this.defaultProject.id), User.guestUser)
      }

      intercept[IllegalAccessException] {
        this.service.deleteGrantsOn(GrantTarget.project(this.defaultProject.id), this.defaultUser)
      }
    }

    "delete grants matching a set of filters" taggedAs GrantTag in {
      val anotherProject =
        this.createProjectStructure("deleteMatchingGrantsProject", "deleteMatchingGrantsProject")
      val anotherGrant = this.repository
        .create(this.setupProjectGrant(randomUser, Grant.ROLE_WRITE_ACCESS, anotherProject))

      service.deleteMatchingGrants(
        grantee = Some(Grantee.user(randomUser.id)),
        role = Some(Grant.ROLE_WRITE_ACCESS),
        user = User.superUser
      )

      this.service
        .retrieveMatchingGrants(
          grantee = Some(List(Grantee.user(randomUser.id))),
          role = Some(Grant.ROLE_WRITE_ACCESS),
          user = User.superUser
        )
        .isEmpty mustEqual true
    }

    "reject roles that are invalid for the grant target" taggedAs GrantTag in {
      val invalid = List(
        (Grant.ROLE_SUPER_USER, GrantTarget.project(this.defaultProject.id)),
        (Grant.ROLE_SUPER_USER, GrantTarget.project(0)),
        (Grant.ROLE_OWNER, GrantTarget.project(this.defaultProject.id)),
        (Grant.ROLE_OWNER, GrantTarget.challenge(this.defaultChallenge.id)),
        (4, GrantTarget.project(this.defaultProject.id)),
        (Grant.ROLE_SUPER_USER, GrantTarget.group(1)),
        (4, GrantTarget.group(1))
      )
      invalid.foreach {
        case (role, target) =>
          intercept[InvalidException] {
            this.service
              .createGrant(Grant(-1, "", Grantee.user(randomUser.id), role, target), User.superUser)
          }
      }
    }

    "allow the owner role on a team" taggedAs GrantTag in {
      val team = this.serviceManager.team
        .create(
          this.getTestTeam("GrantServiceSpec_owner"),
          MemberObject.user(this.defaultUser.id),
          this.defaultUser
        )
        .get
      val grant = this.service.createGrant(
        Grant(-1, "", Grantee.user(randomUser.id), Grant.ROLE_OWNER, GrantTarget.group(team.id)),
        User.superUser
      )
      grant.get.role mustEqual Grant.ROLE_OWNER
    }

    "reject an invalid role at the database even when bypassing the service" taggedAs GrantTag in {
      intercept[Exception] {
        this.repository.create(
          setupProjectGrant(randomUser, Grant.ROLE_SUPER_USER, this.defaultProject)
        )
      }
    }

    "seed superusers only from the dedicated superuser grant" taggedAs GrantTag in {
      this.service.getSuperUserIdsFromDatabase must not contain randomUser.id
      this.service.createSuperUserGrant(randomUser.id, "GrantServiceSpec superuser", User.superUser)
      this.service.getSuperUserIdsFromDatabase must contain(randomUser.id)
      this.service.deleteSuperUserFromDatabase(randomUser.id)
      this.service.getSuperUserIdsFromDatabase must not contain randomUser.id
    }

    "not allow creating a superuser grant by non-superuser" taggedAs GrantTag in {
      intercept[IllegalAccessException] {
        this.service.createSuperUserGrant(randomUser.id, "GrantServiceSpec superuser", this.defaultUser)
      }
    }

    "requires at least a grantee or target to delete grants matching filters" taggedAs GrantTag in {
      intercept[InvalidException] {
        service.deleteMatchingGrants(
          role = Some(Grant.ROLE_WRITE_ACCESS),
          user = User.superUser
        )
      }
    }

    "not allow deletion of matching grants by non-superuser" taggedAs GrantTag in {
      intercept[IllegalAccessException] {
        this.service.deleteMatchingGrants(
          target = Some(GrantTarget.project(this.defaultProject.id)),
          user = User.guestUser
        )
      }

      intercept[IllegalAccessException] {
        this.service.deleteMatchingGrants(
          target = Some(GrantTarget.project(this.defaultProject.id)),
          user = this.defaultUser
        )
      }
    }
  }

  override implicit val projectTestName: String = "GrantServiceSpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    defaultGrant = this.defaultProject.grants.head
    randomUser = this.serviceManager.user.create(
      this.getTestUser(12345, "RandomOUser"),
      User.superUser
    )
  }

  protected def setupProjectGrant(user: User, role: Int, project: Project): Grant = {
    Grant(-1, "", Grantee.user(user.id), role, GrantTarget.project(project.id))
  }
}

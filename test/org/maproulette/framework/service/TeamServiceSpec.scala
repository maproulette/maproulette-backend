/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import org.maproulette.exception.{InvalidException, NotFoundException}
import org.maproulette.framework.model.{
  ChallengeExtra,
  TeamMember,
  TeamRole,
  Group,
  MemberObject,
  User,
  Grant,
  Grantee,
  GrantTarget
}
import org.maproulette.data.UserType
import org.maproulette.framework.util.{FrameworkHelper, TeamTag}
import org.maproulette.framework.psql.{Paging}
import play.api.Application

/**
  * @author mcuthbert
  */
class TeamServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val service: TeamService = this.serviceManager.team

  var defaultTeam: Group = null
  var randomUser: User   = null
  var anotherUser: User  = null

  "TeamService" should {
    "create a new team with an initial admin member" taggedAs TeamTag in {
      val team = this.service
        .create(
          this.getTestTeam("TeamService_createTeamTest Team"),
          MemberObject.user(this.defaultUser.id),
          this.defaultUser
        )
        .get
      team.name mustEqual "TeamService_createTeamTest Team"
      team.groupType mustEqual Group.GROUP_TYPE_TEAM

      val members = this.service.teamMembers(team, this.defaultUser)
      members.size mustEqual 1
      members.head.memberType mustEqual UserType().typeId
      members.head.memberId mustEqual this.defaultUser.id
      this.service.isTeamAdmin(
        this.defaultTeam,
        MemberObject.user(this.defaultUser.id),
        this.defaultUser
      ) mustEqual true
    }

    "add a member to a team" taggedAs TeamTag in {
      val addedMember = this.service.addTeamMember(
        this.defaultTeam,
        MemberObject.user(this.randomUser.id),
        Grant.ROLE_READ_ONLY,
        TeamMember.STATUS_MEMBER,
        this.defaultUser
      )

      val allMembers = this.service.teamMembers(this.defaultTeam, this.defaultUser)
      allMembers.size mustEqual 2
      Seq(this.defaultUser.id, this.randomUser.id) must contain(allMembers.head.memberId)
      Seq(this.defaultUser.id, this.randomUser.id) must contain(allMembers(1).memberId)
    }

    "add a member to a team with a specific status" taggedAs TeamTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(22213597, "AddTeamMemberStatusOUser"),
        User.superUser
      )

      val addedMember = this.service.addTeamMember(
        this.defaultTeam,
        MemberObject.user(freshUser.id),
        Grant.ROLE_READ_ONLY,
        TeamMember.STATUS_INVITED,
        this.defaultUser
      )

      addedMember.get.status mustEqual TeamMember.STATUS_INVITED
      addedMember.get.memberType mustEqual UserType().typeId
      addedMember.get.memberId mustEqual freshUser.id

      val retrievedMember =
        this.service
          .getTeamMember(this.defaultTeam, MemberObject.user(freshUser.id), this.defaultUser)

      retrievedMember.get.memberId mustEqual freshUser.id
      retrievedMember.get.memberType mustEqual UserType().typeId
      retrievedMember.get.status mustEqual TeamMember.STATUS_INVITED
    }

    "not allow a non-admin member to add members to the team" taggedAs TeamTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(22224680, "AddTeamMemberOUser"),
        User.superUser
      )

      an[IllegalAccessException] should be thrownBy this.service.addTeamMember(
        this.defaultTeam,
        MemberObject.user(freshUser.id),
        Grant.ROLE_READ_ONLY,
        TeamMember.STATUS_INVITED,
        freshUser
      )
    }

    "retrieve a team by id" taggedAs TeamTag in {
      val team = this.service.retrieve(this.defaultTeam.id, User.superUser)
      team.get.id mustEqual this.defaultTeam.id
    }

    "retrieve a team by name" taggedAs TeamTag in {
      val team = this.service.retrieveByName("TeamServiceSpec_Team A", User.superUser)
      team.get.id mustEqual this.defaultTeam.id
    }

    "do a basic search for teams" taggedAs TeamTag in {
      val teamB = this.service
        .create(
          this.getTestTeam("TeamServiceSpec_searchTeams Team B"),
          MemberObject.user(this.defaultUser.id),
          this.defaultUser
        )
        .get
      val teamC = this.service
        .create(
          this.getTestTeam("TeamServiceSpec_searchTeams Team C"),
          MemberObject.user(this.defaultUser.id),
          this.defaultUser
        )
        .get

      val teamsWithA    = this.service.search("Team A", Paging(), this.defaultUser)
      val teamsWithB    = this.service.search("Team B", Paging(), this.defaultUser)
      val matchNone     = this.service.search("Nothing", Paging(), this.defaultUser)
      val matchMultiple = this.service.search("searchTeams Team", Paging(), this.defaultUser)

      teamsWithA.size mustEqual 1
      teamsWithA.head.id mustEqual this.defaultTeam.id

      teamsWithB.size mustEqual 1
      teamsWithB.head.id mustEqual teamB.id

      matchNone.size mustEqual 0

      matchMultiple.size mustEqual 2
      Seq(teamB.id, teamC.id) must contain(matchMultiple.head.id)
      Seq(teamB.id, teamC.id) must contain(matchMultiple(1).id)
    }

    "retrieve all members of a team" taggedAs TeamTag in {
      val team = this.service
        .create(
          this.getTestTeam("TeamService_retrieveAllMembersTest Team"),
          MemberObject.user(this.defaultUser.id),
          this.defaultUser
        )
        .get
      this.service.addTeamMember(
        team,
        MemberObject.user(this.randomUser.id),
        Grant.ROLE_READ_ONLY,
        TeamMember.STATUS_MEMBER,
        this.defaultUser
      )
      val allMembers = this.service.teamMembers(team, this.defaultUser)

      allMembers.size mustEqual 2
      Seq(this.defaultUser.id, this.randomUser.id) must contain(allMembers.head.memberId)
      Seq(this.defaultUser.id, this.randomUser.id) must contain(allMembers(1).memberId)
    }

    "retrieve user representation of team members" taggedAs TeamTag in {
      val team = this.service
        .create(
          this.getTestTeam("TeamService_retrieveUserMembersTest Team"),
          MemberObject.user(this.defaultUser.id),
          this.defaultUser
        )
        .get
      this.service.addTeamMember(
        team,
        MemberObject.user(this.randomUser.id),
        Grant.ROLE_READ_ONLY,
        TeamMember.STATUS_MEMBER,
        this.defaultUser
      )
      this.service.addTeamMember(
        team,
        MemberObject.user(this.anotherUser.id),
        Grant.ROLE_READ_ONLY,
        TeamMember.STATUS_MEMBER,
        this.defaultUser
      )
      val allMembers  = this.service.teamMembers(team, this.defaultUser)
      val userMembers = this.service.memberUsers(allMembers, this.defaultUser)

      userMembers.size mustEqual 3
      Seq(this.defaultUser.id, this.randomUser.id, this.anotherUser.id) must contain(
        userMembers.head.userId
      )
      Seq(this.defaultUser.id, this.randomUser.id, this.anotherUser.id) must contain(
        userMembers(1).userId
      )
      Seq(this.defaultUser.id, this.randomUser.id, this.anotherUser.id) must contain(
        userMembers(2).userId
      )
      userMembers.foreach(_.teamName mustEqual team.name)
    }

    "check if a member is on a team" taggedAs TeamTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(22297531, "AddIsTeamMemberOUser"),
        User.superUser
      )
      this.service.isActiveTeamMember(
        this.defaultTeam,
        MemberObject.user(this.defaultUser.id),
        this.defaultUser
      ) mustEqual true
      this.service.isActiveTeamMember(
        this.defaultTeam,
        MemberObject.user(this.randomUser.id),
        this.defaultUser
      ) mustEqual true
      this.service.isActiveTeamMember(
        this.defaultTeam,
        MemberObject.user(freshUser.id),
        this.defaultUser
      ) mustEqual false
    }

    "check if a user is an admin of a team" taggedAs TeamTag in {
      this.service.isTeamAdmin(
        this.defaultTeam,
        MemberObject.user(this.defaultUser.id),
        this.defaultUser
      ) mustEqual true
      this.service.isTeamAdmin(
        this.defaultTeam,
        MemberObject.user(this.randomUser.id),
        this.defaultUser
      ) mustEqual false
      this.service.isTeamAdmin(
        this.defaultTeam,
        MemberObject.user(this.anotherUser.id),
        this.defaultUser
      ) mustEqual false
    }

    "not consider an invited member to be active on the team" taggedAs TeamTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(22212985, "InviteesNotActiveOUser"),
        User.superUser
      )
      this.service.addTeamMember(
        this.defaultTeam,
        MemberObject.user(freshUser.id),
        Grant.ROLE_READ_ONLY,
        TeamMember.STATUS_INVITED,
        this.defaultUser
      )
      this.service.isActiveTeamMember(
        this.defaultTeam,
        MemberObject.user(freshUser.id),
        this.defaultUser
      ) mustEqual false
    }

    "update the status of a team member" taggedAs TeamTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(22298912, "InviteesNotActiveOUser"),
        User.superUser
      )
      val addedMember = this.service.addTeamMember(
        this.defaultTeam,
        MemberObject.user(freshUser.id),
        Grant.ROLE_READ_ONLY,
        TeamMember.STATUS_INVITED,
        this.defaultUser
      )

      addedMember.get.status mustEqual TeamMember.STATUS_INVITED
      addedMember.get.memberType mustEqual UserType().typeId
      addedMember.get.memberId mustEqual freshUser.id

      val updatedMember = this.service.updateMemberStatus(
        this.defaultTeam,
        MemberObject.user(freshUser.id),
        TeamMember.STATUS_MEMBER,
        this.defaultUser
      )

      updatedMember.get.status mustEqual TeamMember.STATUS_MEMBER
      updatedMember.get.memberType mustEqual UserType().typeId
      updatedMember.get.memberId mustEqual freshUser.id

      val retrievedMember =
        this.service
          .getTeamMember(this.defaultTeam, MemberObject.user(freshUser.id), this.defaultUser)

      retrievedMember.get.status mustEqual TeamMember.STATUS_MEMBER
      retrievedMember.get.memberType mustEqual UserType().typeId
      retrievedMember.get.memberId mustEqual freshUser.id

      this.service.isActiveTeamMember(
        this.defaultTeam,
        MemberObject.user(freshUser.id),
        this.defaultUser
      ) mustEqual true
    }

    "retrieve an individual member of a team" taggedAs TeamTag in {
      val member =
        this.service
          .getTeamMember(this.defaultTeam, MemberObject.user(this.randomUser.id), this.defaultUser)

      member.get.groupId mustEqual defaultTeam.id
      member.get.memberType mustEqual UserType().typeId
      member.get.memberId mustEqual randomUser.id
      member.get.status mustEqual TeamMember.STATUS_MEMBER
    }

    "remove a member from a team" taggedAs TeamTag in {
      val team = this.service
        .create(
          this.getTestTeam("TeamService_removeMemberTest Team"),
          MemberObject.user(this.defaultUser.id),
          this.defaultUser
        )
        .get
      this.service.addTeamMember(
        team,
        MemberObject.user(this.randomUser.id),
        Grant.ROLE_READ_ONLY,
        TeamMember.STATUS_MEMBER,
        this.defaultUser
      )
      this.service.teamMembers(team, this.defaultUser).size mustEqual 2

      this.service.removeTeamMember(team, MemberObject.user(this.randomUser.id), this.defaultUser)
      val remainingMembers = this.service.teamMembers(team, this.defaultUser)
      remainingMembers.size mustEqual 1
      remainingMembers.head.memberId mustEqual this.defaultUser.id
    }

    "not allow a normal member to remove members from a team" taggedAs TeamTag in {
      an[IllegalAccessException] should be thrownBy this.service.removeTeamMember(
        this.defaultTeam,
        MemberObject.user(this.defaultUser.id),
        this.randomUser
      )
    }

    "assign a new role to a team member" taggedAs TeamTag in {
      val team = this.service
        .create(
          this.getTestTeam("TeamService_updateMemberRoleTest Team"),
          MemberObject.user(this.defaultUser.id),
          this.defaultUser
        )
        .get

      this.service.addTeamMember(
        team,
        MemberObject.user(this.randomUser.id),
        Grant.ROLE_READ_ONLY,
        TeamMember.STATUS_MEMBER,
        this.defaultUser
      )
      this.service.updateMemberRole(
        team,
        MemberObject.user(this.randomUser.id),
        Grant.ROLE_WRITE_ACCESS,
        this.defaultUser
      )

      this.serviceManager.grant
        .retrieveMatchingGrants(
          grantee = Some(List(Grantee.user(this.randomUser.id))),
          role = Some(Grant.ROLE_WRITE_ACCESS),
          target = Some(GrantTarget.group(team.id)),
          user = User.superUser
        )
        .size mustEqual 1
    }

    "not allow normal members to assign new roles to team members" taggedAs TeamTag in {
      an[IllegalAccessException] should be thrownBy this.service.updateMemberRole(
        this.defaultTeam,
        MemberObject.user(this.defaultUser.id),
        Grant.ROLE_READ_ONLY,
        this.randomUser
      )
    }

    "update a team" taggedAs TeamTag in {
      val team = this.service
        .create(
          this.getTestTeam("TeamService_updateTeamTest Team"),
          MemberObject.user(this.defaultUser.id),
          this.defaultUser
        )
        .get
      this.service.updateTeam(
        team.copy(name = "TeamService_updateTeamTest New Team Name"),
        this.defaultUser
      )

      val retrievedTeam = this.service.retrieve(team.id, this.defaultUser)
      retrievedTeam.get.id mustEqual team.id
      retrievedTeam.get.name mustEqual "TeamService_updateTeamTest New Team Name"
    }

    "not allow normal members to update a team" taggedAs TeamTag in {
      an[IllegalAccessException] should be thrownBy this.service.updateTeam(
        defaultTeam.copy(name = "New Team Name"),
        this.randomUser
      )
    }

    "retrieve all team memberships possessed by a user" taggedAs TeamTag in {
      val freshUser = this.serviceManager.user.create(
        this.getTestUser(22235791, "RetrieveMemberTeamsOUser"),
        User.superUser
      )

      val team = this.service
        .create(
          this.getTestTeam("TeamService_retrieveMemberTeamsTest Team"),
          MemberObject.user(freshUser.id),
          freshUser
        )
        .get

      this.service.addTeamMember(
        this.defaultTeam,
        MemberObject.user(freshUser.id),
        Grant.ROLE_READ_ONLY,
        TeamMember.STATUS_MEMBER,
        this.defaultUser
      )

      val allMemberships =
        this.service.teamUsersByUserIds(List(freshUser.id), freshUser)

      allMemberships.size mustEqual 2
      Seq(this.defaultTeam.id, team.id) must contain(allMemberships.head.teamId)
      Seq(this.defaultTeam.id, team.id) must contain(allMemberships(1).teamId)
      Seq(this.defaultTeam.name, team.name) must contain(allMemberships.head.teamName)
      Seq(this.defaultTeam.name, team.name) must contain(allMemberships(1).teamName)
    }

    "make whoever creates a team its owner" taggedAs TeamTag in {
      val team  = ownedTeam("ownerOnCreate")
      val owner = MemberObject.user(this.defaultUser.id)

      this.service.teamRoleFor(team, owner, this.defaultUser) mustEqual Some(TeamRole.OWNER)
      this.service.teamOwners(team).map(_.memberId) mustEqual List(this.defaultUser.id)
    }

    "treat an owner as satisfying every lesser role" taggedAs TeamTag in {
      val team  = ownedTeam("ownerOutranks")
      val owner = MemberObject.user(this.defaultUser.id)

      this.service.isTeamOwner(team, owner, this.defaultUser) mustEqual true
      this.service.isTeamAdmin(team, owner, this.defaultUser) mustEqual true
      this.service.isTeamManager(team, owner, this.defaultUser) mustEqual true
    }

    "not let a plain member manage the content of a team" taggedAs TeamTag in {
      val team   = ownedTeam("memberManages")
      val member = MemberObject.user(this.randomUser.id)
      this.service
        .addTeamMember(team, member, TeamRole.MEMBER, TeamMember.STATUS_MEMBER, this.defaultUser)

      this.service.isTeamManager(team, member, this.defaultUser) mustEqual false
      this.service.isTeamAdmin(team, member, this.defaultUser) mustEqual false
    }

    "let a manager manage the content of a team but not its membership" taggedAs TeamTag in {
      val team    = ownedTeam("managerScope")
      val manager = MemberObject.user(this.randomUser.id)
      this.service
        .addTeamMember(team, manager, TeamRole.MANAGER, TeamMember.STATUS_MEMBER, this.defaultUser)

      this.service.isTeamManager(team, manager, this.defaultUser) mustEqual true
      this.service.isTeamAdmin(team, manager, this.defaultUser) mustEqual false
    }

    "refuse to demote the last owner of a team" taggedAs TeamTag in {
      val team = ownedTeam("lastOwnerDemote")
      intercept[InvalidException] {
        this.service.updateMemberRole(
          team,
          MemberObject.user(this.defaultUser.id),
          TeamRole.ADMIN,
          this.defaultUser
        )
      }
    }

    "refuse to remove the last owner of a team" taggedAs TeamTag in {
      val team = ownedTeam("lastOwnerRemove")
      intercept[InvalidException] {
        this.service
          .removeTeamMember(team, MemberObject.user(this.defaultUser.id), this.defaultUser)
      }
    }

    "let an owner step down once another owner exists" taggedAs TeamTag in {
      val team      = ownedTeam("ownershipHandover")
      val successor = MemberObject.user(this.randomUser.id)
      this.service
        .addTeamMember(team, successor, TeamRole.OWNER, TeamMember.STATUS_MEMBER, this.defaultUser)

      this.service.updateMemberRole(
        team,
        MemberObject.user(this.defaultUser.id),
        TeamRole.ADMIN,
        this.defaultUser
      ) mustEqual true
      this.service.teamOwners(team).map(_.memberId) mustEqual List(this.randomUser.id)
    }

    "not let an admin hand out the owner role" taggedAs TeamTag in {
      val team  = ownedTeam("adminGrantsOwner")
      val admin = MemberObject.user(this.randomUser.id)
      this.service
        .addTeamMember(team, admin, TeamRole.ADMIN, TeamMember.STATUS_MEMBER, this.defaultUser)

      intercept[IllegalAccessException] {
        this.service.updateMemberRole(
          team,
          MemberObject.user(this.anotherUser.id),
          TeamRole.OWNER,
          this.randomUser
        )
      }
    }

    "reject a role that is not a team role" taggedAs TeamTag in {
      val team = ownedTeam("bogusRole")
      intercept[InvalidException] {
        this.service.addTeamMember(
          team,
          MemberObject.user(this.randomUser.id),
          99,
          TeamMember.STATUS_MEMBER,
          this.defaultUser
        )
      }
    }

    "only let an owner delete a team" taggedAs TeamTag in {
      val team = ownedTeam("ownerDeletes")
      this.service.addTeamMember(
        team,
        MemberObject.user(this.randomUser.id),
        TeamRole.ADMIN,
        TeamMember.STATUS_MEMBER,
        this.defaultUser
      )

      intercept[IllegalAccessException] {
        this.service.deleteTeam(team, this.randomUser)
      }
      this.service.deleteTeam(team, this.defaultUser) mustEqual true
    }

    "offer only the teams whose content a user runs" taggedAs TeamTag in {
      val managed = ownedTeam("managedByUser")
      val joined  = ownedTeam("merelyJoined")
      this.service.addTeamMember(
        managed,
        MemberObject.user(this.anotherUser.id),
        TeamRole.MANAGER,
        TeamMember.STATUS_MEMBER,
        this.defaultUser
      )
      this.service.addTeamMember(
        joined,
        MemberObject.user(this.anotherUser.id),
        TeamRole.MEMBER,
        TeamMember.STATUS_MEMBER,
        this.defaultUser
      )

      val offered = this.service.teamsManagedBy(freshUser(this.anotherUser)).map(_.id)
      offered must contain(managed.id)
      offered must not contain joined.id
    }

    "leave an invitation out of the teams a user runs until it is accepted" taggedAs TeamTag in {
      val team = ownedTeam("invitePending")
      this.service.addTeamMember(
        team,
        MemberObject.user(this.anotherUser.id),
        TeamRole.MANAGER,
        TeamMember.STATUS_INVITED,
        this.defaultUser
      )

      this.service.teamsManagedBy(freshUser(this.anotherUser)).map(_.id) must not contain team.id
    }

    "refuse a challenge to a team the user merely belongs to" taggedAs TeamTag in {
      val team = ownedTeam("challengeOwnership")
      this.service.addTeamMember(
        team,
        MemberObject.user(this.randomUser.id),
        TeamRole.MEMBER,
        TeamMember.STATUS_MEMBER,
        this.defaultUser
      )

      intercept[InvalidException] {
        this.service.requireChallengeOwnership(team.id, freshUser(this.randomUser))
      }
      this.service.requireChallengeOwnership(team.id, this.defaultUser)
    }

    "list the projects a team has been granted a role on" taggedAs TeamTag in {
      val team = this.ownedTeam("teamProjects")
      this.service.addTeamToProject(
        team.id,
        this.defaultProject.id,
        Grant.ROLE_ADMIN,
        this.defaultUser
      )

      val projects = this.service.teamProjects(team.id, this.defaultUser)
      projects.map(_.id) mustEqual List(this.defaultProject.id)
    }

    "list no projects for a team that manages none" taggedAs TeamTag in {
      val team = this.ownedTeam("teamProjectsEmpty")
      this.service.teamProjects(team.id, this.defaultUser) mustEqual List.empty
    }

    "list the challenges given to a team" taggedAs TeamTag in {
      val team = this.ownedTeam("teamChallenges")
      val challenge = this.challengeDAL.insert(
        this
          .getTestChallenge("TeamServiceSpec_ownedChallenge")
          .copy(extra = ChallengeExtra(ownerTeamId = Some(team.id))),
        User.superUser
      )

      val challenges = this.service.teamChallenges(team.id, User.superUser)
      challenges.map(_.id) mustEqual List(challenge.id)
    }

    "list no challenges for a team that owns none" taggedAs TeamTag in {
      val team = this.ownedTeam("teamChallengesEmpty")
      this.service.teamChallenges(team.id, User.superUser) mustEqual List.empty
    }

    "refuse to list the contents of a team that does not exist" taggedAs TeamTag in {
      intercept[NotFoundException] {
        this.service.teamProjects(-1000, this.defaultUser)
      }
      intercept[NotFoundException] {
        this.service.teamChallenges(-1000, this.defaultUser)
      }
    }
  }

  /** A team the default user owns, named for the test that asked for it. */
  private def ownedTeam(label: String): Group =
    this.service
      .create(
        this.getTestTeam(s"TeamServiceSpec_$label"),
        MemberObject.user(this.defaultUser.id),
        this.defaultUser
      )
      .get

  /**
    * Re-read a user so their grants reflect role changes made during the test
    * rather than whatever the cached copy was created with.
    */
  private def freshUser(user: User): User = this.serviceManager.user.retrieve(user.id).get

  override implicit val projectTestName: String = "TeamServiceSpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    randomUser = this.serviceManager.user.create(
      this.getTestUser(22212345, "RandomOUser"),
      User.superUser
    )
    anotherUser = this.serviceManager.user.create(
      this.getTestUser(22298765, "AnotherUser"),
      User.superUser
    )
    defaultTeam = this.serviceManager.team
      .create(
        this.getTestTeam("TeamServiceSpec_Team A"),
        MemberObject.user(defaultUser.id),
        defaultUser
      )
      .get
  }
}

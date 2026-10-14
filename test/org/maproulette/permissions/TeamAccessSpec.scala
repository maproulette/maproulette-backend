/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.permissions

import org.maproulette.framework.model.{
  Challenge,
  Grant,
  Group,
  MemberObject,
  Project,
  TeamMember,
  TeamRole,
  User
}
import org.maproulette.framework.util.{FrameworkHelper, TeamTag}
import play.api.Application

/**
  * What a team actually confers on the work it is associated with.
  *
  * Every route to a project or challenge now resolves through
  * Permission.effectiveRole, and these cover the team-shaped ones: the team
  * that owns the work, and a team merely attached to it. Both hand a member the
  * role they hold in the team, and neither lets a plain member in.
  */
class TeamAccessSpec(implicit val application: Application) extends FrameworkHelper {
  private var teamUser: User = null

  "A team attached to a project" should {
    "hand an admin of the team admin on the project" taggedAs TeamTag in {
      val (project, _) = attachedTeam("attach_admin", TeamRole.ADMIN)

      permission.effectiveRole(project, fresh(teamUser)) mustEqual Some(Grant.ROLE_ADMIN)
    }

    "hand a manager of the team write, not admin" taggedAs TeamTag in {
      val (project, _) = attachedTeam("attach_manager", TeamRole.MANAGER)

      permission.effectiveRole(project, fresh(teamUser)) mustEqual Some(Grant.ROLE_WRITE_ACCESS)
    }

    "hand a plain member of the team nothing at all" taggedAs TeamTag in {
      val (project, _) = attachedTeam("attach_member", TeamRole.MEMBER)

      permission.effectiveRole(project, fresh(teamUser)) mustEqual None
    }

    "hand nothing to someone who is not on the team" taggedAs TeamTag in {
      val (project, _) = attachedTeam("attach_stranger", TeamRole.ADMIN)

      permission.effectiveRole(project, fresh(this.stranger)) mustEqual None
    }

    "ignore whatever role the grant itself was created with" taggedAs TeamTag in {
      // The grant says read; the member is a team admin, and that is what counts.
      val (project, _) = attachedTeam("attach_ignores_grant", TeamRole.ADMIN, Grant.ROLE_READ_ONLY)

      permission.effectiveRole(project, fresh(teamUser)) mustEqual Some(Grant.ROLE_ADMIN)
    }
  }

  "A team that owns a project" should {
    "hand its managers the same role attachment would" taggedAs TeamTag in {
      val team    = ownedTeam("owns_project")
      val project = teamProject("owned_project", ownerTeamId = Some(team.id))
      joinTeam(team, TeamRole.MANAGER)

      permission.effectiveRole(project, fresh(teamUser)) mustEqual Some(Grant.ROLE_WRITE_ACCESS)
    }

    "still hand a plain member nothing" taggedAs TeamTag in {
      val team    = ownedTeam("owns_project_member")
      val project = teamProject("owned_project_member", ownerTeamId = Some(team.id))
      joinTeam(team, TeamRole.MEMBER)

      permission.effectiveRole(project, fresh(teamUser)) mustEqual None
    }
  }

  "A team attached to a challenge" should {
    "reach that challenge without reaching its project" taggedAs TeamTag in {
      val team      = ownedTeam("attach_challenge")
      val project   = teamProject("challenge_host", ownerTeamId = None)
      val challenge = challengeIn(project, "attached_challenge")
      joinTeam(team, TeamRole.MANAGER)
      this.serviceManager.team
        .addTeamToChallenge(team.id, challenge.id, Grant.ROLE_WRITE_ACCESS, User.superUser)

      permission.effectiveRole(challenge, fresh(teamUser)) mustEqual Some(Grant.ROLE_WRITE_ACCESS)
      // The project it sits in stays out of reach, which is the point of
      // granting on the challenge rather than the project.
      permission.effectiveRole(project, fresh(teamUser)) mustEqual None
    }
  }

  "A challenge with no team of its own" should {
    "inherit whatever the user holds on its project" taggedAs TeamTag in {
      val team      = ownedTeam("inherits")
      val project   = teamProject("inherited_project", ownerTeamId = Some(team.id))
      val challenge = challengeIn(project, "inherited_challenge")
      joinTeam(team, TeamRole.ADMIN)

      permission.effectiveRole(challenge, fresh(teamUser)) mustEqual Some(Grant.ROLE_ADMIN)
    }
  }

  "An invitation not yet accepted" should {
    "confer nothing, even though the grant already exists" taggedAs TeamTag in {
      val team    = ownedTeam("invited_only")
      val project = teamProject("invited_project", ownerTeamId = Some(team.id))

      // An invite writes the grant immediately, so anything reading grants
      // without checking membership status would let this user straight in.
      this.serviceManager.team.addTeamMember(
        team,
        MemberObject.user(teamUser.id),
        TeamRole.ADMIN,
        TeamMember.STATUS_INVITED,
        this.defaultUser
      )

      permission.effectiveRole(project, fresh(teamUser)) mustEqual None
    }
  }

  // --- helpers ---------------------------------------------------------------

  /** A project owned by nobody unless told otherwise, outside the default tree. */
  private def teamProject(label: String, ownerTeamId: Option[Long]): Project =
    this.serviceManager.project
      .create(
        Project(
          -1,
          this.defaultUser.osmProfile.id,
          s"TeamAccessSpec_$label",
          ownerTeamId = ownerTeamId
        ),
        this.defaultUser
      )

  private def challengeIn(project: Project, label: String): Challenge =
    this.challengeDAL.insert(
      this.getTestChallenge(s"TeamAccessSpec_$label", project.id),
      User.superUser
    )

  private def ownedTeam(label: String): Group =
    this.serviceManager.team
      .create(
        this.getTestTeam(s"TeamAccessSpec_$label"),
        MemberObject.user(this.defaultUser.id),
        this.defaultUser
      )
      .get

  private def joinTeam(team: Group, role: Int): Unit =
    this.serviceManager.team.addTeamMember(
      team,
      MemberObject.user(teamUser.id),
      role,
      TeamMember.STATUS_MEMBER,
      this.defaultUser
    )

  /**
    * A project with a team attached at the given team role, and the grant made
    * at `grantRole` to prove that value plays no part.
    */
  private def attachedTeam(
      label: String,
      teamRole: Int,
      grantRole: Int = Grant.ROLE_ADMIN
  ): (Project, Group) = {
    val team    = ownedTeam(label)
    val project = teamProject(label, ownerTeamId = None)
    joinTeam(team, teamRole)
    this.serviceManager.team.addTeamToProject(team.id, project.id, grantRole, User.superUser)
    (project, team)
  }

  /** Re-read a user so their grants reflect roles handed out during the test. */
  private def fresh(user: User): User = this.serviceManager.user.retrieve(user.id).get

  private var strangerUser: User = null
  private def stranger: User     = strangerUser

  override implicit val projectTestName: String = "TeamAccessSpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    teamUser = this.serviceManager.user.create(
      this.getTestUser(51234567, "TeamAccessMember"),
      User.superUser
    )
    strangerUser = this.serviceManager.user.create(
      this.getTestUser(51234568, "TeamAccessStranger"),
      User.superUser
    )
  }
}

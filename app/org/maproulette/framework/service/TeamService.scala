/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.maproulette.exception.{InvalidException, NotFoundException}
import org.maproulette.framework.model._
import org.maproulette.data.{Actions, UserType, GroupType, ProjectType}
import org.maproulette.framework.psql._
import org.maproulette.framework.psql.filter._
import org.maproulette.provider.websockets.{WebSocketMessages, WebSocketProvider}
import org.maproulette.permissions.Permission

/**
  * Service for handling teams, which are really just groups
  *
  * @author nrotstan
  */
@Singleton
class TeamService @Inject() (
    groupService: GroupService,
    grantService: GrantService,
    serviceManager: ServiceManager,
    webSocketProvider: WebSocketProvider,
    permission: Permission
) extends ServiceMixin[Group] {

  /**
    * Query teams. Note that this version runs as the guest user
    */
  override def query(query: Query): List[Group] = this.query(query, User.guestUser)

  /**
    * Query teams
    *
    * @param user The user making the request
    */
  def query(query: Query, user: User): List[Group] = {
    // Everyone has read access to teams, so no need to check permissions
    this.groupService.query(
      query.addFilterGroup(
        FilterGroup(
          List(BaseParameter(Group.FIELD_GROUP_TYPE, Group.GROUP_TYPE_TEAM))
        )
      )
    )
  }

  /**
    * Retrieves a single team based on an id. Note that this version runs as
    * the guest user
    *
    * @param id The id of the team
    */
  def retrieve(id: Long): Option[Group] = this.retrieve(id, User.guestUser)

  /**
    * Retrieves a single team based on an id
    *
    * @param id   The id of the team
    * @param user The user making the request
    */
  def retrieve(id: Long, user: User): Option[Group] = {
    // Everyone has read access to teams, so no need to check permissions
    this
      .query(
        Query.simple(List(BaseParameter(Group.FIELD_ID, id)))
      )
      .headOption
  }

  /**
    * Retrieves all teams matching the ids
    */
  def list(ids: List[Long], user: User): List[Group] =
    // Everyone has read access to teams, so no need to check permissions
    this.groupService.list(
      ids,
      Query.simple(List(BaseParameter(Group.FIELD_GROUP_TYPE, Group.GROUP_TYPE_TEAM)))
    )

  /**
    * Retrieves a single team based on a team name
    *
    * @param id   The name of the team
    * @param user The user making the request
    */
  def retrieveByName(name: String, user: User): Option[Group] =
    this.groupService.retrieveByName(
      name,
      Query.simple(List(BaseParameter(Group.FIELD_GROUP_TYPE, Group.GROUP_TYPE_TEAM)))
    )

  /**
    * Search for teams matching the given search criteria
    *
    * @param nameFragment team name fragment to match
    * @param user         The user making the request
    */
  def search(nameFragment: String, paging: Paging, user: User): List[Group] = {
    // Everyone has read access to teams, so no need to check permissions
    this.groupService.search(
      nameFragment,
      Query.simple(
        List(
          BaseParameter(Group.FIELD_GROUP_TYPE, Group.GROUP_TYPE_TEAM)
        ),
        paging = paging
      )
    )
  }

  /**
    * Create a new team
    *
    * @param team  The team to create
    * @param admin The initial administrator of the team
    * @param user  The user creating team
    * @return The newly created team
    */
  def create(team: Group, admin: MemberObject, user: User): Option[Group] = {
    // Anyone can create a team
    this.groupService.create(team.copy(groupType = Group.GROUP_TYPE_TEAM)) match {
      case Some(createdTeam) =>
        this.addTeamMember(
          createdTeam,
          admin,
          Grant.ROLE_ADMIN,
          TeamMember.STATUS_MEMBER,
          User.superUser
        )
        Some(createdTeam)
      case None => None
    }
  }

  /**
    * Retrieve all members of a team regardless of status
    *
    * @param team The team for which members are desired
    * @param user The user making the request
    */
  def teamMembers(team: Group, user: User): List[GroupMember] = {
    // Everyone has read access to teams, so no need to check permissions
    this.ensureTeam(team)
    this.groupService.groupMembersForGroupIds(List(team.id))
  }

  /**
    * Retrieve memberships in all teams for given user member ids
    *
    * @param memberIds The ids of the member objects (NOT users!) representing the team users
    * @param user The user making the request
    * @return TeamUser representations of the memberships
    */
  def listTeamUsers(memberIds: List[Long], user: User): List[TeamUser] = {
    // Everyone has read access to teams, so no need to check permissions
    if (memberIds.isEmpty) {
      return List()
    }

    val members = this.groupService.listGroupMembers(memberIds)
    this.memberUsers(members, user)
  }

  /**
    * Retrieve TeamUser representation of all member users on a team
    *
    * @param teamIds ids of teams for which members are desired
    * @param user    The user making the request
    */
  def teamUsersByTeamIds(teamIds: List[Long], user: User): List[TeamUser] = {
    // Everyone has read access to teams, so no need to check permissions
    if (teamIds.isEmpty) {
      return List()
    }

    val members = this.groupService.groupMembersForGroupIds(teamIds)
    this.memberUsers(members, user)
  }

  /**
    * Retrieve active members of a team granted admin role on the team
    *
    * @param team The team for which admins are desired
    * @param user The user making the request
    */
  def teamAdmins(team: Group, user: User): List[GroupMember] = {
    // Everyone has read access to teams, so no need to check permissions
    this.ensureTeam(team)
    val adminMemberIds = this.grantService
      .retrieveMatchingGrants(
        role = Some(Grant.ROLE_ADMIN),
        target = Some(GrantTarget.group(team.id)),
        user = User.superUser
      )
      .map(grant => grant.grantee.granteeId)

    this.groupService.groupMembers(
      team,
      Query.simple(
        List(
          BaseParameter(GroupMember.FIELD_MEMBER_ID, adminMemberIds, Operator.IN),
          BaseParameter(GroupMember.FIELD_STATUS, TeamMember.STATUS_INVITED, Operator.NE)
        )
      )
    )
  }

  /**
    * Retrieve TeamUser representations of the given user members
    *
    * @param members  List of GroupMembers for which User objects are desired
    * @param user     The user making the request
    */
  def memberUsers(
      members: List[GroupMember],
      user: User
  ): List[TeamUser] = {
    if (members.isEmpty) {
      return List()
    }

    val userType    = UserType().typeId
    val userMembers = members.filter(member => member.memberType == userType)
    val userIds     = userMembers.map(member => member.memberId)
    val users = this.serviceManager.user.query(
      Query.simple(
        List(BaseParameter(User.FIELD_ID, userIds, Operator.IN))
      ),
      user
    )

    userMembers
      .map(member => {
        users.find(u => u.id == member.memberId) match {
          case Some(user) => Some(TeamUser.fromUser(member.groupId, member, user))
          case None       => None
        }
      })
      .flatten
  }

  /**
    * Retrieve TeamUser representations of all user members on a team
    *
    * @param teamId The id of the team for which members are desired
    * @param user   The user making the request
    */
  def teamUsers(teamId: Long, user: User): List[TeamUser] = {
    this.retrieve(teamId, user) match {
      case Some(team) =>
        this.memberUsers(this.teamMembers(team, user), user)
      case None =>
        throw new NotFoundException(s"No team with id ${teamId} found")
    }
  }

  /**
    * Add a member to a team with a specific role
    *
    * @param team   The team on which to add the member
    * @param member The member to be added to the team
    * @param role   The member's role
    * @param status The member's status on the team, defaults to INVITED
    * @param user   The user making the request
    * @return       The new GroupMember
    */
  def addTeamMember(
      team: Group,
      member: MemberObject,
      role: Int,
      status: Int = TeamMember.STATUS_INVITED,
      user: User
  ): Option[GroupMember] = {
    // Only team admin can add members to a team
    this.ensureTeam(team)
    this.permission.hasObjectAdminAccess(team, user)

    val addedMember = this.groupService.addGroupMember(team, member, status)
    this.grantTeamRole(team, role, member)

    webSocketProvider.sendMessage(
      WebSocketMessages.teamUpdate(
        WebSocketMessages.TeamUpdateData(team.id, Some(member.objectId))
      )
    )
    this.serviceManager.user.clearCache(member.objectId)

    if (status == TeamMember.STATUS_INVITED) {
      this.serviceManager.notification.createTeamInviteNotification(
        user,
        member.objectId,
        team
      )
    }
    addedMember
  }

  /**
    * Invite a user to join a team
    *
    * @param teamId The id of the team to which the member should be invited
    * @param userId The member to be invited to the team
    * @param role   The member's role should they accept the invitation
    * @param user   The user making the request
    * @return       The new TeamUser
    */
  def inviteTeamUser(teamId: Long, userId: Long, role: Int, user: User): Option[TeamUser] = {
    val team = this.retrieve(teamId, user) match {
      case Some(t) => t
      case None =>
        throw new NotFoundException(s"No team with id $teamId found")
    }

    val invitee = this.serviceManager.user.retrieve(userId) match {
      case Some(u) => u
      case None =>
        throw new NotFoundException(s"No user with id $userId found")
    }

    this.addTeamMember(team, MemberObject.user(userId), role, TeamMember.STATUS_INVITED, user) match {
      case Some(member) => Some(TeamUser.fromUser(team.id, member, invitee))
      case None         => None
    }
  }

  /**
    * Accept an invitation to join a team
    *
    * @param teamId The id of the team the member is joining
    * @param member The member accepting the invitation
    * @param user   The user making the request
    * @return       The updated GroupMember
    */
  def acceptInvitation(teamId: Long, member: MemberObject, user: User): Option[GroupMember] = {
    this.retrieve(teamId, user) match {
      case Some(team) =>
        this.getTeamMember(team, member, user) match {
          case Some(teamMember) =>
            if (teamMember.status != TeamMember.STATUS_INVITED) {
              throw new InvalidException("Invitation has already been accepted")
            }
            this.updateMemberStatus(team, member, TeamMember.STATUS_MEMBER, User.superUser)
          case None =>
            throw new NotFoundException("No open invitation found")
        }
      case None =>
        throw new NotFoundException(s"No team with id $teamId found")
    }
  }

  /**
    * Accept an invitation made to user to join a team
    *
    * @param teamId The id of the team the member is joining
    * @param userId The id of the user member accepting the invitation
    * @param user   The user making the request
    * @return       The updated TeamUser
    */
  def acceptUserInvitation(teamId: Long, userId: Long, user: User): Option[TeamUser] = {
    val invitee = this.serviceManager.user.retrieve(userId) match {
      case Some(u) => u
      case None =>
        throw new NotFoundException(s"No user with id $userId found")
    }

    this.acceptInvitation(teamId, MemberObject.user(userId), user) match {
      case Some(member) => Some(TeamUser.fromUser(teamId, member, invitee))
      case None         => None
    }
  }

  /**
    * Decline an invitation to join a team
    *
    * @param teamId The id of the team the member was invited to join
    * @param member The member declining the invitation
    * @param user   The user making the request
    */
  def declineInvitation(teamId: Long, member: MemberObject, user: User): Boolean = {
    this.retrieve(teamId, user) match {
      case Some(team) =>
        this.getTeamMember(team, member, user) match {
          case Some(teamMember) if (teamMember.status == TeamMember.STATUS_INVITED) =>
            this.removeTeamMember(team, member, User.superUser)
          case _ =>
            throw new NotFoundException("No open invitation found")
        }
      case None =>
        throw new NotFoundException(s"No team with id $teamId found")
    }
  }

  /**
    * Retrieve a GroupMember object representing the given team member
    *
    * @param team   The desired team
    * @param member The member to retrieve
    * @param user   The user making the request
    */
  def getTeamMember(team: Group, member: MemberObject, user: User): Option[GroupMember] = {
    // Everyone has read access to teams, so no need to check permissions
    this.ensureTeam(team)
    this.groupService.getGroupMember(team, member)
  }

  /**
    * Remove a member from a team
    *
    * @param team   The team from which to remove the member
    * @param member The member to be removed from the team
    * @param user   The user performing the removal
    */
  def removeTeamMember(team: Group, member: MemberObject, user: User): Boolean = {
    this.ensureTeam(team)
    // Only team admin (or the member itself) can remove a member from a team
    if (MemberObject.user(user.id) != member) {
      this.permission.hasObjectAdminAccess(team, user)
    }

    // Don't let the last admin get removed from the team
    this.ensureNotLastAdmin(team, member)

    this.groupService.removeGroupMember(team, member)
    this.clearTeamRoles(team, member)

    webSocketProvider.sendMessage(
      WebSocketMessages.teamUpdate(
        WebSocketMessages.TeamUpdateData(team.id, Some(member.objectId))
      )
    )
    this.serviceManager.user.clearCache(member.objectId)
    true
  }

  /**
    * Update the role granted to a team member on a team
    *
    * @param team   The team to which the member belongs
    * @param member The member whose role is to be updated
    * @param role   The new role
    * @param user   The user making the request
    * @return       The new TeamMember
    */
  def updateMemberRole(team: Group, member: MemberObject, role: Int, user: User): Boolean = {
    // Only a team admin can update the role of a member
    this.ensureTeam(team)
    this.permission.hasObjectAdminAccess(team, user)

    // Make sure the member is actually on the team before we update their role
    if (this.getTeamMember(team, member, user) == None) {
      throw new InvalidException(s"Cannot update role on team for non-member")
    }

    // Don't let the last admin get demoted
    if (Grant.hasLesserPrivilege(role, Grant.ROLE_ADMIN)) {
      this.ensureNotLastAdmin(team, member)
    }
    this.setTeamRole(team, role, member)

    webSocketProvider.sendMessage(
      WebSocketMessages.teamUpdate(
        WebSocketMessages.TeamUpdateData(team.id, Some(member.objectId))
      )
    )
    true
  }

  /**
    * Update the role granted to a user member on a team
    *
    * @param teamId The id of the team to which the member belongs
    * @param userId The id of the user member whose role is to be updated
    * @param role   The new role
    * @param user   The user making the request
    * @return       The updated TeamUser
    */
  def updateUserRole(teamId: Long, userId: Long, role: Int, user: User): TeamUser = {
    val team = this.retrieve(teamId, user) match {
      case Some(t) => t
      case None    => throw new NotFoundException(s"No team with id $teamId found")
    }

    this.updateMemberRole(team, MemberObject.user(userId), role, user)

    // Fetch the updated member and user data
    val member = getTeamMember(team, MemberObject.user(userId), user) match {
      case Some(m) => m
      case None =>
        throw new NotFoundException(s"No membership on team $teamId for user $userId found")
    }

    this.serviceManager.user.retrieve(userId) match {
      case Some(u) => TeamUser.fromUser(teamId, member, u)
      case None =>
        throw new NotFoundException(s"No user with id $userId found")
    }
  }

  /**
    * Update the status of a team member
    *
    * @param team   The team to which the member belongs
    * @param member The member whose role is to be updated
    * @param status The new status
    * @param user   The user making the request
    * @return       The updated GroupMember
    */
  def updateMemberStatus(
      team: Group,
      member: MemberObject,
      status: Int,
      user: User
  ): Option[GroupMember] = {
    // Only a team admin can update the status of a member
    this.ensureTeam(team)
    this.permission.hasObjectAdminAccess(team, user)
    val updatedMember = this.groupService.updateGroupMemberStatus(team, member, status)

    webSocketProvider.sendMessage(
      WebSocketMessages.teamUpdate(
        WebSocketMessages.TeamUpdateData(team.id, Some(member.objectId))
      )
    )
    updatedMember
  }

  /**
    * Retrieve a list of TeamUser instances representing all of the team
    * memberships for each given user
    *
    * @param userIds The ids of the users for which team memberships are desired
    * @param user    The user making the request
    */
  def teamUsersByUserIds(userIds: List[Long], user: User): List[TeamUser] = {
    if (userIds.isEmpty) {
      return List()
    }

    val users = this.serviceManager.user.retrieveListById(userIds)
    val userMembers =
      this.groupService.getMembershipsForMembers(UserType().typeId, userIds)
    val teams = this.list(userMembers.map(_.groupId), user)

    userMembers
      .map(member => {
        teams.find(t => t.id == member.groupId) match {
          case Some(team) =>
            users.find(u => u.id == member.memberId) match {
              case Some(user) => Some(TeamUser.fromUser(member.groupId, member, user))
              case None       => None
            }
          case None => None
        }
      })
      .flatten
  }

  /**
    * Retrieves list of team ids to which a user has membership
    *
    * @param userId The user for which member teams are desired
    * @param user The user making the request
    */
  def teamIdsByUser(userId: Long, user: User): List[Long] =
    this.grantService
      .retrieveGrantsTo(Grantee.user(userId), User.superUser)
      .filter(_.target.objectType == GroupType())
      .map(_.target.objectId)

  /**
    * Retrieves a list of grants on projects assigned to the given teams
    *
    * @param teamIds The ids of the teams for which managed project ids are desired
    * @param user The user making the request
    */
  def projectGrantsForTeams(teamIds: List[Long], user: User): List[Grant] = {
    if (teamIds.isEmpty) {
      return List.empty
    }

    this.grantService
      .retrieveMatchingGrants(
        grantee = Some(teamIds.map(id => Grantee.group(id))),
        user = User.superUser
      )
      .filter(_.target.objectType == ProjectType())
  }

  /**
    * Retrieves list of project ids managed by the given user id through
    * their team getMemberships
    * @param userId The user for which managed project ids are desired
    * @param user The user making the request
    */
  def projectGrantsForUser(userId: Long, user: User): List[Grant] =
    this.projectGrantsForTeams(this.teamIdsByUser(userId, user), user)

  /**
    * Retrieves list of project ids managed by the given user id through
    * their team getMemberships
    * @param userId The user for which managed project ids are desired
    * @param user The user making the request
    */
  def managedProjectIdsForUser(userId: Long, user: User): List[Long] =
    this.projectGrantsForUser(userId, user).map(_.target.objectId)

  /**
    * Determines if a member has membership in the given team and an active
    * status (i.e. has not merely been invited to join)
    *
    * @param team   The team
    * @param member The member to test for membership
    * @param user   The user making the request
    */
  def isActiveTeamMember(team: Group, member: MemberObject, user: User): Boolean =
    this
      .teamMembers(team, user)
      .exists(m => m.status != TeamMember.STATUS_INVITED && m.asMemberObject() == member)

  /**
    * Determines if a member has been granted a role on the team
    *
    * @param team   The team
    * @param role   The role to test for
    * @param member The member to test for a role
    * @param user   The user making the request
    */
  def hasTeamRole(team: Group, role: Int, member: MemberObject, user: User) = {
    val grants = this.grantService.retrieveMatchingGrants(
      grantee = Some(List(Grantee(Actions.getItemType(member.objectType).get, member.objectId))),
      role = Some(role),
      target = Some(GrantTarget.group(team.id)),
      user = User.superUser
    )
    this.isActiveTeamMember(team, member, user) && !grants.isEmpty
  }

  /**
    * Determines if a member has been granted the admin role on a team
    *
    * @param team   The team
    * @param member The member to test for admin role on team
    * @param user   The user making the request
    */
  def isTeamAdmin(team: Group, member: MemberObject, user: User): Boolean = {
    this.hasTeamRole(team, Grant.ROLE_ADMIN, member, user)
  }

  /**
    * Determines if a user member has been granted the admin role on a team
    *
    * @param team       The team
    * @param memberUser The user member to test for admin role on team
    * @param user       The user making the request
    */
  def isUserTeamAdmin(team: Group, memberUser: User, user: User): Boolean =
    this.isTeamAdmin(team, MemberObject.user(memberUser.id), user)

  /**
    * Update a team
    *
    * @param team The latest team data
    * @param user The user updating the team
    */
  def updateTeam(team: Group, user: User): Option[Group] = {
    // Only a team admin can update a team
    this.ensureTeam(team)
    this.permission.hasObjectAdminAccess(team, user)
    val updatedGroup = this.groupService.updateGroup(team)

    webSocketProvider.sendMessage(
      WebSocketMessages.teamUpdate(
        WebSocketMessages.TeamUpdateData(team.id, None)
      )
    )
    updatedGroup
  }

  /**
    * Deletes a team from the database
    *
    * @param team The team to delete
    * @param user The user deleting the team
    * @return Boolean if delete was successful
    */
  def deleteTeam(team: Group, user: User): Boolean = {
    // Only a team admin can delete a team
    this.permission.hasObjectAdminAccess(team, user)
    this.groupService.deleteGroup(team)
    this.grantService.deleteMatchingGrants(
      target = Some(GrantTarget.group(team.id)),
      user = User.superUser
    )
    webSocketProvider.sendMessage(
      WebSocketMessages.teamUpdate(
        WebSocketMessages.TeamUpdateData(team.id, None)
      )
    )
    true
  }

  /**
    * Adds a team to a project. All members of the team will be indirectly
    * granted the given role on the project
    *
    * @param id        The ID of the team to add to the project
    * @param projectId The project that user is being added too
    * @param role      The type of role to add 1 - Admin, 2 - Write, 3 - Read
    * @param user      The user that is adding the user to the project
    */
  def addTeamToProject(
      id: Long,
      projectId: Long,
      role: Int,
      user: User,
      clear: Boolean = false
  ): Boolean = {
    if (!this.retrieve(id, user).isDefined) {
      throw new NotFoundException(s"No team with id ${id} found")
    }
    this.permission.hasProjectAccess(this.serviceManager.project.retrieve(projectId), user)

    if (clear) {
      this.grantService.deleteMatchingGrants(
        grantee = Some(Grantee.group(id)),
        target = Some(GrantTarget.project(projectId)),
        user = User.superUser
      )
    }

    val grant = this.grantService.createGrant(
      Grant(-1, "", Grantee.group(id), role, GrantTarget.project(projectId)),
      User.superUser
    )

    this.serviceManager.project.clearCache(projectId)
    this.serviceManager.user.clearCache()
    true
  }

  /**
    * Removes a team from a project
    *
    * @param id        The ID of the team to remove from the project
    * @param projectId The project from which the team is being removed
    * @param user      The user making the request
    */
  def removeTeamFromProject(id: Long, projectId: Long, user: User): Boolean = {
    if (!this.retrieve(id, user).isDefined) {
      throw new NotFoundException(s"No team with id ${id} found")
    }
    this.permission.hasProjectAccess(this.serviceManager.project.retrieve(projectId), user)

    this.grantService.deleteMatchingGrants(
      grantee = Some(Grantee.group(id)),
      target = Some(GrantTarget.project(projectId)),
      user = User.superUser
    )

    this.serviceManager.project.clearCache(projectId)
    this.serviceManager.user.clearCache()
    true
  }

  /**
    * Retrieve any teams granted a role on a project
    *
    * @param projectId The project for which teams are desired
    * @param user      The user making the request
    */
  def getTeamsManagingProject(projectId: Long, user: User): List[ManagingTeam] = {
    this.permission.hasObjectReadAccess(this.serviceManager.project.retrieve(projectId), user)

    val teamGrants = this.grantService
      .retrieveGrantsOn(
        GrantTarget.project(projectId),
        User.superUser
      )
      .filter(_.grantee.granteeType == GroupType())

    this
      .list(teamGrants.map(_.grantee.granteeId).distinct, user)
      .map(team =>
        ManagingTeam(
          team,
          teamGrants.filter(_.grantee.granteeId == team.id)
        )
      )
  }

  /**
    * Remove all granted roles to member on team
    */
  private def clearTeamRoles(team: Group, member: MemberObject) = {
    this.grantService.deleteMatchingGrants(
      grantee = Some(Grantee(Actions.getItemType(member.objectType).get, member.objectId)),
      target = Some(GrantTarget.group(team.id)),
      user = User.superUser
    )
    this.clearCachedMember(member)
  }

  /**
    * Grant role to member on team
    */
  private def grantTeamRole(team: Group, role: Int, member: MemberObject) = {
    this.grantService.createGrant(
      Grant(
        -1L,
        "",
        Grantee(Actions.getItemType(member.objectType).get, member.objectId),
        role,
        GrantTarget.group(team.id)
      ),
      User.superUser
    )
    this.clearCachedMember(member)
  }

  /**
    * Set member's role on team, clearing any prior roles
    */
  private def setTeamRole(team: Group, role: Int, member: MemberObject) = {
    this.clearTeamRoles(team, member)
    this.grantTeamRole(team, role, member)
  }

  /**
    * Clear member object from its cache so it gets refreshed from the database
    */
  private def clearCachedMember(member: MemberObject) = {
    member.objectType match {
      case Actions.ITEM_TYPE_USER =>
        this.serviceManager.user.clearCache(member.objectId)
      case _ => // Nothing to do
    }
  }

  /**
    * Ensure the given group actually represents a team, throwing an exception
    * if not
    */
  private def ensureTeam(team: Group) = {
    if (team.groupType != Group.GROUP_TYPE_TEAM) {
      throw new InvalidException(s"Group ${team.id} is not a team")
    }
  }

  /**
    * Ensure the current member is not the last administrator of the team,
    * throwing an exception if so. Useful for operations that would remove or
    * alter the role of an existing administrator
    */
  private def ensureNotLastAdmin(team: Group, member: MemberObject) = {
    val admins = this.teamAdmins(team, User.superUser)
    if (admins.size == 1 && admins.head.asMemberObject() == member) {
      throw new InvalidException(
        "Teams must have at least one administrator"
      )
    }
  }
}

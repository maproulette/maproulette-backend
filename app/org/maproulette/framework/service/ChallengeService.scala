/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.maproulette.data.UserType
import org.maproulette.exception.{InvalidException, NotFoundException}
import org.maproulette.permissions.Permission
import org.maproulette.framework.model.{
  ArchivableChallenge,
  ArchivableTask,
  Challenge,
  ChallengeManager,
  Grant,
  Grantee,
  GrantTarget,
  Project,
  User
}
import org.maproulette.framework.psql.{OR, Paging, Query}
import org.maproulette.framework.psql.filter._
import org.maproulette.framework.repository.ChallengeRepository

/**
  * Service layer for Challenges to handle all the challenge business logic
  *
  * @author mcuthbert
  */
@Singleton
class ChallengeService @Inject() (
    repository: ChallengeRepository,
    grantService: GrantService,
    serviceManager: ServiceManager,
    permission: Permission
) extends ServiceMixin[Challenge] {

  def retrieve(id: Long): Option[Challenge] =
    this.query(Query.simple(List(BaseParameter(Challenge.FIELD_ID, id)))).headOption

  def list(ids: List[Long], paging: Paging = Paging()): List[Challenge] = {
    if (ids.isEmpty) {
      return List()
    }

    this.query(
      Query.simple(
        List(
          BaseParameter(Challenge.FIELD_ID, ids, Operator.IN)
        ),
        paging = paging
      )
    )
  }

  def query(query: Query): List[Challenge] = this.repository.query(query)

  /**
    * Returns a FilterGroup that creates a subquery to filter challengs
    * to include only ones visible to the user. This means the user is
    * a superuser or owner of the parent Project, or:
    *  - Both Challenge and parent Project are enabled
    *  - User is granted a role on the parent Project
    *
    * @param - User to check visibility for.
    */
  def challengeVisibilityFilter(user: User): FilterGroup = {
    FilterGroup(
      List(
        SubQueryFilter(
          "",
          Query.simple(
            List(
              BaseParameter(Project.FIELD_ENABLED, "", Operator.BOOL, table = Some(Project.TABLE)),
              BaseParameter(
                Challenge.FIELD_ENABLED,
                "",
                Operator.BOOL,
                table = Some(Challenge.TABLE)
              )
            ),
            includeWhere = false
          ),
          operator = Operator.CUSTOM
        ),
        BaseParameter(
          Challenge.FIELD_PARENT_ID,
          user.managedProjectIds(),
          Operator.IN,
          table = Some(Challenge.TABLE)
        )
      ),
      OR(),
      !permission.isSuperUser(user)
    )
  }

  /**
    * The users granted a role on this challenge directly, as opposed to those
    * who reach it through the parent project or the team that owns it.
    *
    * @param challengeId The challenge whose managers are desired
    * @param user        The user making the request
    */
  def challengeManagers(challengeId: Long, user: User): List[ChallengeManager] = {
    val challenge = this.requireChallenge(challengeId)
    this.permission.hasObjectReadAccess(challenge, user)

    val grants = this.grantService
      .retrieveGrantsOn(GrantTarget.challenge(challengeId), User.superUser)
      .filter(_.grantee.granteeType == UserType())

    if (grants.isEmpty) {
      return List.empty
    }

    val usersById = this.serviceManager.user
      .retrieveListById(grants.map(_.grantee.granteeId).distinct)
      .map(u => u.id -> u)
      .toMap

    grants.flatMap { grant =>
      usersById
        .get(grant.grantee.granteeId)
        .map(granted => ChallengeManager(granted.id, granted.name, grant.role))
    }
  }

  /**
    * Grants a user a role on a single challenge, reaching where the parent
    * project's grants do not. Any role the user already held on the challenge
    * is cleared first, so a user holds one role on a challenge at a time.
    *
    * @param challengeId The challenge to grant the role on
    * @param userId      The user receiving the role
    * @param role        The role to grant
    * @param user        The user making the request
    */
  def addUserToChallenge(challengeId: Long, userId: Long, role: Int, user: User): Unit = {
    val challenge = this.requireChallenge(challengeId)
    // Handing out a role on a challenge is itself an administrative act, so it
    // takes admin on the challenge rather than mere write access.
    this.permission.hasObjectWriteAccess(challenge, user, Grant.ROLE_ADMIN)

    if (!List(Grant.ROLE_ADMIN, Grant.ROLE_WRITE_ACCESS, Grant.ROLE_READ_ONLY).contains(role)) {
      throw new InvalidException(s"Invalid role $role")
    }

    val granted = this.serviceManager.user.retrieve(userId) match {
      case Some(u) => u
      case None    => throw new NotFoundException(s"No user with id $userId found")
    }

    this.removeUserFromChallenge(challengeId, granted.id, user)
    this.grantService.createGrant(
      Grant(
        -1,
        s"Challenge $challengeId manager",
        Grantee.user(granted.id),
        role,
        GrantTarget.challenge(challengeId)
      ),
      User.superUser
    )

    // A user carries their grants on the cached copy of themselves, so the new
    // role does not reach them until that copy is thrown away.
    this.serviceManager.user.clearCache(granted.id)
  }

  /**
    * Clears any role a user was granted directly on a challenge. Roles they
    * hold through the parent project or an owning team are untouched.
    *
    * @param challengeId The challenge to revoke the role on
    * @param userId      The user losing the role
    * @param user        The user making the request
    */
  def removeUserFromChallenge(challengeId: Long, userId: Long, user: User): Unit = {
    val challenge = this.requireChallenge(challengeId)
    this.permission.hasObjectWriteAccess(challenge, user, Grant.ROLE_ADMIN)

    this.grantService.deleteMatchingGrants(
      grantee = Some(Grantee.user(userId)),
      target = Some(GrantTarget.challenge(challengeId)),
      user = User.superUser
    )

    // As above: the revoked role lingers on the cached user until it is cleared.
    this.serviceManager.user.clearCache(userId)
  }

  private def requireChallenge(challengeId: Long): Challenge =
    this.retrieve(challengeId) match {
      case Some(c) => c
      case None    => throw new NotFoundException(s"No challenge with id $challengeId found")
    }

  /**
    * Retrieve a list of active challenges
    * @param archived: include archived if true
    * @return list of challenges
    */
  def activeChallenges(archived: Boolean = false): List[ArchivableChallenge] = {
    this.repository.activeChallenges(archived);
  }

  /**
    * Retrieve a list of tasks by challenge id
    * @param challengeId
    * @return list of tasks
    */
  def getTasksByParentId(id: Long): List[ArchivableTask] = {
    this.repository.getTasksByParentId(id);
  }

  /**
    * update challenge archive status
    * @param challengeId
    * @param archiving boolean indicating if you are archiving or unarchiving
    * @param systemArchive boolean indicating if system is performing this
    */
  def archiveChallenge(
      challengeId: Long,
      archiving: Boolean = true,
      systemArchive: Boolean = false
  ): Boolean = {
    val result = this.repository.archiveChallenge(challengeId, archiving, systemArchive)
    result
  }
}

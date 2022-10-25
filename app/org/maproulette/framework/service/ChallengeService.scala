/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.maproulette.permissions.Permission
import org.maproulette.framework.model.{
  ArchivableChallenge,
  ArchivableTask,
  Challenge,
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

  /**
    * update challenge completion metrics
    * @param challengeId
    * @param tasksRemaining
    * @param completionPercentage
    */
  def updateChallengeCompletionMetrics(
      challengeId: Long,
      tasksRemaining: Integer,
      completionPercentage: Integer
  ): Unit = {
    this.repository
      .updateChallengeCompletionMetrics(challengeId, tasksRemaining, completionPercentage);
  }
}

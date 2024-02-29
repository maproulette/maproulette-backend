/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model._
import org.maproulette.framework.psql.{Query, _}
import org.maproulette.framework.psql.filter.{BaseParameter, _}
import org.maproulette.framework.repository.ChallengeListingRepository
import org.maproulette.permissions.Permission

/**
  * Service layer for ChallengeListings to handle all the challenge listing business logic
  *
  * @author krotstan
  */
@Singleton
class ChallengeListingService @Inject() (
    repository: ChallengeListingRepository,
    challengeService: ChallengeService,
    permission: Permission
) extends ServiceMixin[ChallengeListing] {
  def retrieve(parentId: Long): Option[ChallengeListing] =
    this.query(Query.simple(List(BaseParameter(Challenge.FIELD_PARENT_ID, parentId)))).headOption

  def emptyFilterGroup: FilterGroup = FilterGroup(List.empty)

  /**
    * Returns a list of challenges that have reviews/review requests.
    *
    * @param reviewTasksType       The type of reviews (1: To Be Reviewed, 2: User's reviewed Tasks, 3: All reviewed by users)
    * @param user                  The user making request (for challenge permission visibility)
    * @param taskStatus            The task statuses to include
    * @param excludeOtherReviewers Whether tasks completed by other reviewers should be included
    * @param challengeSearchQuery  Search query for filtering challenges
    * @param projectSearchQuery    Search query for filtering projects
    * @param paging                Paging information
    * @return A list of children listing objects
    */
  def withReviewList(
      reviewTasksType: Int,
      user: User,
      taskStatus: Option[List[Int]] = None,
      excludeOtherReviewers: Boolean = false,
      challengeSearchQuery: String = "",
      projectSearchQuery: String = "",
      paging: Paging = Paging()
  ): List[ChallengeListing] = {
    val challengeNameParameter =
      if (challengeSearchQuery.nonEmpty) {
        Some(
          BaseParameter(
            Challenge.FIELD_NAME,
            SQLUtils.search(challengeSearchQuery),
            Operator.ILIKE,
            table = Some(Challenge.TABLE)
          )
        )
      } else None

    val projectNameParameter =
      if (projectSearchQuery.nonEmpty) {
        Some(
          BaseParameter(
            Project.FIELD_DISPLAY_NAME,
            SQLUtils.search(projectSearchQuery),
            Operator.ILIKE,
            table = Some(Project.TABLE)
          )
        )
      } else None

    val parameters = List(challengeNameParameter, projectNameParameter).flatten
    val filterGroup =
      if (parameters.nonEmpty) FilterGroup(parameters)
      else emptyFilterGroup

    val taskReviewFilterGroup = FilterGroup(
      List(
        // Has a task review
        BaseParameter(
          TaskReview.FIELD_ID,
          "",
          Operator.NULL,
          negate = true,
          table = Some(TaskReview.TABLE)
        ),
        // Exclude unnecessary review status
        BaseParameter(
          "review_status",
          Task.REVIEW_STATUS_UNNECESSARY,
          Operator.EQ,
          negate = true,
          table = Some(TaskReview.TABLE)
        ),
        // Task Status in list if given a list of task statuses
        FilterParameter.conditional(
          "status",
          taskStatus.getOrElse(List.empty),
          Operator.IN,
          includeOnlyIfTrue = taskStatus.getOrElse(List.empty).nonEmpty,
          table = Some("tasks")
        ),
        // review_requested_by != user.id unless a super user
        // to be reviewed tasks (review type = 1)
        FilterParameter.conditional(
          TaskReview.FIELD_REVIEW_REQUESTED_BY,
          user.id,
          negate = true,
          includeOnlyIfTrue = (reviewTasksType == 1) && !permission.isSuperUser(user),
          table = Some(TaskReview.TABLE)
        ),
        // reviewed_by == user.id for 'tasks reviewed by me' (review type = 3)
        FilterParameter.conditional(
          TaskReview.FIELD_REVIEWED_BY,
          user.id,
          includeOnlyIfTrue = reviewTasksType == 2,
          table = Some(TaskReview.TABLE)
        ),
        // reviewed_by == user.id for 'my reviewed tasks' (review type = 2)
        FilterParameter.conditional(
          TaskReview.FIELD_REVIEW_REQUESTED_BY,
          user.id,
          includeOnlyIfTrue = reviewTasksType == 3,
          table = Some(TaskReview.TABLE)
        ),
        // review status = requested or disputed if reviewTasksType = 1
        FilterParameter.conditional(
          TaskReview.FIELD_REVIEW_STATUS,
          List(Task.REVIEW_STATUS_REQUESTED, Task.REVIEW_STATUS_DISPUTED),
          Operator.IN,
          includeOnlyIfTrue = reviewTasksType == 1,
          table = Some(TaskReview.TABLE)
        )
      )
    )

    val excludeOtherReviewersFilterGroup = FilterGroup(
      List(
        BaseParameter(
          TaskReview.FIELD_REVIEWED_BY,
          "",
          Operator.NULL,
          table = Some(TaskReview.TABLE)
        ),
        BaseParameter(TaskReview.FIELD_REVIEWED_BY, user.id, table = Some(TaskReview.TABLE))
      ),
      OR(),
      excludeOtherReviewers && reviewTasksType == 1
    )

    val filter =
      Filter(
        List(
          filterGroup,
          taskReviewFilterGroup,
          challengeService.challengeVisibilityFilter(user),
          excludeOtherReviewersFilterGroup
        )
      )
    this.query(
      Query(
        filter,
        paging = paging,
        grouping = Grouping > "id"
      )
    )
  }

  def query(query: Query): List[ChallengeListing] = this.repository.query(query)
}

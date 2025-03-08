/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model._
import org.maproulette.framework.psql.{Query, _}
import org.maproulette.framework.psql.filter.{BaseParameter, FilterParameter, _}
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

  /**
    * Returns a list of challenges that have reviews/review requests.
    *
    * @param reviewTasksType  The type of reviews (1: To Be Reviewed,  2: User's reviewed Tasks, 3: All reviewed by users)
    * @param user The user making request (for challenge permission visibility)
    * @param taskStatus The task statuses to include
    * @param excludeOtherReviewers Whether tasks completed by other reviewers should be included
    * @return A list of children listing objects
    */
  def withReviewList(
      reviewTasksType: Int,
      user: User,
      taskStatus: Option[List[Int]] = None,
      excludeOtherReviewers: Boolean = false,
      projectSearch: String = "",
      challengeSearch: String = "",
      paging: Paging = Paging()
  ): List[ChallengeListing] = {
    val filter =
      Filter(
        List(
          FilterGroup(
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
                includeOnlyIfTrue = taskStatus.nonEmpty,
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
              ),
              // Project name search
              FilterParameter.conditional(
                "display_name",
                s"%$projectSearch%",
                Operator.ILIKE,
                includeOnlyIfTrue = projectSearch.nonEmpty,
                table = Some("projects")
              ),
              // Challenge name search
              FilterParameter.conditional(
                "name",
                s"%$challengeSearch%",
                Operator.ILIKE,
                includeOnlyIfTrue = challengeSearch.nonEmpty,
                table = Some("challenges")
              )
            )
          ),
          // Check project/challenge visiblity
          challengeService.challengeVisibilityFilter(user),
          // reviewed_by is empty or user.id if excludeOtherReviewers
          FilterGroup(
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
        )
      )
    this.query(
      Query(
        filter,
        paging = paging,
        grouping = Grouping > ("id", "projects.display_name")
      )
    )
  }

  def query(query: Query): List[ChallengeListing] = this.repository.query(query)
}

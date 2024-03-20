/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.mixins

import org.maproulette.session.SearchParameters
import org.maproulette.framework.psql.filter._
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.{AND, OR}
import org.maproulette.framework.model.{TaskReview, Project, Challenge, User, Task}
import org.maproulette.permissions.Permission

/**
  * ReviewSearchMixin provides a method to setup the Query filters for a
  * review search. It will apply all the filters from the search parameters
  * as well as ensuring the correct conditions are met for the given
  * ReviewTasksType.
  */
trait ReviewSearchMixin extends SearchParametersMixin {
  val REVIEW_REQUESTED_TASKS = 1 // Tasks needing to be reviewed
  val MY_REVIEWED_TASKS      = 2 // Tasks reviewed by user
  val REVIEWED_TASKS_BY_ME   = 3 // Tasks completed by user and done review
  val ALL_REVIEWED_TASKS     = 4 // All review(ed) tasks
  val META_REVIEW_TASKS      = 5 // Meta Review tasks

  /**
    * Setup the search clauses for searching the review tables
    */
  def setupReviewSearchClause(
      query: Query,
      user: User,
      permission: Permission,
      searchParameters: SearchParameters,
      reviewTasksType: Int = ALL_REVIEWED_TASKS,
      includeDisputed: Boolean = true,
      onlySaved: Boolean = false,
      excludeOtherReviewers: Boolean = false,
      includeVirtualProjects: Boolean = false
  ): Query = {
    query
      .addFilterGroup(this.userHasPermission(user, permission, reviewTasksType))
      // Filter bundles to only primary tasks
      .addFilterGroup(
        FilterGroup(
          List(
            BaseParameter(
              Task.FIELD_BUNDLE_ID,
              None,
              Operator.NULL,
              table = Some(Task.TABLE)
            ),
            BaseParameter(
              Task.FIELD_BUNDLE_PRIMARY,
              true,
              Operator.BOOL,
              table = Some(Task.TABLE)
            )
          ),
          OR()
        )
      )
      // Exclude unnecessary reviews
      .addFilterGroup(
        FilterGroup(
          List(
            BaseParameter(
              TaskReview.FIELD_REVIEW_STATUS,
              Task.REVIEW_STATUS_UNNECESSARY,
              Operator.NE,
              table = Some(TaskReview.TABLE)
            )
          )
        )
      )
      // Limit to only review requested and disputed
      .addFilterGroup(
        FilterGroup(
          List(
            BaseParameter(
              TaskReview.FIELD_REVIEW_STATUS,
              Task.REVIEW_STATUS_REQUESTED,
              Operator.EQ,
              table = Some(TaskReview.TABLE)
            ),
            FilterParameter.conditional(
              TaskReview.FIELD_REVIEW_STATUS,
              Task.REVIEW_STATUS_DISPUTED,
              Operator.EQ,
              includeOnlyIfTrue = includeDisputed,
              table = Some(TaskReview.TABLE)
            )
          ),
          OR(),
          reviewTasksType == REVIEW_REQUESTED_TASKS
        )
      )
      // Only challenges 'saved' (marked as favorite) by this user
      .addFilterGroup(
        FilterGroup(
          List(
            SubQueryFilter(
              Challenge.FIELD_ID,
              Query.simple(
                List(
                  BaseParameter(
                    "user_id",
                    user.id,
                    Operator.EQ,
                    useValueDirectly = true,
                    table = Some("sc")
                  )
                ),
                "SELECT challenge_id from saved_challenges sc"
              ),
              false,
              Operator.IN,
              Some("c")
            )
          ),
          AND(),
          onlySaved && reviewTasksType == REVIEW_REQUESTED_TASKS
        )
      )
      // Don't show tasks already reviewed by someone else
      // Used most often when tasks need a "re-review" (so in a requested state)
      .addFilterGroup(
        FilterGroup(
          List(
            BaseParameter(
              TaskReview.FIELD_REVIEWED_BY,
              null,
              Operator.NULL,
              table = Some(TaskReview.TABLE)
            ),
            BaseParameter(
              TaskReview.FIELD_REVIEWED_BY,
              user.id,
              Operator.EQ,
              useValueDirectly = true,
              table = Some(TaskReview.TABLE)
            )
          ),
          OR(),
          excludeOtherReviewers && reviewTasksType == REVIEW_REQUESTED_TASKS
        )
      )
      // Limit to already reviewed tasks
      .addFilterGroup(
        FilterGroup(
          List(
            BaseParameter(
              TaskReview.FIELD_REVIEW_STATUS,
              Task.REVIEW_STATUS_REQUESTED,
              Operator.NE,
              table = Some(TaskReview.TABLE)
            )
          ),
          AND(),
          reviewTasksType == MY_REVIEWED_TASKS
        )
      )
      .addFilterGroup(
        FilterGroup(
          List(
            FilterParameter.conditional(
              TaskReview.FIELD_REVIEW_STATUS,
              searchParameters.taskParams.taskReviewStatus.getOrElse(List()).mkString(","),
              Operator.IN,
              searchParameters.invertFields.getOrElse(List()).contains("trStatus"),
              true,
              searchParameters.taskParams.taskReviewStatus.getOrElse(List()).nonEmpty,
              table = Some(TaskReview.TABLE)
            )
          )
        )
      )
      .addFilterGroup(this.filterProjects(searchParameters, includeVirtualProjects))
      .addFilterGroup(this.filterProjectEnabled(searchParameters))
      .addFilterGroup(this.filterChallenges(searchParameters))
      .addFilterGroup(this.filterChallengeEnabled(searchParameters))
      .addFilterGroup(this.filterOwner(searchParameters))
      .addFilterGroup(this.filterReviewer(searchParameters))
      .addFilterGroup(this.filterMetaReviewer(searchParameters))
      .addFilterGroup(this.filterMapper(searchParameters))
      .addFilterGroup(this.filterTaskStatus(searchParameters, List()))
      .addFilterGroup(this.filterMetaReviewStatusDirect(searchParameters))
      .addFilterGroup(this.filterReviewMappers(searchParameters))
      .addFilterGroup(this.filterReviewers(searchParameters))
      .addFilterGroup(this.filterMetaReviewers(searchParameters))
      .addFilterGroup(this.filterTaskPriorities(searchParameters))
      .addFilterGroup(this.filterLocation(searchParameters))
      .addFilterGroup(this.filterProjectSearch(searchParameters))
      .addFilterGroup(this.filterTaskId(searchParameters))
      .addFilterGroup(this.filterTaskFeatureId(searchParameters))
      .addFilterGroup(this.filterPriority(searchParameters))
      .addFilterGroup(this.filterTaskTags(searchParameters))
      .addFilterGroup(this.filterReviewDate(searchParameters))
      .addFilterGroup(this.filterMappedOnDate(searchParameters))
  }

  def copyParamsForMetaReview(
      asMetaReview: Boolean,
      searchParameters: SearchParameters
  ): SearchParameters = {
    asMetaReview match {
      case true =>
        searchParameters.reviewParams.metaReviewStatus match {
          case None =>
            val copiedParams = searchParameters.copy(
              reviewParams = searchParameters.reviewParams.copy(
                metaReviewStatus =
                  Some(List(Task.REVIEW_STATUS_REQUESTED, Task.META_REVIEW_STATUS_NOT_SET))
              )
            )
            copiedParams.taskParams.taskReviewStatus match {
              case None =>
                copiedParams.copy(
                  taskParams = searchParameters.taskParams.copy(
                    taskReviewStatus = Some(
                      List(
                        Task.REVIEW_STATUS_APPROVED,
                        Task.REVIEW_STATUS_APPROVED_WITH_REVISIONS,
                        Task.REVIEW_STATUS_APPROVED_WITH_FIXES_AFTER_REVISIONS,
                        Task.REVIEW_STATUS_ASSISTED
                      )
                    )
                  )
                )
              case _ => copiedParams
            }
          case _ =>
            searchParameters.taskParams.taskReviewStatus match {
              case None =>
                searchParameters.copy(
                  taskParams = searchParameters.taskParams.copy(
                    taskReviewStatus = Some(
                      List(
                        Task.REVIEW_STATUS_APPROVED,
                        Task.REVIEW_STATUS_APPROVED_WITH_REVISIONS,
                        Task.REVIEW_STATUS_APPROVED_WITH_FIXES_AFTER_REVISIONS,
                        Task.REVIEW_STATUS_ASSISTED
                      )
                    )
                  )
                )
              case _ => searchParameters
            }
        }
      case false => searchParameters
    }
  }

  private def filterMetaReviewStatusDirect(params: SearchParameters): FilterGroup = {
    params.reviewParams.metaReviewStatus match {
      case Some(statuses) if statuses.nonEmpty =>
        FilterGroup(
          List(
            FilterParameter.conditional(
              TaskReview.FIELD_META_REVIEW_STATUS,
              params.reviewParams.metaReviewStatus.getOrElse(List()).mkString(","),
              Operator.IN,
              params.invertFields.getOrElse(List()).contains("mrStatus"),
              true,
              params.reviewParams.metaReviewStatus.getOrElse(List()).nonEmpty,
              table = Some(TaskReview.TABLE)
            ),
            FilterParameter.conditional(
              TaskReview.FIELD_META_REVIEW_STATUS,
              None,
              Operator.NULL,
              params.invertFields.getOrElse(List()).contains("mrStatus"),
              true,
              params.reviewParams.metaReviewStatus
                .getOrElse(List())
                .contains(Task.META_REVIEW_STATUS_NOT_SET),
              table = Some(TaskReview.TABLE)
            )
          ),
          if (params.invertFields.getOrElse(List()).contains("mrStatus")) AND()
          else OR()
        )
      case _ => FilterGroup(List())
    }
  }

  private def userHasPermission(
      user: User,
      permission: Permission,
      reviewTasksType: Int
  ): FilterGroup = {
    if (permission.isSuperUser(user)) return FilterGroup(List())

    // Setup permissions
    // You see review task when:
    // 1. Project and Challenge enabled (so visible to everyone)
    // 2. You manage the project (your user group matches groups of project)
    // 3. You worked on the task - ie. review/reviewed it
    //    (unless it's a review request as you can't review tasks you requested)

    val enabledFilter =
      FilterGroup(
        List(
          BaseParameter(Project.FIELD_ENABLED, true, Operator.BOOL, table = Some("p")),
          BaseParameter(Challenge.FIELD_ENABLED, true, Operator.BOOL, table = Some("c"))
        ),
        AND()
      )

    val managesProjectFilter =
      if (user.managedProjectIds().nonEmpty) {
        // user manages project ids
        FilterGroup(
          List(
            BaseParameter(
              Project.FIELD_ID,
              user.managedProjectIds().mkString(","),
              Operator.IN,
              useValueDirectly = true,
              table = Some("p")
            )
          )
        )
      } else {
        FilterGroup(List())
      }

    if (reviewTasksType == REVIEW_REQUESTED_TASKS) {
      // If not a super user than you are not allowed to request reviews
      // on tasks you completed.
      FilterGroup(
        List(
          CustomParameter(
            "(" +
              Filter(
                List(
                  enabledFilter,
                  managesProjectFilter
                ),
                OR()
              ).sql() + ")"
          ),
          BaseParameter(
            TaskReview.FIELD_REVIEW_REQUESTED_BY,
            user.id,
            Operator.NE,
            useValueDirectly = true,
            table = Some(TaskReview.TABLE)
          )
        ),
        AND()
      )
    } else {
      FilterGroup(
        List(
          CustomParameter(
            "(" +
              Filter(
                List(
                  enabledFilter,
                  managesProjectFilter,
                  FilterGroup(
                    List(
                      BaseParameter(
                        TaskReview.FIELD_REVIEW_REQUESTED_BY,
                        user.id,
                        Operator.EQ,
                        useValueDirectly = true,
                        table = Some(TaskReview.TABLE)
                      )
                    )
                  ),
                  FilterGroup(
                    List(
                      BaseParameter(
                        TaskReview.FIELD_REVIEWED_BY,
                        user.id,
                        Operator.EQ,
                        useValueDirectly = true,
                        table = Some(TaskReview.TABLE)
                      )
                    )
                  )
                ),
                OR()
              ).sql() + ")"
          )
        )
      )
    }
  }

}

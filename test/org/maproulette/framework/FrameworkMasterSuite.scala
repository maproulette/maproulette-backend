/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework

import org.maproulette.framework.repository._
import org.maproulette.framework.service._
import org.maproulette.framework.util._
import org.scalatest.{BeforeAndAfterAll, Suite, Suites, Tag}

/**
  * @author mcuthbert
  */
class FrameworkMasterSuite extends Suites with BeforeAndAfterAll with TestDatabase {
  private val suites = IndexedSeq(
    new AchievementServiceSpec,
    new NotificationRepositorySpec,
    new NotificationSubscriptionRepositorySpec,
    new NotificationServiceSpec,
    new FollowServiceSpec,
    new TeamServiceSpec,
    new ChallengeServiceSpec,
    new ChallengeRepositorySpec,
    new TeamImageRepositorySpec,
    new TeamAvatarRepositorySpec,
    new ChallengeListingServiceSpec,
    new ChallengeSnapshotServiceSpec,
    new CommentServiceSpec,
    new CommentRepositorySpec,
    new DataServiceSpec,
    new GrantServiceSpec,
    new GrantRepositorySpec,
    new ProjectServiceSpec,
    new ProjectRepositorySpec,
    new LeaderboardServiceSpec,
    new LeaderboardRepositorySpec,
    new TagServiceSpec,
    new TagRepositorySpec,
    new TaskRepositorySpec,
    new TaskClusterRepositorySpec,
    new TaskHistoryRepositorySpec,
    new TaskReviewRepositorySpec,
    new TaskServiceSpec,
    new TaskBundleServiceSpec,
    new TaskClusterServiceSpec,
    new TaskReviewServiceSpec,
    new TaskReviewMetricsServiceSpec,
    new UserMetricsServiceSpec,
    new UserSavedObjectsServiceSpec,
    new UserSavedObjectsRepositorySpec,
    new UserServiceSpec,
    new UserRepositorySpec,
    new GroupServiceSpec,
    new GroupRepositorySpec,
    new GroupMemberRepositorySpec,
    new VirtualProjectServiceSpec,
    new VirtualProjectRepositorySpec
  )
  private val tagFilters: Seq[Tag] = Seq()
  override val nestedSuites: IndexedSeq[Suite] =
    suites.filter(
      tagFilters.isEmpty || _.tags
        .flatMap(_._2)
        .exists(tag => tagFilters.map(_.name).contains(tag.toLowerCase))
    )

  override protected def beforeAll(): Unit = this.applyEvolutions()

  override protected def afterAll(): Unit = this.cleanupEvolutions()
}

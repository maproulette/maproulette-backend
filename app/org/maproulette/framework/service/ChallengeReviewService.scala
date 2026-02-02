/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model.{ChallengeReview, ChallengeReviewSummary, User}
import org.maproulette.framework.repository.ChallengeReviewRepository
import org.maproulette.exception.{InvalidException, NotFoundException}

@Singleton
class ChallengeReviewService @Inject() (
    repository: ChallengeReviewRepository,
    challengeService: ChallengeService
) {

  def submitReview(
      user: User,
      challengeId: Long,
      review: ChallengeReview
  ): Option[ChallengeReview] = {
    challengeService.retrieve(challengeId) match {
      case None => throw new NotFoundException(s"Challenge $challengeId not found")
      case Some(_) =>
        if (review.rating < 1 || review.rating > 5)
          throw new InvalidException("Rating must be between 1 and 5")
        repository.upsert(review.copy(userId = user.id, challengeId = challengeId))
    }
  }

  def removeReview(user: User, challengeId: Long): Boolean =
    repository.delete(user.id, challengeId)

  def getUserReview(user: User, challengeId: Long): Option[ChallengeReview] =
    repository.getUserReview(user.id, challengeId)

  def getReviewsForChallenge(
      challengeId: Long,
      limit: Int = 10,
      offset: Int = 0
  ): List[ChallengeReview] =
    repository.getReviewsForChallenge(challengeId, limit, offset)

  def getReviewSummary(challengeId: Long): ChallengeReviewSummary =
    repository.getSummary(challengeId)

  def getRecommended(
      user: User,
      userLon: Option[Double] = None,
      userLat: Option[Double] = None,
      userDifficulty: Option[String] = None,
      limit: Int = 20,
      offset: Int = 0
  ): List[(Long, Double)] = {
    repository.getRecommended(user.id, userLon, userLat, userDifficulty, limit, offset)
  }
}

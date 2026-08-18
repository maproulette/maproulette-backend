/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import scala.collection.mutable.ListBuffer
import org.maproulette.framework.model.{Task, Challenge, User, Tag, Achievement}
import org.maproulette.framework.repository.{UserRepository}
import org.maproulette.provider.websockets.{WebSocketMessages, WebSocketProvider}

/**
  * @author nrotstan
  */
@Singleton
class AchievementService @Inject() (
    repository: UserRepository,
    serviceManager: ServiceManager,
    webSocketProvider: WebSocketProvider
) {

  /**
    * Award any appropriate achievements based on completion of a task
    */
  def awardTaskCompletionAchievements(user: User, task: Task, newStatus: Int): Option[User] = {
    // Achievements are only awarded if the task was fixed
    if (newStatus == Task.STATUS_FIXED) {
      // We build up a list of all potential achievements. When we add them to the user,
      // only the new ones (not already possessed by the user) will be actually be added
      val achievements = ListBuffer[Int]()
      achievements += Achievement.FIXED_TASK
      if (task.cooperativeWork != None) {
        achievements += Achievement.FIXED_COOP_TASK
      }

      this.serviceManager.challenge.retrieve(task.parent) match {
        case Some(challenge) =>
          if (challenge.status.getOrElse(Challenge.STATUS_NA) == Challenge.STATUS_FINISHED) {
            achievements += Achievement.FIXED_FINAL_TASK
          }
          val challengeTags = this.serviceManager.tag.listByChallenge(task.parent)
          this.appendTaskCompletionCategoryAchievements(challengeTags, achievements)
        case None =>
      }

      this.appendPointBasedAchievements(user, achievements)
      this.addAchievementsToUser(user, achievements.toList)
    } else {
      Some(user)
    }
  }

  /**
    * Award any appropriate achievements based on review of a task
    */
  def awardTaskReviewAchievements(user: User, task: Task, newReviewStatus: Int): Option[User] = {
    // Achievements are only awarded if the review was completed
    if (newReviewStatus != Task.REVIEW_STATUS_APPROVED &&
        newReviewStatus != Task.REVIEW_STATUS_APPROVED_WITH_REVISIONS &&
        newReviewStatus != Task.REVIEW_STATUS_APPROVED_WITH_FIXES_AFTER_REVISIONS &&
        newReviewStatus != Task.REVIEW_STATUS_REJECTED &&
        newReviewStatus != Task.REVIEW_STATUS_ASSISTED) {
      return Some(user)
    }

    this.addAchievementsToUser(user, List(Achievement.REVIEWED_TASK))
  }

  /**
    * Award any appropriate achievements based on completion of a challenge
    */
  def awardChallengeCompletionAchievements(challenge: Challenge) = {
    // Achievements are only awarded if the challenge is finished
    if (challenge.status.getOrElse(Challenge.STATUS_NA) == Challenge.STATUS_FINISHED) {
      // Retrieve mappers as User instances, as getChallengeMappers gives back
      // UserSearchResult instances
      val mappers = this.serviceManager.user.retrieveListById(
        this.serviceManager.user.getChallengeMappers(challenge.id).map(m => m.id)
      )

      mappers.foreach { mapper =>
        this.addAchievementsToUser(mapper, List(Achievement.CHALLENGE_COMPLETED))
      }
    }
  }

  /**
    * Award any appropriate achievements based on creation of a new challenge
    */
  def awardChallengeCreationAchievements(challenge: Challenge): Option[User] = {
    // Achievements are only awarded if the challenge is ready and public
    if (challenge.status.getOrElse(Challenge.STATUS_NA) != Challenge.STATUS_READY ||
        !challenge.general.enabled) {
      return None
    }

    this.serviceManager.project.retrieve(challenge.general.parent) match {
      case Some(project) if project.enabled =>
        this.serviceManager.user.retrieveByOSMId(challenge.general.owner) match {
          case Some(user) =>
            this.addAchievementsToUser(user, List(Achievement.CREATED_CHALLENGE))
          case None => None
        }
      case _ => None
    }
  }

  /**
    * Add achievements to user (only new achievements will be added)
    */
  def addAchievementsToUser(user: User, achievements: List[Int]): Option[User] = {
    if (achievements.filterNot(user.achievements.getOrElse(List.empty).toSet).isEmpty) {
      // User already has all of these achievements. Nothing to do
      return Some(user)
    }

    // Add the achievements, capturing exactly which ones were newly awarded.
    // The delta is computed against the current database state inside
    // addAchievements, so it does not depend on the possibly-stale achievements
    // list carried by the in-memory user object.
    var justAwarded: List[Int] = List.empty
    this.serviceManager.user.cacheManager.withDeletingCache(id =>
      this.serviceManager.user.retrieve(id)
    ) { implicit cachedItem =>
      justAwarded = this.repository.addAchievements(user.id, achievements)
      Some(cachedItem)
    }(id = user.id)

    // Notify websocket clients of any new awards
    if (!justAwarded.isEmpty) {
      webSocketProvider.sendMessage(
        WebSocketMessages.achievementAwarded(
          WebSocketMessages.AchievementData(user.id, justAwarded)
        )
      )
    }

    this.serviceManager.user.retrieve(user.id)
  }

  /**
    * Appends achievements related to completing a task in a specific category
    * as determined by the tags on the parent challenge
    */
  private def appendTaskCompletionCategoryAchievements(
      challengeTags: List[Tag],
      achievements: ListBuffer[Int]
  ) = {
    if (challengeTags.exists(t => t.name == "highway")) {
      achievements += Achievement.MAPPED_ROADS
    }
    if (challengeTags.exists(t => t.name == "building")) {
      achievements += Achievement.MAPPED_BUILDINGS
    }
    if (challengeTags.exists(t => t.name == "natural" || t.name == "water")) {
      achievements += Achievement.MAPPED_WATER
    }
    if (challengeTags.exists(t => t.name == "amenity" || t.name == "leisure")) {
      achievements += Achievement.MAPPED_POI
    }
    if (challengeTags.exists(t => t.name == "landuse" || t.name == "boundary")) {
      achievements += Achievement.MAPPED_LANDUSE
    }
    if (challengeTags.exists(t => t.name == "public_transport" || t.name == "railway")) {
      achievements += Achievement.MAPPED_TRANSIT
    }
  }

  private def appendPointBasedAchievements(user: User, achievements: ListBuffer[Int]) = {
    if (user.score.getOrElse(0) >= 100) {
      achievements += Achievement.POINTS_100
    }
    if (user.score.getOrElse(0) >= 500) {
      achievements += Achievement.POINTS_500
    }
    if (user.score.getOrElse(0) >= 1000) {
      achievements += Achievement.POINTS_1000
    }
    if (user.score.getOrElse(0) >= 5000) {
      achievements += Achievement.POINTS_5000
    }
    if (user.score.getOrElse(0) >= 10000) {
      achievements += Achievement.POINTS_10000
    }
    if (user.score.getOrElse(0) >= 50000) {
      achievements += Achievement.POINTS_50000
    }
    if (user.score.getOrElse(0) >= 100000) {
      achievements += Achievement.POINTS_100000
    }
    if (user.score.getOrElse(0) >= 500000) {
      achievements += Achievement.POINTS_500000
    }
    if (user.score.getOrElse(0) >= 1000000) {
      achievements += Achievement.POINTS_1000000
    }
  }
}

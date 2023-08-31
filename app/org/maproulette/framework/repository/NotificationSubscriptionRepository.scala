/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.repository

import java.sql.Connection

import anorm.SqlParser._
import anorm._
import javax.inject.Inject
import org.maproulette.framework.model.{NotificationSubscriptions}
import org.maproulette.framework.psql._
import org.maproulette.framework.psql.filter._
import play.api.db.Database

/**
  * @author nrotstan
  */
class NotificationSubscriptionRepository @Inject() (
    override val db: Database
) extends RepositoryMixin {
  implicit val baseTable: String = NotificationSubscriptions.TABLE

  /**
    * Finds 0 or more notification subscriptions that match the filter criteria
    *
    * @param query The psql query object containing all the filtering, paging and ordering information
    * @param c An implicit connection, that defaults to None
    * @return The list of subscriptions that match the filter criteria
    */
  def query(
      query: Query
  )(implicit c: Option[Connection] = None): List[NotificationSubscriptions] = {
    withMRConnection { implicit c =>
      query
        .build(
          s"SELECT * FROM user_notification_subscriptions"
        )
        .as(NotificationSubscriptionRepository.parser.*)
    }
  }

  /**
    * Retrieve notification subscriptions for a user
    *
    * @param userId The id of the subscribing user
    * @param user   The user making the request
    * @return
    */
  def getNotificationSubscriptions(userId: Long): Option[NotificationSubscriptions] =
    this
      .query(
        Query.simple(
          List(
            BaseParameter(NotificationSubscriptions.FIELD_USER_ID, userId)
          ),
          paging = Paging(1, 0)
        )
      )
      .headOption

  /**
    * Updates notification subscriptions for a user
    *
    * @param userId        The id of the subscribing user
    * @param subscriptions The updated subscriptions
    * @return
    */
  def updateNotificationSubscriptions(
      userId: Long,
      subscriptions: NotificationSubscriptions
  )(implicit c: Option[Connection] = None): Unit = {
    withMRTransaction { implicit c =>
      // Upsert new subscription settings
      SQL(
        """
        |INSERT INTO user_notification_subscriptions (user_id, system, mention, review_approved,
        |                                             review_rejected, review_again, challenge_completed, team,
        |                                             follow, meta_review, review_count, revision_count)
        |VALUES({userId}, {system}, {mention}, {reviewApproved}, {reviewRejected}, {reviewAgain},
        |       {challengeCompleted}, {team}, {follow}, {metaReview}, {reviewCount}, {revisionCount})
        |ON CONFLICT (user_id) DO
        |UPDATE SET system=EXCLUDED.system, mention=EXCLUDED.mention,
        |           review_approved=EXCLUDED.review_approved, review_rejected=EXCLUDED.review_rejected,
        |           review_again=EXCLUDED.review_again, challenge_completed=EXCLUDED.challenge_completed, 
        |           team=EXCLUDED.team, follow=EXCLUDED.follow, meta_review=EXCLUDED.meta_review,
        |           review_count=EXCLUDED.review_count, revision_count=EXCLUDED.revision_count
        """.stripMargin
      ).on(
          Symbol("userId")             -> userId,
          Symbol("system")             -> subscriptions.system,
          Symbol("mention")            -> subscriptions.mention,
          Symbol("reviewApproved")     -> subscriptions.reviewApproved,
          Symbol("reviewRejected")     -> subscriptions.reviewRejected,
          Symbol("reviewAgain")        -> subscriptions.reviewAgain,
          Symbol("challengeCompleted") -> subscriptions.challengeCompleted,
          Symbol("team")               -> subscriptions.team,
          Symbol("follow")             -> subscriptions.follow,
          Symbol("metaReview")         -> subscriptions.metaReview,
          Symbol("reviewCount")        -> subscriptions.reviewCount,
          Symbol("revisionCount")      -> subscriptions.revisionCount
        )
        .executeUpdate()
    }
  }
}

object NotificationSubscriptionRepository {
  // The anorm row parser for user's subscriptions to notifications
  val parser: RowParser[NotificationSubscriptions] = {
    get[Long]("id") ~
      get[Long]("user_id") ~
      get[Int]("system") ~
      get[Int]("mention") ~
      get[Int]("review_approved") ~
      get[Int]("review_rejected") ~
      get[Int]("review_again") ~
      get[Int]("challenge_completed") ~
      get[Int]("team") ~
      get[Int]("follow") ~
      get[Int]("meta_review") ~
      get[Int]("review_count") ~
      get[Int]("revision_count") map {
      case id ~ userId ~ system ~ mention ~ reviewApproved ~ reviewRejected ~ reviewAgain ~
            challengeCompleted ~ team ~ follow ~ metaReview ~ reviewCount ~ revisionCount =>
        NotificationSubscriptions(
          id,
          userId,
          system,
          mention,
          reviewApproved,
          reviewRejected,
          reviewAgain,
          challengeCompleted,
          team,
          follow,
          metaReview,
          reviewCount,
          revisionCount
        )
    }
  }
}

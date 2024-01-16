/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.jobs

import akka.actor.{Actor, Props}
import anorm.SqlParser._
import anorm._

import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.data.SnapshotManager
import org.maproulette.framework.model.{
  Task,
  User,
  UserNotification,
  UserNotificationEmailDigest,
  UserRevCount
}
import org.maproulette.framework.service.ServiceManager
import org.maproulette.jobs.SchedulerActor.RunJob
import org.maproulette.jobs.utils.LeaderboardHelper
import org.maproulette.metrics.Metrics
import org.maproulette.framework.model.Task.STATUS_CREATED
import org.maproulette.framework.model._
import org.maproulette.models.dal.DALManager
import org.maproulette.provider.{EmailProvider, KeepRightBox, KeepRightError, KeepRightProvider}
import org.maproulette.utils.BoundingBoxFinder
import org.slf4j.LoggerFactory
import play.api.Application
import play.api.db.Database

import scala.util.{Failure, Success}

/**
  * The main actor that handles all scheduled activities
  *
  * @author cuthbertm
  * @author davis_20
  */
@Singleton
class SchedulerActor @Inject() (
    config: Config,
    application: Application,
    db: Database,
    dALManager: DALManager,
    serviceManager: ServiceManager,
    keepRightProvider: KeepRightProvider,
    boundingBoxFinder: BoundingBoxFinder,
    emailProvider: EmailProvider,
    implicit val snapshotManager: SnapshotManager
) extends Actor {
  // cleanOldTasks configuration
  lazy val oldTasksStatusFilter = appConfig
    .getOptional[Seq[Int]](Config.KEY_SCHEDULER_CLEAN_TASKS_STATUS_FILTER)
    .getOrElse(
      Seq[Int](STATUS_CREATED)
    )

  import scala.concurrent.ExecutionContext.Implicits.global

  val appConfig      = application.configuration
  private val logger = LoggerFactory.getLogger(this.getClass)

  override def receive: Receive = {
    case RunJob("rebuildChallengesLeaderboard", action) => this.rebuildChallengesLeaderboard(action)
    case RunJob("rebuildCountryLeaderboard", action)    => this.rebuildCountryLeaderboard(action)
    case RunJob("cleanLocks", action)                   => this.cleanLocks(action)
    case RunJob("cleanClaimLocks", action)              => this.cleanClaimLocks(action)
    case RunJob("runChallengeSchedules", action)        => this.runChallengeSchedules(action)
    case RunJob("updateLocations", action)              => this.updateLocations(action)
    case RunJob("cleanOldTasks", action)                => this.cleanOldTasks(action)
    case RunJob("expireTaskReviews", action)            => this.expireTaskReviews(action)
    case RunJob("cleanExpiredVirtualChallenges", action) =>
      this.cleanExpiredVirtualChallenges(action)
    case RunJob("FindChangeSets", action)      => this.findChangeSets(action)
    case RunJob("OSMChangesetMatcher", action) => this.matchChangeSets(action)
    case RunJob("cleanDeleted", action)        => this.cleanDeleted(action)
    case RunJob("KeepRightUpdate", action)     => this.keepRightUpdate(action)
    case RunJob("snapshotUserMetrics", action) => this.snapshotUserMetrics(action)
    case RunJob("snapshotChallenges", action)  => this.snapshotChallenges(action)
    case RunJob("sendImmediateNotificationEmails", action) =>
      this.sendImmediateNotificationEmails(action)
    case RunJob("sendDigestNotificationEmails", action) => this.sendDigestNotificationEmails(action)
    case RunJob("sendCountNotificationDailyEmails", action) =>
      this.handleSendCountNotificationEmails(action, UserNotification.NOTIFICATION_EMAIL_DAILY)
    case RunJob("sendCountNotificationWeeklyEmails", action) =>
      this.handleSendCountNotificationEmails(action, UserNotification.NOTIFICATION_EMAIL_WEEKLY)
    case RunJob("archiveChallenges", action) =>
      this.handleArchiveChallenges(action)
    case RunJob("updateChallengeCompletionMetrics", action) =>
      this.handleUpdateChallengeCompletionMetrics(action)
  }

  /**
    * This job will remove all stale locks from the system. A stale lock is a lock that has maintained
    * the lock for over an hour. To enable, set:
    *    osm.scheduler.cleanLocks.interval=FiniteDuration
    */
  def cleanLocks(action: String): Unit = {
    logger.info(s"Scheduled Task '$action': Starting run")
    val start = System.currentTimeMillis

    this.db.withTransaction { implicit c =>
      val query        = s"DELETE FROM locked WHERE AGE(NOW(), locked_time) > '${config.taskLockExpiry}'"
      val locksDeleted = SQL(query).executeUpdate()

      val totalTime = System.currentTimeMillis - start
      logger.info(
        s"Scheduled Task '$action': Finished run. Time spent: ${String.format("%1d", totalTime)}ms. $locksDeleted were found and deleted"
      )
    }
  }

  /**
    * This job will remove all stale (older than 1 day) review claim locks from the system
    */
  def cleanClaimLocks(action: String): Unit = {
    logger.info(s"Scheduled Task '$action': Starting run")
    val start = System.currentTimeMillis

    this.db.withTransaction { implicit c =>
      val claimsRemoved = SQL"""UPDATE task_review
                                SET review_claimed_by = NULL, review_claimed_at = NULL
                                WHERE AGE(NOW(), review_claimed_at) > '12 hours'""".executeUpdate()

      val totalTime = System.currentTimeMillis - start
      logger.info(
        s"Scheduled Task '$action': Finished run. Time spent: ${String.format("%1d", totalTime)}ms. $claimsRemoved stale review claims were found and deleted"
      )
    }
  }

  /**
    * This job will update the challenges from remote geojson or overpass query based on the supplied
    * schedules in the challenge. To enable, set:
    *    osm.scheduler.runChallengeSchedules.interval=FiniteDuration
    */
  def runChallengeSchedules(action: String): Unit = {
    // TOOD(ljdelight): Old code that needs to be purged? This is a NOOP and not use anywhere besides the job scheduler setup.
    logger.info(s"Scheduled Task '$action': Starting run")
    logger.info(s"Scheduled Task '$action': Finished run")
  }

  /**
    * This job will update the locations of all the challenges periodically. To enable, set:
    *    osm.scheduler.updateLocations.interval=FiniteDuration
    */
  def updateLocations(action: String): Unit = {
    logger.info(s"Scheduled Task '$action': Starting run")
    val start           = System.currentTimeMillis
    val challengeFilter = "deleted = false AND is_archived = false AND enabled = true";
    val staleChallengeIds = db.withConnection { implicit c =>
      SQL(
        s"SELECT id FROM challenges WHERE ${challengeFilter} AND (modified > last_updated OR last_updated IS NULL)"
      ).as(SqlParser.long("id").*)
    }
    logger.info(s"Updating locations and bounding boxes for ${staleChallengeIds.length} challenges")

    // For each "stale" challenge, update the fields: location, bounding box, and last_updated time.
    // Previously the below code was written as a single database transaction, which is much faster, but when a single
    // challenge had bad data for the location/box columns it would fail the entire transaction, reverting any changes.
    // See https://github.com/maproulette/maproulette3/issues/567 for more details.
    staleChallengeIds.foreach(id => {
      try {
        db.withTransaction {
          implicit c =>
            val query =
              s"""UPDATE challenges SET
                        location = (SELECT ST_Centroid(ST_Collect(ST_Makevalid(location)))
                                    FROM tasks
                                    WHERE parent_id = ${id}),
                        bounding = (SELECT ST_Envelope(ST_Buffer((ST_SetSRID(ST_Extent(location), 4326))::geography,2)::geometry)
                                    FROM tasks
                                    WHERE parent_id = ${id}),
                        last_updated = NOW()
                    WHERE id = ${id};"""
            SQL(query).executeUpdate()
        }
        // The above query will not update the cache, so remove the id from the cache in case it is there
        logger.debug(s"Flushing challenge cache of challenge with id $id")
        this.dALManager.challenge.cacheManager.cache.remove(id)
      } catch {
        case e: Exception => {
          logger.error("Unable to update location on challenge " + id, e)
        }
      }
    })

    val totalTime = System.currentTimeMillis - start
    logger.info(
      s"Scheduled Task '$action': Finished run. Time spent: ${String.format("%1d", totalTime)}ms"
    )
  }

  /**
    * This job will delete old tasks, filtered by the statusFilter. To enable, set:
    *    osm.scheduler.cleanOldTasks.interval=FiniteDuration
    *    osm.scheduler.cleanOldTasks.olderThan=FiniteDuration
    */
  def cleanOldTasks(action: String): Unit = {
    logger.info(s"Scheduled Task '$action': Starting run")
    val start = System.currentTimeMillis
    config.withFiniteDuration(Config.KEY_SCHEDULER_CLEAN_TASKS_OLDER_THAN) { duration =>
      Metrics.timer("Cleaning old challenge tasks") { () =>
        db.withTransaction { implicit c =>
          logger.info(
            s"Cleaning old challenge tasks older than $duration with status [$oldTasksStatusFilter]..."
          )
          val tasksDeleted =
            SQL("""DELETE FROM tasks t USING challenges c
                    WHERE t.parent_id = c.id AND c.updateTasks = true AND t.status IN ({statuses})
                     AND AGE(NOW(), c.modified) > {duration}::INTERVAL
                     AND AGE(NOW(), t.modified) > {duration}::INTERVAL""")
              .on(
                Symbol("duration") -> ToParameterValue
                  .apply[String]
                  .apply(String.valueOf(duration)),
                Symbol("statuses") -> ToParameterValue.apply[Seq[Int]].apply(oldTasksStatusFilter)
              )
              .executeUpdate()

          // Clear the task cache if any were deleted
          if (tasksDeleted > 0) {
            this.dALManager.task.cacheManager.clearCaches
          }

          val totalTime = System.currentTimeMillis - start
          logger.info(
            s"Scheduled Task '$action': Finished run. Time spent: ${String.format("%1d", totalTime)}ms. $tasksDeleted old challenge tasks were found and deleted"
          )
        }
      }
    }
  }

  /**
    * This job moves expired task reviews to 'unnecessary'. To enable, set:
    *    osm.scheduler.expireTaskReviews.interval=FiniteDuration
    *    osm.scheduler.expireTaskReviews.olderThan=FiniteDuration
    */
  def expireTaskReviews(action: String): Unit = {
    logger.info(s"Scheduled Task '$action': Starting run")
    config.withFiniteDuration(Config.KEY_SCHEDULER_EXPIRE_TASK_REVIEWS_OLDER_THAN) { duration =>
      db.withConnection { implicit c =>
        logger.info(
          s"Expiring task reviews older than $duration ..."
        )
        val start = System.currentTimeMillis

        val reviewsExpired = this.serviceManager.taskReview.expireTaskReviews(duration)

        val totalTime = System.currentTimeMillis - start
        logger.info(
          s"Scheduled Task '$action': Finished run. Time spent: ${String.format("%1d", totalTime)}ms. $reviewsExpired old task reviews were moved to unnecessary"
        )
      }
    }
  }

  /**
    * This job will delete expired Virtual Challenges. To enable, set:
    *    osm.scheduler.cleanExpiredVCs.interval=FiniteDuration
    */
  def cleanExpiredVirtualChallenges(action: String): Unit = {
    logger.info(s"Scheduled Task '$action': Starting run")
    val start = System.currentTimeMillis
    db.withConnection { implicit c =>
      val numberOfDeleted =
        SQL"""DELETE FROM virtual_challenges WHERE expired < NOW()""".executeUpdate()

      // Clear the task cache if any were deleted
      if (numberOfDeleted > 0) {
        this.dALManager.virtualChallenge.cacheManager.clearCaches
      }

      val totalTime = System.currentTimeMillis - start
      logger.info(
        s"Scheduled Task '$action': Finished run. Time spent: ${String.format("%1d", totalTime)}ms $numberOfDeleted Virtual Challenges expired and removed from database"
      )
    }
  }

  /**
    * Run through all the tasks and match OSM Changesets to fixed tasks. This will run through tasks
    * 5000 at a time, and limit the tasks returned to only tasks that have actually had their status
    * set to FIXED and changeset value not set to -2. If the value is -2 then it assumes that we have
    * already tried to match the changeset and couldn't find any viable option for it.
    *
    * @param action
    */
  def matchChangeSets(action: String): Unit = {
    val start = System.currentTimeMillis
    logger.info(s"Scheduled Task '$action': Starting run")

    if (config.osmMatcherEnabled) {
      db.withConnection { implicit c =>
        val query =
          s"""
             |SELECT ${dALManager.task.retrieveColumns} FROM tasks
             |WHERE status = 1 AND changeset_id = -1
             |LIMIT ${config.osmMatcherBatchSize}
         """.stripMargin
        SQL(query)
          .as(dALManager.task.parser.*)
          .foreach(t => {
            dALManager.task.matchToOSMChangeSet(t, User.superUser)
          })
      }
    }

    val totalTime = System.currentTimeMillis - start
    logger.info(
      s"Scheduled Task '$action': Finished run. Time spent: ${String.format("%1d", totalTime)}ms"
    )
  }

  /**
    * Task that manually matches the OSM changesets to tasks
    *
    * @param str
    */
  def findChangeSets(str: String): Unit = {
    logger.info("match changesets")
    val start = System.currentTimeMillis
    if (config.osmMatcherManualOnly) {
      val values = str.split("=")
      if (values.size == 2) {
        implicit val id = values(1).toLong
        values(0) match {
          case "p" =>
            this.serviceManager.project
              .children(-1)
              .foreach(c => {
                dALManager.challenge
                  .listChildren(-1)(c.id)
                  .filter(_.status.contains(Task.STATUS_FIXED))
                  .foreach(t => dALManager.task.matchToOSMChangeSet(t, User.superUser, false))
              })
          case "c" =>
            dALManager.challenge
              .listChildren(-1)
              .foreach(t => {
                dALManager.task.matchToOSMChangeSet(t, User.superUser, false)
              })
          case "t" =>
            dALManager.task.retrieveById match {
              case Some(t) => dALManager.task.matchToOSMChangeSet(t, User.superUser, false)
              case None    =>
            }
          case _ => // Do nothing because there is nothing to do
        }
      }
    }
    val totalTime = System.currentTimeMillis - start
    logger.info(s"find changesets job completed. Time spent: %1d ms".format(totalTime))
  }

  def cleanDeleted(action: String): Unit = {
    logger.info(s"Scheduled Task '$action': Starting run")
    val start = System.currentTimeMillis
    db.withConnection { implicit c =>
      val deletedProjects =
        SQL"DELETE FROM projects WHERE deleted = true RETURNING id".as(SqlParser.int("id").*)
      if (deletedProjects.nonEmpty) {
        logger.debug(s"Finalized deletion of projects with id [${deletedProjects.mkString(",")}]")
      }
      val deletedChallenges =
        SQL"DELETE FROM challenges WHERE deleted = true RETURNING id".as(SqlParser.int("id").*)
      if (deletedChallenges.nonEmpty) {
        logger.debug(
          s"Finalized deletion of challenges with id [${deletedChallenges.mkString(",")}]"
        )
      }
    }

    val totalTime = System.currentTimeMillis - start
    logger.info(
      s"Scheduled Task '$action': Finished run. Time spent: ${String.format("%1d", totalTime)}ms"
    )
  }

  def keepRightUpdate(action: String): Unit = {
    logger.info(s"Scheduled Task '$action': Starting run")
    val start = System.currentTimeMillis

    val slidingValue = this.config.config
      .getOptional[Int](KeepRightProvider.KEY_SLIDING)
      .getOrElse(KeepRightProvider.DEFAULT_SLIDING)
    val slidingErrors = keepRightProvider.errorList.sliding(slidingValue, slidingValue).toList

    val integrationList: List[(List[KeepRightError], KeepRightBox)] =
      if (config.config.getOptional[Boolean](KeepRightProvider.KEY_ENABLED).getOrElse(false)) {
        if (keepRightProvider.boundingBoxes.nonEmpty && keepRightProvider.errorList.nonEmpty) {
          slidingErrors.flatMap(error =>
            keepRightProvider.boundingBoxes map { bounding =>
              (error, bounding)
            }
          )
        } else {
          List.empty
        }
      } else {
        List.empty
      }
    integrationList.headOption match {
      case Some(h) => this.integrateKeepRight(h, integrationList.tail)
      case None    => //just do nothing
    }

    val totalTime = System.currentTimeMillis - start
    logger.info(
      s"Scheduled Task '$action': Finished run. Time spent: ${String.format("%1d", totalTime)}ms"
    )
  }

  /**
    * Rebuilds the user_leaderboard table.
    *
    * @param action - action string
    */
  def rebuildChallengesLeaderboard(action: String): Unit = {
    val start = System.currentTimeMillis
    logger.info(s"Scheduled Task '$action': Starting run")

    def deleteAndUpdateLeaderboardForTimePeriod(monthDuration: Int): Unit = {
      logger.info(
        s"Scheduled Task '$action': updating user_leaderboard monthDuration=$monthDuration"
      )
      db.withConnection { implicit c =>
        SQL(
          s"DELETE FROM user_leaderboard WHERE country_code IS NULL AND month_duration = {monthDuration}"
        ).on(Symbol("monthDuration") -> monthDuration)
          .executeUpdate()
        SQL(
          s"DELETE FROM user_top_challenges WHERE country_code IS NULL AND month_duration = {monthDuration}"
        ).on(Symbol("monthDuration") -> monthDuration).executeUpdate()

        SQL(LeaderboardHelper.rebuildChallengesLeaderboardSQL(monthDuration, config))
          .executeUpdate()
        SQL(
          LeaderboardHelper.rebuildTopChallengesSQL(monthDuration, config)
        ).executeUpdate()
      }
      logger.info(
        s"Scheduled Task '$action': finished updating user_leaderboard monthDuration=$monthDuration"
      )
    }

    SchedulerActor.MONTH_DURATIONS.foreach(monthDuration => {
      try {
        deleteAndUpdateLeaderboardForTimePeriod(monthDuration)
      } catch {
        case e: Exception =>
          logger.error(
            s"Scheduled Task '$action': Failed to update user_leaderboard monthDuration=$monthDuration",
            e
          )
      }
    })

    val totalTime = System.currentTimeMillis - start
    logger.info(
      s"Scheduled Task '$action': Finished run. Time spent: ${String.format("%1d", totalTime)}ms"
    )
  }

  /**
    * Rebuilds the user_leaderboard table.
    *
    * @param action - action string
    */
  def rebuildCountryLeaderboard(action: String): Unit = {
    val start = System.currentTimeMillis
    logger.info(s"Scheduled Task '$action': Starting run")

    def deleteAndUpdateCountryLeaderboardForTimePeriod(
        monthDuration: Int,
        countryCode: String,
        boundingBox: String
    ): Unit = {
      logger.info(
        s"Scheduled Task '$action': updating user_leaderboard monthDuration=$monthDuration countryCode=$countryCode"
      )
      db.withConnection { implicit c =>
        // Delete the existing entries for the country and time period
        SQL(
          s"DELETE FROM user_leaderboard WHERE country_code = {countryCode} AND month_duration = {monthDuration}"
        ).on(Symbol("countryCode") -> countryCode, Symbol("monthDuration") -> monthDuration)
          .executeUpdate()
        // Insert the new entries for the country and time period
        SQL(
          LeaderboardHelper
            .rebuildChallengesLeaderboardSQLCountry(monthDuration, countryCode, boundingBox, config)
        ).executeUpdate()
      }
      logger.info(
        s"Scheduled Task '$action': finished updating user_leaderboard monthDuration=$monthDuration countryCode=$countryCode"
      )
    }

    // TODO(ljdelight): If the loop order is inverted where each country loops over the monthDuration, will this be faster?
    //                  The database may be able to more effectively cache the per-country results vs the time-based outer loop.
    SchedulerActor.MONTH_DURATIONS.foreach(monthDuration => {
      val countryCodeMap = boundingBoxFinder.boundingBoxforAll()
      for ((countryCode, boundingBox) <- countryCodeMap) {
        try {
          deleteAndUpdateCountryLeaderboardForTimePeriod(monthDuration, countryCode, boundingBox)
        } catch {
          // If an exception occurs, log it and continue to the next country
          case e: Exception =>
            logger.error(
              s"Scheduled Task '$action': Failed to update user_leaderboard monthDuration=$monthDuration countryCode=$countryCode",
              e
            )
        }
      }
    })

    db.withConnection { implicit c =>
      val countryCodeMap = boundingBoxFinder.boundingBoxforAll()
      for ((countryCode, boundingBox) <- countryCodeMap) {
        try {
          logger.info(
            s"Scheduled Task '$action': updating user_top_challenges monthDuration=12 countryCode=$countryCode"
          )
          // Delete the existing entries for the country and time period
          SQL(
            "DELETE FROM user_top_challenges WHERE country_code = {countryCode} AND month_duration = {monthDuration}"
          ).on(
              Symbol("countryCode")   -> countryCode,
              Symbol("monthDuration") -> 12
            )
            .executeUpdate()

          SQL(
            LeaderboardHelper.rebuildTopChallengesSQLCountry(
              SchedulerActor.TWELVE_MONTHS,
              countryCode,
              boundingBox,
              config
            )
          ).executeUpdate()
        } catch {
          case e: Exception =>
            logger.error(
              s"Scheduled Task '$action': Error updating user_top_challenges for monthDuration=12 countryCode=$countryCode",
              e
            )
        }

        try {
          logger.info(
            s"Scheduled Task '$action': updating user_top_challenges monthDuration=-1 countryCode=$countryCode"
          )
          // Delete the existing entries for the country and time period
          SQL(
            "DELETE FROM user_top_challenges WHERE country_code = {countryCode} AND month_duration = {monthDuration}"
          ).on(
              Symbol("countryCode")   -> countryCode,
              Symbol("monthDuration") -> -1
            )
            .executeUpdate()

          SQL(
            LeaderboardHelper.rebuildTopChallengesSQLCountry(
              SchedulerActor.ALL_TIME,
              countryCode,
              boundingBox,
              config
            )
          ).executeUpdate()
        } catch {
          case e: Exception =>
            logger.error(
              s"Scheduled Task '$action': Error updating user_top_challenges for monthDuration=12 countryCode=$countryCode",
              e
            )
        }
      }
    }

    val totalTime = System.currentTimeMillis - start
    logger.info(
      s"Scheduled Task '$action': Finished run. Time spent: ${String.format("%1d", totalTime)}ms"
    )
  }

  def sendImmediateNotificationEmails(action: String) = {
    val start = System.currentTimeMillis
    logger.info(s"Scheduled Task '$action': Starting run")

    // Gather notifications needing an immediate email and send email for each
    this.serviceManager.notification
      .prepareNotificationsForImmediateEmail(
        User.superUser,
        config.notificationImmediateEmailBatchSize
      )
      .foreach(notification => {
        // Send email if user has an email address on file
        try {
          this.serviceManager.user.retrieve(notification.userId) match {
            case Some(user) =>
              user.settings.email match {
                case Some(address) if (!address.isEmpty) =>
                  this.emailProvider.emailNotification(address, notification)
                case _ => None
              }
            case None => None
          }
        } catch {
          case e: Exception => logger.error("Failed to send immediate email: " + e)
        }
      })

    val totalTime = System.currentTimeMillis - start
    logger.info(
      s"Scheduled Task '$action': Finished run. Time spent: ${String.format("%1d", totalTime)}ms"
    )
  }

  def sendCountNotificationEmails(user: UserRevCount, taskType: String): Unit = {
    try {
      if (user.email.nonEmpty) {
        this.emailProvider.emailCountNotification(user.email, user.name, user.tasks, taskType)
      }
    } catch {
      case e: Exception => logger.error("Failed to send count email: " + e)
    }
  }

  def handleSendCountNotificationEmails(action: String, subscriptionType: Int) = {
    val start = System.currentTimeMillis
    logger.info(s"Scheduled Task '$action': Starting run")

    this.serviceManager.notification
      .usersWithTasksToBeReviewed(
        User.superUser
      )
      .foreach(user => {
        if (user.reviewCountSubscriptionType == subscriptionType) {
          sendCountNotificationEmails(user, UserNotification.TASK_TYPE_REVIEW)
        }
      })

    this.serviceManager.notification
      .usersWithTasksToBeRevised(
        User.superUser
      )
      .foreach(user => {
        if (user.revisionCountSubscriptionType == subscriptionType) {
          sendCountNotificationEmails(user, UserNotification.TASK_TYPE_REVISION)
        }
      })

    val totalTime = System.currentTimeMillis - start
    logger.info(
      s"Scheduled Task '$action': Finished run. Time spent: ${String.format("%1d", totalTime)}ms"
    )
  }

  /**
    * finds the first task with a modified data sooner than the restriction param
    * @param tasks - the list of tasks in question
    * @param restrictionDate - the date used to check against the modifiedDate of the tasks
    * @return a task or undefined
    */
  def findNonStaleTask = (tasks: List[ArchivableTask], restrictionDate: String) => {
    tasks.find(task => task.modified.toString() > restrictionDate)
  }

  def handleArchiveChallenges(action: String) = {
    val start = System.currentTimeMillis
    logger.info(s"Scheduled Task '$action': Starting run")

    val currentDate = DateTime.now()
    val staleDate =
      currentDate.minusMonths(this.config.archiveStaleTimeInMonths).toString("yyyy-MM-dd");

    logger.info(action + " - Stale Date: " + staleDate);

    this.serviceManager.challenge
      .activeChallenges()
      .filter(challenge => challenge.created.toString("yyyy-MM-dd") < staleDate)
      .foreach(challenge => {

        val tasks         = this.serviceManager.challenge.getTasksByParentId(challenge.id);
        val nonStaleTasks = findNonStaleTask(tasks, staleDate)

        if (nonStaleTasks.isEmpty) {
          this.serviceManager.challenge.archiveChallenge(challenge.id, true, true);
        }
      })

    val totalTime = System.currentTimeMillis - start
    logger.info(
      s"Scheduled Task '$action': Finished run. Time spent: ${String.format("%1d", totalTime)}ms"
    )
  }

  /**
    * Updates completion metrics for all active challenges.
    *
    * @param action An action descriptor, providing more context for logging.
    */
  def handleUpdateChallengeCompletionMetrics(action: String): Unit = {
    val startTime = System.currentTimeMillis
    logger.info(s"Scheduled Task '$action': Starting run")

    this.serviceManager.challenge.updateCompletionMetricsOfActiveChallenges()

    val elapsedTime = System.currentTimeMillis - startTime
    logger.info(
      s"Scheduled Task '$action': Finished run. Time spent: ${String.format("%1d", elapsedTime)}ms"
    )
  }

  def sendDigestNotificationEmails(action: String) = {
    val start = System.currentTimeMillis
    logger.info(s"Scheduled Task '$action': Starting run")

    // Compile together notification digests for each user with pending notifications
    val digests = this.serviceManager.notification
      .usersWithNotificationEmails(
        User.superUser,
        UserNotification.NOTIFICATION_EMAIL_DIGEST
      )
      .map(recipientId =>
        UserNotificationEmailDigest(
          recipientId,
          this.serviceManager.notification
            .prepareNotificationsForDigestEmail(recipientId, User.superUser)
        )
      )

    // Email each digest if recipient has an email address on file
    digests.foreach(digest => {
      try {
        this.serviceManager.user.retrieve(digest.userId) match {
          case Some(user) =>
            user.settings.email match {
              case Some(address) if (!address.isEmpty) =>
                this.emailProvider.emailNotificationDigest(address, digest.notifications)
              case _ => None
            }
          case None => None
        }
      } catch {
        case e: Exception => logger.error("Failed to send digest email: " + e)
      }
    })

    val totalTime = System.currentTimeMillis - start
    logger.info(
      s"Scheduled Task '$action': Finished run. Time spent: ${String.format("%1d", totalTime)}ms"
    )
  }

  /**
    * Snapshots the user_metrics table and stores in in user_metrics_history
    *
    * @param action - action string
    */
  def snapshotUserMetrics(action: String): Unit = {
    val start = System.currentTimeMillis
    logger.info(s"Scheduled Task '$action': Starting run")

    db.withConnection { implicit c =>
      SQL(s"""UPDATE user_metrics set score=data.score, total_fixed=data.total_fixed,
              total_false_positive=data.total_false_positive, total_already_fixed=data.total_already_fixed,
              total_too_hard=data.total_too_hard, total_skipped=data.total_skipped,
              total_time_spent=data.total_time_spent, tasks_with_time=data.tasks_with_time
              FROM (
              SELECT users.id,
                       SUM(CASE sa.status
                           WHEN ${Task.STATUS_FIXED} THEN ${config.taskScoreFixed}
                           WHEN ${Task.STATUS_FALSE_POSITIVE} THEN ${config.taskScoreFalsePositive}
                           WHEN ${Task.STATUS_ALREADY_FIXED} THEN ${config.taskScoreAlreadyFixed}
                           WHEN ${Task.STATUS_TOO_HARD} THEN ${config.taskScoreTooHard}
                           WHEN ${Task.STATUS_SKIPPED} THEN ${config.taskScoreSkipped}
                           ELSE 0
                       END) AS score,
                       SUM(CASE WHEN sa.status = ${Task.STATUS_FIXED} THEN ${config.taskScoreFixed} else 0 end) total_fixed,
                       SUM(CASE WHEN sa.status = ${Task.STATUS_FALSE_POSITIVE} THEN ${config.taskScoreFalsePositive} else 0 end) total_false_positive,
                       SUM(CASE WHEN sa.status = ${Task.STATUS_ALREADY_FIXED} THEN ${config.taskScoreAlreadyFixed} else 0 end) total_already_fixed,
                       SUM(CASE WHEN sa.status = ${Task.STATUS_TOO_HARD} THEN ${config.taskScoreTooHard} end) total_too_hard,
                       SUM(CASE WHEN sa.status = ${Task.STATUS_SKIPPED} THEN ${config.taskScoreSkipped} else 0 end) total_skipped,
                       SUM(CASE WHEN (sa.created IS NOT NULL AND
                                       sa.started_at IS NOT NULL)
                                THEN (EXTRACT(EPOCH FROM (sa.created - sa.started_at)) * 1000)
                                ELSE 0 END) as total_time_spent,
                       SUM(CASE WHEN (sa.created IS NOT NULL AND
                                       sa.started_at IS NOT NULL)
                                THEN 1 ELSE 0 END) as tasks_with_time
               FROM status_actions sa, users
               WHERE users.osm_id = sa.osm_user_id AND sa.old_status <> sa.status
               GROUP BY sa.osm_user_id, users.id) AS data
              WHERE user_metrics.user_id = data.id
           """).executeUpdate()
      logger.info(s"Refreshed user metrics from status actions.")
    }

    db.withConnection { implicit c =>
      SQL(s"""INSERT INTO user_metrics_history
              SELECT user_id, score, total_fixed, total_false_positive, total_already_fixed,
                     total_too_hard, total_skipped, now(), initial_rejected, initial_approved,
                     initial_assisted, total_rejected, total_approved, total_assisted,
                     total_disputed_as_mapper, total_disputed_as_reviewer,
                     total_time_spent, tasks_with_time, total_review_time, tasks_with_review_time
              FROM user_metrics
           """).executeUpdate()

      logger.info(s"Succesfully created snapshot of user metrics.")
    }

    val totalTime = System.currentTimeMillis - start
    logger.info(
      s"Scheduled Task '$action': Finished run. Time spent: ${String.format("%1d", totalTime)}ms"
    )
  }

  /**
    * Records snaphshots for all challenges.
    *
    * @param action - action string
    */
  def snapshotChallenges(action: String): Unit = {
    val start = System.currentTimeMillis
    logger.info(s"Scheduled Task '$action': Starting run")

    db.withConnection { implicit c =>
      val ids = SQL(s"""SELECT id FROM challenges""").as(long("id").*)
      ids.foreach(id => {
        this.snapshotManager.recordChallengeSnapshot(id, false)
      })
      val totalTime = System.currentTimeMillis - start
      logger.info(
        s"Scheduled Task '$action': Finished run. Time spent: ${String.format("%1d", totalTime)}ms"
      )
    }
  }

  /**
    * We essentially create this recursive function, so that we don't take down the KeepRight servers
    * by bombarding it with tons of API requests.
    *
    * @param head The head of the list, which is a tuple containing a KeepRightError and a KeepRightBox
    * @param tail The tail list of box objects
    */
  private def integrateKeepRight(
      head: (List[KeepRightError], KeepRightBox),
      tail: List[(List[KeepRightError], KeepRightBox)]
  ): Unit = {
    keepRightProvider.integrate(head._1.map(_.id), head._2) onComplete {
      case Success(x) =>
        if (!x) {
          logger.warn(s"KeepRight challenge failed, but continuing to next one")
        }
        tail.headOption match {
          case Some(head) => this.integrateKeepRight(head, tail.tail)
          case None       => // just do nothing because we are finished
        }
      case Failure(f) =>
        // something went wrong, we should bail out immediately
        logger.warn(s"The KeepRight challenge creation failed. ${f.getMessage}")
    }
  }
}

object SchedulerActor {
  private val ONE_MONTH     = 1
  private val THREE_MONTHS  = 3
  private val SIX_MONTHS    = 6
  private val TWELVE_MONTHS = 12
  private val ALL_TIME      = -1

  private val MONTH_DURATIONS = List(
    SchedulerActor.ONE_MONTH,
    SchedulerActor.THREE_MONTHS,
    SchedulerActor.SIX_MONTHS,
    SchedulerActor.TWELVE_MONTHS,
    SchedulerActor.ALL_TIME
  )

  def props = Props[SchedulerActor]()

  case class RunJob(name: String, action: String = "")

}

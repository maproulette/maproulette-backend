/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.jobs

import java.util.Calendar

import akka.actor.{ActorRef, ActorSystem}
import javax.inject.{Inject, Named}
import org.maproulette.Config
import org.maproulette.jobs.SchedulerActor.RunJob
import org.slf4j.LoggerFactory
import play.api.{Application, Logger}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * @author cuthbertm
  * @author davis_20
  */
class Scheduler @Inject() (
    val system: ActorSystem,
    @Named("scheduler-actor") val schedulerActor: ActorRef,
    val config: Config
)(implicit application: Application, ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  schedule("cleanLocks", "Cleaning locks", 1.minute, Config.KEY_SCHEDULER_CLEAN_LOCKS_INTERVAL)
  schedule(
    "cleanClaimLocks",
    "Cleaning review claim locks",
    1.minute,
    Config.KEY_SCHEDULER_CLEAN_CLAIM_LOCKS_INTERVAL
  )
  schedule(
    "runChallengeSchedules",
    "Running challenge Schedules",
    1.minute,
    Config.KEY_SCHEDULER_RUN_CHALLENGE_SCHEDULES_INTERVAL
  )
  schedule(
    "updateLocations",
    "Updating locations",
    1.minute,
    Config.KEY_SCHEDULER_UPDATE_LOCATIONS_INTERVAL
  )
  schedule(
    "cleanOldTasks",
    "Cleaning old tasks",
    1.minute,
    Config.KEY_SCHEDULER_CLEAN_TASKS_INTERVAL
  )
  schedule(
    "expireTaskReviews",
    "Moving old task reviews to unnecessary",
    1.minute,
    Config.KEY_SCHEDULER_EXPIRE_TASK_REVIEWS_INTERVAL
  )
  schedule(
    "cleanExpiredVirtualChallenges",
    "Cleaning up expired Virtual Challenges",
    1.minute,
    Config.KEY_SCHEDULER_CLEAN_VC_INTERVAL
  )
  schedule(
    "OSMChangesetMatcher",
    "Matches OSM changesets to tasks",
    1.minute,
    Config.KEY_SCHEDULER_OSM_MATCHER_INTERVAL
  )
  schedule(
    "cleanDeleted",
    "Deleting Project/Challenges",
    1.minute,
    Config.KEY_SCHEDULER_CLEAN_DELETED
  )
  schedule(
    "KeepRightUpdate",
    "Updating KeepRight Challenges",
    1.minute,
    Config.KEY_SCHEDULER_KEEPRIGHT
  )
  schedule(
    "rebuildChallengesLeaderboard",
    "Rebuilding Challenges Leaderboard",
    1.minute,
    Config.KEY_SCHEDULER_CHALLENGES_LEADERBOARD
  )
  schedule(
    "sendImmediateNotificationEmails",
    "Sending Immediate Notification Emails",
    1.minute,
    Config.KEY_SCHEDULER_NOTIFICATION_IMMEDIATE_EMAIL_INTERVAL
  )

  scheduleAtTime(
    "sendCountNotificationDailyEmails",
    "Sending Count Notification Daily Emails",
    config.getValue(Config.KEY_SCHEDULER_COUNT_NOTIFICATION_DAILY_START),
    Config.KEY_SCHEDULER_COUNT_NOTIFICATION_DAILY_INTERVAL
  )

  scheduleAtTime(
    "archiveChallenges",
    "Archiving Challenges",
    config.getValue(Config.KEY_SCHEDULER_ARCHIVE_CHALLENGES_START),
    Config.KEY_SCHEDULER_ARCHIVE_CHALLENGES_INTERVAL
  )

  scheduleAtTime(
    "updateChallengeCompletionMetrics",
    "Updating Challenge Completion Metrics",
    config.getValue(Config.KEY_SCHEDULER_UPDATE_CHALLENGE_COMPLETION_START),
    Config.KEY_SCHEDULER_UPDATE_CHALLENGE_COMPLETION_INTERVAL
  )

  scheduleAtTime(
    "sendCountNotificationWeeklyEmails",
    "Sending Count Notification Weekly Emails",
    config.getValue(Config.KEY_SCHEDULER_COUNT_NOTIFICATION_WEEKLY_START),
    Config.KEY_SCHEDULER_COUNT_NOTIFICATION_WEEKLY_INTERVAL
  )

  scheduleAtTime(
    "sendDigestNotificationEmails",
    "Sending Notification Email Digests",
    config.getValue(Config.KEY_SCHEDULER_NOTIFICATION_DIGEST_EMAIL_START),
    Config.KEY_SCHEDULER_NOTIFICATION_DIGEST_EMAIL_INTERVAL
  )

  // Run the rebuild of the country leaderboard at
  scheduleAtTime(
    "rebuildCountryLeaderboard",
    "Rebuilding Country Leaderboard",
    config.getValue(Config.KEY_SCHEDULER_COUNTRY_LEADERBOARD_START),
    Config.KEY_SCHEDULER_COUNTRY_LEADERBOARD
  )

  // Run the user metrics snapshot at
  scheduleAtTime(
    "snapshotUserMetrics",
    "Snapshotting User Metrics",
    config.getValue(Config.KEY_SCHEDULER_SNAPSHOT_USER_METRICS_START),
    Config.KEY_SCHEDULER_SNAPSHOT_USER_METRICS
  )

  // Run the challenge snapshots at
  scheduleAtTime(
    "snapshotChallenges",
    "Snapshotting Challenges",
    config.getValue(Config.KEY_SCHEDULER_SNAPSHOT_CHALLENGES_START),
    Config.KEY_SCHEDULER_SNAPSHOT_CHALLENGES_INTERVAL
  )

  /**
    * Conditionally schedules message event to start at an initial time and run every duration
    *
    * @param name           The message name sent to the SchedulerActor
    * @param action         The action this job is performing for logging
    * @param initialRunTime String time in format "00:00:00"
    * @param intervalKey    Configuration key that, when set, will enable periodic scheduled messages
    */
  def scheduleAtTime(
      name: String,
      action: String,
      initialRunTime: Option[String],
      intervalKey: String
  ): Unit = {
    initialRunTime match {
      case Some(initialRunTime) =>
        val timeValues = initialRunTime.split(":")
        val c          = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, timeValues(0).toInt)
        c.set(Calendar.MINUTE, timeValues(1).toInt)
        c.set(Calendar.SECOND, timeValues(2).toInt)
        c.set(Calendar.MILLISECOND, 0)

        if (c.getTimeInMillis() < System.currentTimeMillis()) {
          c.add(Calendar.DATE, 1)
        }
        val msBeforeStart = c.getTimeInMillis() - System.currentTimeMillis()

        logger.debug("Scheduling " + action + " to run in " + msBeforeStart + "ms.")
        schedule(name, action, msBeforeStart.milliseconds, intervalKey)

      case _ => logger.error("Invalid start time given for " + action + "!")
    }

  }

  /**
    * Conditionally schedules message event when configured with a valid duration
    *
    * @param name         The message name sent to the SchedulerActor
    * @param action       The action this job is performing for logging
    * @param initialDelay FiniteDuration until the initial message is sent
    * @param intervalKey  Configuration key that, when set, will enable periodic scheduled messages
    */
  def schedule(
      name: String,
      action: String,
      initialDelay: FiniteDuration,
      intervalKey: String
  ): Unit = {
    config.withFiniteDuration(intervalKey) { interval =>
      this.system.scheduler
        .schedule(initialDelay, interval, this.schedulerActor, RunJob(name, action))
      logger.info(s"$action every $interval")
    }
  }
}

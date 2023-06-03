/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.jobs

import java.util.Calendar
import akka.actor.{ActorRef, ActorSystem}

import java.util.concurrent.{ThreadLocalRandom, TimeUnit}
import javax.inject.{Inject, Named}
import org.maproulette.Config
import org.maproulette.jobs.SchedulerActor.RunJob
import org.slf4j.LoggerFactory
import play.api.Application

import java.time.temporal.ChronoUnit
import java.time.{LocalDateTime, ZoneOffset}
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

  private lazy val hourlyTaskJitter = config
    .getValue(Config.KEY_SCHEDULER_START_TIME_JITTER_HOUR_TASKS)
    .map(durationStr => Duration(durationStr))
    .get
  private val minuteTaskJitter = config
    .getValue(Config.KEY_SCHEDULER_START_TIME_JITTER_MINUTE_TASKS)
    .map(durationStr => Duration(durationStr))
    .get

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

  schedule(
    "updateChallengeCompletionMetrics",
    "Updating Challenge Completion Metrics",
    5.minutes,
    Config.KEY_SCHEDULER_UPDATE_CHALLENGE_COMPLETION_INTERVAL
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

  private def getJitterDelayByInterval(
      jitter: Option[FiniteDuration],
      interval: FiniteDuration
  ): FiniteDuration = {

    jitter match {
      case Some(t) =>
        // A specific jitter was provided. Use that jitter and don't use a random delay
        t
      case None =>
        // A task repeating every hour needs to have a different jitter vs a task that repeats every minute.
        val randomJitterMillis = if (interval >= 1.hour) {
          ThreadLocalRandom.current().nextLong(hourlyTaskJitter.toMillis)
        } else {
          ThreadLocalRandom.current().nextLong(minuteTaskJitter.toMillis)
        }

        val ret = FiniteDuration.apply(randomJitterMillis, TimeUnit.MILLISECONDS)
        logger.trace(s"Using randomJitter=${ret.toSeconds}s for interval=${interval.toSeconds}s")
        ret
    }
  }

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

        logger.debug(
          s"Task '$name' ('$action') to be scheduled at time not before +${msBeforeStart}ms"
        )
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
    * @param initialDelayJitter Initial start delay to avoid having scheduled jobs running at the exact same time (eg the top of the hour).
    *                           Most calls should not override this value; the default value of None will imply a random jitter will be used.
    */
  def schedule(
      name: String,
      action: String,
      initialDelay: FiniteDuration,
      intervalKey: String,
      initialDelayJitter: Option[FiniteDuration] = None
  ): Unit = {
    config.withFiniteDuration(intervalKey) { interval =>
      val firstRunJitter               = getJitterDelayByInterval(initialDelayJitter, initialDelay)
      val firstRunDelayWithJitter      = initialDelay + firstRunJitter
      val subsequentRunJitter          = getJitterDelayByInterval(initialDelayJitter, interval)
      val subsequentRunDelayWithJitter = interval + firstRunDelayWithJitter + subsequentRunJitter

      val firstRunDateTime =
        LocalDateTime.now(ZoneOffset.UTC).plus(firstRunDelayWithJitter.toMillis, ChronoUnit.MILLIS)
      val subsequentRunDateTime = LocalDateTime
        .now(ZoneOffset.UTC)
        .plus(subsequentRunDelayWithJitter.toMillis, ChronoUnit.MILLIS)

      logger.info(
        s"Scheduled Task '$name' ('$action'): Configuration: interval=${interval.toSeconds}s initialDelay=${initialDelay.toSeconds}s"
      )
      logger.info(
        s"Scheduled Task '$name' ('$action'): First   run $firstRunDateTime firstRunJitter=${firstRunJitter.toSeconds}s"
      )
      logger.info(
        s"Scheduled Task '$name' ('$action'): Future runs $subsequentRunDateTime subsequentRunJitter=${subsequentRunJitter.toSeconds}s"
      )

      this.system.scheduler
        .scheduleOnce(firstRunDelayWithJitter, this.schedulerActor, RunJob(name, action))

      this.system.scheduler
        .schedule(
          subsequentRunDelayWithJitter,
          interval,
          this.schedulerActor,
          RunJob(name, action)
        )
    }
  }
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette

import javax.inject.{Inject, Singleton}
import org.maproulette.cache.CacheManager
import org.maproulette.data.Actions
import org.maproulette.framework.model.MapillaryServerInfo
import play.api.Configuration
import play.api.libs.oauth.ConsumerKey

import scala.concurrent.duration.{Duration, FiniteDuration}

case class OSMOAuth(
    userDetailsURL: String,
    requestTokenURL: String,
    accessTokenURL: String,
    authorizationURL: String,
    consumerKey: ConsumerKey
)

case class OSMQLProvider(providerURL: String, requestTimeout: Duration)

/**
  * @author cuthbertm
  */
@Singleton
class Config @Inject() (implicit val configuration: Configuration) {
  lazy val logoURL = this.config.getOptional[String](Config.KEY_LOGO) match {
    case Some(logo) => logo
    case None       => "/assets/images/logo.png" // default to the MapRoulette Icon
  }
  lazy val superKey: Option[String] = this.config.getOptional[String](Config.KEY_SUPER_KEY)
  lazy val superAccounts: List[String] =
    this.config.getOptional[String](Config.KEY_SUPER_ACCOUNTS) match {
      case Some(accs) => accs.split(",").toList
      case None       => List.empty
    }
  lazy val ignoreSessionTimeout: Boolean = this.sessionTimeout == -1
  lazy val isBootstrapMode: Boolean =
    this.config.getOptional[Boolean](Config.KEY_BOOTSTRAP).getOrElse(false)
  lazy val isDebugMode: Boolean =
    this.config.getOptional[Boolean](Config.KEY_DEBUG).getOrElse(false)
  lazy val isDevMode: Boolean =
    this.config.getOptional[Boolean](Config.KEY_DEVMODE).getOrElse(false)
  lazy val skipOSMChangesetSubmission: Boolean =
    this.config.getOptional[Boolean](Config.KEY_SKIP_OSM_CHANGESET_SUBMISSION).getOrElse(false)
  lazy val skipTooHard: Boolean =
    this.config.getOptional[Boolean](Config.KEY_SKIP_TOOHARD).getOrElse(false)
  lazy val impersonateUserId: Long =
    this.config.getOptional[Long](Config.KEY_IMPERSONATE_USER).getOrElse(-1L)
  lazy val actionLevel: Int =
    this.config.getOptional[Int](Config.KEY_ACTION_LEVEL).getOrElse(Actions.ACTION_LEVEL_2)
  lazy val numberOfChallenges: Int =
    this.config
      .getOptional[Int](Config.KEY_NUM_OF_CHALLENGES)
      .getOrElse(Config.DEFAULT_NUM_OF_CHALLENGES)
  lazy val numberOfActivities: Int =
    this.config
      .getOptional[Int](Config.KEY_RECENT_ACTIVITY)
      .getOrElse(Config.DEFAULT_RECENT_ACTIVITY)
  lazy val osmMatcherBatchSize: Int =
    this.config
      .getOptional[Int](Config.KEY_SCHEDULER_OSM_MATCHER_BATCH_SIZE)
      .getOrElse(Config.DEFAULT_VIRTUAL_CHALLENGE_BATCH_SIZE)
  lazy val virtualChallengeLimit: Double =
    this.config
      .getOptional[Double](Config.KEY_VIRTUAL_CHALLENGE_LIMIT)
      .getOrElse(Config.DEFAULT_VIRTUAL_CHALLENGE_LIMIT)
  lazy val virtualChallengeBatchSize: Int =
    this.config
      .getOptional[Int](Config.KEY_VIRTUAL_CHALLENGE_BATCH_SIZE)
      .getOrElse(Config.DEFAULT_VIRTUAL_CHALLENGE_BATCH_SIZE)
  lazy val virtualChallengeExpiry: Duration =
    Duration(
      this.config
        .getOptional[String](Config.KEY_VIRTUAL_CHALLENGE_EXPIRY)
        .getOrElse(Config.DEFAULT_VIRTUAL_CHALLENGE_EXPIRY)
    )
  lazy val changeSetTimeLimit: Duration =
    Duration(
      this.config
        .getOptional[String](Config.KEY_CHANGESET_TIME_LIMIT)
        .getOrElse(Config.DEFAULT_CHANGESET_HOUR_LIMIT)
    )
  lazy val changeSetEnabled: Boolean =
    this.config
      .getOptional[Boolean](Config.KEY_CHANGESET_ENABLED)
      .getOrElse(Config.DEFAULT_CHANGESET_ENABLED)
  lazy val taskLockExpiry: String =
    this.config
      .getOptional[String](Config.KEY_TASK_LOCK_EXPIRY)
      .getOrElse(Config.DEFAULT_TASK_LOCK_EXPIRY)
  lazy val taskScoreFixed: Int =
    this.config
      .getOptional[Int](Config.KEY_TASK_SCORE_FIXED)
      .getOrElse(Config.DEFAULT_TASK_SCORE_FIXED)
  lazy val taskScoreFalsePositive: Int =
    this.config
      .getOptional[Int](Config.KEY_TASK_SCORE_FALSE_POSITIVE)
      .getOrElse(Config.DEFAULT_TASK_SCORE_FALSE_POSITIVE)
  lazy val taskScoreAlreadyFixed: Int =
    this.config
      .getOptional[Int](Config.KEY_TASK_SCORE_ALREADY_FIXED)
      .getOrElse(Config.DEFAULT_TASK_SCORE_ALREADY_FIXED)
  lazy val taskScoreTooHard: Int =
    this.config
      .getOptional[Int](Config.KEY_TASK_SCORE_TOO_HARD)
      .getOrElse(Config.DEFAULT_TASK_SCORE_TOO_HARD)
  lazy val taskScoreSkipped: Int =
    this.config
      .getOptional[Int](Config.KEY_TASK_SCORE_SKIPPED)
      .getOrElse(Config.DEFAULT_TASK_SCORE_SKIPPED)
  lazy val defaultNeedsReview: Int =
    this.config
      .getOptional[Int](Config.KEY_REVIEW_NEEDED_DEFAULT)
      .getOrElse(Config.DEFAULT_REVIEW_NEEDED)
  lazy val archiveStaleTimeInMonths: Int =
    this.config
      .getOptional[Int](Config.KEY_SCHEDULER_ARCHIVE_CHALLENGES_STALE_TIME_IN_MONTHS)
      .getOrElse(Config.DEFAULT_ARCHIVE_STALE_TIME_IN_MONTHS)
  lazy val baseCompletedTimeSpent: Int =
    this.config
      .getOptional[Int](Config.KEY_TASK_BASE_COMPLETED_TIME_SPENT)
      .getOrElse(Config.DEFAULT_TASK_BASE_COMPLETED_TIME_SPENT)
  lazy val maxTasksPerChallenge: Int =
    this.config
      .getOptional[Int](Config.KEY_TASK_MAX_TASKS_PER_CHALLENGE)
      .getOrElse(Config.DEFAULT_MAX_TASKS_PER_CHALLENGE)
  lazy val osmMatcherEnabled: Boolean =
    this.config
      .getOptional[Boolean](Config.KEY_SCHEDULER_OSM_MATCHER_ENABLED)
      .getOrElse(Config.DEFAULT_OSM_MATCHER_ENABLED)
  lazy val osmMatcherManualOnly: Boolean =
    this.config
      .getOptional[Boolean](Config.KEY_SCHEDULER_OSM_MATCHER_MANUAL)
      .getOrElse(Config.DEFAULT_OSM_MATCHER_MANUAL)
  lazy val proxyPort: Option[Int] = this.config.getOptional[Int](Config.KEY_PROXY_PORT)
  lazy val isProxySSL: Boolean =
    this.config.getOptional[Boolean](Config.KEY_PROXY_SSL).getOrElse(false);
  lazy val allowMatchOSM        = changeSetEnabled || osmMatcherEnabled || osmMatcherManualOnly
  lazy val getOSMServer: String = this.config.getOptional[String](Config.KEY_OSM_SERVER).get
  lazy val getOSMPreferences: String =
    this.config.getOptional[String](Config.KEY_OSM_PREFERENCES).get
  lazy val getOSMOauth: OSMOAuth = {
    val osmServer = this.getOSMServer
    OSMOAuth(
      osmServer + this.config.getOptional[String](Config.KEY_OSM_USER_DETAILS_URL).get,
      osmServer + this.config.getOptional[String](Config.KEY_OSM_REQUEST_TOKEN_URL).get,
      osmServer + this.config.getOptional[String](Config.KEY_OSM_ACCESS_TOKEN_URL).get,
      osmServer + this.config.getOptional[String](Config.KEY_OSM_AUTHORIZATION_URL).get,
      ConsumerKey(
        this.config.getOptional[String](Config.KEY_OSM_CONSUMER_KEY).get,
        this.config.getOptional[String](Config.KEY_OSM_CONSUMER_SECRET).get
      )
    )
  }
  lazy val getOSMQLProvider: OSMQLProvider = OSMQLProvider(
    this.config.getOptional[String](Config.KEY_OSM_QL_PROVIDER).get,
    Duration(
      this.config
        .getOptional[Int](Config.KEY_OSM_QL_TIMEOUT)
        .getOrElse(Config.DEFAULT_OSM_QL_TIMEOUT),
      "s"
    )
  )
  lazy val getMapillaryServerInfo: MapillaryServerInfo = {
    MapillaryServerInfo(
      this.config.getOptional[String](Config.KEY_MAPILLARY_HOST).getOrElse(""),
      this.config.getOptional[String](Config.KEY_MAPILLARY_CLIENT_ID).getOrElse(""),
      this.config
        .getOptional[Double](Config.KEY_MAPILLARY_BORDER)
        .getOrElse(Config.DEFAULT_MAPILLARY_BORDER)
    )
  }
  lazy val getSemanticVersion: String =
    this.config.getOptional[String](Config.KEY_SEMANTIC_VERSION).getOrElse("N/A")
  lazy val sessionTimeout: Long = this.config
    .getOptional[Long](Config.KEY_SESSION_TIMEOUT)
    .getOrElse(Config.DEFAULT_SESSION_TIMEOUT)
  lazy val getPublicOrigin: Option[String] =
    this.config.getOptional[String](Config.KEY_PUBLIC_ORIGIN)
  lazy val getEmailFrom: Option[String] =
    this.config.getOptional[String](Config.KEY_EMAIL_FROM)
  lazy val notificationImmediateEmailBatchSize: Int =
    this.config
      .getOptional[Int](Config.KEY_SCHEDULER_NOTIFICATION_IMMEDIATE_EMAIL_BATCH_SIZE)
      .getOrElse(Config.DEFAULT_NOTIFICATION_IMMEDIATE_EMAIL_BATCH_SIZE)
  lazy val taskReset: Int =
    this.config.getOptional[Int](Config.KEY_TASK_RESET).getOrElse(Config.DEFAULT_TASK_RESET)
  lazy val signIn: Boolean =
    this.config.getOptional[Boolean](Config.KEY_SIGNIN).getOrElse(Config.DEFAULT_SIGNIN)

  //caching properties
  lazy val cacheType: String =
    this.config.getOptional[String](Config.KEY_CACHING_TYPE).getOrElse(CacheManager.CAFFEINE_CACHE)
  lazy val cacheLimit: Int = this.config
    .getOptional[Int](Config.KEY_CACHING_CACHE_LIMIT)
    .getOrElse(CacheManager.DEFAULT_CACHE_LIMIT)
  lazy val cacheExpiry: Int = this.config
    .getOptional[Int](Config.KEY_CACHING_CACHE_EXPIRY)
    .getOrElse(CacheManager.DEFAULT_CACHE_EXPIRY)
  lazy val redisHost: Option[String] =
    this.config.getOptional[String](Config.KEY_CACHING_REDIS_HOST)
  lazy val redisPort: Option[Int] = this.config.getOptional[Int](Config.KEY_CACHING_REDIS_PORT)
  lazy val redisResetOnStart: Boolean =
    this.config.getOptional[Boolean](Config.KEY_CACHING_REDIS_RESET).getOrElse(false);

  val config = configuration

  /**
    * Retrieves a value from the configuration file
    *
    * @param key Configuration Key
    */
  def getValue(key: String): Option[String] = {
    this.config.getOptional[String](key)
  }

  /**
    * Retrieves a FiniteDuration config value from the configuration and executes the
    * block of code when found.
    *
    * @param key   Configuration Key
    * @param block The block of code executed if a FiniteDuration is found
    */
  def withFiniteDuration(key: String)(block: (FiniteDuration) => Unit): Unit = {
    configuration
      .getOptional[String](key)
      .filter(_.nonEmpty)
      .map(Duration(_))
      .filter(_.isFinite)
      .map(duration => FiniteDuration(duration._1, duration._2))
      .foreach(block(_))
  }
}

object Config {
  val GROUP_MAPROULETTE                  = "maproulette"
  val GROUP_MAPROULETTE_CACHING          = s"$GROUP_MAPROULETTE.caching"
  val KEY_CACHING_TYPE                   = s"$GROUP_MAPROULETTE_CACHING.type"
  val KEY_CACHING_CACHE_LIMIT            = s"$GROUP_MAPROULETTE_CACHING.cacheLimit"
  val KEY_CACHING_CACHE_EXPIRY           = s"$GROUP_MAPROULETTE_CACHING.cacheExpiry"
  val KEY_CACHING_REDIS_HOST             = s"$GROUP_MAPROULETTE_CACHING.redis.host"
  val KEY_CACHING_REDIS_PORT             = s"$GROUP_MAPROULETTE_CACHING.redis.port"
  val KEY_CACHING_REDIS_RESET            = s"$GROUP_MAPROULETTE_CACHING.redis.resetOnStart"
  val KEY_BOOTSTRAP                      = s"$GROUP_MAPROULETTE.bootstrap"
  val KEY_PROXY_PORT                     = s"$GROUP_MAPROULETTE.proxy.port"
  val KEY_PROXY_SSL                      = s"$GROUP_MAPROULETTE.proxy.ssl"
  val KEY_LOGO                           = s"$GROUP_MAPROULETTE.logo"
  val KEY_SUPER_KEY                      = s"$GROUP_MAPROULETTE.super.key"
  val KEY_SUPER_ACCOUNTS                 = s"$GROUP_MAPROULETTE.super.accounts"
  val KEY_DEBUG                          = s"$GROUP_MAPROULETTE.debug"
  val KEY_DEVMODE                        = s"$GROUP_MAPROULETTE.devMode"
  val KEY_SKIP_TOOHARD                   = s"$GROUP_MAPROULETTE.skipTooHard"
  val KEY_IMPERSONATE_USER               = s"$GROUP_MAPROULETTE.impersonateUser"
  val KEY_ACTION_LEVEL                   = s"$GROUP_MAPROULETTE.action.level"
  val KEY_NUM_OF_CHALLENGES              = s"$GROUP_MAPROULETTE.limits.challenges"
  val KEY_RECENT_ACTIVITY                = s"$GROUP_MAPROULETTE.limits.activities"
  val KEY_CHANGESET_TIME_LIMIT           = s"$GROUP_MAPROULETTE.tasks.changesets.timeLimit"
  val KEY_CHANGESET_ENABLED              = s"$GROUP_MAPROULETTE.tasks.changesets.enabled"
  val KEY_MAX_SAVED_CHALLENGES           = s"$GROUP_MAPROULETTE.limits.saved"
  val KEY_SEMANTIC_VERSION               = s"$GROUP_MAPROULETTE.version"
  val KEY_SESSION_TIMEOUT                = s"$GROUP_MAPROULETTE.session.timeout"
  val KEY_PUBLIC_ORIGIN                  = s"$GROUP_MAPROULETTE.publicOrigin"
  val KEY_EMAIL_FROM                     = s"$GROUP_MAPROULETTE.emailFrom"
  val KEY_TASK_RESET                     = s"$GROUP_MAPROULETTE.task.reset"
  val KEY_SIGNIN                         = s"$GROUP_MAPROULETTE.signin"
  val KEY_TASK_LOCK_EXPIRY               = s"$GROUP_MAPROULETTE.task.lock.expiry"
  val KEY_TASK_SCORE_FIXED               = s"$GROUP_MAPROULETTE.task.score.fixed"
  val KEY_TASK_SCORE_FALSE_POSITIVE      = s"$GROUP_MAPROULETTE.task.score.falsePositive"
  val KEY_TASK_SCORE_ALREADY_FIXED       = s"$GROUP_MAPROULETTE.task.score.alreadyFixed"
  val KEY_TASK_SCORE_TOO_HARD            = s"$GROUP_MAPROULETTE.task.score.tooHard"
  val KEY_TASK_SCORE_SKIPPED             = s"$GROUP_MAPROULETTE.task.score.skipped"
  val KEY_TASK_BASE_COMPLETED_TIME_SPENT = s"$GROUP_MAPROULETTE.task.base_completed_time_spent"
  val KEY_TASK_MAX_TASKS_PER_CHALLENGE   = s"$GROUP_MAPROULETTE.task.max_tasks_per_challenge"
  val KEY_REVIEW_NEEDED_DEFAULT          = s"$GROUP_MAPROULETTE.review.default"

  val SUB_GROUP_SCHEDULER                      = s"$GROUP_MAPROULETTE.scheduler"
  val KEY_SCHEDULER_CLEAN_LOCKS_INTERVAL       = s"$SUB_GROUP_SCHEDULER.cleanLocks.interval"
  val KEY_SCHEDULER_CLEAN_CLAIM_LOCKS_INTERVAL = s"$SUB_GROUP_SCHEDULER.cleanClaimLocks.interval"
  val KEY_SCHEDULER_RUN_CHALLENGE_SCHEDULES_INTERVAL =
    s"$SUB_GROUP_SCHEDULER.runChallengeSchedules.interval"
  val KEY_SCHEDULER_UPDATE_LOCATIONS_INTERVAL = s"$SUB_GROUP_SCHEDULER.updateLocations.interval"
  val KEY_SCHEDULER_CLEAN_TASKS_INTERVAL      = s"$SUB_GROUP_SCHEDULER.cleanOldTasks.interval"
  val KEY_SCHEDULER_CLEAN_TASKS_STATUS_FILTER = s"$SUB_GROUP_SCHEDULER.cleanOldTasks.statusFilter"
  val KEY_SCHEDULER_CLEAN_TASKS_OLDER_THAN    = s"$SUB_GROUP_SCHEDULER.cleanOldTasks.olderThan"
  val KEY_SCHEDULER_CLEAN_VC_INTERVAL         = s"$SUB_GROUP_SCHEDULER.cleanExpiredVCs.interval"

  val KEY_SCHEDULER_EXPIRE_TASK_REVIEWS_INTERVAL =
    s"$SUB_GROUP_SCHEDULER.expireTaskReviews.interval"
  val KEY_SCHEDULER_EXPIRE_TASK_REVIEWS_OLDER_THAN =
    s"$SUB_GROUP_SCHEDULER.expireTaskReviews.olderThan"

  val KEY_SCHEDULER_OSM_MATCHER_INTERVAL      = s"$SUB_GROUP_SCHEDULER.osmMatcher.interval"
  val KEY_SCHEDULER_OSM_MATCHER_BATCH_SIZE    = s"$SUB_GROUP_SCHEDULER.osmMatcher.batchSize"
  val KEY_SCHEDULER_OSM_MATCHER_ENABLED       = s"$SUB_GROUP_SCHEDULER.osmMatcher.enabled"
  val KEY_SCHEDULER_OSM_MATCHER_MANUAL        = s"$SUB_GROUP_SCHEDULER.osmMatcher.manual"
  val KEY_SCHEDULER_CLEAN_DELETED             = s"$SUB_GROUP_SCHEDULER.cleanDeleted.interval"
  val KEY_SCHEDULER_KEEPRIGHT                 = s"$SUB_GROUP_SCHEDULER.keepright.interval"
  val KEY_SCHEDULER_CHALLENGES_LEADERBOARD    = s"$SUB_GROUP_SCHEDULER.challengesLeaderboard.interval"
  val KEY_SCHEDULER_COUNTRY_LEADERBOARD       = s"$SUB_GROUP_SCHEDULER.countryLeaderboard.interval"
  val KEY_SCHEDULER_COUNTRY_LEADERBOARD_START = s"$SUB_GROUP_SCHEDULER.countryLeaderboard.startTime"
  val KEY_SCHEDULER_NOTIFICATION_IMMEDIATE_EMAIL_INTERVAL =
    s"$SUB_GROUP_SCHEDULER.notifications.immediateEmail.interval"
  val KEY_SCHEDULER_NOTIFICATION_IMMEDIATE_EMAIL_BATCH_SIZE =
    s"$SUB_GROUP_SCHEDULER.notifications.immediateEmail.batchSize"
  val KEY_SCHEDULER_COUNT_NOTIFICATION_DAILY_INTERVAL =
    s"$SUB_GROUP_SCHEDULER.notifications.countNotificationDaily.interval"
  val KEY_SCHEDULER_COUNT_NOTIFICATION_DAILY_START =
    s"$SUB_GROUP_SCHEDULER.notifications.countNotificationDaily.startTime"
  val KEY_SCHEDULER_COUNT_NOTIFICATION_WEEKLY_INTERVAL =
    s"$SUB_GROUP_SCHEDULER.notifications.countNotificationWeekly.interval"
  val KEY_SCHEDULER_COUNT_NOTIFICATION_WEEKLY_START =
    s"$SUB_GROUP_SCHEDULER.notifications.countNotificationWeekly.startTime"
  val KEY_SCHEDULER_ARCHIVE_CHALLENGES_INTERVAL =
    s"$SUB_GROUP_SCHEDULER.archiveChallenges.interval"
  val KEY_SCHEDULER_ARCHIVE_CHALLENGES_START =
    s"$SUB_GROUP_SCHEDULER.archiveChallenges.startTime"
  val KEY_SCHEDULER_ARCHIVE_CHALLENGES_STALE_TIME_IN_MONTHS =
    s"$SUB_GROUP_SCHEDULER.archiveChallenges.staleTimeInMonths"
  val KEY_SCHEDULER_UPDATE_CHALLENGE_COMPLETION_INTERVAL =
    s"$SUB_GROUP_SCHEDULER.updateChallengeCompletionMetrics.interval"
  val KEY_SCHEDULER_NOTIFICATION_DIGEST_EMAIL_INTERVAL =
    s"$SUB_GROUP_SCHEDULER.notifications.digestEmail.interval"
  val KEY_SCHEDULER_NOTIFICATION_DIGEST_EMAIL_START =
    s"$SUB_GROUP_SCHEDULER.notifications.digestEmail.startTime"
  val KEY_SCHEDULER_SNAPSHOT_USER_METRICS = s"$SUB_GROUP_SCHEDULER.userMetricsSnapshot.interval"
  val KEY_SCHEDULER_SNAPSHOT_USER_METRICS_START =
    s"$SUB_GROUP_SCHEDULER.userMetricsSnapshot.startTime"
  val KEY_SCHEDULER_SNAPSHOT_CHALLENGES_INTERVAL =
    s"$SUB_GROUP_SCHEDULER.challengesSnapshot.interval"
  val KEY_SCHEDULER_SNAPSHOT_CHALLENGES_START = s"$SUB_GROUP_SCHEDULER.challengesSnapshot.startTime"

  val SUB_GROUP_MAPILLARY     = s"$GROUP_MAPROULETTE.mapillary"
  val KEY_MAPILLARY_HOST      = s"$SUB_GROUP_MAPILLARY.host"
  val KEY_MAPILLARY_CLIENT_ID = s"$SUB_GROUP_MAPILLARY.clientId"
  val KEY_MAPILLARY_BORDER    = s"$SUB_GROUP_MAPILLARY.border"

  val GROUP_OSM                         = "osm"
  val KEY_OSM_SERVER                    = s"$GROUP_OSM.server"
  val KEY_OSM_PREFERENCES               = s"$GROUP_OSM.preferences"
  val KEY_OSM_USER_DETAILS_URL          = s"$GROUP_OSM.userDetails"
  val KEY_OSM_REQUEST_TOKEN_URL         = s"$GROUP_OSM.requestTokenURL"
  val KEY_OSM_ACCESS_TOKEN_URL          = s"$GROUP_OSM.accessTokenURL"
  val KEY_OSM_AUTHORIZATION_URL         = s"$GROUP_OSM.authorizationURL"
  val KEY_OSM_CONSUMER_KEY              = s"$GROUP_OSM.consumerKey"
  val KEY_OSM_CONSUMER_SECRET           = s"$GROUP_OSM.consumerSecret"
  val KEY_SKIP_OSM_CHANGESET_SUBMISSION = s"$GROUP_OSM.skipOSMChangesetSubmission"

  val GROUP_CHALLENGES                 = "challenges"
  val KEY_VIRTUAL_CHALLENGE_LIMIT      = s"$GROUP_CHALLENGES.virtual.limit"
  val KEY_VIRTUAL_CHALLENGE_BATCH_SIZE = s"$GROUP_CHALLENGES.virtual.batchSize"
  val KEY_VIRTUAL_CHALLENGE_EXPIRY     = s"$GROUP_CHALLENGES.virtual.expiry"

  val KEY_OSM_QL_PROVIDER = s"$GROUP_OSM.ql.provider"
  val KEY_OSM_QL_TIMEOUT  = s"$GROUP_OSM.ql.timeout"

  val DEFAULT_SESSION_TIMEOUT                         = 3600000L
  val DEFAULT_TASK_LOCK_EXPIRY                        = "1 hour"
  val DEFAULT_TASK_RESET                              = 7
  val DEFAULT_TASK_SCORE_FIXED                        = 5
  val DEFAULT_TASK_SCORE_FALSE_POSITIVE               = 3
  val DEFAULT_TASK_SCORE_ALREADY_FIXED                = 3
  val DEFAULT_TASK_SCORE_TOO_HARD                     = 1
  val DEFAULT_TASK_SCORE_SKIPPED                      = 0
  val DEFAULT_OSM_QL_TIMEOUT                          = 25
  val DEFAULT_NUM_OF_CHALLENGES                       = 3
  val DEFAULT_RECENT_ACTIVITY                         = 5
  val DEFAULT_LIST_SIZE                               = 10
  val DEFAULT_SIGNIN                                  = false
  val DEFAULT_MR3_DEV_MODE                            = false
  val DEFAULT_MR3_HOST                                = "/external"
  val DEFAULT_VIRTUAL_CHALLENGE_LIMIT                 = 100
  val DEFAULT_VIRTUAL_CHALLENGE_BATCH_SIZE            = 500
  val DEFAULT_VIRTUAL_CHALLENGE_EXPIRY                = "6 hours"
  val DEFAULT_CHANGESET_HOUR_LIMIT                    = "1 hour"
  val DEFAULT_CHANGESET_ENABLED                       = false
  val DEFAULT_OSM_MATCHER_ENABLED                     = false
  val DEFAULT_OSM_MATCHER_MANUAL                      = false
  val DEFAULT_NOTIFICATION_IMMEDIATE_EMAIL_BATCH_SIZE = 10
  val DEFAULT_MATCHER_BATCH_SIZE                      = 5000
  val DEFAULT_MAPILLARY_BORDER                        = 10
  val DEFAULT_REVIEW_NEEDED                           = 0
  val DEFAULT_MAX_TASKS_PER_CHALLENGE                 = 50000
  val DEFAULT_ARCHIVE_STALE_TIME_IN_MONTHS            = 6
  val DEFAULT_TASK_BASE_COMPLETED_TIME_SPENT          = 14000

  // Redis Cache id's for different caches
  val CACHE_ID_TAGS               = "1";
  val CACHE_ID_USERS              = "2";
  val CACHE_ID_PROJECTS           = "3";
  val CACHE_ID_CHALLENGES         = "4";
  val CACHE_ID_TASKS              = "5";
  val CACHE_ID_USERGROUPS         = "6"
  val CACHE_ID_VIRTUAL_CHALLENGES = "7"
  val CACHE_ID_GRANTS             = "8"
}

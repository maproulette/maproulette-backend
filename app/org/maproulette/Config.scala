// Copyright (C) 2016 MapRoulette contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package org.maproulette

import javax.inject.{Inject, Singleton}

import org.apache.commons.lang3.StringUtils
import org.maproulette.actions.Actions
import play.api.Application
import play.api.libs.oauth.ConsumerKey

import scala.concurrent.duration.{Duration, FiniteDuration}

case class OSMOAuth(userDetailsURL:String, requestTokenURL:String, accessTokenURL:String,
                    authorizationURL:String, consumerKey:ConsumerKey)

case class OSMQLProvider(providerURL:String, requestTimeout:Duration)

/**
  * @author cuthbertm
  */
@Singleton
class Config @Inject() (implicit val application:Application) {
  private val config = application.configuration

  lazy val logoURL = this.config.getString(Config.KEY_LOGO) match {
    case Some(logo) => logo
    case None => "/assets/images/logo.png"// default to the MapRoulette Icon
  }

  lazy val superKey : Option[String] = this.config.getString(Config.KEY_SUPER_KEY)

  lazy val superAccounts : List[String] = this.config.getString(Config.KEY_SUPER_ACCOUNTS) match {
    case Some(accs) => accs.split(",").toList
    case None => List.empty
  }

  lazy val ignoreSessionTimeout : Boolean = this.sessionTimeout == -1

  lazy val isDebugMode : Boolean =
    this.config.getBoolean(Config.KEY_DEBUG).getOrElse(false)

  lazy val isDevMode : Boolean =
    this.config.getBoolean(Config.KEY_DEVMODE).getOrElse(false)

  lazy val actionLevel : Int =
    this.config.getInt(Config.KEY_ACTION_LEVEL).getOrElse(Actions.ACTION_LEVEL_2)

  lazy val numberOfChallenges : Int =
    this.config.getInt(Config.KEY_NUM_OF_CHALLENGES).getOrElse(Config.DEFAULT_NUM_OF_CHALLENGES)

  lazy val numberOfActivities : Int =
    this.config.getInt(Config.KEY_RECENT_ACTIVITY).getOrElse(Config.DEFAULT_RECENT_ACTIVITY)

  lazy val osmMatcherBatchSize : Int =
    this.config.getInt(Config.KEY_SCHEDULER_OSM_MATCHER_BATCH_SIZE).getOrElse(Config.DEFAULT_VIRTUAL_CHALLENGE_BATCH_SIZE)

  lazy val virtualChallengeLimit : Double =
    this.config.getDouble(Config.KEY_VIRTUAL_CHALLENGE_LIMIT).getOrElse(Config.DEFAULT_VIRTUAL_CHALLENGE_LIMIT)

  lazy val virtualChallengeBatchSize : Int =
    this.config.getInt(Config.KEY_VIRTUAL_CHALLENGE_BATCH_SIZE).getOrElse(Config.DEFAULT_VIRTUAL_CHALLENGE_BATCH_SIZE)

  lazy val virtualChallengeExpiry : Duration =
    Duration(this.config.getString(Config.KEY_VIRTUAL_CHALLENGE_EXPIRY).getOrElse(Config.DEFAULT_VIRTUAL_CHALLENGE_EXPIRY))

  lazy val changeSetTimeLimit : Duration =
    Duration(this.config.getString(Config.KEY_CHANGESET_TIME_LIMIT).getOrElse(Config.DEFAULT_CHANGESET_HOUR_LIMIT))

  lazy val changeSetEnabled : Boolean = this.config.getBoolean(Config.KEY_CHANGESET_ENABLED).getOrElse(Config.DEFAULT_CHANGESET_ENABLED)

  lazy val osmMatcherEnabled : Boolean = this.config.getBoolean(Config.KEY_SCHEDULER_OSM_MATCHER_ENABLED).getOrElse(Config.DEFAULT_OSM_MATCHER_ENABLED)

  lazy val osmMatcherManualOnly : Boolean = this.config.getBoolean(Config.KEY_SCHEDULER_OSM_MATCHER_MANUAL).getOrElse(Config.DEFAULT_OSM_MATCHER_MANUAL)

  lazy val allowMatchOSM = changeSetEnabled || osmMatcherEnabled || osmMatcherManualOnly

  lazy val getOSMServer : String = this.config.getString(Config.KEY_OSM_SERVER).get

  lazy val getOSMOauth : OSMOAuth = {
    val osmServer = this.getOSMServer
    OSMOAuth(
      osmServer + this.config.getString(Config.KEY_OSM_USER_DETAILS_URL).get,
      osmServer + this.config.getString(Config.KEY_OSM_REQUEST_TOKEN_URL).get,
      osmServer + this.config.getString(Config.KEY_OSM_ACCESS_TOKEN_URL).get,
      osmServer + this.config.getString(Config.KEY_OSM_AUTHORIZATION_URL).get,
      ConsumerKey(this.config.getString(Config.KEY_OSM_CONSUMER_KEY).get,
        this.config.getString(Config.KEY_OSM_CONSUMER_SECRET).get)
    )
  }

  lazy val getOSMQLProvider : OSMQLProvider = OSMQLProvider(
    this.config.getString(Config.KEY_OSM_QL_PROVIDER).get,
    Duration(this.config.getInt(Config.KEY_OSM_QL_TIMEOUT).getOrElse(Config.DEFAULT_OSM_QL_TIMEOUT), "s")
  )

  lazy val getSemanticVersion : String =
    this.config.getString(Config.KEY_SEMANTIC_VERSION).getOrElse("N/A")

  lazy val sessionTimeout : Long = this.config.getLong(Config.KEY_SESSION_TIMEOUT).getOrElse(Config.DEFAULT_SESSION_TIMEOUT)

  lazy val taskReset : Int = this.config.getInt(Config.KEY_TASK_RESET).getOrElse(Config.DEFAULT_TASK_RESET)

  lazy val signIn : Boolean = this.config.getBoolean(Config.KEY_SIGNIN).getOrElse(Config.DEFAULT_SIGNIN)

  lazy val mr3JSManifest : String = this.config.getString(Config.KEY_MR3_MANIFEST).get
  lazy val mr3StaticPath : Option[String] = {
    val static = this.config.getString(Config.KEY_MR3_STATIC_PATH).getOrElse("")
    if (StringUtils.isEmpty(static)) {
      None
    } else {
      Some(static)
    }
  }
  lazy val mr3DevMode : Boolean = this.config.getBoolean(Config.KEY_MR3_DEV_MODE).getOrElse(Config.DEFAULT_MR3_DEV_MODE)
  lazy val mr3Host : String = {
    val host = this.config.getString(Config.KEY_MR3_HOST).getOrElse(Config.DEFAULT_MR3_HOST)
    if (StringUtils.isEmpty(host)) {
      Config.DEFAULT_MR3_HOST
    } else {
      host
    }
  }

  /**
    * Retrieves a FiniteDuration config value from the configuration and executes the
    * block of code when found.
    *
    * @param key Configuration Key
    * @param block The block of code executed if a FiniteDuration is found
    */
  def withFiniteDuration(key:String)(block:(FiniteDuration) => Unit):Unit = {
    application.configuration.getString(key)
      .map(Duration(_)).filter(_.isFinite())
      .map(duration => FiniteDuration(duration._1, duration._2))
      .foreach(block(_))
  }
}

object Config {
  val GROUP_MAPROULETTE = "maproulette"
  val KEY_LOGO = s"$GROUP_MAPROULETTE.logo"
  val KEY_SUPER_KEY = s"$GROUP_MAPROULETTE.super.key"
  val KEY_SUPER_ACCOUNTS = s"$GROUP_MAPROULETTE.super.accounts"
  val KEY_DEBUG = s"$GROUP_MAPROULETTE.debug"
  val KEY_DEVMODE = s"$GROUP_MAPROULETTE.devMode"
  val KEY_ACTION_LEVEL = s"$GROUP_MAPROULETTE.action.level"
  val KEY_NUM_OF_CHALLENGES = s"$GROUP_MAPROULETTE.limits.challenges"
  val KEY_RECENT_ACTIVITY = s"$GROUP_MAPROULETTE.limits.activities"
  val KEY_CHANGESET_TIME_LIMIT = s"$GROUP_MAPROULETTE.tasks.changesets.timeLimit"
  val KEY_CHANGESET_ENABLED = s"$GROUP_MAPROULETTE.tasks.changesets.enabled"
  val KEY_MAX_SAVED_CHALLENGES = s"$GROUP_MAPROULETTE.limits.saved"
  val KEY_SEMANTIC_VERSION = s"$GROUP_MAPROULETTE.version"
  val KEY_SESSION_TIMEOUT = s"$GROUP_MAPROULETTE.session.timeout"
  val KEY_TASK_RESET = s"$GROUP_MAPROULETTE.task.reset"
  val KEY_SIGNIN = s"$GROUP_MAPROULETTE.signin"

  val SUB_GROUP_SCHEDULER = s"$GROUP_MAPROULETTE.scheduler"
  val KEY_SCHEDULER_CLEAN_LOCKS_INTERVAL = s"$SUB_GROUP_SCHEDULER.cleanLocks.interval"
  val KEY_SCHEDULER_RUN_CHALLENGE_SCHEDULES_INTERVAL = s"$SUB_GROUP_SCHEDULER.runChallengeSchedules.interval"
  val KEY_SCHEDULER_UPDATE_LOCATIONS_INTERVAL = s"$SUB_GROUP_SCHEDULER.updateLocations.interval"
  val KEY_SCHEDULER_CLEAN_TASKS_INTERVAL = s"$SUB_GROUP_SCHEDULER.cleanOldTasks.interval"
  val KEY_SCHEDULER_CLEAN_TASKS_STATUS_FILTER = s"$SUB_GROUP_SCHEDULER.cleanOldTasks.statusFilter"
  val KEY_SCHEDULER_CLEAN_TASKS_OLDER_THAN = s"$SUB_GROUP_SCHEDULER.cleanOldTasks.olderThan"
  val KEY_SCHEDULER_CLEAN_VC_INTEVAL = s"$SUB_GROUP_SCHEDULER.cleanExpiredVCs.interval"
  val KEY_SCHEDULER_OSM_MATCHER_INTERVAL = s"$SUB_GROUP_SCHEDULER.osmMatcher.interval"
  val KEY_SCHEDULER_OSM_MATCHER_BATCH_SIZE = s"$SUB_GROUP_SCHEDULER.osmMatcher.batchSize"
  val KEY_SCHEDULER_OSM_MATCHER_ENABLED = s"$SUB_GROUP_SCHEDULER.osmMatcher.enabled"
  val KEY_SCHEDULER_OSM_MATCHER_MANUAL = s"$SUB_GROUP_SCHEDULER.osmMatcher.manual"
  val KEY_SCHEDULER_CLEAN_DELETED = s"$SUB_GROUP_SCHEDULER.cleanDeleted.interval"

  val GROUP_OSM = "osm"
  val KEY_OSM_SERVER = s"$GROUP_OSM.server"
  val KEY_OSM_USER_DETAILS_URL = s"$GROUP_OSM.userDetails"
  val KEY_OSM_REQUEST_TOKEN_URL = s"$GROUP_OSM.requestTokenURL"
  val KEY_OSM_ACCESS_TOKEN_URL = s"$GROUP_OSM.accessTokenURL"
  val KEY_OSM_AUTHORIZATION_URL = s"$GROUP_OSM.authorizationURL"
  val KEY_OSM_CONSUMER_KEY = s"$GROUP_OSM.consumerKey"
  val KEY_OSM_CONSUMER_SECRET = s"$GROUP_OSM.consumerSecret"

  val GROUP_MR3 = "mr3"
  val KEY_MR3_MANIFEST = s"$GROUP_MR3.manifest"
  val KEY_MR3_STATIC_PATH = s"$GROUP_MR3.staticPath"
  val KEY_MR3_DEV_MODE = s"$GROUP_MR3.devMode"
  val KEY_MR3_HOST = s"$GROUP_MR3.host"

  val GROUP_CHALLENGES = "challenges"
  val KEY_VIRTUAL_CHALLENGE_LIMIT = s"$GROUP_CHALLENGES.virtual.limit"
  val KEY_VIRTUAL_CHALLENGE_BATCH_SIZE = s"$GROUP_CHALLENGES.virtual.batchSize"
  val KEY_VIRTUAL_CHALLENGE_EXPIRY = s"$GROUP_CHALLENGES.virtual.expiry"

  val KEY_OSM_QL_PROVIDER = s"$GROUP_OSM.ql.provider"
  val KEY_OSM_QL_TIMEOUT = s"$GROUP_OSM.ql.timeout"

  val DEFAULT_SESSION_TIMEOUT = 3600000L
  val DEFAULT_TASK_RESET = 7
  val DEFAULT_OSM_QL_TIMEOUT = 25
  val DEFAULT_NUM_OF_CHALLENGES = 3
  val DEFAULT_RECENT_ACTIVITY = 5
  val DEFAULT_LIST_SIZE = 10
  val DEFAULT_SIGNIN = false
  val DEFAULT_MR3_DEV_MODE = false
  val DEFAULT_MR3_HOST = "/external"
  val DEFAULT_VIRTUAL_CHALLENGE_LIMIT = 100
  val DEFAULT_VIRTUAL_CHALLENGE_BATCH_SIZE = 500
  val DEFAULT_VIRTUAL_CHALLENGE_EXPIRY ="6 hours"
  val DEFAULT_CHANGESET_HOUR_LIMIT = "1 hour"
  val DEFAULT_CHANGESET_ENABLED = false
  val DEFAULT_OSM_MATCHER_ENABLED = false
  val DEFAULT_OSM_MATCHER_MANUAL = false
  val DEFAULT_MATCHER_BATCH_SIZE = 5000
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.jobs

import javax.inject.{Inject, Provider, Singleton}
import org.maproulette.Config
import org.maproulette.framework.service.UserService
import org.slf4j.LoggerFactory
import play.api.db.Database
import play.api.inject.ApplicationLifecycle

import scala.concurrent.Future

/**
  * @author mcuthbert
  */
@Singleton
class Bootstrap @Inject() (
    appLifeCycle: ApplicationLifecycle,
    db: Database,
    config: Config,
    userService: Provider[UserService]
) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def start(): Unit = {
    if (!config.isBootstrapMode) {
      // Check superuser settings at startup and output appropriate warnings
      config.superAccounts match {
        case List("*") =>
          logger.warn(
            "Configuration is setting all users to super users. Make sure this is what you want."
          )
        case Nil =>
          logger.warn("Configuration has NO super users. Make sure this is what you want.")
        case _ => // do nothing
      }
    }

    userService.get().promoteSuperUsersInConfig()
  }

  appLifeCycle.addStopHook { () =>
    Future.successful(())
  }

  start()
}

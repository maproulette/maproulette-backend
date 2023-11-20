/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.jobs

import javax.inject.{Inject, Singleton}
import org.maproulette.Config
import org.slf4j.LoggerFactory
import play.api.db.Database
import play.api.inject.ApplicationLifecycle

import scala.concurrent.Future

/**
  * @author mcuthbert
  */
@Singleton
class Bootstrap @Inject() (appLifeCycle: ApplicationLifecycle, db: Database, config: Config) {

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
  }

  appLifeCycle.addStopHook { () =>
    Future.successful(())
  }

  start()
}

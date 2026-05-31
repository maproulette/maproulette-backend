/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.jobs

import com.google.inject.AbstractModule
import org.maproulette.framework.controller.ServiceInfoController
import play.api.libs.concurrent.AkkaGuiceSupport

/**
  * @author cuthbertm
  */
class JobModule extends AbstractModule with AkkaGuiceSupport {
  override def configure(): Unit = {
    bindActor[SchedulerActor]("scheduler-actor")
    bind(classOf[Scheduler]).asEagerSingleton()
    bind(classOf[Bootstrap]).asEagerSingleton()

    // Eagerly bind the ServiceInfoController so that the runtime uptime is set when the service starts.
    bind(classOf[ServiceInfoController]).asEagerSingleton()
  }
}

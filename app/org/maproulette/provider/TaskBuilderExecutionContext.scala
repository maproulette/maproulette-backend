/*
 * Copyright (C) 2026 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.provider

import akka.actor.ActorSystem
import javax.inject.{Inject, Singleton}
import play.api.libs.concurrent.CustomExecutionContext

/**
  * Execution context for challenge task-building work. Using a separate dispatcher/EC
  * means that bursts of requests to POST /challenge/:id/rebuild will queue up and use
  * a fixed number of DB connections, rather than potentially exhausting the pool and
  * preventing other requests from being served.
  */
@Singleton
class TaskBuilderExecutionContext @Inject() (system: ActorSystem)
    extends CustomExecutionContext(system, "akka.task-builder-dispatcher")

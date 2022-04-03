/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.metrics

import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory

/**
  * @author cuthbertm
  */
object Metrics {
  private val logger = LoggerFactory.getLogger(getClass.getName)

  def timer[T](name: String, suppress: Boolean = false)(block: () => T): T = {
    val start  = Instant.now()
    val result = block()
    val end    = Instant.now()

    val duration = Duration.between(start, end)
    if (!suppress) {
      logger.info("{} block took {}ms", name, duration.toMillis)
    }
    result
  }
}

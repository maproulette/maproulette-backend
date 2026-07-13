/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import play.api.libs.json.{Json, Reads, Writes}

/**
  * Per-status task counts for a challenge or project. Stored directly on the
  * owning row so the counts are available without a separate stats call.
  */
case class CompletionMetrics(
    total: Int = 0,
    available: Int = 0,
    fixed: Int = 0,
    falsePositive: Int = 0,
    skipped: Int = 0,
    deleted: Int = 0,
    alreadyFixed: Int = 0,
    tooHard: Int = 0,
    answered: Int = 0,
    validated: Int = 0,
    disabled: Int = 0,
    // Derived: tasks still needing work. Always recomputed on read so the
    // persisted value cannot drift from the per-status counts.
    tasksRemaining: Int = 0
)

object CompletionMetrics {
  implicit val writes: Writes[CompletionMetrics] = Json.writes[CompletionMetrics]
  implicit val reads: Reads[CompletionMetrics] =
    Json.using[Json.WithDefaultValues].reads[CompletionMetrics].map { m =>
      m.copy(tasksRemaining = m.available + m.skipped + m.tooHard)
    }

  def completionPercentage(m: CompletionMetrics): Int = {
    val completable = m.total - m.deleted - m.disabled
    if (completable <= 0) 0
    else {
      val pct = math
        .round((m.fixed + m.falsePositive + m.alreadyFixed).toDouble * 100 / completable)
        .toInt
      pct.max(0).min(100)
    }
  }
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.graphql.fetchers

import org.maproulette.framework.graphql.UserContext
import sangria.execution.deferred.{Fetcher}
import scala.concurrent.{Future}

/**
  * @author nrotstan
  */
trait ChallengeFetchers {}
object ChallengeFetchers {
  val challengesFetcher = Fetcher((ctx: UserContext, ids: Seq[Long]) =>
    Future.successful(
      ctx.services.challenge.list(ids.toList).toSeq
    )
  )

  val fetchers = List(challengesFetcher)
}

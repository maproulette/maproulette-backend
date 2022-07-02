/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.graphql.fetchers

import org.maproulette.framework.graphql.UserContext
import sangria.execution.deferred.{Fetcher, HasId}
import scala.concurrent.{Future}

/**
  * @author nrotstan
  */
trait UserFetchers {}
object UserFetchers {
  val usersFetcher = Fetcher((ctx: UserContext, ids: Seq[Long]) =>
    Future.successful(
      ctx.services.user.retrieveListById(ids.toList).toSeq
    )
  )

  val osmUsersFetcher = Fetcher((ctx: UserContext, osmIds: Seq[Long]) =>
    Future.successful(
      ctx.services.user.retrieveListByOSMId(osmIds.toList).toSeq
    )
  )(HasId(_.osmProfile.id))

  val fetchers = List(usersFetcher, osmUsersFetcher)
}

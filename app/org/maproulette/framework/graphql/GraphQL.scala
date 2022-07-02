/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.graphql

import javax.inject.Inject
import org.maproulette.framework.graphql.schemas._
import org.maproulette.framework.graphql.fetchers._
import sangria.schema.{ObjectType, fields}
import sangria.execution.deferred.DeferredResolver

/**
  * @author mcuthbert
  */
class GraphQL @Inject() (
    projectSchema: ProjectSchema,
    challengeSchema: ChallengeSchema,
    commentSchema: CommentSchema,
    grantSchema: GrantSchema,
    userSchema: UserSchema,
    tagSchema: TagSchema,
    teamSchema: TeamSchema,
    actionItemSchema: ActionItemSchema
) {
  private val queries =
    MRSchema.baseQueries ++
      projectSchema.queries ++
      challengeSchema.queries ++
      commentSchema.queries ++
      grantSchema.queries ++
      userSchema.queries ++
      tagSchema.queries ++
      teamSchema.queries ++
      actionItemSchema.queries

  private val mutations =
    MRSchema.baseMutations ++
      projectSchema.mutations ++
      challengeSchema.mutations ++
      commentSchema.mutations ++
      grantSchema.mutations ++
      userSchema.mutations ++
      tagSchema.mutations ++
      teamSchema.mutations

  private val fetchers =
    UserFetchers.fetchers ++
      TaskFetchers.fetchers ++
      ChallengeFetchers.fetchers ++
      ProjectFetchers.fetchers ++
      TeamFetchers.fetchers

  val schema: sangria.schema.Schema[UserContext, Unit] = sangria.schema.Schema[UserContext, Unit](
    query = ObjectType("Query", fields(queries: _*)),
    mutation = Some(ObjectType("Mutation", fields(mutations: _*)))
  )

  val resolver = DeferredResolver.fetchers(fetchers: _*)
}

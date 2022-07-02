/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.graphql.schemas

import javax.inject.Inject
import org.maproulette.Config
import org.maproulette.framework.graphql.UserContext
import org.maproulette.data.ActionManager
import sangria.schema._

/**
  * @author nrotstan
  */
class ActionItemSchema @Inject() (val manager: ActionManager) extends MRSchemaTypes {
  val queries: List[Field[UserContext, Unit]] = List(
    Field(
      name = "recentActions",
      description = Some("Retrieve recent actions, optionally filtered by user (osm ids)"),
      fieldType = ListType(ActionItemType),
      arguments = UserSchema.osmIdsArg :: MRSchema.pagingLimitArg :: MRSchema.pagingOffsetArg :: Nil,
      resolve = context => {
        // Don't allow all activity to be retrieved
        val limit = if (context.arg(MRSchema.pagingLimitArg) > 0) {
          context.arg(MRSchema.pagingLimitArg)
        } else {
          Config.DEFAULT_LIST_SIZE
        }

        this.manager.getRecentActivity(
          context.arg(UserSchema.osmIdsArg).getOrElse(List.empty).toList,
          limit,
          context.arg(MRSchema.pagingOffsetArg) * limit
        )
      }
    )
  )
}

object ActionItemSchema {
  val actionItemIdArg: Argument[Long] = Argument("actionItemId", LongType, "The action identifier")
}

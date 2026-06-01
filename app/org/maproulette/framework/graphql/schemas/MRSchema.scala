/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.graphql.schemas

import org.maproulette.exception.NotFoundException
import org.maproulette.framework.graphql.UserContext
import org.maproulette.framework.service.ServiceMixin
import sangria.schema._
import sangria.validation.Violation

/**
  * @author mcuthbert
  */
trait MRSchema[T] {
  val service: ServiceMixin[T]

  def retrieveObject(id: Long): T = {
    this.service.retrieve(id) match {
      case Some(o) => o
      case None    => throw new NotFoundException(s"No object found with id $id")
    }
  }
}

object MRSchema extends MRSchemaTypes {
  val idArg: Argument[Long]       = Argument("id", LongType, "The ID of the object")
  val idsArg                      = Argument("ids", ListInputType(LongType), "A list of ID's for the object")
  val osmIdArg: Argument[Long]    = Argument("osmId", LongType, "A user's OSM ID")
  val nameArg: Argument[String]   = Argument("name", StringType, "The name of the object")
  val apiKeyArg: Argument[String] = Argument("apiKey", StringType, "The user's API key")
  val pagingOffsetArg: Argument[Int] =
    Argument(
      "offset",
      OptionInputType(IntType),
      "The paging offset, defaults to 0 being the first page.",
      defaultValue = 0
    )
  val pagingLimitArg: Argument[Int] = Argument(
    "limit",
    OptionInputType(IntType),
    "The paging limit, defaults to 0 (ALL). This will limit the amount of objects in the list",
    defaultValue = 0
  )
  val orderArg: Argument[Option[Seq[String]]] = Argument(
    "order",
    OptionInputType(ListInputType(StringType)),
    "The ordering of the returned results"
  )

  val authQuery: Field[UserContext, Unit] =
    Field(
      name = "auth",
      description = Some(
        "The auth function authenticates a user based on an APIKey for any required authenticated requests in graphQL"
      ),
      fieldType = OptionType(UserType),
      arguments = apiKeyArg :: Nil,
      resolve = context =>
        UpdateCtx(context.ctx.getUser(context.arg(apiKeyArg))) { user =>
          context.ctx.copy(user = user)
        }
    )

  val baseQueries: List[Field[UserContext, Unit]]   = List(authQuery)
  val baseMutations: List[Field[UserContext, Unit]] = List(authQuery)
}

case object DateTimeCoerceViolation extends Violation {
  override def errorMessage: String = "Error during parsing DateTime"
}

case object ArrayLongCoerceViolation extends Violation {
  override def errorMessage: String = "Error during parsing Array[Long]"
}

case object JsonCoerceViolation extends Violation {
  override def errorMessage: String = "Not valid JSON"
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.graphql.schemas

import javax.inject.Inject
import org.maproulette.exception.NotFoundException
import org.maproulette.framework.graphql.UserContext
import org.maproulette.framework.model._
import org.maproulette.framework.psql.{Order, OrderField, Paging}
import org.maproulette.framework.service.ProjectService
import play.api.libs.json.Json
import sangria.macros.derive._
import sangria.schema._

/**
  * @author mcuthbert
  */
class ProjectSchema @Inject() (override val service: ProjectService)
    extends MRSchema[Project]
    with MRSchemaTypes {
  val queries: List[Field[UserContext, Unit]] = List(
    Field(
      name = "project",
      description = Some("Retrieve a project based on the provided identifier"),
      fieldType = OptionType(ProjectType),
      arguments = MRSchema.idArg :: Nil,
      resolve = context => this.service.retrieve(context.arg(MRSchema.idArg))
    ),
    Field(
      name = "projects",
      description = Some("List all the projects based on the search criteria"),
      fieldType = ListType(ProjectType),
      arguments = ProjectSchema.searchArg :: MRSchema.pagingLimitArg :: MRSchema.pagingOffsetArg :: ProjectSchema.onlyEnabledArg :: MRSchema.orderArg :: Nil,
      resolve = context =>
        this.service.find(
          context.arg(ProjectSchema.searchArg),
          Paging(context.arg(MRSchema.pagingLimitArg), context.arg(MRSchema.pagingOffsetArg)),
          context.arg(ProjectSchema.onlyEnabledArg).getOrElse(true),
          Order(context.arg(MRSchema.orderArg).getOrElse(Seq.empty).toList.map(OrderField(_)))
        )
    ),
    Field(
      name = "projectChildren",
      description = Some("All the challenge children for the provided project identifier"),
      fieldType = ListType(ChallengeType),
      arguments = MRSchema.idArg :: Nil,
      resolve = context => this.service.children(context.arg(MRSchema.idArg))
    )
  )

  val mutations: List[Field[UserContext, Unit]] = List(
    Field(
      name = "createProject",
      description = Some("Creates a new Project"),
      fieldType = ProjectType,
      arguments = ProjectSchema.projectArg :: Nil,
      resolve = context => {
        val user = context.ctx.user
        this.service
          .create(
            context.arg(ProjectSchema.projectArg).copy(id = -1, owner = user.osmProfile.id),
            user
          )
      }
    ),
    Field(
      name = "updateProject",
      description = Some("Updates a project"),
      fieldType = OptionType(ProjectType),
      arguments = MRSchema.idArg :: ProjectSchema.projectArg :: Nil,
      resolve = context => {
        val user      = context.ctx.user
        val projectId = context.arg(MRSchema.idArg)
        val project = this.service.retrieve(projectId) match {
          case Some(p) => p
          case None =>
            throw new NotFoundException(s"No project with id $projectId found to update!")
        }
        this.service.update(
          project.id,
          Json.toJson(context.arg(ProjectSchema.projectArg)),
          user
        )
      }
    ),
    Field(
      name = "deleteProject",
      description = Some("Deletes a project"),
      fieldType = BooleanType,
      arguments = List(MRSchema.idArg),
      resolve = context => {
        val user = context.ctx.user
        this.service.delete(context.arg(MRSchema.idArg), user)
      }
    )
  )
}

object ProjectSchema {
  import sangria.marshalling.playJson._

  implicit val ProjectInputType: InputObjectType[Project] = deriveInputObjectType[Project](
    InputObjectTypeName("ProjectInput"),
    InputObjectTypeDescription("A project in MapRoulette"),
    ExcludeInputFields("created", "modified", "grants", "completionMetrics")
  )

  val projectIdArg: Argument[Long]  = Argument("projectId", LongType, "The project identifier")
  val projectArg: Argument[Project] = Argument("project", ProjectInputType, "The project object")
  val onlyEnabledArg: Argument[Option[Boolean]] =
    Argument(
      "onlyEnabled",
      OptionInputType(BooleanType),
      "Set if the you only want the 'onlyEnabled' flag set"
    )
  val searchArg: Argument[String] =
    Argument("search", StringType, "Search string for Project names or display names")
}

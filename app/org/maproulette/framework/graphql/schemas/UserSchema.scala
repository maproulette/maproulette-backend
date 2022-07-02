/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.graphql.schemas

import javax.inject.Inject
import org.maproulette.exception.NotFoundException
import org.maproulette.framework.graphql.UserContext
import org.maproulette.framework.model._
import org.maproulette.framework.psql.Paging
import org.maproulette.framework.service.UserService
import play.api.libs.json.{DefaultWrites, Json, Reads}
import sangria.macros.derive._
import sangria.schema._

/**
  * @author mcuthbert
  */
class UserSchema @Inject() (override val service: UserService)
    extends MRSchema[User]
    with MRSchemaTypes {
  val queries: List[Field[UserContext, Unit]] = List(
    Field(
      name = "user",
      description = Some("Retrieve a user based on the provided identifier"),
      fieldType = OptionType(UserType),
      arguments = MRSchema.idArg :: Nil,
      resolve = context => this.service.retrieve(context.arg(MRSchema.idArg))
    ),
    Field(
      name = "users",
      description = Some("Retrieve all the users based on a provided set of ids"),
      fieldType = ListType(UserType),
      arguments = MRSchema.idsArg :: MRSchema.pagingLimitArg :: MRSchema.pagingOffsetArg :: Nil,
      resolve = context =>
        this.service.retrieveListById(
          context.arg(MRSchema.idsArg).toList,
          Paging(context.arg(MRSchema.pagingLimitArg), context.arg(MRSchema.pagingOffsetArg))
        )
    ),
    Field(
      name = "retrieveByApiKey",
      description = Some("Retrieve a user based on their id and API Key"),
      fieldType = OptionType(UserType),
      arguments = MRSchema.idArg :: MRSchema.apiKeyArg :: Nil,
      resolve = context =>
        this.service.retrieveByAPIKey(
          context.arg(MRSchema.idArg),
          context.arg(MRSchema.apiKeyArg),
          context.ctx.user
        )
    ),
    Field(
      name = "retrieveByOSMUsername",
      description = Some("Retrieve a user based on their username"),
      fieldType = OptionType(UserType),
      arguments = MRSchema.nameArg :: Nil,
      resolve = context =>
        this.service.retrieveByOSMUsername(context.arg(MRSchema.nameArg), context.ctx.user)
    ),
    Field(
      name = "searchByOSMUsername",
      description = Some("Search the users based on OSM username"),
      fieldType = ListType(UserType),
      arguments = MRSchema.nameArg :: MRSchema.pagingLimitArg :: MRSchema.pagingOffsetArg :: Nil,
      resolve = context =>
        this.service.searchByOSMUsername(
          context.arg(MRSchema.nameArg),
          Paging(context.arg(MRSchema.pagingLimitArg), context.arg(MRSchema.pagingOffsetArg))
        )
    ),
    Field(
      name = "retrieveByOSMId",
      description = Some("Retrieve user based on their OSM identifier"),
      fieldType = OptionType(UserType),
      arguments = MRSchema.osmIdArg :: Nil,
      resolve = context => this.service.retrieveByOSMId(context.arg(MRSchema.osmIdArg))
    ),
    Field(
      name = "savedChallenges",
      description = Some("Retrieve all the saved challenges for a given user"),
      fieldType = ListType(ChallengeType),
      arguments = MRSchema.idArg :: MRSchema.pagingLimitArg :: MRSchema.pagingOffsetArg :: Nil,
      resolve = context =>
        this.service.getSavedChallenges(
          context.arg(MRSchema.idArg),
          context.ctx.user,
          Paging(context.arg(MRSchema.pagingLimitArg), context.arg(MRSchema.pagingOffsetArg))
        )
    ),
    Field(
      name = "savedTasks",
      description = Some("Retrieve all the saved tasks for a given user"),
      fieldType = ListType(TaskType),
      arguments = MRSchema.idArg :: ChallengeSchema.challengeIdsArg :: MRSchema.pagingLimitArg :: MRSchema.pagingOffsetArg :: Nil,
      resolve = context =>
        this.service.getSavedTasks(
          context.arg(MRSchema.idArg),
          context.ctx.user,
          context.arg(ChallengeSchema.challengeIdsArg),
          Paging(context.arg(MRSchema.pagingLimitArg), context.arg(MRSchema.pagingOffsetArg))
        )
    ),
    Field(
      name = "projectManagers",
      description = Some("Retrieve all the project managers for a given project"),
      fieldType = ListType(ProjectManagerType),
      arguments = ProjectSchema.projectIdArg :: UserSchema.osmIdsArg :: Nil,
      resolve = context =>
        this.service.getUsersManagingProject(
          context.arg(ProjectSchema.projectIdArg),
          context.arg(UserSchema.osmIdsArg).map(_.toList),
          context.ctx.user
        )
    )
  )

  val mutations: List[Field[UserContext, Unit]] = List(
    Field(
      name = "updateAPIKey",
      description = Some("Update the API key for a specified user"),
      fieldType = OptionType(UserType),
      arguments = MRSchema.idArg :: Nil,
      resolve = context =>
        this.service
          .generateAPIKey(this.retrieveObject(context.arg(MRSchema.idArg)), context.ctx.user)
    ),
    Field(
      name = "delete",
      description = Some("Delete a user based on the user identifier"),
      fieldType = BooleanType,
      arguments = MRSchema.idArg :: Nil,
      resolve = context => this.service.delete(context.arg(MRSchema.idArg), context.ctx.user)
    ),
    Field(
      name = "deleteByOsmID",
      description = Some("Delete a user based on the user's OSM identifier"),
      fieldType = BooleanType,
      arguments = MRSchema.osmIdArg :: Nil,
      resolve =
        context => this.service.deleteByOsmID(context.arg(MRSchema.osmIdArg), context.ctx.user)
    ),
    Field(
      name = "update",
      description = Some("Updates a user's information that is not set by OpenStreetMap"),
      fieldType = UserType,
      arguments = MRSchema.idArg :: UserSchema.userSettingsArg :: UserSchema.propertiesArg :: Nil,
      resolve = context => {
        val userId = context.arg(MRSchema.idArg)
        val properties = context.arg(UserSchema.propertiesArg) match {
          case Some(value) => Some(Json.parse(value))
          case None        => None
        }
        this.service.managedUpdate(
          userId,
          context.arg(UserSchema.userSettingsArg),
          properties,
          context.ctx.user
        ) match {
          case Some(u) => u
          case None    => throw new NotFoundException(s"No user found with id $userId")
        }
      }
    ),
    Field(
      name = "removeUserFromProject",
      description = Some("Removes a user from a specific project"),
      fieldType = BooleanType,
      arguments = MRSchema.osmIdArg :: ProjectSchema.projectIdArg :: GrantSchema.roleArg :: Nil,
      resolve = context => {
        // -1 indicates all roles, represented as an absence of a role filter
        val roleFilter =
          if (context.arg(GrantSchema.roleArg) == -1)
            None
          else
            Some(context.arg(GrantSchema.roleArg))

        this.service.removeUserFromProject(
          context.arg(MRSchema.osmIdArg),
          context.arg(ProjectSchema.projectIdArg),
          roleFilter,
          context.ctx.user
        )
        true
      }
    ),
    Field(
      name = "addUserToProject",
      description = Some("Adds a user to a specified project"),
      fieldType = UserType,
      arguments = MRSchema.osmIdArg :: ProjectSchema.projectIdArg :: GrantSchema.roleArg :: Nil,
      resolve = context =>
        this.service.addUserToProject(
          context.arg(MRSchema.osmIdArg),
          context.arg(ProjectSchema.projectIdArg),
          context.arg(GrantSchema.roleArg),
          context.ctx.user
        )
    ),
    Field(
      name = "UserHome",
      description = Some("Retrieves the users Project Home"),
      fieldType = ProjectType,
      arguments = MRSchema.osmIdArg :: Nil,
      resolve = context => {
        val osmId = context.arg(MRSchema.osmIdArg)
        this.service.retrieveByOSMId(osmId) match {
          case Some(u) => this.service.getHomeProject(u)
          case None    => throw new NotFoundException(s"No user found for OSM id $osmId")
        }
      }
    ),
    Field(
      name = "saveChallenge",
      description = Some("Saves a challenge to a users profile"),
      fieldType = BooleanType,
      arguments = MRSchema.idArg :: ChallengeSchema.challengeIdArg :: Nil,
      resolve = context => {
        this.service.saveChallenge(
          context.arg(MRSchema.idArg),
          context.arg(ChallengeSchema.challengeIdArg),
          context.ctx.user
        )
        true
      }
    ),
    Field(
      name = "unsaveChallenge",
      description = Some("Removes a challenge from a users profile"),
      fieldType = BooleanType,
      arguments = MRSchema.idArg :: ChallengeSchema.challengeIdArg :: Nil,
      resolve = context => {
        this.service.unsaveChallenge(
          context.arg(MRSchema.idArg),
          context.arg(ChallengeSchema.challengeIdArg),
          context.ctx.user
        )
        true
      }
    ),
    Field(
      name = "saveTask",
      description = Some("Saves a task to a users profile"),
      fieldType = BooleanType,
      arguments = MRSchema.idArg :: TaskSchema.taskIdArg :: Nil,
      resolve = context => {
        this.service.saveTask(
          context.arg(MRSchema.idArg),
          context.arg(TaskSchema.taskIdArg),
          context.ctx.user
        )
        true
      }
    ),
    Field(
      name = "unsaveTask",
      description = Some("Removes a task from a users profile"),
      fieldType = BooleanType,
      arguments = MRSchema.idArg :: TaskSchema.taskIdArg :: Nil,
      resolve = context => {
        this.service.unsaveTask(
          context.arg(MRSchema.idArg),
          context.arg(TaskSchema.taskIdArg),
          context.ctx.user
        )
        true
      }
    ),
    Field(
      name = "follow",
      description = Some("Follow a user"),
      fieldType = UserType,
      arguments = MRSchema.idArg :: Nil,
      resolve = context => {
        val user       = context.ctx.user
        val followedId = context.arg(MRSchema.idArg)
        val followed = this.service.retrieve(followedId) match {
          case Some(u) => u
          case None =>
            throw new NotFoundException(s"No user with id $followedId found")
        }
        context.ctx.services.follow.follow(user, followed, user)
        user
      }
    ),
    Field(
      name = "stopFollowing",
      description = Some("Stop following a user"),
      fieldType = UserType,
      arguments = MRSchema.idArg :: Nil,
      resolve = context => {
        val user       = context.ctx.user
        val followedId = context.arg(MRSchema.idArg)
        val followed = this.service.retrieve(followedId) match {
          case Some(u) => u
          case None =>
            throw new NotFoundException(s"No user with id $followedId found")
        }
        context.ctx.services.follow.stopFollowing(user, followed, user)
        user
      }
    ),
    Field(
      name = "blockFollower",
      description = Some("Block a follower from following a user"),
      fieldType = UserType,
      arguments = MRSchema.idArg :: Nil,
      resolve = context => {
        val user       = context.ctx.user
        val followerId = context.arg(MRSchema.idArg)
        context.ctx.services.follow.blockFollower(followerId, user, user)
        user
      }
    ),
    Field(
      name = "unblockFollower",
      description = Some("Unblock a blocked follower"),
      fieldType = UserType,
      arguments = MRSchema.idArg :: Nil,
      resolve = context => {
        val user       = context.ctx.user
        val followerId = context.arg(MRSchema.idArg)
        context.ctx.services.follow.unblockFollower(followerId, user, user)
        user
      }
    )
  )
}

object UserSchema extends DefaultWrites {
  import sangria.marshalling.playJson._
  implicit val settingsReads: Reads[UserSettings] = User.settingsReads

  implicit val CustomBasemapInputType: InputObjectType[CustomBasemap] =
    deriveInputObjectType[CustomBasemap](
      InputObjectTypeName("CustomBasemapInput"),
      InputObjectTypeDescription("Custom Basemaps for a user settings object")
    )

  implicit val UserSettingsInputType: InputObjectType[UserSettings] =
    deriveInputObjectType[UserSettings](
      InputObjectTypeName("UserSettingsInput"),
      InputObjectTypeDescription("Settings for a user object")
    )

  val userIdArg: Argument[Long] = Argument("userId", LongType, "The user identifier")
  val userSettingsArg: Argument[UserSettings] = Argument(
    "settings",
    UserSettingsInputType,
    "The user settings that are specific to MapRoulette and not retrieved from OpenStreetMap"
  )
  val propertiesArg: Argument[Option[String]] = Argument(
    "properties",
    OptionInputType(StringType),
    "The user properties that are specific to MapRoulette"
  )
  val osmIdsArg: Argument[Option[Seq[Long]]] =
    Argument(
      "osmIds",
      OptionInputType(ListInputType(LongType)),
      "An optional list of OSM identifiers"
    )
}

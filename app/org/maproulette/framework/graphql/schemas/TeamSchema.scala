/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.graphql.schemas

import javax.inject.Inject
import org.maproulette.exception.NotFoundException
import org.maproulette.framework.graphql.fetchers.TeamFetchers
import org.maproulette.framework.graphql.UserContext
import org.maproulette.framework.model._
import org.maproulette.framework.service.TeamService
import play.api.libs.json.DefaultWrites
import sangria.schema._

/**
  * @author nrotstan
  */
class TeamSchema @Inject() (override val service: TeamService)
    extends MRSchema[Group]
    with MRSchemaTypes {
  val queries: List[Field[UserContext, Unit]] = List(
    Field(
      name = "team",
      description = Some("Retrieve team with ID"),
      fieldType = OptionType(GroupType),
      arguments = MRSchema.idArg :: Nil,
      resolve = context => TeamFetchers.teamsFetcher.deferOpt(context.arg(MRSchema.idArg))
    ),
    Field(
      name = "teams",
      description = Some("Retrieve teams matching IDs"),
      fieldType = ListType(GroupType),
      arguments = MRSchema.idsArg :: Nil,
      resolve = context => TeamFetchers.teamsFetcher.deferSeq(context.arg(MRSchema.idsArg))
    ),
    Field(
      name = "teamUsers",
      description = Some("Retrieve users on a team"),
      fieldType = ListType(TeamUserType),
      arguments = MRSchema.idArg :: Nil,
      resolve = context =>
        TeamFetchers.teamUsersFetcher.deferRelSeq(
          TeamFetchers.teamUsersByTeamRel,
          context.arg(MRSchema.idArg)
        )
    ),
    Field(
      name = "userTeams",
      description = Some("Retrieve team memberships for users matching IDs"),
      fieldType = ListType(TeamUserType),
      arguments = MRSchema.idArg :: Nil,
      resolve = context =>
        TeamFetchers.userTeamsFetcher.deferRelSeq(
          TeamFetchers.teamUsersByUserRel,
          context.arg(MRSchema.idArg)
        )
    )
  )

  val mutations: List[Field[UserContext, Unit]] = List(
    Field(
      name = "createTeam",
      description = Some("Creates a new Team"),
      fieldType = OptionType(GroupType),
      arguments = MRSchema.nameArg :: TeamSchema.descriptionArg :: TeamSchema.avatarUrlArg :: Nil,
      resolve = context => {
        val user = context.ctx.user
        this.service.create(
          Group(
            -1,
            context.arg(MRSchema.nameArg),
            context.arg(TeamSchema.descriptionArg),
            context.arg(TeamSchema.avatarUrlArg),
            Group.GROUP_TYPE_TEAM
          ),
          MemberObject.user(user.id),
          user
        )
      }
    ),
    Field(
      name = "updateTeam",
      description = Some("Updates a team"),
      fieldType = OptionType(GroupType),
      arguments = MRSchema.idArg :: TeamSchema.optionalNameArg :: TeamSchema.descriptionArg :: TeamSchema.avatarUrlArg :: Nil,
      resolve = context => {
        val user   = context.ctx.user
        val teamId = context.arg(MRSchema.idArg)
        val team = this.service.retrieve(teamId, user) match {
          case Some(t) => t
          case None    => throw new NotFoundException(s"No team with id ${teamId} found")
        }
        this.service.updateTeam(
          team.copy(
            name = context.arg(TeamSchema.optionalNameArg) match {
              case Some(name) => name
              case None       => team.name
            },
            description = context.arg(TeamSchema.descriptionArg) match {
              case Some(description) => Some(description)
              case None              => team.description
            },
            avatarURL = context.arg(TeamSchema.avatarUrlArg) match {
              case Some(avatarURL) => Some(avatarURL)
              case None            => team.avatarURL
            }
          ),
          user
        )
      }
    ),
    Field(
      name = "deleteTeam",
      description = Some("Deletes a team"),
      fieldType = BooleanType,
      arguments = MRSchema.idArg :: Nil,
      resolve = context => {
        val user   = context.ctx.user
        val teamId = context.arg(MRSchema.idArg)
        val team = this.service.retrieve(teamId, user) match {
          case Some(t) => t
          case None =>
            throw new NotFoundException(s"No team with id $teamId found")
        }
        this.service.deleteTeam(team, user)
      }
    ),
    Field(
      name = "inviteTeamUser",
      description = Some("Invite a user to join a team with a role"),
      fieldType = OptionType(TeamUserType),
      arguments = MRSchema.idArg :: UserSchema.userIdArg :: GrantSchema.roleArg :: Nil,
      resolve = context => {
        val user   = context.ctx.user
        val teamId = context.arg(MRSchema.idArg)
        val userId = context.arg(UserSchema.userIdArg)
        val role   = context.arg(GrantSchema.roleArg)
        this.service.inviteTeamUser(teamId, userId, role, user)
      }
    ),
    Field(
      name = "acceptTeamInvite",
      description = Some("Accept an invitation to join a team"),
      fieldType = OptionType(TeamUserType),
      arguments = MRSchema.idArg :: Nil,
      resolve = context => {
        val user   = context.ctx.user
        val teamId = context.arg(MRSchema.idArg)
        this.service.acceptUserInvitation(teamId, user.id, User.superUser)
      }
    ),
    Field(
      name = "declineTeamInvite",
      description = Some("Decline an invitation to join a team"),
      fieldType = BooleanType,
      arguments = MRSchema.idArg :: Nil,
      resolve = context => {
        val user   = context.ctx.user
        val teamId = context.arg(MRSchema.idArg)
        this.service.declineInvitation(teamId, MemberObject.user(user.id), User.superUser)
      }
    ),
    Field(
      name = "updateMemberRole",
      description = Some("Update the role granted to a user member on a team"),
      fieldType = TeamUserType,
      arguments = MRSchema.idArg :: UserSchema.userIdArg :: GrantSchema.roleArg :: Nil,
      resolve = context => {
        val user   = context.ctx.user
        val teamId = context.arg(MRSchema.idArg)
        val userId = context.arg(UserSchema.userIdArg)
        val role   = context.arg(GrantSchema.roleArg)
        this.service.updateUserRole(teamId, userId, role, user)
      }
    ),
    Field(
      name = "removeTeamUser",
      description = Some("Remove a member user from a team"),
      fieldType = BooleanType,
      arguments = MRSchema.idArg :: UserSchema.userIdArg :: Nil,
      resolve = context => {
        val user   = context.ctx.user
        val teamId = context.arg(MRSchema.idArg)
        val userId = context.arg(UserSchema.userIdArg)
        val team = this.service.retrieve(teamId, user) match {
          case Some(t) => t
          case None    => throw new NotFoundException(s"No team with id $teamId found")
        }
        this.service.removeTeamMember(team, MemberObject.user(userId), user)
      }
    )
  )
}

object TeamSchema extends DefaultWrites {
  val optionalNameArg: Argument[Option[String]] =
    Argument(
      "name",
      OptionInputType(StringType),
      "Name of object"
    )

  val descriptionArg: Argument[Option[String]] =
    Argument(
      "description",
      OptionInputType(StringType),
      "The description of the object"
    )
  val avatarUrlArg: Argument[Option[String]] =
    Argument(
      "avatarURL",
      OptionInputType(StringType),
      "An avatar URL representing the object"
    )
}

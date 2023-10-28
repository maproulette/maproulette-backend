/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.graphql.schemas

import play.api.libs.json._

import org.joda.time.DateTime
import org.maproulette.framework.model._
import org.maproulette.framework.graphql.fetchers._
import org.maproulette.framework.graphql.UserContext
import org.maproulette.data._
import play.api.libs.oauth.RequestToken
import sangria.ast.StringValue
import sangria.macros.derive._
import sangria.schema._
import scala.concurrent.{Future}

/**
  * @author mcuthbert
  */
trait MRSchemaTypes {
  implicit val IdentifiableType = InterfaceType(
    "Identifiable",
    fields[Unit, Identifiable](
      Field("id", LongType, resolve = _.value.id)
    )
  )
  implicit val graphQLDateTime: ScalarType[DateTime] = ScalarType[DateTime](
    "DateTime",
    coerceOutput = (dt, _) => dt.toString,
    coerceInput = {
      case StringValue(dt, _, _, _, _) => Right(DateTime.parse(dt))
      case _                           => Left(DateTimeCoerceViolation)
    },
    coerceUserInput = {
      case s: String => Right(DateTime.parse(s))
      case _         => Left(DateTimeCoerceViolation)
    }
  )

  // Project Types
  implicit lazy val ProjectType: ObjectType[Unit, Project] =
    deriveObjectType[Unit, Project](ObjectTypeName("Project"))
  // Grant Types
  implicit val GranteeType = ObjectType(
    "Grantee",
    "Something granted a security role",
    fields[Unit, Grantee](
      Field("granteeType", IntType, resolve = _.value.granteeType.typeId),
      Field("granteeId", LongType, resolve = _.value.granteeId)
    )
  )
  implicit val GrantTargetType = ObjectType(
    "GrantTarget",
    "Something to which a security role is granted",
    fields[Unit, GrantTarget](
      Field("objectType", IntType, resolve = _.value.objectType.typeId),
      Field("objectId", LongType, resolve = _.value.objectId)
    )
  )
  implicit val GrantType: ObjectType[Unit, Grant] =
    deriveObjectType[Unit, Grant](ObjectTypeName("Grant"))
  // User Types
  implicit val RequestTokenType: ObjectType[Unit, RequestToken] =
    deriveObjectType[Unit, RequestToken](ObjectTypeName("RequestToken"))
  implicit val CustomBasemapType: ObjectType[Unit, CustomBasemap] =
    deriveObjectType[Unit, CustomBasemap](ObjectTypeName("CustomBasemap"))
  implicit val UserSettingsType: ObjectType[Unit, UserSettings] =
    deriveObjectType[Unit, UserSettings](ObjectTypeName("UserSettings"))
  implicit val LocationType: ObjectType[Unit, Location] =
    deriveObjectType[Unit, Location](ObjectTypeName("Location"))
  implicit val OSMProfileType: ObjectType[Unit, OSMProfile] =
    deriveObjectType[Unit, OSMProfile](ObjectTypeName("OSMProfile"))
  implicit lazy val UserType: ObjectType[Unit, User] =
    deriveObjectType[Unit, User](
      ObjectTypeName("User"),
      Interfaces(IdentifiableType),
      ReplaceField(
        "followingGroupId",
        Field(
          "following",
          ListType(UserType),
          resolve = context => {
            val userContext = context.ctx.asInstanceOf[UserContext]
            Future.successful(
              userContext.services.follow
                .getUsersFollowedBy(context.value.id, userContext.user)
                .toSeq
            )
          }
        )
      ),
      ReplaceField(
        "followersGroupId",
        Field(
          "followers",
          ListType(FollowerType),
          resolve = context => {
            val userContext = context.ctx.asInstanceOf[UserContext]
            Future.successful(
              userContext.services.follow.getUserFollowers(context.value.id, userContext.user).toSeq
            )
          }
        )
      )
    )
  implicit lazy val ActionItemType: ObjectType[Unit, ActionItem] =
    deriveObjectType[Unit, ActionItem](
      ObjectTypeName("ActionItem"),
      Interfaces(IdentifiableType),
      ReplaceField(
        "osmUserId",
        Field(
          "user",
          UserType,
          resolve = context => UserFetchers.osmUsersFetcher.defer(context.value.osmUserId.get)
        )
      ),
      AddFields(
        Field(
          "task",
          OptionType(TaskType),
          resolve = context => {
            context.value.typeId match {
              case Some(typeId) if typeId == Actions.ITEM_TYPE_TASK =>
                TaskFetchers.tasksFetcher.deferOpt(context.value.itemId.get)
              case _ =>
                Future.successful(None)
            }
          }
        ),
        Field(
          "challenge",
          OptionType(ChallengeType),
          resolve = context => {
            context.value.parentId match {
              case Some(challengeId) =>
                context.value.typeId match {
                  case Some(typeId) if typeId == Actions.ITEM_TYPE_TASK =>
                    ChallengeFetchers.challengesFetcher.deferOpt(challengeId)
                  case _ =>
                    Future.successful(None)
                }
              case None => Future.successful(None)
            }
          }
        )
      )
    )
  implicit val ProjectManagerType: ObjectType[Unit, ProjectManager] =
    deriveObjectType[Unit, ProjectManager](ObjectTypeName("ProjectManager"))
  // Challenge Types
  implicit lazy val ChallengeType: ObjectType[Unit, Challenge] =
    deriveObjectType[Unit, Challenge](ObjectTypeName("Challenge"))
  implicit lazy val ChallengeGeneralType: ObjectType[Unit, ChallengeGeneral] =
    deriveObjectType[Unit, ChallengeGeneral](
      ObjectTypeName("ChallengeGeneral"),
      ReplaceField(
        "parent",
        Field(
          "parent",
          ProjectType,
          resolve = context => ProjectFetchers.projectsFetcher.defer(context.value.parent)
        )
      )
    )
  implicit val ChallengeCreationType: ObjectType[Unit, ChallengeCreation] =
    deriveObjectType[Unit, ChallengeCreation](ObjectTypeName("ChallengeCreation"))
  implicit val ChallengePriorityType: ObjectType[Unit, ChallengePriority] =
    deriveObjectType[Unit, ChallengePriority](ObjectTypeName("ChallengePriority"))

  implicit val ChallengeExtraType: ObjectType[Unit, ChallengeExtra] =
    deriveObjectType[Unit, ChallengeExtra](
      ObjectTypeName("ChallengeExtra"),
      ReplaceField(
        "taskWidgetLayout",
        Field(
          "taskWidgetLayout",
          StringType,
          resolve = ctx =>
            ctx.value.taskWidgetLayout match {
              case Some(jsValue) => Json.stringify(jsValue)
              case None          => "{}"
            }
        )
      )
    )
  // Comment Types
  implicit val CommentType: ObjectType[Unit, Comment] =
    deriveObjectType[Unit, Comment](ObjectTypeName("Comment"))
  // Task Types
  implicit val TaskType: ObjectType[Unit, Task] =
    deriveObjectType[Unit, Task](ObjectTypeName("Task"))
  implicit val TaskReviewFieldsType: ObjectType[Unit, TaskReviewFields] =
    deriveObjectType[Unit, TaskReviewFields](ObjectTypeName("TaskReviewFields"))
  implicit val MapillaryImageType: ObjectType[Unit, MapillaryImage] =
    deriveObjectType[Unit, MapillaryImage](ObjectTypeName("MapillaryImage"))
  // Task Bundle Types
  implicit val TaskBundleType: ObjectType[Unit, TaskBundle] =
    deriveObjectType[Unit, TaskBundle](ObjectTypeName("TaskBundle"))
  // Tag Types
  implicit val TagType: ObjectType[Unit, Tag] =
    deriveObjectType[Unit, Tag](ObjectTypeName("Keyword"))
  implicit lazy val GroupType: ObjectType[Unit, Group] =
    deriveObjectType[Unit, Group](
      ObjectTypeName("Group"),
      Interfaces(IdentifiableType),
      AddFields(
        Field(
          "teamUsers",
          ListType(TeamUserType),
          resolve = ctx =>
            TeamFetchers.teamUsersFetcher.deferRelSeq(TeamFetchers.teamUsersByTeamRel, ctx.value.id)
        )
      )
    )
  implicit lazy val GroupMemberType: ObjectType[Unit, GroupMember] =
    deriveObjectType[Unit, GroupMember](
      ObjectTypeName("GroupMember"),
      Interfaces(IdentifiableType)
    )
  implicit lazy val TeamUserType: ObjectType[Unit, TeamUser] = deriveObjectType[Unit, TeamUser](
    ObjectTypeName("TeamUser"),
    Interfaces(IdentifiableType),
    ExcludeFields("teamId"),
    AddFields(
      Field(
        "team",
        GroupType,
        resolve = ctx => TeamFetchers.teamsFetcher.defer(ctx.value.teamId)
      )
    )
  )
  implicit lazy val FollowerType: ObjectType[Unit, Follower] = deriveObjectType[Unit, Follower](
    ObjectTypeName("Follower"),
    Interfaces(IdentifiableType)
  )
}

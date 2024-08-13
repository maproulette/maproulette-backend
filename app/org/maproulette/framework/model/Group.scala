/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.joda.time.DateTime
import org.maproulette.framework.psql.CommonField
import org.maproulette.data.{UserType}
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.libs.json.JodaWrites._
import play.api.libs.json.JodaReads._

/**
  * Groups represent simple collections of member objects, such teams of users.
  * Group members are referenced by both type and id for flexiblity
  *
  * @author nrotstan
  */
case class Group(
    id: Long,
    name: String,
    description: Option[String] = None,
    avatarURL: Option[String] = None,
    groupType: Int = Group.GROUP_TYPE_STANDARD,
    created: DateTime = DateTime.now(),
    modified: DateTime = DateTime.now()
) extends Identifiable

object Group extends CommonField {
  implicit val writes: Writes[Group] = Json.writes[Group]
  implicit val reads: Reads[Group] = (
    (JsPath \ "id").read[Long] and
      (JsPath \ "name").read[String] and
      (JsPath \ "description").readNullable[String] and
      (JsPath \ "avatarURL").readNullable[String] and
      (JsPath \ "groupType").read[Int] and
      ((JsPath \ "created").read[DateTime] or Reads.pure(DateTime.now())) and
      ((JsPath \ "modified").read[DateTime] or Reads.pure(DateTime.now()))
  )(Group.apply _)

  val TABLE            = "groups"
  val FIELD_GROUP_TYPE = "group_type"

  // Types of groups
  val GROUP_TYPE_STANDARD  = 0
  val GROUP_TYPE_TEAM      = 1
  val GROUP_TYPE_FOLLOWING = 2
  val GROUP_TYPE_FOLLOWERS = 3
}

/**
  * GroupMember represents an object that belongs to a Group
  */
case class GroupMember(
    id: Long,
    groupId: Long,
    memberType: Int,
    memberId: Long,
    status: Int,
    created: DateTime = DateTime.now(),
    modified: DateTime = DateTime.now()
) extends Identifiable {
  def asMemberObject(): MemberObject =
    MemberObject(this.memberType, this.memberId)
}

object GroupMember extends CommonField {
  implicit val writes: Writes[GroupMember] = Json.writes[GroupMember]
  implicit val reads: Reads[GroupMember]   = Json.reads[GroupMember]

  val TABLE             = "group_members"
  val FIELD_GROUP_ID    = "group_id"
  val FIELD_MEMBER_TYPE = "member_type"
  val FIELD_MEMBER_ID   = "member_id"
  val FIELD_STATUS      = "status"

  // Member statuses are specific to the type of group, but a baseline "member"
  // status is defined here as a default
  val STATUS_MEMBER = 0
}

case class MemberObject(
    objectType: Int,
    objectId: Long
)

object MemberObject {
  def user(userId: Long) = MemberObject(UserType().typeId, userId)
}

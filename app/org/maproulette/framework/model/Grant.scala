/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.maproulette.data.{ItemType, UserType, ProjectType, GroupType, Actions}
import org.maproulette.framework.psql.CommonField
import play.api.libs.json._
import play.api.libs.functional.syntax._

/**
  * @author nrotstan
  */
/**
  * Grantee represents something that has been granted a security role,
  * identified by an ItemType and id (for example a UserType and user id).
  * Convenience methods are included for constructing common types of Grantee
  * with just an appropriate id
  */
case class Grantee(
    granteeType: ItemType,
    granteeId: Long
)

object Grantee {
  def withItemType(itemType: Int, granteeId: Long) =
    new Grantee(Actions.getItemType(itemType).get, granteeId)

  implicit val implicitGranteeWrites = new Writes[Grantee] {
    def writes(g: Grantee): JsValue = {
      Json.obj(
        "granteeType" -> g.granteeType.typeId,
        "granteeId"   -> g.granteeId
      )
    }
  }

  implicit val implicitGranteeReads: Reads[Grantee] = (
    (JsPath \ "granteeType").read[Int] and
      (JsPath \ "granteeId").read[Long]
  )(Grantee.withItemType _)

  def user(userId: Long)   = Grantee(UserType(), userId)
  def group(groupId: Long) = Grantee(GroupType(), groupId)
}

/**
  * GrantTarget represents something on which security roles are being granted,
  * identified by an ItemType and id (for example a ProjectType and project id).
  * Convenience methods are included for constructing common types of
  * GrantTarget with just an appropriate id
  */
case class GrantTarget(
    objectType: ItemType,
    objectId: Long
)

object GrantTarget {
  def withItemType(itemType: Int, targetId: Long) =
    new GrantTarget(Actions.getItemType(itemType).get, targetId)

  implicit val implicitGrantTargetWrites = new Writes[GrantTarget] {
    def writes(g: GrantTarget): JsValue = {
      Json.obj(
        "objectType" -> g.objectType.typeId,
        "objectId"   -> g.objectId
      )
    }
  }

  implicit val implicitGrantTargetReads: Reads[GrantTarget] = (
    (JsPath \ "objectType").read[Int] and
      (JsPath \ "objectId").read[Long]
  )(GrantTarget.withItemType _)

  // Convenience methods for generating GrantTarget instances for common types
  def project(projectId: Long) = GrantTarget(ProjectType(), projectId)
  def group(groupId: Long)     = GrantTarget(GroupType(), groupId)
}

case class Grant(
    id: Long,
    name: String,
    grantee: Grantee,
    role: Int,
    target: GrantTarget
) {
  def description(): String = {
    val granteeTypeName = Actions.getTypeName(this.grantee.granteeType.typeId).getOrElse("Grantee")
    val targetTypeName  = Actions.getTypeName(this.target.objectType.typeId).getOrElse("Object")
    val roleName        = Grant.roleNameMap.getOrElse(this.role, "Role")
    s"${granteeTypeName} ${this.grantee.granteeId} granted ${roleName} on ${targetTypeName} ${this.target.objectId}"
  }
}

object Grant extends CommonField {
  implicit val writes: Writes[Grant] = Json.writes[Grant]
  implicit val reads: Reads[Grant]   = Json.reads[Grant]

  val TABLE              = "grants"
  val FIELD_GRANTEE_ID   = "grantee_id"
  val FIELD_GRANTEE_TYPE = "grantee_type"
  val FIELD_ROLE         = "role"
  val FIELD_OBJECT_ID    = "object_id"
  val FIELD_OBJECT_TYPE  = "object_type"

  val ROLE_SUPER_USER        = -1
  val ROLE_SUPER_USER_NAME   = "Superuser"
  val ROLE_ADMIN             = 1
  val ROLE_ADMIN_NAME        = "Admin"
  val ROLE_WRITE_ACCESS      = 2
  val ROLE_WRITE_ACCESS_NAME = "Write"
  val ROLE_READ_ONLY         = 3
  val ROLE_READ_ONLY_NAME    = "Read"

  val roleNameMap = Map(
    ROLE_SUPER_USER   -> ROLE_SUPER_USER_NAME,
    ROLE_ADMIN        -> ROLE_ADMIN_NAME,
    ROLE_WRITE_ACCESS -> ROLE_WRITE_ACCESS_NAME,
    ROLE_READ_ONLY    -> ROLE_READ_ONLY_NAME
  )

  def hasLesserPrivilege(proposedRole: Int, benchmarkRole: Int) =
    proposedRole > benchmarkRole

  def hasGreaterPrivilege(proposedRole: Int, benchmarkRole: Int) =
    proposedRole < benchmarkRole
}

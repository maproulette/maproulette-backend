/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import play.api.libs.json._

/**
  * @author nrotstan
  */
trait TeamMember {}
object TeamMember {
  val STATUS_MEMBER  = GroupMember.STATUS_MEMBER
  val STATUS_INVITED = 1
}

/**
  * The four roles a team member can hold, and what each one is allowed to do.
  * They are the generic grant roles under team-facing names, so the ordering
  * the rest of the permission system relies on (lower number, more privilege)
  * still holds and `role <= X` checks keep working.
  *
  *   - Owner   deletes the team, and everything an admin can do
  *   - Admin   invites and removes people and sets their roles, and everything
  *             a manager can do
  *   - Manager creates, edits and deletes the team's projects and challenges
  *   - Member  belongs to the team and sees it listed, and nothing more
  */
object TeamRole {
  val OWNER   = Grant.ROLE_OWNER
  val ADMIN   = Grant.ROLE_ADMIN
  val MANAGER = Grant.ROLE_WRITE_ACCESS
  val MEMBER  = Grant.ROLE_READ_ONLY

  val all: List[Int] = List(OWNER, ADMIN, MANAGER, MEMBER)

  val names: Map[Int, String] = Map(
    OWNER   -> "Owner",
    ADMIN   -> "Admin",
    MANAGER -> "Manager",
    MEMBER  -> "Member"
  )

  def name(role: Int): String = this.names.getOrElse(role, "Unknown")

  def isValid(role: Int): Boolean = this.all.contains(role)

  /** Whether the role may create, edit and delete the team's content. */
  def managesContent(role: Int): Boolean = role <= MANAGER

  /** Whether the role may invite members, remove them and set their roles. */
  def managesMembers(role: Int): Boolean = role <= ADMIN

  /** Whether the role may delete the team outright. */
  def ownsTeam(role: Int): Boolean = role <= OWNER
}

/**
  * Represents basic user fields relevant to team membership
  */
case class TeamUser(
    id: Long,
    userId: Long,
    osmId: Long,
    name: String,
    teamId: Long,
    teamName: String,
    teamGrants: List[Grant],
    status: Int
) extends Identifiable

object TeamUser {
  implicit val writes: Writes[TeamUser] = Json.writes[TeamUser]
  implicit val reads: Reads[TeamUser]   = Json.reads[TeamUser]

  def fromUser(teamId: Long, teamName: String, member: GroupMember, user: User) = {
    val teamTarget = GrantTarget.group(teamId)
    val teamGrants = user.grants.filter(g => g.target == teamTarget)
    TeamUser(
      member.id,
      user.id,
      user.osmProfile.id,
      user.osmProfile.displayName,
      teamId,
      teamName,
      teamGrants,
      member.status
    )
  }
}

/**
  * Bundles together a team with grants
  */
case class ManagingTeam(
    team: Group,
    grants: List[Grant]
)
object ManagingTeam {
  implicit val writes: Writes[ManagingTeam] = Json.writes[ManagingTeam]
  implicit val reads: Reads[ManagingTeam]   = Json.reads[ManagingTeam]
}

/**
  * A team the requesting user runs the content of, paired with the role that
  * says so and the image the team puts on its challenges. This is what the
  * challenge form offers as owners: picking one hands the challenge to that
  * team and puts its image on the card.
  */
case class ManagedTeam(
    team: Group,
    role: Int,
    challengeImageUrl: Option[String]
)

object ManagedTeam {
  implicit val writes: Writes[ManagedTeam] = new Writes[ManagedTeam] {
    def writes(managed: ManagedTeam): JsValue =
      Json.obj(
        "team"     -> managed.team,
        "role"     -> managed.role,
        "roleName" -> TeamRole.name(managed.role),
        // Absent rather than a dead link when the team has no approved image,
        // so a client can tell "no picture" from "picture failed to load".
        "challengeImageUrl" -> managed.challengeImageUrl
      )
  }
}

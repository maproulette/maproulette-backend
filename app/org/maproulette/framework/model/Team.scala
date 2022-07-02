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
  * Represents basic user fields relevant to team membership
  */
case class TeamUser(
    id: Long,
    userId: Long,
    osmId: Long,
    name: String,
    teamId: Long,
    teamGrants: List[Grant],
    status: Int
) extends Identifiable

object TeamUser {
  implicit val writes: Writes[TeamUser] = Json.writes[TeamUser]
  implicit val reads: Reads[TeamUser]   = Json.reads[TeamUser]

  def fromUser(teamId: Long, member: GroupMember, user: User) = {
    val teamTarget = GrantTarget.group(teamId)
    val teamGrants = user.grants.filter(g => g.target == teamTarget)
    TeamUser(
      member.id,
      user.id,
      user.osmProfile.id,
      user.osmProfile.displayName,
      teamId,
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

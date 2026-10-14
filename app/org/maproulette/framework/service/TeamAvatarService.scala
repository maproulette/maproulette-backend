/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.service

import java.sql.Connection
import javax.inject.{Inject, Singleton}
import org.maproulette.exception.NotFoundException
import org.maproulette.framework.model.{Group, TeamAvatar, TeamImageData, User}
import org.maproulette.framework.repository.TeamAvatarRepository
import org.maproulette.permissions.Permission
import play.api.db.Database

/**
  * Owns the rules around a team's own avatar: who may replace or remove it,
  * and what has to happen alongside the bytes so the team's avatar url never
  * disagrees with what we are actually storing.
  *
  * Unlike a team's challenge image this needs no review. A team admin can
  * already point the avatar url at any image on the internet through the team
  * endpoint, so gating only the uploaded case would be stricter about the
  * safer of the two paths.
  *
  * @author nrotstan
  */
@Singleton
class TeamAvatarService @Inject() (
    repository: TeamAvatarRepository,
    teamService: TeamService,
    permission: Permission,
    db: Database
) {

  /**
    * Retrieves a team's avatar metadata, or fails with a 404.
    */
  def retrieve(teamId: Long): TeamAvatar =
    this.repository
      .retrieve(teamId)
      .getOrElse(throw new NotFoundException(s"No avatar found for team $teamId"))

  /**
    * Retrieves the bytes of a team's avatar, for serving it.
    */
  def retrieveData(teamId: Long): TeamImageData =
    this.repository
      .retrieveData(teamId)
      .getOrElse(throw new NotFoundException(s"No avatar found for team $teamId"))

  /**
    * Stores a team's avatar, replacing whatever avatar it had, and points the
    * team's avatar url at it so the rest of the app keeps treating the avatar
    * as a plain url.
    *
    * The bytes and the url are two writes describing one fact, so they commit
    * together. Left apart, a failure between them strands the bytes with the
    * url still on the team's previous avatar, and the url carries a version
    * stamp that would then be stale in browser caches until the next upload.
    *
    * @param teamId      The team whose avatar is being replaced
    * @param data        The avatar's bytes, already validated
    * @param contentType The format those bytes were sniffed to be
    * @param user        The user uploading, who must be a team admin
    * @return The updated team
    */
  def upload(teamId: Long, data: Array[Byte], contentType: String, user: User): Group = {
    val team = this.requireAdmin(teamId, user)

    val updated = this.db.withTransaction { connection =>
      implicit val c: Option[Connection] = Some(connection)
      val modified                       = this.repository.upsert(teamId, contentType, data, user.id)
      this.teamService.updateTeam(
        team.copy(avatarURL = Some(TeamAvatar.urlFor(teamId, modified.getMillis))),
        user
      )
    }

    // Only now that the two writes are durable, so a rollback cannot announce
    // an avatar nobody will be served.
    this.teamService.broadcastTeamUpdate(teamId)
    updated.get
  }

  /**
    * Removes a team's uploaded avatar.
    *
    * An avatar url the team pasted in themselves is left alone: there are no
    * bytes of ours behind it, and clearing it would be deleting something this
    * endpoint never set. Rather than guess at the url's shape, the stored row
    * says what we would have written, and only an exact match is ours to
    * clear - so a team that has since typed their own url keeps it.
    *
    * @param teamId The team whose avatar is being removed
    * @param user   The user removing it, who must be a team admin
    * @return The updated team
    */
  def remove(teamId: Long, user: User): Group = {
    val team = this.requireAdmin(teamId, user)
    val ours =
      this.repository.retrieve(teamId).map(a => TeamAvatar.urlFor(teamId, a.modified.getMillis))
    val clears = ours.isDefined && team.avatarURL == ours

    val updated = this.db.withTransaction { connection =>
      implicit val c: Option[Connection] = Some(connection)
      this.repository.delete(teamId)
      this.teamService.updateTeam(
        if (clears) team.copy(avatarURL = None) else team,
        user
      )
    }

    this.teamService.broadcastTeamUpdate(teamId)
    updated.get
  }

  /**
    * Fetches a team and confirms the user may write to it. Checked before
    * anything is stored, so a non-admin cannot write bytes and only be turned
    * away afterwards.
    */
  private def requireAdmin(teamId: Long, user: User): Group = {
    val team = this.teamService
      .retrieve(teamId, user)
      .getOrElse(throw new NotFoundException(s"No team with id $teamId found"))
    this.permission.hasObjectAdminAccess(team, user)
    team
  }
}

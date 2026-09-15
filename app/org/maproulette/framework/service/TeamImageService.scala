/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.maproulette.exception.{InvalidException, NotFoundException}
import org.maproulette.framework.model.{MemberObject, TeamImage, TeamImageData, TeamImageFile, User}
import org.maproulette.framework.repository.TeamImageRepository
import org.maproulette.permissions.Permission

/**
  * Owns the rules around team-owned challenge images: who may request, see and
  * remove them, and what has to happen alongside a review decision. Both the
  * image endpoints and the challenge endpoints go through here, so the rule
  * that decides whether an image may be used has a single definition.
  *
  * @author mcuthbert
  */
@Singleton
class TeamImageService @Inject() (
    repository: TeamImageRepository,
    groupService: GroupService,
    teamService: TeamService,
    permission: Permission
) {

  /**
    * Retrieves an image's metadata, or fails with a 404.
    */
  def retrieve(imageId: Long): TeamImage =
    this.repository
      .retrieve(imageId)
      .getOrElse(throw new NotFoundException(s"No team image found with id $imageId"))

  /**
    * Retrieves just enough of an image to decide whether it may be served,
    * without reading its bytes.
    */
  def retrieveFile(imageId: Long): TeamImageFile =
    this.repository
      .retrieveFile(imageId)
      .getOrElse(throw new NotFoundException(s"No team image found with id $imageId"))

  /**
    * Retrieves an image's bytes, or fails with a 404.
    */
  def retrieveData(imageId: Long): TeamImageData =
    this.repository
      .retrieveData(imageId)
      .getOrElse(throw new NotFoundException(s"No team image found with id $imageId"))

  /**
    * Whether a user may act on a team's images at all - superusers because
    * they review them, active members because they are the ones asking for
    * them. This is also what decides whether an image that hasn't been
    * approved yet is visible.
    */
  def hasTeamAccess(teamId: Long, user: User): Boolean =
    this.permission.isSuperUser(user) ||
      this.teamService.isActiveTeamMember(
        this.team(teamId),
        MemberObject.user(user.id),
        User.superUser
      )

  /**
    * Requires that the user may act on the team's images.
    */
  def requireTeamAccess(teamId: Long, user: User): Unit =
    if (!this.hasTeamAccess(teamId, user)) {
      throw new IllegalAccessException(
        s"You must be a member of team $teamId to manage its images"
      )
    }

  /**
    * The image a team is currently using on its challenges, if it has one.
    * This is what a card renders, and what the public image endpoint serves.
    */
  def approvedForTeam(teamId: Long): Option[TeamImage] =
    this.repository.currentForTeam(teamId, TeamImage.STATUS_APPROVED)

  /**
    * The approved images of several teams at once, for listing teams alongside
    * the picture each one puts on its challenges without a query per team.
    */
  def approvedForTeams(teamIds: List[Long]): List[TeamImage] =
    this.repository.listForTeams(teamIds, Some(TeamImage.STATUS_APPROVED))

  def listForTeam(teamId: Long): List[TeamImage] = this.repository.listForTeam(teamId)

  def listPending(): List[TeamImage] = this.repository.listPending()

  /**
    * The request a team currently has in front of the reviewers, if any.
    */
  def pendingForTeam(teamId: Long): Option[TeamImage] =
    this.repository.currentForTeam(teamId, TeamImage.STATUS_PENDING)

  /**
    * Stores a new image request for a team, awaiting review. A team carries
    * one image, so it only ever has one request outstanding: the team has to
    * withdraw the request it already made before asking for a different image.
    * The image the team is currently using is not in the way - the new request
    * replaces it once approved.
    */
  def request(
      teamId: Long,
      name: String,
      contentType: String,
      data: Array[Byte],
      requestedBy: Long
  ): TeamImage = {
    this.pendingForTeam(teamId).foreach { pending =>
      throw new InvalidException(
        s"Team $teamId already has an image awaiting review. Withdraw '${pending.name}' " +
          "before requesting a different one."
      )
    }
    this.retrieve(this.repository.create(teamId, name, contentType, data, requestedBy))
  }

  /**
    * Records a review decision and returns the reviewed image. Approving hands
    * the team's single image slot to this image, replacing whatever the team
    * had before. Cards need no attention either way: a challenge stores the
    * team that owns it, not an image id, so they follow the team's current
    * image on their own.
    */
  def review(imageId: Long, status: Int, reviewedBy: Long, comment: Option[String]): TeamImage = {
    if (!this.repository
          .review(imageId, status, reviewedBy, comment.map(_.trim).filter(_.nonEmpty))) {
      throw new NotFoundException(s"No team image found with id $imageId")
    }
    this.retrieve(imageId)
  }

  /**
    * Deletes an image. Superusers and the owning team's admins can remove any
    * of the team's images; an ordinary member can withdraw a request they made
    * that is still pending.
    */
  def delete(imageId: Long, user: User): Unit = {
    val image = this.retrieve(imageId)

    // The member withdrawing their own pending request is decided entirely
    // from the image already in hand, so check it before the admin lookup,
    // which costs several queries.
    val ownPendingRequest =
      image.requestedBy.contains(user.id) && image.status == TeamImage.STATUS_PENDING

    if (!ownPendingRequest && !this.permission.isSuperUser(user) &&
        !this.teamService.isUserTeamAdmin(this.team(image.teamId), user, User.superUser)) {
      throw new IllegalAccessException(
        "Only a team admin or the requester of a still-pending image can remove it"
      )
    }

    this.repository.delete(imageId)
  }

  private def team(teamId: Long) =
    this.teamService
      .retrieve(teamId)
      .getOrElse(throw new NotFoundException(s"No team found with id $teamId"))
}

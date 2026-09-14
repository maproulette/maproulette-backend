/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.maproulette.data.UserType
import org.maproulette.exception.{InvalidException, NotFoundException}
import org.maproulette.framework.model.{
  MemberObject,
  TeamImage,
  TeamImageData,
  TeamImageFile,
  TeamMember,
  User
}
import org.maproulette.framework.repository.TeamImageRepository
import org.maproulette.models.dal.ChallengeDAL
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
    challengeDAL: ChallengeDAL,
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
    * Requires that an image may be attached to a challenge by this user. Image
    * ids are just numbers on the wire, so without this anyone could borrow
    * another team's image, or an image still awaiting review, by guessing an
    * id.
    */
  def requireUsable(imageId: Long, user: User): Unit = {
    val image = this.retrieve(imageId)
    if (image.status != TeamImage.STATUS_APPROVED) {
      throw new InvalidException(
        s"Team image $imageId has not been approved and cannot be used on a challenge"
      )
    }
    if (!this.hasTeamAccess(image.teamId, user)) {
      throw new InvalidException(
        s"You must be a member of team ${image.teamId} to use its images"
      )
    }
  }

  /**
    * The ids of the teams whose approved images the user may choose from, i.e.
    * every team they are an active member of. Read straight from the
    * memberships rather than via full team objects, since only the ids are
    * wanted.
    */
  def teamIdsFor(user: User): List[Long] =
    this.groupService
      .getMembershipsForMembers(UserType().typeId, List(user.id))
      .filter(_.status != TeamMember.STATUS_INVITED)
      .map(_.groupId)
      .distinct

  /**
    * Every approved image across the teams the user belongs to. This is the
    * set the challenge form offers as display images.
    */
  def listAvailable(user: User): List[TeamImage] =
    this.repository.listForTeams(this.teamIdsFor(user), Some(TeamImage.STATUS_APPROVED))

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
    * the team's single image slot to this image and moves the challenges that
    * were on the old one across; anything else detaches it from the challenges
    * using it. Either way challenges change image in SQL, which the challenge
    * cache has no way of noticing, so the repository reports back which ones
    * to evict.
    */
  def review(imageId: Long, status: Int, reviewedBy: Long, comment: Option[String]): TeamImage = {
    val affected = this.repository
      .review(imageId, status, reviewedBy, comment.map(_.trim).filter(_.nonEmpty))
      .getOrElse(throw new NotFoundException(s"No team image found with id $imageId"))

    affected.foreach(this.challengeDAL.cacheManager.cache.remove)
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

    // Deleting detaches the image from the challenges showing it, which the
    // challenge cache has no way of noticing, so those challenges are evicted.
    this.repository
      .delete(imageId)
      .getOrElse(Nil)
      .foreach(this.challengeDAL.cacheManager.cache.remove)
  }

  private def team(teamId: Long) =
    this.teamService
      .retrieve(teamId)
      .getOrElse(throw new NotFoundException(s"No team found with id $teamId"))
}

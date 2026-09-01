/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.controller

import javax.inject.Inject
import org.maproulette.data.ActionManager
import org.maproulette.exception.{InvalidException, NotFoundException, StatusMessage}
import org.maproulette.framework.model.{Group, MemberObject, TeamImage, TeamMember, User}
import org.maproulette.framework.repository.TeamImageRepository
import org.maproulette.framework.service.TeamService
import org.maproulette.models.dal.ChallengeDAL
import org.maproulette.permissions.Permission
import org.maproulette.session.SessionManager
import play.api.libs.Files
import play.api.libs.json._
import play.api.mvc._

/**
  * Handles the team-owned images offered to team members as challenge display
  * images. Any active member of a team can request an image; a superuser has
  * to approve it before it can be attached to a challenge.
  */
class TeamImageController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val bodyParsers: PlayBodyParsers,
    teamImageRepository: TeamImageRepository,
    teamService: TeamService,
    challengeDAL: ChallengeDAL,
    permission: Permission,
    components: ControllerComponents
) extends AbstractController(components)
    with MapRouletteController {

  /**
    * Fetches a team, or fails with a 404.
    */
  private def team(teamId: Long): Group =
    this.teamService
      .retrieve(teamId)
      .getOrElse(throw new NotFoundException(s"No team found with id $teamId"))

  /**
    * Fetches an image, or fails with a 404.
    */
  private def image(imageId: Long): TeamImage =
    this.teamImageRepository
      .retrieve(imageId)
      .getOrElse(throw new NotFoundException(s"No team image found with id $imageId"))

  /**
    * Runs a mutation that may detach the image from challenges, evicting any
    * challenge it was attached to from the challenge cache. The detaching is
    * done in SQL, which the cache has no way of noticing on its own, so
    * without this a challenge would keep reporting an image it no longer has.
    */
  private def withChallengeCacheEviction[T](imageId: Long)(mutate: => T): T = {
    val affected = this.teamImageRepository.challengeIdsUsing(imageId)
    val result   = mutate
    affected.foreach(this.challengeDAL.cacheManager.cache.remove)
    result
  }

  private def isMemberOf(teamId: Long, user: User): Boolean =
    this.teamService
      .isActiveTeamMember(this.team(teamId), MemberObject.user(user.id), User.superUser)

  /**
    * Requires that the user is an active member of the team (superusers pass
    * regardless, as they do everywhere else).
    */
  private def requireTeamMembership(teamId: Long, user: User): Unit = {
    if (!this.permission.isSuperUser(user) && !this.isMemberOf(teamId, user)) {
      throw new IllegalAccessException(
        s"You must be a member of team $teamId to manage its images"
      )
    }
  }

  /**
    * Requests a new image for a team. Any active member can ask; the image is
    * stored immediately but stays pending until a superuser reviews it.
    *
    * @param teamId The id of the team the image belongs to
    * @return 200 OK with the newly created (pending) image
    */
  def requestImage(teamId: Long): Action[MultipartFormData[Files.TemporaryFile]] =
    Action.async(parse.multipartFormData) { implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        this.requireTeamMembership(teamId, user)

        if (this.teamImageRepository
              .pendingCountForTeam(teamId) >= TeamImage.MAX_PENDING_PER_TEAM) {
          throw new InvalidException(
            s"Team $teamId already has ${TeamImage.MAX_PENDING_PER_TEAM} images awaiting review"
          )
        }

        request.body.file("image") match {
          case Some(upload) =>
            if (upload.fileSize > TeamImage.MAX_SIZE_BYTES) {
              throw new InvalidException(
                s"Image is larger than the ${TeamImage.MAX_SIZE_BYTES / (1024 * 1024)}MB limit"
              )
            }

            val data = java.nio.file.Files.readAllBytes(upload.ref.path)
            // The declared content type is caller-supplied, so the leading
            // bytes are what we actually trust before storing something we
            // will later serve back from our own origin.
            val contentType = TeamImage.detectContentType(data) match {
              case Some(detected) => detected
              case None =>
                throw new InvalidException(
                  s"Unsupported image format. Supported formats: ${TeamImage.ALLOWED_CONTENT_TYPES.toList.sorted
                    .mkString(", ")}"
                )
            }

            val name = request.body.dataParts
              .get("name")
              .flatMap(_.headOption)
              .map(_.trim)
              .filter(_.nonEmpty)
              .orElse(Option(upload.filename).map(_.trim).filter(_.nonEmpty))
              .getOrElse("Untitled image")

            val id = this.teamImageRepository.create(teamId, name, contentType, data, user.id)
            Ok(Json.toJson(this.image(id)))
          case None =>
            throw new InvalidException("No image file provided in the 'image' field")
        }
      }
    }

  /**
    * Lists a team's images, including ones still pending review or rejected,
    * so members can see where their requests stand.
    *
    * @param teamId The id of the team
    * @return 200 OK with the team's images
    */
  def listTeamImages(teamId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.requireTeamMembership(teamId, user)
      Ok(Json.toJson(this.teamImageRepository.listForTeam(teamId)))
    }
  }

  /**
    * Lists every approved image across all teams the current user belongs to.
    * This is the set the challenge form offers as display images.
    *
    * @return 200 OK with the approved images available to the user
    */
  def listAvailableImages(): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val teamIds = this.teamService
        .teamUsersByUserIds(List(user.id), user)
        .filter(_.status != TeamMember.STATUS_INVITED)
        .map(_.teamId)
        .distinct

      Ok(
        Json.toJson(
          this.teamImageRepository.listForTeams(teamIds, Some(TeamImage.STATUS_APPROVED))
        )
      )
    }
  }

  /**
    * Lists every image awaiting review, oldest first.
    *
    * @return 200 OK with the pending review queue
    */
  def listPendingImages(): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.permission.hasSuperAccess(user)
      Ok(Json.toJson(this.teamImageRepository.listPending()))
    }
  }

  /**
    * Approves an image, making it available to the owning team's members.
    *
    * @param imageId The id of the image to approve
    * @return 200 OK with the reviewed image
    */
  def approveImage(imageId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.permission.hasSuperAccess(user)
      this.review(imageId, TeamImage.STATUS_APPROVED, user, request.getQueryString("comment"))
    }
  }

  /**
    * Rejects an image. Rejecting one that was previously approved also
    * detaches it from every challenge using it.
    *
    * @param imageId The id of the image to reject
    * @return 200 OK with the reviewed image
    */
  def rejectImage(imageId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.permission.hasSuperAccess(user)
      this.review(imageId, TeamImage.STATUS_REJECTED, user, request.getQueryString("comment"))
    }
  }

  private def review(imageId: Long, status: Int, user: User, comment: Option[String]): Result = {
    this.image(imageId)
    this.teamImageRepository.review(
      imageId,
      status,
      user.id,
      comment.map(_.trim).filter(_.nonEmpty)
    )
    Ok(Json.toJson(this.image(imageId)))
  }

  /**
    * Deletes an image, detaching it from any challenges using it. Superusers
    * and the owning team's admins can remove any of the team's images; an
    * ordinary member can withdraw a request they made that is still pending.
    *
    * @param imageId The id of the image to delete
    * @return 200 OK with a success message
    */
  def deleteImage(imageId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val existing = this.image(imageId)

      val allowed = this.permission.isSuperUser(user) ||
        this.teamService
          .isTeamAdmin(this.team(existing.teamId), MemberObject.user(user.id), User.superUser) ||
        (existing.requestedBy.contains(user.id) && existing.status == TeamImage.STATUS_PENDING)

      if (!allowed) {
        throw new IllegalAccessException(
          "Only a team admin or the requester of a still-pending image can remove it"
        )
      }

      this.withChallengeCacheEviction(imageId) {
        this.teamImageRepository.delete(imageId)
      }
      Ok(Json.toJson(StatusMessage("OK", JsString(s"Team image $imageId deleted"))))
    }
  }

  /**
    * Serves an image's bytes. Anonymous, because the url is consumed by plain
    * img tags on challenge cards. Only approved images are served — a pending
    * or rejected one is indistinguishable from one that doesn't exist.
    *
    * @param imageId The id of the image to serve
    * @return 200 OK with the image bytes
    */
  def getImageFile(imageId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      val metadata = this.image(imageId)
      if (metadata.status != TeamImage.STATUS_APPROVED) {
        throw new NotFoundException(s"No team image found with id $imageId")
      }

      this.teamImageRepository.retrieveData(imageId) match {
        case Some(image) =>
          val etag = "\"" + s"$imageId-${image.modified.getMillis}" + "\""
          if (request.headers.get("If-None-Match").contains(etag)) {
            NotModified.withHeaders("ETag" -> etag)
          } else {
            Ok(image.data)
              .as(image.contentType)
              .withHeaders(
                "ETag"                   -> etag,
                "Cache-Control"          -> "public, max-age=86400",
                "X-Content-Type-Options" -> "nosniff",
                "Content-Disposition"    -> "inline"
              )
          }
        case None =>
          throw new NotFoundException(s"No team image found with id $imageId")
      }
    }
  }
}

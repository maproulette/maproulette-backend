/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.controller

import javax.inject.Inject
import org.maproulette.data.ActionManager
import org.maproulette.exception.{NotFoundException, StatusMessage}
import org.maproulette.framework.mixins.ImageUploadMixin
import org.maproulette.framework.model.TeamImage
import org.maproulette.framework.service.TeamImageService
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
    teamImageService: TeamImageService,
    permission: Permission,
    components: ControllerComponents
) extends AbstractController(components)
    with MapRouletteController
    with ImageUploadMixin {

  private val maxUploadBytes = TeamImage.MAX_UPLOAD_BYTES

  /**
    * Requests a new image for a team. Any active member can ask; the image is
    * stored immediately but stays pending until a superuser reviews it.
    *
    * @param teamId The id of the team the image belongs to
    * @return 200 OK with the newly created (pending) image
    */
  def requestImage(teamId: Long): Action[MultipartFormData[Files.TemporaryFile]] =
    Action.async(parse.multipartFormData(maxLength = maxUploadBytes)) { implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        this.teamImageService.requireTeamAccess(teamId, user)

        val upload = this.readImageUpload(request)

        val name = request.body.dataParts
          .get("name")
          .flatMap(_.headOption)
          .map(_.trim)
          .filter(_.nonEmpty)
          .orElse(Option(upload.filename).map(_.trim).filter(_.nonEmpty))
          .getOrElse("Untitled image")

        Ok(
          Json.toJson(
            this.teamImageService.request(teamId, name, upload.contentType, upload.data, user.id)
          )
        )
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
      this.teamImageService.requireTeamAccess(teamId, user)
      Ok(Json.toJson(this.teamImageService.listForTeam(teamId)))
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
      Ok(Json.toJson(this.teamImageService.listPending()))
    }
  }

  /**
    * Approves an image, making it available to the owning team's members.
    *
    * @param imageId The id of the image to approve
    * @return 200 OK with the reviewed image
    */
  def approveImage(imageId: Long): Action[AnyContent] =
    this.review(imageId, TeamImage.STATUS_APPROVED)

  /**
    * Rejects an image. Rejecting one that was previously approved also
    * detaches it from every challenge using it.
    *
    * @param imageId The id of the image to reject
    * @return 200 OK with the reviewed image
    */
  def rejectImage(imageId: Long): Action[AnyContent] =
    this.review(imageId, TeamImage.STATUS_REJECTED)

  private def review(imageId: Long, status: Int): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        this.permission.hasSuperAccess(user)
        Ok(
          Json.toJson(
            this.teamImageService
              .review(imageId, status, user.id, request.getQueryString("comment"))
          )
        )
      }
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
      this.teamImageService.delete(imageId, user)
      Ok(Json.toJson(StatusMessage("OK", JsString(s"Team image $imageId deleted"))))
    }
  }

  /**
    * Serves whatever image a team currently has approved - the picture on the
    * cards of every challenge that team owns. Anonymous, because the url is
    * consumed by plain img tags.
    *
    * A team that has no approved image is reported as not found, which is how
    * a client tells there is simply no picture to show. Only approved images
    * are ever served here: a request still under review is nobody's card image
    * yet, and is reachable only through its own image endpoint.
    *
    * @param teamId The id of the team whose image is wanted
    * @return 200 OK with the image bytes
    */
  def getTeamImageFile(teamId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      val image = this.teamImageService
        .approvedForTeam(teamId)
        .getOrElse(throw new NotFoundException(s"Team $teamId has no challenge image"))

      this.serveImage(image.id, request, "public, max-age=86400")
    }
  }

  /**
    * Serves an image's bytes. Anonymous, because the url is consumed by plain
    * img tags on challenge cards.
    *
    * An image that isn't approved is only served to the people who have a
    * reason to look at it: a superuser working the review queue, who can
    * hardly judge an image sight unseen, and members of the owning team, who
    * requested it. To anyone else it is indistinguishable from an image that
    * doesn't exist.
    *
    * @param imageId The id of the image to serve
    * @return 200 OK with the image bytes
    */
  def getImageFile(imageId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      val file     = this.teamImageService.retrieveFile(imageId)
      val approved = file.status == TeamImage.STATUS_APPROVED
      if (!approved && !user.exists(this.teamImageService.hasTeamAccess(file.teamId, _))) {
        throw new NotFoundException(s"No team image found with id $imageId")
      }

      // An approved image's bytes never change, so it is public and cacheable.
      // One still under review is neither: it is only for this viewer, and it
      // stops being served the moment it is rejected.
      this.serveImage(
        imageId,
        request,
        if (approved) "public, max-age=86400" else "private, no-cache"
      )
    }
  }

  /**
    * Writes an image's bytes out through the shared serving rules.
    */
  private def serveImage(
      imageId: Long,
      request: Request[AnyContent],
      cacheControl: String
  ): Result = {
    implicit val r: Request[AnyContent] = request
    val file                            = this.teamImageService.retrieveFile(imageId)
    this.serveImageBytes(
      etagKey = imageId.toString,
      modified = file.modified,
      contentType = file.contentType,
      data = this.teamImageService.retrieveData(imageId).data,
      cacheControl = cacheControl
    )
  }
}

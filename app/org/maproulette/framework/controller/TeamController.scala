/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.controller

import java.sql.Connection
import javax.inject.Inject
import org.maproulette.data.ActionManager
import org.maproulette.exception.{
  InvalidException,
  MPExceptionUtil,
  NotFoundException,
  StatusMessage
}
import org.maproulette.framework.service.TeamService
import org.maproulette.framework.model.{User, MemberObject, Group, TeamAvatar}
import org.maproulette.framework.repository.TeamAvatarRepository
import org.maproulette.framework.psql.{Paging}
import org.maproulette.permissions.Permission
import org.maproulette.session.SessionManager
import play.api.db.Database
import play.api.libs.Files
import play.api.libs.json._
import play.api.mvc._

/**
  * @author nrotstan
  */
class TeamController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val bodyParsers: PlayBodyParsers,
    teamService: TeamService,
    teamAvatarRepository: TeamAvatarRepository,
    permission: Permission,
    db: Database,
    components: ControllerComponents
) extends AbstractController(components)
    with MapRouletteController {

  /**
    * Create a new team
    */
  def createTeam(): Action[JsValue] = Action.async(bodyParsers.json) { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val result = request.body.validate[Group]
      result.fold(
        errors => {
          BadRequest(Json.toJson(StatusMessage("KO", JsError.toJson(errors))))
        },
        element => {
          MPExceptionUtil.internalExceptionCatcher { () =>
            Created(
              Json.toJson(
                this.teamService
                  .create(
                    element.copy(groupType = Group.GROUP_TYPE_TEAM),
                    MemberObject.user(user.id),
                    user
                  )
                  .get
              )
            )
          }
        }
      )
    }
  }

  /**
    * Retrieve a specific team
    *
    * @param teamId The id of the team to retrieve
    * @return The team
    */
  def retrieve(teamId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      this.teamService.retrieve(teamId, user.getOrElse(User.guestUser)) match {
        case Some(team) => Ok(Json.toJson(team))
        case None       => NotFound
      }
    }
  }

  /**
    * Search teams by name, returning matching results
    *
    * @param name  A name fragment to search by
    * @param limit Maximum number of results to return
    * @param page  Page of results to return
    * @return Matching teams
    */
  def find(name: String, limit: Int, page: Int): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        Ok(
          Json.toJson(
            this.teamService.search(name, Paging(limit, page), user.getOrElse(User.guestUser))
          )
        )
      }
  }

  /**
    * Retrieves all of a user's team memberships
    *
    * @return A list of TeamUser instances
    */
  def userTeamMemberships(userId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(
        Json.toJson(
          this.teamService.teamUsersByUserIds(List(userId), user.getOrElse(User.guestUser))
        )
      )
    }
  }

  /**
    * Retrieve all the users who are members of a team, including users who
    * have an invitation pending
    *
    * @param teamId The id of the team
    * @return A list of teams
    */
  def teamUsers(teamId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(
        Json.toJson(
          this.teamService.teamUsers(teamId, user.getOrElse(User.guestUser))
        )
      )
    }
  }

  /**
    * Invite a user to join a team
    *
    * @param teamId    The team onto which to invite the user
    * @param inviteeId The id of the user to invite
    * @param role      The role to be granted to user on the team if they accept the invitation
    */
  def inviteUser(teamId: Long, inviteeId: Long, role: Int): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        Ok(Json.toJson(this.teamService.inviteTeamUser(teamId, inviteeId, role, user)))
      }
  }

  /**
    * Accept an invitation the logged-in user received to join a team
    *
    * @param teamId The id of the team the user is invited to join
    */
  def acceptInvite(teamId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      Ok(Json.toJson(this.teamService.acceptUserInvitation(teamId, user.id, user)))
    }
  }

  /**
    * Decline an invitation the logged-in user received to join a team
    *
    * @param teamId The id of the team the user was invited to join
    */
  def declineInvite(teamId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.teamService.declineInvitation(teamId, MemberObject.user(user.id), user)
      Ok
    }
  }

  /**
    * Update role granted to member on team
    *
    * @param teamId   The id of the team on which the role is granted
    * @param memberId The id of the user who is to have their role updated
    * @param role     The new role
    */
  def updateMemberRole(teamId: Long, memberId: Long, role: Int): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        Ok(Json.toJson(this.teamService.updateUserRole(teamId, memberId, role, user)))
      }
  }

  /**
    * Remove a member from a team
    *
    * @param teamId   The id of the team from which the member is being removed
    * @param memberId The id of the member who is to be removed
    */
  def removeTeamMember(teamId: Long, memberId: Long): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        this.teamService.retrieve(teamId, user) match {
          case Some(team) =>
            this.teamService.removeTeamMember(team, MemberObject.user(memberId), user)
            Ok
          case None => NotFound
        }
      }
  }

  /**
    * Adds a team to a project, granting it the given role on the project.  All
    * members of the team will be indirectly granted that role on the project
    *
    * @param teamId    The id of the team to add to the project
    * @param projectId The id of the project to which the team is to be added
    * @param role      The role to grant the team on the project
    */
  def addTeamToProject(teamId: Long, projectId: Long, role: Int): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        this.teamService.addTeamToProject(teamId, projectId, role, user)
        Ok
      }
    }

  /**
    * Sets a team's granted role on a project, clearing any prior granted roles.
    * All members of the team will be indirectly granted the role on the
    * project
    *
    * @param teamId    The id of the team to receive the role on the project
    * @param projectId The id of the project
    * @param role      The role to grant the team on the project
    */
  def setTeamProjectRole(teamId: Long, projectId: Long, role: Int): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        this.teamService.addTeamToProject(teamId, projectId, role, user, true)
        Ok
      }
    }

  /**
    * Removes a team from a project, clearing any roles it was granted on the
    * project
    *
    * @param teamId    The id of the team to remove from the project
    * @param projectId The id of the project from which the team is to be removed
    */
  def removeTeamFromProject(teamId: Long, projectId: Long): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        this.teamService.removeTeamFromProject(teamId, projectId, user)
        Ok
      }
  }

  /**
    * Gets any teams that have been granted roles on a project
    *
    * @param projectId The id of the project for which teams are desired
    */
  def getTeamsManagingProject(projectId: Long): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        Ok(
          Json.toJson(
            this.teamService.getTeamsManagingProject(projectId, user)
          )
        )
      }
    }

  /**
    * Update a team's name, description, and/or avatar URL
    *
    * @param teamId      The id of the team to update
    */
  def updateTeam(teamId: Long): Action[JsValue] = Action.async(bodyParsers.json) {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        val result = request.body.validate[Group]
        result.fold(
          errors => {
            BadRequest(Json.toJson(StatusMessage("KO", JsError.toJson(errors))))
          },
          element => {
            MPExceptionUtil.internalExceptionCatcher { () =>
              this.teamService.retrieve(teamId, user) match {
                case Some(team: Group) =>
                  Ok(
                    Json.toJson(
                      this.teamService
                        .updateTeam(
                          team.copy(
                            name = element.name,
                            description = element.description,
                            avatarURL = element.avatarURL
                          ),
                          user
                        )
                        .get
                    )
                  )
                case None => NotFound
              }
            }
          }
        )
      }
  }

  /**
    * Delete a team
    *
    * @param teamId The id of the team to delete
    * @return Ok if successful
    */
  def deleteTeam(teamId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.teamService.retrieve(teamId, user) match {
        case Some(team) =>
          this.teamService.deleteTeam(team, user)
          Ok
        case None => NotFound
      }
    }
  }

  /**
    * Fetches a team, or fails with a 404.
    */
  private def team(teamId: Long, user: User): Group =
    this.teamService
      .retrieve(teamId, user)
      .getOrElse(throw new NotFoundException(s"No team found with id $teamId"))

  /**
    * Uploads a team's avatar, replacing whatever avatar it had. The bytes are
    * stored by us and the team's avatar url is pointed at them, so the rest of
    * the app keeps treating the avatar as a plain url.
    *
    * Unlike a team's challenge images this needs no review: a team admin could
    * already point the avatar url at any image on the internet, so gating only
    * the uploaded case would be stricter about the safer of the two paths.
    *
    * @param teamId The id of the team whose avatar is being set
    * @return 200 OK with the updated team
    */
  def uploadAvatar(teamId: Long): Action[MultipartFormData[Files.TemporaryFile]] =
    Action.async(parse.multipartFormData) { implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        val existing = this.team(teamId, user)
        // Checked before anything is stored, so a non-admin can't write bytes
        // and only be turned away afterwards
        this.permission.hasObjectAdminAccess(existing, user)

        request.body.file("image") match {
          case Some(upload) =>
            if (upload.fileSize > TeamAvatar.MAX_SIZE_BYTES) {
              throw new InvalidException(
                s"Image is larger than the ${TeamAvatar.MAX_SIZE_BYTES / (1024 * 1024)}MB limit"
              )
            }

            val data = java.nio.file.Files.readAllBytes(upload.ref.path)
            // The declared content type is caller-supplied, so the leading
            // bytes are what we actually trust before storing something we
            // will later serve back from our own origin.
            val contentType = TeamAvatar.detectContentType(data) match {
              case Some(detected) => detected
              case None =>
                throw new InvalidException(
                  s"Unsupported image format. Supported formats: ${TeamAvatar.ALLOWED_CONTENT_TYPES.toList.sorted
                    .mkString(", ")}"
                )
            }

            // Storing the bytes and pointing the team's avatar url at them
            // are two writes describing one fact, so they commit together. Left
            // apart, a failure between them strands the bytes with the url
            // still on the team's previous avatar, and the url carries a
            // `?v=<modified>` stamp that would then be stale in browser caches
            // until the next upload.
            val updated = this.db.withTransaction { connection =>
              implicit val c: Option[Connection] = Some(connection)
              val modified =
                this.teamAvatarRepository.upsert(teamId, contentType, data, user.id)
              this.teamService.updateTeam(
                existing.copy(avatarURL = Some(TeamAvatar.urlFor(teamId, modified.getMillis))),
                user
              )
            }

            Ok(Json.toJson(updated.get))
          case None =>
            throw new InvalidException("No image file provided in the 'image' field")
        }
      }
    }

  /**
    * Removes a team's uploaded avatar. An avatar url the team pasted in
    * themselves is left alone - there are no bytes of ours behind it, and
    * clearing it would be deleting something this endpoint never set.
    *
    * @param teamId The id of the team whose avatar is being removed
    * @return 200 OK with the updated team
    */
  def deleteAvatar(teamId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val existing = this.team(teamId, user)
      this.permission.hasObjectAdminAccess(existing, user)

      this.teamAvatarRepository.delete(teamId)
      val remainingURL = existing.avatarURL.filterNot(TeamAvatar.isStoredAvatarUrl(_, teamId))

      Ok(
        Json.toJson(this.teamService.updateTeam(existing.copy(avatarURL = remainingURL), user).get)
      )
    }
  }

  /**
    * Serves a team's avatar bytes. Anonymous, because the url is consumed by
    * plain img tags wherever the team is shown.
    *
    * @param teamId The id of the team whose avatar to serve
    * @return 200 OK with the avatar bytes
    */
  def getAvatarFile(teamId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      this.teamAvatarRepository.retrieveData(teamId) match {
        case Some(avatar) =>
          val etag = "\"" + s"$teamId-${avatar.modified.getMillis}" + "\""
          if (request.headers.get("If-None-Match").contains(etag)) {
            NotModified.withHeaders("ETag" -> etag)
          } else {
            Ok(avatar.data)
              .as(avatar.contentType)
              .withHeaders(
                "ETag"                   -> etag,
                "Cache-Control"          -> "public, max-age=86400",
                "X-Content-Type-Options" -> "nosniff",
                "Content-Disposition"    -> "inline"
              )
          }
        case None =>
          throw new NotFoundException(s"No avatar found for team $teamId")
      }
    }
  }
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.controller


import javax.inject.Inject
import org.maproulette.data.ActionManager
import org.maproulette.exception.NotFoundException
import org.maproulette.framework.service.{ServiceManager, FollowService}
import org.maproulette.framework.model.User
import org.maproulette.session.SessionManager
import play.api.libs.json._
import play.api.mvc._

/**
  * @author nrotstan
  */
class FollowController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val bodyParsers: PlayBodyParsers,
    service: FollowService,
    serviceManager: ServiceManager,
    components: ControllerComponents
) extends AbstractController(components)
    with MapRouletteController {

  /**
    * Retrieve users followed by a user
    *
    * @param userId The id of the user who is following other users
    * @return The followed users
    */
  def followedBy(userId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(
        Json.toJson(
          this.service.getUsersFollowedBy(userId, user.getOrElse(User.guestUser))
        )
      )
    }
  }

  /**
    * Retrieve users following a user
    *
    * @param userId The id of the user who being followed
    * @return The users who are following
    */
  def followersOf(userId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(
        Json.toJson(
          this.service.getUserFollowers(userId, user.getOrElse(User.guestUser))
        )
      )
    }
  }

  /**
    * Start following a user
    *
    * @param userId The id of the user to follow
    */
  def follow(userId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val followed = this.serviceManager.user.retrieve(userId) match {
        case Some(u) => u
        case None =>
          throw new NotFoundException(s"No user with id $userId found")
      }
      this.service.follow(user, followed, user)
      Ok(Json.toJson(this.service.getUsersFollowedBy(user.id, user)))
    }
  }

  /**
    * Stop following a user
    *
    * @param userId The id of the user to stop following
    */
  def unfollow(userId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val followed = this.serviceManager.user.retrieve(userId) match {
        case Some(u) => u
        case None =>
          throw new NotFoundException(s"No user with id $userId found")
      }
      this.service.stopFollowing(user, followed, user)
      Ok(Json.toJson(this.service.getUsersFollowedBy(user.id, user)))
    }
  }

  /**
    * Block a follower
    *
    * @param userId The id of the user to block
    */
  def block(userId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.service.blockFollower(userId, user, user)
      Ok(Json.toJson(this.service.getUserFollowers(user.id, user)))
    }
  }

  /**
    * Unblock a blocked follower
    *
    * @param userId The id of the user to unblock
    */
  def unblock(userId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.service.unblockFollower(userId, user, user)
      Ok(Json.toJson(this.service.getUserFollowers(user.id, user)))
    }
  }
}

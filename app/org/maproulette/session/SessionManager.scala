/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.session

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat
import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.exception.MPExceptionUtil
import org.maproulette.framework.model.User
import org.maproulette.framework.service.ServiceManager
import org.maproulette.models.dal.DALManager
import org.maproulette.permissions.Permission
import org.maproulette.utils.Crypto
import org.slf4j.LoggerFactory
import play.api.db.Database
import play.api.libs.oauth._
import play.api.libs.ws.WSClient
import play.api.mvc.{AnyContent, Request, RequestHeader, Result}
import play.shaded.oauth.oauth.signpost.exception.{OAuthException, OAuthNotAuthorizedException}

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

/**
  * The Session manager handles the current user session. Making sure that requests that require
  * authorization are correctly authorized.
  *
  * @author cuthbertm
  */
@Singleton
class SessionManager @Inject() (
    ws: WSClient,
    dalManager: DALManager,
    serviceManager: ServiceManager,
    config: Config,
    db: Database,
    crypto: Crypto,
    permission: Permission
) {

  import scala.concurrent.ExecutionContext.Implicits.global

  private val logger = LoggerFactory.getLogger(this.getClass)

  // URLs used for OAuth 1.0a
  private val osmOAuth = config.getOSMOauth

  // The OAuth object used to make the requests to the OpenStreetMap servers
  private val oauth = OAuth(
    ServiceInfo(
      osmOAuth.requestTokenURL,
      osmOAuth.accessTokenURL,
      osmOAuth.authorizationURL,
      osmOAuth.consumerKey
    ),
    true
  )

  /**
    * Retrieves the user from the OpenStreetMap servers
    *
    * @param token The oauth2 access token from OSM
    * @param request  The request made from the OpenStreetMap servers based on OAuth
    * @return A Future which will contain the user
    */
  def retrieveUser(token: String)(implicit request: Request[AnyContent]): Future[User] = {
    val p = Promise[User]()
    this.loginUser(token) onComplete {
      case Success(user) =>
        user match {
          case Some(u) =>
            p success u.copy(osmProfile = u.osmProfile.copy(requestToken = token))
          case None => p failure new OAuthNotAuthorizedException()
        }

      case Failure(e) =>
        logger.error(e.getMessage, e)
        p failure e
    }
    p.future
  }

  /**
    * Retrieves the request token and then makes a callback to the MapRoulette auth URL
    *
    * @param callback The callback after the request is made to retrieve the request token
    * @return Either OAuthException (ie. NotAuthorized) or the request token
    */
  def retrieveRequestToken(callback: String): Either[OAuthException, RequestToken] =
    this.oauth.retrieveRequestToken(callback)

  /**
    * The URL where the user needs to be redirected to grant authorization to your application.
    *
    * @param token request token
    */
  def redirectUrl(token: String): String = this.oauth.redirectUrl(token)

  /**
    * For a user aware request we are simply checking to see if we can find a user that can be
    * associated with the current session. So if a session token is available we will try to authenticate
    * the user and optionally return a User object.
    *
    * @param block   The block of code that is executed after user has been checked
    * @param request The incoming http request
    * @return The result from the block of code
    */
  def userAwareRequest(
      block: Option[User] => Result
  )(implicit request: Request[Any]): Future[Result] = {
    MPExceptionUtil.internalAsyncExceptionCatcher { () =>
      this.userAware(block)
    }
  }

  protected def userAware(
      block: Option[User] => Result
  )(implicit request: Request[Any]): Future[Result] = {
    val p = Promise[Result]()
    this.sessionUser onComplete {
      case Success(result) =>
        Try(block(result)) match {
          case Success(res) => p success res
          case Failure(f)   => p failure f
        }
      case Failure(error) => p failure error
    }
    p.future
  }

  /**
    * For an authenticated request we expect there to currently be a valid session. If no session
    * is available an OAuthNotAuthorizedException will be thrown.
    *
    * @param block            The block of code to execute after a valid session has been found
    * @param request          The incoming http request
    * @param requireSuperUser Whether a super user is required for this request
    * @return The result from the block of code
    */
  def authenticatedRequest(
      block: User => Result
  )(implicit request: Request[Any], requireSuperUser: Boolean = false): Future[Result] = {
    MPExceptionUtil.internalAsyncExceptionCatcher { () =>
      this.authenticated(Left(block))
    }
  }

  protected def authenticated(
      execute: Either[User => Result, User => Future[Result]]
  )(implicit request: Request[Any], requireSuperUser: Boolean = false): Future[Result] = {
    val p = Promise[Result]()
    try {
      this.sessionUser onComplete {
        case Success(result) =>
          result match {
            case Some(user) =>
              try {
                if (requireSuperUser && !permission.isSuperUser(user)) {
                  p failure new IllegalAccessException("Only a super user can make this request")
                } else {
                  execute match {
                    case Left(block) =>
                      Try(block(user)) match {
                        case Success(s) => p success s
                        case Failure(f) => p failure f
                      }
                    case Right(block) =>
                      Try(block(user)) match {
                        case Success(s) =>
                          s onComplete {
                            case Success(s) => p success s
                            case Failure(f) => p failure f
                          }
                        case Failure(f) => p failure f
                      }
                  }
                }
              } catch {
                case e: Exception => p failure e
              }
            case None => p failure new OAuthNotAuthorizedException()
          }
        case Failure(e) => p failure e
      }
    } catch {
      case e: Exception => p failure e
    }
    p.future
  }

  /**
    * Retrieves the hash of the OSM access token stored in the user's session cookie.
    *
    * @param request The http request
    * @return The token hash. None if no session has been established yet, or it has timed out
    */
  private def retrieveSessionTokenHash(implicit request: RequestHeader): Option[String] = {
    for {
      hash <- request.session.get(SessionManager.KEY_TOKEN_HASH)
      tick <- request.session.get(SessionManager.KEY_USER_TICK)
      if tick.toLong >= DateTime
        .now()
        .getMillis - config.sessionTimeout || config.ignoreSessionTimeout
    } yield {
      hash
    }
  }

  /**
    * Retrieves the user for the current session. The session cookie holds the user's id and a hash
    * of their OSM access token. The session is valid only while that hash matches the token in the
    * database, so changing users.oauth_token revokes it.
    *
    * @param request The http request
    * @return A Future for an optional user, None if there is no valid session
    */
  def sessionUser(implicit request: RequestHeader): Future[Option[User]] = {
    devModeUser match {
      case Some(u) => Future.successful(Some(u))
      case None =>
        val user = for {
          hash   <- retrieveSessionTokenHash
          userId <- request.session.get(SessionManager.KEY_USER_ID)
          u      <- this.serviceManager.user.retrieve(userId.toLong)
          if MessageDigest.isEqual(
            SessionManager.hashToken(u.osmProfile.requestToken).getBytes(UTF_8),
            hash.getBytes(UTF_8)
          )
        } yield u
        user match {
          case Some(u) => refreshIfStale(u)
          case None    => Future.successful(None)
        }
    }
  }

  /**
    * Retrieves the user that owns a newly issued OSM access token, creating the user from their
    * OSM profile if they don't exist yet.
    *
    * @param accessToken The OSM access token
    * @return A Future for an optional user, None if the user could not be found or created
    */
  private def loginUser(accessToken: String): Future[Option[User]] = {
    devModeUser match {
      case Some(u) => Future.successful(Some(u))
      case None =>
        this.serviceManager.user.matchByRequestToken(-1, accessToken, User.superUser) match {
          case Some(u) => refreshIfStale(u)
          case None    => this.refreshProfile(accessToken, User.superUser)
        }
    }
  }

  /**
    * In dev mode every request is made as the super user, or as the user configured for
    * impersonation.
    */
  private def devModeUser: Option[User] = {
    if (!config.isDevMode) {
      None
    } else if (config.impersonateUserId < 0) {
      Some(User.superUser)
    } else {
      Some(
        this.serviceManager.user
          .retrieveByOSMId(config.impersonateUserId)
          .getOrElse(User.superUser)
      )
    }
  }

  private def refreshIfStale(user: User): Future[Option[User]] = {
    if (user.modified.plusDays(1).isBefore(DateTime.now())) {
      this.refreshProfile(user.osmProfile.requestToken, User.superUser)
    } else {
      Future.successful(Some(user))
    }
  }

  /**
    * Will refresh the current profile or will create a new user based on this profile
    *
    * @param accessToken The oauth2 access token for the current user session
    * @return A Future for an optional user, if user not found, or could not be created will return
    *         None.
    */
  def refreshProfile(accessToken: String, user: User): Future[Option[User]] = {
    val p = Promise[Option[User]]()
    // if no user is matched, then lets create a new user
    val endpoint = s"${config.getOSMServer}/api/0.6/user/details"

    ws.url(endpoint)
      .withHttpHeaders(
        "Authorization" -> s"Bearer $accessToken"
      )
      .get()
      .map { response =>
        if (response.status == 200) {
          try {
            val newUser = User.generate(response.body, accessToken, config)
            val osmUser = this.serviceManager.user.create(newUser, user)
            p success Some(this.serviceManager.user.initializeHomeProject(osmUser))
          } catch {
            case e: Exception => p failure e
          }
        } else {
          throw new RuntimeException(
            s"Failed to retrieve user from OSM. Status code: ${response.status}"
          )
        }
      }
    p.future
  }

  /**
    * For an authenticated request we expect there to currently be a valid session. If no session is
    * available an OAuthNotAuthorizedException will be thrown. This function differs from the
    * authenticatedRequest as it allows the lambda function to return a future
    *
    * @param block            The block of code to execute after a valid session has been found
    * @param request          The incoming http request
    * @param requireSuperUser Whether a super user is required for this request
    * @return The result from the block of code
    */
  def authenticatedFutureRequest(
      block: User => Future[Result]
  )(implicit request: Request[Any], requireSuperUser: Boolean = false): Future[Result] = {
    MPExceptionUtil.internalAsyncExceptionCatcher { () =>
      this.authenticated(Right(block))
    }
  }
}

object SessionManager {
  val KEY_USER_TICK  = "userTick"
  val KEY_TOKEN_HASH = "tokenHash"
  val KEY_USER_ID    = "userId"
  val KEY_OSM_ID     = "osmId"
  val KEY_STATE      = "state"

  /**
    * Returns the hex-encoded SHA-256 hash of an OSM access token, so we can store
    * it in the session cookie (the cookie is signed but not encrpyted, so its unsafe
    * to store the raw token in it).
    */
  def hashToken(token: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8)))
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package controllers

import com.google.inject.Inject
import org.apache.commons.lang3.StringUtils
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.exception._
import org.maproulette.framework.model.{Grant, GrantTarget}
import org.maproulette.framework.service.UserService
import org.maproulette.models.dal.DALManager
import org.maproulette.session.SessionManager
import org.maproulette.permissions.Permission
import org.maproulette.utils.Crypto
import play.api.libs.json.{JsString, Json}
import play.api.mvc._
import play.api.libs.ws.WSClient
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.Promise
import scala.util.{Failure, Success}
import scala.concurrent.Future
import java.security.SecureRandom

/**
  * All the authentication actions go in this class
  *
  * @author cuthbertm
  */
class AuthController @Inject() (
    components: ControllerComponents,
    sessionManager: SessionManager,
    userService: UserService,
    dalManager: DALManager,
    permission: Permission,
    wsClient: WSClient,
    crypto: Crypto,
    val config: Config
) extends AbstractController(components)
    with StatusMessages {

  val logger: Logger = LoggerFactory.getLogger(classOf[AuthController])
  import scala.concurrent.ExecutionContext.Implicits.global

  /**
    * Resolves the frontend origin to use as the OAuth2 redirect_uri. Prefers
    * an explicit redirectUri request parameter, falling back to the request's
    * Origin header and finally the configured frontend. authenticate() stores
    * the result in the session so that callback() uses the same redirect_uri.
    * OSM rejects any redirect_uri not registered on the OAuth application, so
    * we don't need to check the URI against an allowlist ourselves here.
    */
  private def resolveRedirectURI(
      requested: String
  )(implicit request: Request[AnyContent]): String =
    if (StringUtils.isNotEmpty(requested)) requested
    else request.headers.get(ORIGIN).getOrElse(config.getMRFrontend)

  /**
    * The OAuth2 callback handler: it exchanges the authorization code from OSM
    * for an access token, finds or creates the corresponding user, and starts a
    * session for them.
    *
    * The state must match the one that authenticate() stored in the session.
    * This prevents login CSRF, where an attacker tricks a victim into logging
    * in with the attacker's own authorization code. The state is removed from
    * the session whatever the outcome, so it can only be used once.
    *
    * @param code  The authorization code issued by OSM
    * @param state The state that OSM passed back along with the code
    */
  def callback(code: String, state: String): Action[AnyContent] = Action.async { implicit request =>
    val result = (
      request.session.get(SessionManager.KEY_STATE),
      request.session.get(SessionManager.KEY_REDIRECT_URI)
    ) match {
      case (Some(`state`), Some(redirectUri)) => exchangeCode(code, redirectUri)
      case _ =>
        Future.successful(
          BadRequest(Json.toJson(StatusMessage("KO", JsString("Invalid OAuth state"))))
        )
    }
    result.map(
      _.removingFromSession(SessionManager.KEY_STATE, SessionManager.KEY_REDIRECT_URI)
    )
  }

  private def exchangeCode(code: String, redirectUri: String)(
      implicit request: Request[AnyContent]
  ): Future[Result] =
    MPExceptionUtil.internalAsyncExceptionCatcher { () =>
      val tokenEndpoint = s"${config.getOSMServer}/oauth2/token"
      val clientId      = s"${config.getOSMOauth.consumerKey.key}"
      val clientSecret  = s"${config.getOSMOauth.consumerKey.secret}"

      val requestBody = Map(
        "grant_type"    -> "authorization_code",
        "code"          -> code,
        "client_id"     -> clientId,
        "client_secret" -> clientSecret,
        "redirect_uri"  -> redirectUri
      )

      val responseFuture = for {
        response <- wsClient
          .url(tokenEndpoint)
          .withHttpHeaders(ACCEPT -> JSON)
          .withHttpHeaders(CONTENT_TYPE -> FORM)
          .post(requestBody)
        result <- response.status match {
          case OK =>
            val accessToken = (response.json \ "access_token").as[String]
            val p           = Promise[Result]()

            //use the accessToken to retrieve the user.  if not found, create a new user
            sessionManager.retrieveUser(accessToken) onComplete {
              case Success(user) =>
                // We received the authorized token in the OAuth object - store it before we proceed
                val json = Json.obj(
                  "token" -> accessToken
                )

                p success
                  Ok(json)
                    .withHeaders(("Cache-Control", "no-cache"))
                    .withSession(
                      SessionManager.KEY_TOKEN_HASH -> SessionManager
                        .hashToken(user.osmProfile.requestToken),
                      SessionManager.KEY_USER_ID   -> user.id.toString,
                      SessionManager.KEY_OSM_ID    -> user.osmProfile.id.toString,
                      SessionManager.KEY_USER_TICK -> DateTime.now().getMillis.toString
                    )
              case Failure(e) => p failure e
            }

            p.future
          case _ =>
            val errorMessage = (response.json \ "error_description")
              .asOpt[String]
              .getOrElse("Failed to obtain access token")
            Future.successful(InternalServerError(errorMessage))
        }
      } yield result

      responseFuture.recover {
        case ex: Exception =>
          logger.error(ex.getMessage, ex)
          InternalServerError("Failed to obtain access token")
      }

    }

  def authenticate(redirectUri: String): Action[AnyContent] = Action.async { implicit request =>
    MPExceptionUtil.internalAsyncExceptionCatcher { () =>
      val LENGTH = 48
      val UNICODE_ASCII_CHARACTER_SET =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toSeq

      // Generate a random state string of a given length using a given set of characters
      def generateRandomState(
          length: Int = LENGTH,
          chars: Seq[Char] = UNICODE_ASCII_CHARACTER_SET
      ): String = {
        val rand  = new SecureRandom()
        val state = new Array[Char](length)
        for (i <- 0 until length) {
          state(i) = chars(rand.nextInt(chars.length))
        }
        new String(state)
      }

      val state             = generateRandomState()
      val resolvedRedirect  = resolveRedirectURI(redirectUri)
      val clientId          = s"${config.getOSMOauth.consumerKey.key}"
      val authorizeEndpoint = s"${config.getOSMServer}/oauth2/authorize"

      val params = Map(
        "client_id"     -> clientId,
        "response_type" -> "code",
        "redirect_uri"  -> resolvedRedirect,
        "scope"         -> config.getOSMOauth.scope,
        "state"         -> state
      )

      val url =
        wsClient.url(authorizeEndpoint).withQueryStringParameters(params.toSeq: _*).uri.toString

      val json = Json.obj(
        "state"    -> state,
        "redirect" -> url
      )

      Future(
        Ok(json)
          .withHeaders(CACHE_CONTROL -> "no-store")
          .addingToSession(
            SessionManager.KEY_STATE        -> state,
            SessionManager.KEY_REDIRECT_URI -> resolvedRedirect
          )
      )
    }
  }

  private def proxyRedirect(call: Call)(implicit request: Request[AnyContent]): String = {
    config.proxyPort match {
      case Some(port) =>
        val applicationPort = System.getProperty("http.port")
        call
          .absoluteURL(config.isProxySSL)
          .replaceFirst(s":$applicationPort", s"${if (port == 80) {
            ""
          } else {
            s":$port"
          }}")
      case None => call.absoluteURL(config.isProxySSL)
    }
  }

  // Signing in with a username and API key is deprecated; sign in through the OSM OAuth flow instead.
  def signIn(redirect: String): Action[AnyContent] = Action(apiKeysDisabled)

  /**
    * Signs out the user, creating essentially a blank new session and responds with a 200 OK
    *
    * @return 200 OK Status
    */
  def signOut(): Action[AnyContent] = Action { implicit request =>
    Ok.withNewSession
  }

  def deleteUser(userId: Long): Action[AnyContent] = Action.async { implicit request =>
    implicit val requireSuperUser: Boolean = true
    sessionManager.authenticatedRequest { implicit user =>
      Ok(
        Json.toJson(
          StatusMessage(
            "OK",
            JsString(
              s"${this.userService.delete(userId, user)} User deleted by super user ${user.name} [${user.id}]."
            )
          )
        )
      )
    }
  }

  // API keys are disabled and can no longer be generated or reset
  def generateAPIKey(userId: Long = -1): Action[AnyContent] = Action(apiKeysDisabled)
  def resetAllAPIKeys(): Action[AnyContent]                 = Action(apiKeysDisabled)

  private def apiKeysDisabled: Result =
    Gone(Json.toJson(StatusMessage("KO", JsString("API keys are disabled"))))

  /**
    * Adds an Admin role on the project to the user
    *
    * @param projectId The id of the project to add the user too
    * @return NoContent
    */
  def addUserToProject(userId: Long, projectId: Long): Action[AnyContent] = Action.async {
    implicit request =>
      implicit val requireSuperUser: Boolean = true
      sessionManager.authenticatedRequest { implicit user =>
        this.userService.retrieve(userId) match {
          case Some(addUser) =>
            val projectTarget = GrantTarget.project(projectId)
            if (addUser.grants
                  .exists(g => g.target == projectTarget && g.role == Grant.ROLE_ADMIN)) {
              throw new InvalidException(
                s"User ${addUser.name} is already an admin of project $projectId"
              )
            }
            this.userService
              .addUserToProject(addUser.osmProfile.id, projectId, Grant.ROLE_ADMIN, user)
            Ok(
              Json.toJson(
                StatusMessage(
                  "OK",
                  JsString(s"User ${addUser.name} made admin of project $projectId")
                )
              )
            )
          case None => throw new NotFoundException(s"Could not find user with ID $userId")
        }
      }
  }
}

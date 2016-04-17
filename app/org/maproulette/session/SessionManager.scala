package org.maproulette.session

import javax.inject.Inject
import javax.inject.Singleton

import controllers.WebJarAssets
import dal.UserDAL
import io.netty.handler.codec.http.HttpResponseStatus
import oauth.signpost.exception.OAuthNotAuthorizedException
import org.apache.commons.lang3.StringUtils
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.exception.MPExceptionUtil
import org.maproulette.models.dal.ChallengeDAL
import play.api.i18n.Messages
import play.api.{Application, Logger}
import play.api.libs.Crypto
import play.api.libs.oauth._
import play.api.libs.ws.WSClient
import play.api.mvc.{AnyContent, Request, RequestHeader, Result}

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

/**
  * The Session manager handles the current user session. Making sure that requests that require
  * authorization are correctly authorized, and handling the APIKey for API request authorizations.
  *
  * @author cuthbertm
  */
@Singleton
class SessionManager @Inject() (ws:WSClient, userDAL:UserDAL, challengeDAL:ChallengeDAL, application:Application, config:Config) {
  import scala.concurrent.ExecutionContext.Implicits.global

  // URLs used for OAuth 1.0a
  private val userDetailsURL = application.configuration.getString("osm.userDetails").get
  private val requestTokenURL = application.configuration.getString("osm.requestTokenURL").get
  private val accessTokenURL = application.configuration.getString("osm.accessTokenURL").get
  private val authorizationURL = application.configuration.getString("osm.authorizationURL").get
  // the consumer key and secret for the Map Roulette application
  private val consumerKey = ConsumerKey(application.configuration.getString("osm.consumerKey").get,
    application.configuration.getString("osm.consumerSecret").get)

  // The OAuth object used to make the requests to the OpenStreetMap servers
  private val oauth = OAuth(ServiceInfo(requestTokenURL, accessTokenURL, authorizationURL, consumerKey), true)

  /**
    * Retrieves the user based on the verifier query string values from the authorization request to
    * the OpenStreetMap servers
    *
    * @param verifier The verifier query string values
    * @param request The request made from the OpenStreetMap servers based on OAuth
    * @return A Future which will contain the user
    */
  def retrieveUser(verifier:String)(implicit request:Request[AnyContent]) : Future[User] = {
    val p = Promise[User]
    sessionTokenPair match {
      case Some(pair) =>
        oauth.retrieveAccessToken(pair, verifier) match {
          case Right(accessToken) =>
            sessionUser(Some(accessToken), true)(request) onComplete {
              case Success(user) =>
                user match {
                  case Some(u) => p success u.copy(osmProfile = u.osmProfile.copy(requestToken = accessToken))
                  case None => p failure new OAuthNotAuthorizedException()
                }

              case Failure(e) =>
                Logger.error(e.getMessage, e)
                p failure e
            }
          case Left(e) =>
            Logger.error(e.getMessage, e)
            p failure e
        }
      case None => p failure new OAuthNotAuthorizedException()
    }
    p.future
  }

  /**
    * Retrieves the request token and then makes a callback to the Map Roulette auth URL
    *
    * @param callback The callback after the request is made to retrieve the request token
    * @return Either OAuthException (ie. NotAuthorized) or the request token
    */
  def retrieveRequestToken(callback:String) = oauth.retrieveRequestToken(callback)

  /**
    * The URL where the user needs to be redirected to grant authorization to your application.
    *
    * @param token request token
    */
  def redirectUrl(token:String) = oauth.redirectUrl(token)

  /**
    * Retrieves the session token pair that is stored in the users session cookie. This is the token
    * and secret that a user uses to authorize various requests to OSM. This needs to be stored in
    * a secure cookie, otherwise if someone gains access to the token pair they would gain access
    * to the user account.
    *
    * @param request The http request
    * @return A RequestToken containing the user's access token and secret for the session. None if
    *         no session has been established yet
    */
  def sessionTokenPair(implicit request: RequestHeader): Option[RequestToken] = {
    for {
      token <- request.session.get(SessionManager.KEY_TOKEN)
      secret <- request.session.get(SessionManager.KEY_SECRET)
      tick <- request.session.get(SessionManager.KEY_USER_TICK)
      if tick.toLong >= DateTime.now().getMillis - 1800000
    } yield {
      RequestToken(token, secret)
    }
  }

  /**
    * Based on the access token and some other session information this will retrieve the user
    * from the database. This will also check the apiKey if no session token is found, which can
    * be generated by the user to execute API requests.
    *
    * @param tokenPair The token pair, None if no token pair is found.
    * @param create default false, if true will create a new user in the database from the OSM User details.
    * @param request The http request that initiated the requirement for retrieving the user
    * @return A Future for an optional user, if user not found, or could not be created will return
    *         None.
    */
  def sessionUser(tokenPair:Option[RequestToken], create:Boolean=false)
                 (implicit request:RequestHeader) : Future[Option[User]] = {
    val p = Promise[Option[User]]
    val userId = request.session.get(SessionManager.KEY_USER_ID)
    val osmId = request.session.get(SessionManager.KEY_OSM_ID)
    tokenPair match {
      case Some(pair) => getUser(pair, userId, create) onComplete {
        case Success(optionUser) => p success optionUser
        case Failure(f) => p failure f
      }
      case None =>
        request.headers.get(SessionManager.KEY_API) match {
          case Some(apiKey) =>
            // The super key gives complete access to everything. By default the super key is not
            // enabled, but if it is anybody with that key can do anything in the system. This is
            // generally not a good idea to have it enabled, but useful for internal systems or
            // dev testing.
            if (config.superKey.nonEmpty && StringUtils.equals(config.superKey.get, apiKey)) {
              p success Some(User.superUser)
            } else {
              try {
                val decryptedKey = Crypto.decryptAES(apiKey).split("\\|")
                userDAL.retrieveByAPIKey(apiKey)(decryptedKey(0).toLong) match {
                  case Some(user) => p success Some(user)
                  case None => p success None
                }
              } catch {
                case e: NumberFormatException => p failure new OAuthNotAuthorizedException(s"Invalid APIKey supplied => $apiKey")
                case e: Exception => p failure e
              }
            }
          case None => p success None
        }
    }
    p.future
  }

  /**
    * If a session is found in the secure cookie, which includes the userId, will retrieve the userId
    * from cache/database.
    *
    * @param accessToken The access token (key and secret) current stored in the session
    * @param userId The userId stored in the session
    * @param create If the user does not exist, create a new user in the database. Default false
    * @return A Future for an optional user, if user not found, or could not be created will return
    *         None.
    */
  private def getUser(accessToken:RequestToken, userId:Option[String],
                      create:Boolean=false) : Future[Option[User]] = {
    // we use the userId for caching, so only if this is the first time the user is authorizing
    // in a particular session will it have to hit the database.
    val storedUser = userId match {
      case Some(sessionId) if StringUtils.isNotEmpty(sessionId) =>
        userDAL.matchByRequestTokenAndId(accessToken)(sessionId.toLong)
      case None => userDAL.matchByRequestToken(accessToken)
    }
    storedUser match {
      case Some(u) =>
        // if the user information is more than a day old, then lets update it.
        if (u.modified.plusDays(1).isBefore(DateTime.now())) {
          refreshProfile(u.osmProfile.requestToken, User.superUser)
        } else {
          Future { Some(u) }
        }
      case None =>
        if (create) {
          refreshProfile(accessToken, User.superUser)
        } else {
          Future { None }
        }
    }
  }

  /**
    * Will refresh the current profile or will create a new user based on this profile
    *
    * @param accessToken The access token for the current user session
    * @return A Future for an optional user, if user not found, or could not be created will return
    *         None.
    */
  def refreshProfile(accessToken:RequestToken, user:User) : Future[Option[User]] = {
    val p = Promise[Option[User]]
    // if no user is matched, then lets create a new user
    val details = ws.url(userDetailsURL).sign(OAuthCalculator(consumerKey, accessToken))
    details.get() onComplete {
      case Success(detailsResponse) if detailsResponse.status == HttpResponseStatus.OK.code() =>
        try {
          val newUser = User(detailsResponse.body, accessToken, config)
          p success Some(userDAL.insert(newUser, user))
        } catch {
          case e:Exception => p failure e
        }
      case Success(response) =>
        p failure new OAuthNotAuthorizedException(response.body)
      case Failure(error) =>
        Logger.error(error.getMessage, error)
        p failure error
    }
    p.future
  }

  /**
    * For a user aware request we are simply checking to see if we can find a user that can be
    * associated with the current session. So if a session token is available we will try to authenticate
    * the user and optionally return a User object. This differs from userAwareRequest
    * due to if any exceptions are thrown it will show a UI page, instead of a JSON payload
    *
    * @param block The block of code that is executed after user has been checked
    * @param request The incoming http request
    * @return The result from the block of code
    */
  def userAwareUIRequest(block:Option[User] => Result)
                        (implicit request:Request[Any], messages:Messages, webJarAssets: WebJarAssets) : Future[Result] = {
    MPExceptionUtil.internalAsyncUIExceptionCatcher(User.guestUser, config, challengeDAL) { () =>
      userAware(block)
    }
  }

  /**
    * For a user aware request we are simply checking to see if we can find a user that can be
    * associated with the current session. So if a session token is available we will try to authenticate
    * the user and optionally return a User object.
    *
    * @param block The block of code that is executed after user has been checked
    * @param request The incoming http request
    * @return The result from the block of code
    */
  def userAwareRequest(block:Option[User] => Result)
                      (implicit request:Request[Any]) : Future[Result] = {
    MPExceptionUtil.internalAsyncExceptionCatcher { () =>
      userAware(block)
    }
  }

  protected def userAware(block:Option[User] => Result)
                         (implicit request:Request[Any]) : Future[Result] = {
    val p = Promise[Result]
    sessionUser(sessionTokenPair) onComplete {
      case Success(result) => Try(block(result)) match {
        case Success(res) => p success res
        case Failure(f) => p failure f
      }
      case Failure(error) => p failure error
    }
    p.future
  }

  /**
    * For an authenticated request we expect there to current be a valid session. If no session is
    * available an OAuthNotAuthorizedException will be thrown. This differs from authenticatedRequest
    * due to if any exceptions are thrown it will show a UI page, instead of a JSON payload
    *
    * @param block The block of code to execute after a valid session has been found
    * @param request The incoming http request
    * @return
    */
  def authenticatedUIRequest(block:User => Result)
                            (implicit request:Request[Any], messages:Messages, webJarAssets: WebJarAssets) : Future[Result] = {
    MPExceptionUtil.internalAsyncUIExceptionCatcher(User.guestUser, config, challengeDAL) { () =>
      authenticated(Left(block))
    }
  }

  def authenticatedFutureUIRequest(block:User => Future[Result])
                                  (implicit request:Request[Any], messages:Messages, webJarAssets: WebJarAssets) : Future[Result] = {
    MPExceptionUtil.internalAsyncUIExceptionCatcher(User.guestUser, config, challengeDAL) { () =>
      authenticated(Right(block))
    }
  }

  /**
    * For an authenticated request we expect there to currently be a valid session. If no session
    * is available an OAuthNotAuthorizedException will be thrown.
    *
    * @param block The block of code to execute after a valid session has been found
    * @param request The incoming http request
    * @return The result from the block of code
    */
  def authenticatedRequest(block:User => Result)
                          (implicit request:Request[Any]) : Future[Result] = {
    MPExceptionUtil.internalAsyncExceptionCatcher { () =>
      authenticated(Left(block))
    }
  }

  protected def authenticated(execute:Either[User => Result, User => Future[Result]])
                             (implicit request:Request[Any]) : Future[Result] = {
    val p = Promise[Result]
    try {
      sessionUser(sessionTokenPair) onComplete {
        case Success(result) => result match {
          case Some(user) => try {
            execute match {
              case Left(block) => Try(block(user)) match {
                case Success(s) => p success s
                case Failure(f) => p failure f
              }
              case Right(block) => Try(block(user)) match {
                case Success(s) => s onComplete {
                  case Success(s) => p success s
                  case Failure(f) => p failure f
                }
                case Failure(f) => p failure f
              }
            }
          } catch {
            case e:Exception => p failure e
          }
          case None => p failure new OAuthNotAuthorizedException()
        }
        case Failure(e) => p failure e
      }
    } catch {
      case e:Exception => p failure e
    }
    p.future
  }
}

object SessionManager {
  val KEY_USER_TICK = "userTick"
  val KEY_TOKEN = "token"
  val KEY_SECRET = "secret"
  val KEY_USER_ID = "userId"
  val KEY_OSM_ID = "osmId"
  val KEY_API = "apiKey"
}

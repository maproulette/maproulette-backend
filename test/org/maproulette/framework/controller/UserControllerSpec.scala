/*
 * Copyright (C) 2026 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.controller

import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.framework.model.{Location, OSMProfile, User}
import org.maproulette.framework.service.{ServiceManager, UserService}
import org.maproulette.models.dal.DALManager
import org.maproulette.permissions.Permission
import org.maproulette.session.SessionManager
import org.maproulette.utils.Crypto
import org.mockito.ArgumentMatchers.{any, eq => eqM}
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.db.Database
import play.api.libs.ws.WSClient
import play.api.mvc.ControllerComponents
import play.api.test.FakeRequest
import play.api.test.Helpers._

class UserControllerSpec extends PlaySpec with MockitoSugar {
  implicit val configuration: Configuration = Configuration.from(
    Map(
      "maproulette.secret.key"         -> "test-secret-key",
      Config.KEY_OSM_SERVER            -> "http://localhost",
      Config.KEY_OSM_USER_DETAILS_URL  -> "/api/0.6/user/details",
      Config.KEY_OSM_REQUEST_TOKEN_URL -> "/oauth/request_token",
      Config.KEY_OSM_ACCESS_TOKEN_URL  -> "/oauth/access_token",
      Config.KEY_OSM_AUTHORIZATION_URL -> "/oauth/authorize",
      Config.KEY_OSM_CONSUMER_KEY      -> "test",
      Config.KEY_OSM_CONSUMER_SECRET   -> "test",
      Config.KEY_OSM_OAUTH2_SCOPE      -> "read_prefs"
    )
  )
  val config: Config = new Config()
  val crypto: Crypto = new Crypto(config)

  val rawApiKey: String       = "test-api-key"
  val encryptedApiKey: String = crypto.encrypt(rawApiKey)
  val testUser: User = User(
    12345,
    DateTime.now(),
    DateTime.now(),
    OSMProfile(54321, "TestUser", "Test User", "", Location(1.0, 2.0), DateTime.now(), "token"),
    List.empty,
    Some(encryptedApiKey)
  )

  // The oauth2 access token as found in the "token" field of the PLAY_SESSION cookie
  val sessionToken: String = "3mxmGvQeLOg6YmT5rXo4Arx081-4N_xaYMYEVfM898U"

  val userService: UserService       = mock[UserService]
  val serviceManager: ServiceManager = mock[ServiceManager]
  when(serviceManager.user).thenReturn(userService)
  when(userService.retrieveByAPIKey(any[Long], any[String], any[User])).thenReturn(None)
  when(userService.retrieveByAPIKey(eqM(testUser.id), eqM(encryptedApiKey), any[User]))
    .thenReturn(Some(testUser))
  when(userService.matchByRequestToken(any[Long], any[String], any[User])).thenReturn(None)
  when(userService.matchByRequestToken(eqM(testUser.id), eqM(sessionToken), any[User]))
    .thenReturn(Some(testUser))

  val permission: Permission = mock[Permission]
  val sessionManager: SessionManager = new SessionManager(
    mock[WSClient],
    mock[DALManager],
    serviceManager,
    config,
    mock[Database],
    crypto,
    permission
  )

  val components: ControllerComponents = stubControllerComponents()
  val controller = new UserController(
    serviceManager,
    sessionManager,
    components,
    components.parsers,
    crypto,
    permission
  )

  // Builds a request carrying the session attributes that Play would decode from the
  // PLAY_SESSION cookie's data block: {token, userId, osmId, userTick}
  private def sessionRequest(
      token: String,
      tick: Long = DateTime.now().getMillis
  ) =
    FakeRequest(GET, "/user/whoami").withSession(
      SessionManager.KEY_TOKEN     -> token,
      SessionManager.KEY_USER_ID   -> testUser.id.toString,
      SessionManager.KEY_OSM_ID    -> testUser.osmProfile.id.toString,
      SessionManager.KEY_USER_TICK -> tick.toString
    )

  "GET /user/whoami" should {
    "return 401 when no credentials are provided" in {
      val result = controller.whoami()(FakeRequest(GET, "/user/whoami"))
      status(result) mustEqual UNAUTHORIZED
      (contentAsJson(result) \ "status").validate[String].get mustEqual "NotAuthorized"
    }

    "return 401 when the apiKey does not match a user" in {
      val request =
        FakeRequest(GET, "/user/whoami").withHeaders("apiKey" -> s"${testUser.id}|unknown-key")
      status(controller.whoami()(request)) mustEqual UNAUTHORIZED
    }

    "return 401 when the apiKey has the wrong user id" in {
      val request =
        FakeRequest(GET, "/user/whoami").withHeaders("apiKey" -> s"99999|$rawApiKey")
      status(controller.whoami()(request)) mustEqual UNAUTHORIZED
    }

    "return 401 when the apiKey is malformed" in {
      val missingSeparator =
        FakeRequest(GET, "/user/whoami").withHeaders("apiKey" -> "no-separator")
      status(controller.whoami()(missingSeparator)) mustEqual UNAUTHORIZED

      val nonNumericId =
        FakeRequest(GET, "/user/whoami").withHeaders("apiKey" -> s"not-a-number|$rawApiKey")
      status(controller.whoami()(nonNumericId)) mustEqual UNAUTHORIZED
    }

    "return 200 with the user when a valid apiKey is provided" in {
      val request = FakeRequest(GET, "/user/whoami")
        .withHeaders("apiKey" -> s"${testUser.id}|$rawApiKey")
      val result = controller.whoami()(request)

      status(result) mustEqual OK
      contentType(result) mustEqual Some("application/json")
      val json = contentAsJson(result)
      (json \ "id").validate[Long].get mustEqual testUser.id
      (json \ "osmProfile" \ "id").validate[Long].get mustEqual testUser.osmProfile.id
      (json \ "osmProfile" \ "displayName").validate[String].get mustEqual
        testUser.osmProfile.displayName
      // whoami returns the user's api key in decrypted form
      (json \ "apiKey").validate[String].get mustEqual s"${testUser.id}|$rawApiKey"
    }

    "return 200 with the user when a valid session is provided" in {
      val result = controller.whoami()(sessionRequest(sessionToken))

      status(result) mustEqual OK
      val json = contentAsJson(result)
      (json \ "id").validate[Long].get mustEqual testUser.id
      (json \ "osmProfile" \ "id").validate[Long].get mustEqual testUser.osmProfile.id
    }

    "return 401 when the session token does not match a user" in {
      val result = controller.whoami()(sessionRequest("unknown-session-token"))
      status(result) mustEqual UNAUTHORIZED
    }

    "return 401 when the session userTick has expired" in {
      val expiredTick = DateTime.now().minusHours(2).getMillis
      val result      = controller.whoami()(sessionRequest(sessionToken, expiredTick))
      status(result) mustEqual UNAUTHORIZED
    }
  }
}

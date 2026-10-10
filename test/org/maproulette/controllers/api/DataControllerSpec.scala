package org.maproulette.controllers.api

import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.data.{ActionManager, DataManager, StatusActionManager}
import org.maproulette.framework.model.{Location, OSMProfile, User}
import org.maproulette.framework.service.{ServiceManager, UserService}
import org.maproulette.models.dal.{ChallengeDAL, DALManager}
import org.maproulette.permissions.Permission
import org.maproulette.session.SessionManager
import org.maproulette.utils.Crypto
import org.mockito.ArgumentMatchers.{any, eq => eqM}
import org.mockito.Mockito.{never, reset, verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.db.Database
import play.api.libs.ws.WSClient
import play.api.mvc.{AnyContent, ControllerComponents, Action}
import play.api.test.FakeRequest
import play.api.test.Helpers._

class DataControllerSpec extends PlaySpec with MockitoSugar with BeforeAndAfterEach {
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

  private def user(id: Long, osmId: Long, token: String) =
    User(
      id,
      DateTime.now(),
      DateTime.now(),
      OSMProfile(osmId, s"User$id", "", "", Location(1.0, 2.0), DateTime.now(), token)
    )

  val mapper: User    = user(12345, 54321, "mapper-token")
  val superUser: User = user(23456, 65432, "super-token")

  val userService: UserService       = mock[UserService]
  val serviceManager: ServiceManager = mock[ServiceManager]
  when(serviceManager.user).thenReturn(userService)
  when(userService.retrieve(any[Long])).thenReturn(None)
  when(userService.retrieve(mapper.id)).thenReturn(Some(mapper))
  when(userService.retrieve(superUser.id)).thenReturn(Some(superUser))

  val permission: Permission = mock[Permission]
  when(permission.isSuperUser(any[User])).thenReturn(false)
  when(permission.isSuperUser(superUser)).thenReturn(true)

  val sessionManager: SessionManager = new SessionManager(
    mock[WSClient],
    mock[DALManager],
    serviceManager,
    config,
    mock[Database],
    new Crypto(config),
    permission
  )

  val dataManager: DataManager                 = mock[DataManager]
  val statusActionManager: StatusActionManager = mock[StatusActionManager]
  val components: ControllerComponents         = stubControllerComponents()
  val controller = new DataController(
    sessionManager,
    mock[ChallengeDAL],
    dataManager,
    config,
    mock[ActionManager],
    components,
    statusActionManager
  )

  override def beforeEach(): Unit = {
    reset(dataManager, statusActionManager)
    when(dataManager.getRawActivity(any(), any(), any(), any(), any())).thenReturn(List.empty)
    when(statusActionManager.getStatusUpdates(any(), any(), any(), any())).thenReturn(List.empty)
    when(statusActionManager.getStatusSummary(any(), any(), any(), any())).thenReturn(List.empty)
  }

  private def requestAs(u: User) =
    FakeRequest(GET, "/").withSession(
      SessionManager.KEY_TOKEN_HASH -> SessionManager.hashToken(u.osmProfile.requestToken),
      SessionManager.KEY_USER_ID    -> u.id.toString,
      SessionManager.KEY_OSM_ID     -> u.osmProfile.id.toString,
      SessionManager.KEY_USER_TICK  -> DateTime.now().getMillis.toString
    )

  private val superUserOnly: List[(String, Action[AnyContent])] = List(
    "GET /data/raw/activity" -> controller.getRawActivity("1,2", "", "", "", ""),
    "GET /data/status/activity" ->
      controller.getStatusActivity("1,2", "", "", "", "", "", "", 10, 0),
    "GET /data/status/summary" -> controller.getStatusSummary("1,2", "", "", "", "", 10, 0)
  )

  superUserOnly.foreach {
    case (name, action) =>
      name should {
        "return 401 when not logged in" in {
          status(action(FakeRequest(GET, "/"))) mustEqual UNAUTHORIZED
        }

        "return 403 to a logged-in user who is not a superuser" in {
          status(action(requestAs(mapper))) mustEqual FORBIDDEN
        }

        "return 200 to a superuser" in {
          status(action(requestAs(superUser))) mustEqual OK
        }
      }
  }

  "The superuser-only endpoints" should {
    "never query activity for a non-superuser" in {
      superUserOnly.foreach { case (_, action) => status(action(requestAs(mapper))) }
      verify(dataManager, never()).getRawActivity(any(), any(), any(), any(), any())
      verify(statusActionManager, never()).getStatusUpdates(any(), any(), any(), any())
      verify(statusActionManager, never()).getStatusSummary(any(), any(), any(), any())
    }

    "pass a superuser's userIds filter through unchanged" in {
      status(controller.getRawActivity("1,2", "", "", "", "")(requestAs(superUser))) mustEqual OK
      verify(dataManager).getRawActivity(eqM(Some(List(1L, 2L))), any(), any(), any(), any())
    }
  }
}

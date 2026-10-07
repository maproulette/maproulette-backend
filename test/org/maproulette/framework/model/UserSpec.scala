package org.maproulette.framework.model

import org.joda.time.DateTime
import org.maproulette.framework.graphql.schemas.MRSchemaTypes
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.{JsObject, JsValue, Json}

class UserSpec extends PlaySpec {
  private val user = User(
    7,
    DateTime.now(),
    DateTime.now(),
    OSMProfile(
      42,
      "mapper",
      "about me",
      "https://example.com/avatar.png",
      Location(0, 0),
      DateTime.now(),
      "osm-access-token"
    ),
    apiKey = Some("7|api-key"),
    settings = UserSettings(
      email = Some("mapper@example.com"),
      leaderboardOptOut = Some(true),
      customBasemaps =
        Some(List(CustomBasemap(name = "imagery", url = "https://tiles.example.com/?key=secret")))
    ),
    properties = Some(Json.obj("client" -> "state"))
  )

  private def token(json: JsValue)  = (json \ "osmProfile" \ "requestToken").asOpt[String]
  private def apiKey(json: JsValue) = (json \ "apiKey").asOpt[String]
  private def email(json: JsValue)  = (json \ "settings" \ "email").asOpt[String]

  private def keys(json: JsValue, path: String*): Set[String] =
    path.foldLeft(json)((js, p) => (js \ p).get).as[JsObject].keys.toSet

  "User json" should {
    "only shows public fields by default" in {
      val json = Json.toJson(user)

      keys(json) mustEqual Set("id", "osmProfile", "name", "created", "settings")
      keys(json, "osmProfile") mustEqual Set("id", "avatarURL", "displayName")
      keys(json, "settings") mustEqual Set("leaderboardOptOut")
      (json \ "osmProfile" \ "displayName").as[String] mustEqual "mapper"
      (json \ "settings" \ "leaderboardOptOut").as[Boolean] mustEqual true
    }

    "shows everything except OSM token and API key when read by a superuser" in {
      val json = Json.toJson(user)(User.adminWrites)

      token(json) mustEqual None
      apiKey(json) mustEqual None
      email(json) mustEqual Some("mapper@example.com")
      (json \ "properties" \ "client").as[String] mustEqual "state"
    }

    "shows everything except the API key when read by the user themselves" in {
      val json = Json.toJson(user)(User.privateWrites)

      token(json) mustEqual Some("osm-access-token")
      apiKey(json) mustEqual None
      email(json) mustEqual Some("mapper@example.com")
    }

    "round-trips through the private reader/writer (cache relies on this)" in {
      Json.toJson(user)(User.privateWrites).as[User] mustEqual user.copy(apiKey = None)
    }

    "shows only the public profile for a follower" in {
      val json = Json.toJson(Follower(1, user, Follower.STATUS_FOLLOWING))

      (json \ "status").as[Int] mustEqual Follower.STATUS_FOLLOWING
      (json \ "user").get mustEqual Json.toJson(user)
    }
  }

  "User GraphQL types" should {
    "expose only the public profile and follow lists" in {
      val types = new MRSchemaTypes {}

      types.UserType.fieldsByName.keySet mustEqual
        Set("id", "created", "osmProfile", "settings", "name", "following", "followers")
      types.OSMProfileType.fieldsByName.keySet mustEqual Set("id", "displayName", "avatarURL")
      types.UserSettingsType.fieldsByName.keySet mustEqual Set("leaderboardOptOut")
    }
  }
}

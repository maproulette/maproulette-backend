import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.framework.model._
import org.maproulette.models.dal.{ChallengeDAL, TaskDAL}
import org.maproulette.provider.ChallengeProvider
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq => eqM}
import org.mockito.Mockito.{doAnswer, never, timeout, verify, when}
import org.mockito.invocation.InvocationOnMock
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import play.api.db.Database
import play.api.libs.json._
import play.api.libs.ws.{WSClient, WSRequest, WSResponse}
import java.sql.Connection
import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters._

class ChallengeProviderSpec extends PlaySpec with MockitoSugar {
  val repository: ChallengeProvider = new ChallengeProvider(null, null, null, null, null)

  val challengeWithOsmId = Challenge(
    1,
    "ChallengeWithOsmId",
    DateTime.now(),
    DateTime.now(),
    None,
    false,
    false,
    false,
    false,
    None,
    ChallengeGeneral(101, 1, ""),
    ChallengeCreation(),
    ChallengePriority(),
    ChallengeExtra(osmIdProperty = Some("custom_osm_id"))
  )

  val challengeWithoutOsmId = Challenge(
    1,
    "ChallengeWithoutOsmId",
    DateTime.now(),
    DateTime.now(),
    None,
    false,
    false,
    false,
    false,
    None,
    ChallengeGeneral(101, 1, ""),
    ChallengeCreation(),
    ChallengePriority(),
    ChallengeExtra()
  )

  "getNonNullString" should {
    "return Some string for a non-empty string field" in {
      val json = Json.obj("field" -> "validString")
      repository.getNonNullString(json, "field") mustEqual Some("validString")
    }

    "return Some string for a numeric field" in {
      val json = Json.obj("field" -> 12345)
      repository.getNonNullString(json, "field") mustEqual Some("12345")
    }

    "return None for an empty string field" in {
      val json = Json.obj("field" -> "")
      repository.getNonNullString(json, "field") mustEqual None
    }

    "return None for a null field" in {
      val json = Json.obj("field" -> JsNull)
      repository.getNonNullString(json, "field") mustEqual None
    }

    "return None for a missing field" in {
      val json = Json.obj()
      repository.getNonNullString(json, "field") mustEqual None
    }

    "return None for a non-string and non-numeric field" in {
      val json = Json.obj("field" -> Json.obj("nestedField" -> "value"))
      repository.getNonNullString(json, "field") mustEqual None
    }
  }

  "findName" should {
    "return the first non-null, non-empty string from the list of fields" in {
      val json = Json.obj("field1" -> "first", "field2" -> "second")
      repository.findName(json, List("field1", "field2")) mustEqual Some("first")
    }

    "return None if none of the fields have valid values" in {
      val json = Json.obj("field1" -> "", "field2" -> JsNull)
      repository.findName(json, List("field1", "field2")) mustEqual None
    }

    "return None if the list of fields is empty" in {
      val json = Json.obj("field" -> "value")
      repository.findName(json, List()) mustEqual None
    }

    "return None if the fields do not exist in the JSON" in {
      val json = Json.obj()
      repository.findName(json, List("field1", "field2")) mustEqual None
    }
  }

  "featureOSMId" should {
    "return OSM ID from root if present and specified in challenge" in {
      val json = Json.obj("custom_osm_id" -> "singleFeatureId")
      repository.featureOSMId(json, challengeWithOsmId) mustEqual Some("singleFeatureId")
    }

    "return OSM ID from root if present and specified in challenge (numeric)" in {
      val json = Json.obj("custom_osm_id" -> 9999999)
      repository.featureOSMId(json, challengeWithOsmId) mustEqual Some("9999999")
    }

    "return OSM ID from properties if specified in challenge" in {
      val json = Json.obj("properties" -> Json.obj("custom_osm_id" -> "propertyId"))
      repository.featureOSMId(json, challengeWithOsmId) mustEqual Some("propertyId")
    }

    "return OSM ID from properties if specified in challenge (numeric)" in {
      val json = Json.obj("properties" -> Json.obj("custom_osm_id" -> 9999999))
      repository.featureOSMId(json, challengeWithOsmId) mustEqual Some("9999999")
    }

    "return OSM ID from first feature in list if specified in challenge" in {
      val json = Json.obj("features" -> Json.arr(Json.obj("custom_osm_id" -> "featureId1")))
      repository.featureOSMId(json, challengeWithOsmId) mustEqual Some("featureId1")
    }

    "return OSM ID from nested features if specified in challenge" in {
      val json = Json.obj(
        "features" -> Json.arr(
          Json.obj(
            "properties" -> Json.obj("custom_osm_id" -> "nestedFeatureId1")
          )
        )
      )
      repository.featureOSMId(json, challengeWithOsmId) mustEqual Some("nestedFeatureId1")
    }

    "return None if OSM ID not found in root or properties" in {
      val json = Json.obj("otherField" -> "value")
      repository.featureOSMId(json, challengeWithOsmId) mustEqual None
    }

    "return None if features do not contain specified OSM ID field" in {
      val json = Json.obj("features" -> Json.arr(Json.obj("otherField" -> "value1")))
      repository.featureOSMId(json, challengeWithOsmId) mustEqual None
    }

    "return None if challenge does not specify OSM ID property" in {
      val json = Json.obj("features" -> Json.arr(Json.obj("custom_osm_id" -> "featureId1")))
      repository.featureOSMId(json, challengeWithoutOsmId) mustEqual None
    }

    "return None if JSON has no features and challenge does not specify OSM ID property" in {
      val json = Json.obj("custom_osm_id" -> null, "fillerProperty" -> "string")
      repository.featureOSMId(json, challengeWithoutOsmId) mustEqual None
    }

    "return None if properties object is empty and challenge does not specify OSM ID property" in {
      val json =
        Json.obj("properties" -> Json.obj("custom_osm_id" -> null, "fillerProperty" -> "string"))
      repository.featureOSMId(json, challengeWithoutOsmId) mustEqual None
    }

    "return None if features array is empty and challenge specifies OSM ID property" in {
      val json = Json.obj("features" -> Json.arr())
      repository.featureOSMId(json, challengeWithOsmId) mustEqual None
    }
  }

  "taskNameFromJsValue" should {
    "return OSM ID from root object if present and specified in challenge" in {
      val json = Json.obj("custom_osm_id" -> "12345")
      repository.taskNameFromJsValue(json, challengeWithOsmId) mustEqual "12345"
    }

    "return feature id from properties if ID field is null and other fields are valid" in {
      val json = Json.obj(
        "id" -> null,
        "properties" -> Json
          .obj("name" -> "testName", "id" -> "idstring", "fillerProperty" -> "string")
      )
      repository.taskNameFromJsValue(json, challengeWithoutOsmId) mustEqual "idstring"
    }

    "return feature id from properties if ID field is null and properties contain valid IDs" in {
      val json = Json.obj(
        "properties" -> Json.obj("fillerProperty" -> "string", "id" -> null, "name" -> "testName")
      )
      repository.taskNameFromJsValue(json, challengeWithoutOsmId) mustEqual "testName"
    }

    "return feature id from the first feature if the ID field is null and challenge specifies valid feature IDs" in {
      val json = Json.obj(
        "features" -> Json.arr(
          Json.obj("id" -> null, "properties" -> Json.obj("name" -> "featureName")),
          Json.obj("id" -> null, "properties" -> Json.obj("name" -> "otherFeatureName"))
        )
      )
      repository.taskNameFromJsValue(json, challengeWithoutOsmId) mustEqual "featureName"
    }

    "return random UUID if OSM ID field is specified but not found" in {
      val json   = Json.obj("otherField" -> "value")
      val result = repository.taskNameFromJsValue(json, challengeWithOsmId)
      assert(UUID.fromString(result).toString == result)
    }

    "return random UUID if no valid ID fields are found" in {
      val json   = Json.obj()
      val result = repository.taskNameFromJsValue(json, challengeWithoutOsmId)
      assert(UUID.fromString(result).toString == result)
    }

    "return random UUID if features array is empty" in {
      val json   = Json.obj("features" -> Json.arr())
      val result = repository.taskNameFromJsValue(json, challengeWithoutOsmId)
      assert(UUID.fromString(result).toString == result)
    }

    "return random UUID if JSON features array has objects with null or empty names" in {
      val json   = Json.obj("features" -> Json.arr(Json.obj("name" -> ""), Json.obj("name" -> null)))
      val result = repository.taskNameFromJsValue(json, challengeWithoutOsmId)
      assert(UUID.fromString(result).toString == result)
    }
  }

  private val providerURL = "https://overpass.example/api/interpreter"
  implicit val configuration: Configuration = Configuration.from(
    Map(Config.KEY_OSM_QL_PROVIDER -> providerURL)
  )
  val config: Config = new Config()

  val overpassQuery: String =
    """[out:json]
      |area[name="Salt Lake City"]->.a;
      |node[highway=bus_stop](area.a);
      |out 5;""".stripMargin

  val overpassResponse: JsValue = Json.parse(
    """
    {
      "version": 0.6,
      "generator": "Overpass API 0.7.62.11 87bfad18",
      "osm3s": {
        "timestamp_osm_base": "2026-06-10T08:49:00Z",
        "timestamp_areas_base": "2026-05-12T07:36:16Z",
        "copyright": "The data included in this document is from www.openstreetmap.org. The data is made available under ODbL."
      },
      "elements": [
        {
          "type": "node",
          "id": 566529047,
          "lat": 40.7123196,
          "lon": -111.8653810,
          "tags": {
            "bench": "no",
            "bin": "no",
            "bus": "yes",
            "highway": "bus_stop",
            "lit": "yes",
            "name": "900 E @ 2709 S",
            "network": "UTA",
            "network:wikidata": "Q7902494",
            "operator": "UTA",
            "public_transport": "platform",
            "ref": "137020",
            "shelter": "no",
            "tactile_paving": "no"
          }
        },
        {
          "type": "node",
          "id": 946351389,
          "lat": 40.7752891,
          "lon": -111.8776585,
          "tags": {
            "bench": "no",
            "bin": "no",
            "bus": "yes",
            "highway": "bus_stop",
            "lit": "no",
            "name": "E Street / 5th Avenue (SB)",
            "network": "UTA",
            "network:wikidata": "Q7902494",
            "operator": "Utah Transit Authority",
            "public_transport": "platform",
            "ref": "102138",
            "shelter": "yes",
            "tactile_paving": "no"
          }
        },
        {
          "type": "node",
          "id": 1226596568,
          "lat": 40.7628224,
          "lon": -111.9081310,
          "tags": {
            "bench": "no",
            "bin": "yes",
            "bus": "yes",
            "fixme": "ref? location? stop does not exist in UTA data",
            "highway": "bus_stop",
            "name": "300 S / 600 W",
            "network": "UTA",
            "network:wikidata": "Q7902494",
            "operator": "UTA",
            "public_transport": "platform",
            "route_ref": "220",
            "shelter": "no",
            "tactile_paving": "no"
          }
        },
        {
          "type": "node",
          "id": 1399278912,
          "lat": 40.7727570,
          "lon": -111.8582420,
          "tags": {
            "bench": "no",
            "bus": "yes",
            "highway": "bus_stop",
            "name": "3rd Avenue / R Street (EB)",
            "network": "UTA",
            "network:wikidata": "Q7902494",
            "operator": "Utah Transit Authority",
            "public_transport": "platform",
            "ref": "118030",
            "shelter": "no",
            "tactile_paving": "no"
          }
        },
        {
          "type": "node",
          "id": 1399429828,
          "lat": 40.7805895,
          "lon": -111.8604573,
          "tags": {
            "bench": "no",
            "bus": "yes",
            "highway": "bus_stop",
            "name": "11th Avenue / Cemetery Center Street (EB)",
            "network": "UTA",
            "network:wikidata": "Q7902494",
            "operator": "Utah Transit Authority",
            "public_transport": "platform",
            "ref": "118189",
            "shelter": "no"
          }
        }
      ]
    }
    """
  )

  val overpassChallenge: Challenge = Challenge(
    100,
    "OverpassBusStops",
    DateTime.now(),
    DateTime.now(),
    None,
    false,
    false,
    false,
    false,
    None,
    ChallengeGeneral(101, 1, ""),
    ChallengeCreation(overpassQL = Some(overpassQuery)),
    ChallengePriority(),
    ChallengeExtra()
  )

  val user: User = User.superUser

  // Fresh mocks per test so the async verifications cannot interfere with each other
  private class OverpassFixture(
      status: Int = 200,
      contentType: Option[String] = Some("application/json"),
      responseBody: String = ""
  ) {
    val ws: WSClient               = mock[WSClient]
    val wsRequest: WSRequest       = mock[WSRequest]
    val wsResponse: WSResponse     = mock[WSResponse]
    val challengeDAL: ChallengeDAL = mock[ChallengeDAL]
    val taskDAL: TaskDAL           = mock[TaskDAL]
    val db: Database               = mock[Database]

    when(ws.url(any[String])).thenReturn(wsRequest)
    when(wsRequest.withRequestTimeout(any[Duration])).thenReturn(wsRequest)
    when(wsRequest.post(any[String])(any())).thenReturn(Future.successful(wsResponse))
    when(wsResponse.status).thenReturn(status)
    when(wsResponse.statusText).thenReturn("statusText")
    when(wsResponse.header("Content-Type")).thenReturn(contentType)
    when(wsResponse.json).thenReturn(overpassResponse)
    when(wsResponse.body).thenReturn(responseBody)
    doAnswer { (invocation: InvocationOnMock) =>
      invocation.getArgument(0).asInstanceOf[Connection => Any](mock[Connection])
    }.when(db).withTransaction(any[Connection => Any])

    val provider = new ChallengeProvider(challengeDAL, taskDAL, config, ws, db)
  }

  "buildTasks with an overpass query" should {
    "post the query unmodified to the configured overpass provider" in new OverpassFixture {
      provider.buildTasks(user, overpassChallenge) mustEqual true

      val queryCaptor: ArgumentCaptor[String] = ArgumentCaptor.forClass(classOf[String])
      verify(wsRequest, timeout(5000)).post(queryCaptor.capture())(any())
      // this query already contains [out:json], so rewriteQuery must pass it through untouched
      queryCaptor.getValue mustEqual overpassQuery

      verify(ws).url(providerURL)
      // no [timeout:N] in the query, so the configured default applies
      verify(wsRequest).withRequestTimeout(Duration(Config.DEFAULT_OSM_QL_TIMEOUT, "seconds"))
    }

    "create a task for each element in the overpass response" in new OverpassFixture {
      provider.buildTasks(user, overpassChallenge)

      val taskCaptor: ArgumentCaptor[Task] = ArgumentCaptor.forClass(classOf[Task])
      verify(taskDAL, timeout(5000).times(5))
        .mergeUpdate(taskCaptor.capture(), eqM(user))(any[Long], any[Option[Connection]])

      val tasks = taskCaptor.getAllValues.asScala
      tasks.map(_.name) must contain theSameElementsAs List(
        "566529047",
        "946351389",
        "1226596568",
        "1399278912",
        "1399429828"
      )
      tasks.foreach(_.parent mustEqual overpassChallenge.id)

      val feature = (tasks.find(_.name == "566529047").get.geometries \ "features")(0)
      (feature \ "geometry" \ "type").as[String] mustEqual "Point"
      (feature \ "geometry" \ "coordinates").as[List[Double]] mustEqual
        List(-111.8653810, 40.7123196)
      (feature \ "properties" \ "name").as[String] mustEqual "900 E @ 2709 S"
      (feature \ "properties" \ "highway").as[String] mustEqual "bus_stop"
      (feature \ "properties" \ "osmid").as[String] mustEqual "566529047"
    }

    "mark the challenge as ready once all tasks are built" in new OverpassFixture {
      provider.buildTasks(user, overpassChallenge)

      val updateCaptor: ArgumentCaptor[JsValue] = ArgumentCaptor.forClass(classOf[JsValue])
      verify(challengeDAL, timeout(5000).times(3))
        .update(updateCaptor.capture(), eqM(user))(eqM(overpassChallenge.id), any())
      val statuses = updateCaptor.getAllValues.asScala.map(json => (json \ "status").as[Int])
      statuses.take(2) mustEqual List(Challenge.STATUS_BUILDING, Challenge.STATUS_BUILDING)
      statuses.last mustEqual Challenge.STATUS_READY

      verify(challengeDAL).markTasksRefreshed(eqM(true), any())(eqM(overpassChallenge.id), any())
      verify(challengeDAL).updateBoundingBox()(eqM(overpassChallenge.id), any())
      verify(challengeDAL)
        .updateFinishedStatus(eqM(true), eqM(user))(eqM(overpassChallenge.id), any())
    }

    "fail the challenge when overpass returns a non-JSON content type" in new OverpassFixture(
      contentType = Some("text/csv")
    ) {
      provider.buildTasks(user, overpassChallenge)

      val updateCaptor: ArgumentCaptor[JsValue] = ArgumentCaptor.forClass(classOf[JsValue])
      verify(challengeDAL, timeout(5000).times(3))
        .update(updateCaptor.capture(), eqM(user))(eqM(overpassChallenge.id), any())
      val lastUpdate = updateCaptor.getAllValues.asScala.last
      (lastUpdate \ "status").as[Int] mustEqual Challenge.STATUS_FAILED
      (lastUpdate \ "statusMessage").as[String] must include("Content-Type: text/csv")

      verify(taskDAL, never()).mergeUpdate(any(), any())(any(), any())
    }

    "fail the challenge with a friendly message when overpass is too busy" in new OverpassFixture(
      status = 504,
      responseBody = "<html><strong>Error</strong>runtime error</html>"
    ) {
      provider.buildTasks(user, overpassChallenge)

      val updateCaptor: ArgumentCaptor[JsValue] = ArgumentCaptor.forClass(classOf[JsValue])
      verify(challengeDAL, timeout(5000).times(3))
        .update(updateCaptor.capture(), eqM(user))(eqM(overpassChallenge.id), any())
      val lastUpdate = updateCaptor.getAllValues.asScala.last
      (lastUpdate \ "status").as[Int] mustEqual Challenge.STATUS_FAILED
      (lastUpdate \ "statusMessage").as[String] must include("too busy or timed out")

      verify(taskDAL, never()).mergeUpdate(any(), any())(any(), any())
    }
  }

  private val plainFeatureGeojson =
    """{"features":[{"id":"f1","geometry":{"type":"Point","coordinates":[1,2]},"properties":{}}]}"""

  private val cooperativeFeatureGeojson =
    """{"features":[{"id":"f1","geometry":{"type":"Point","coordinates":[1,2]},
      |"properties":{"maproulette":"{\"cooperativeWork\":{\"meta\":{\"version\":2,\"type\":1}}}"}}]}""".stripMargin
      .replaceAll("\n", "")

  private def dummyTask(name: String): Task =
    Task(
      1,
      name,
      DateTime.now(),
      DateTime.now(),
      challengeWithoutOsmId.id,
      Some(""),
      None,
      Json.obj("type" -> "FeatureCollection", "features" -> Json.arr())
    )

  private class TaskCreationFixture {
    val challengeDAL: ChallengeDAL = mock[ChallengeDAL]
    val taskDAL: TaskDAL           = mock[TaskDAL]
    val provider =
      new ChallengeProvider(challengeDAL, taskDAL, config, mock[WSClient], mock[Database])
  }

  "createTasksFromJson" should {
    "reapply challenge priority rules after a non-cooperative bulk insert" in new TaskCreationFixture {
      when(taskDAL.looksCooperative(any())).thenReturn(false)
      when(taskDAL.bulkInsertNewTasks(any(), eqM(user), eqM(challengeWithoutOsmId))(any()))
        .thenReturn(List(dummyTask("f1")))

      provider.createTasksFromJson(user, challengeWithoutOsmId, plainFeatureGeojson)

      verify(taskDAL).bulkInsertNewTasks(any(), eqM(user), eqM(challengeWithoutOsmId))(any())
      verify(taskDAL, never()).bulkMergeNewTasks(any(), any(), any())(any())
      verify(challengeDAL)
        .updateTaskPriorities(eqM(user), any())(eqM(challengeWithoutOsmId.id), any())
    }

    "skip the priority recompute when the bulk insert creates no new tasks" in new TaskCreationFixture {
      when(taskDAL.looksCooperative(any())).thenReturn(false)
      when(taskDAL.bulkInsertNewTasks(any(), any(), any())(any())).thenReturn(List.empty)

      provider.createTasksFromJson(user, challengeWithoutOsmId, plainFeatureGeojson)

      verify(challengeDAL, never()).updateTaskPriorities(any(), any())(any(), any())
    }

    "route cooperative tasks through bulkMergeNewTasks and skip the priority recompute" in new TaskCreationFixture {
      when(taskDAL.looksCooperative(any())).thenReturn(true)
      when(taskDAL.bulkMergeNewTasks(any(), any(), any())(any())).thenReturn(List(dummyTask("f1")))

      provider.createTasksFromJson(user, challengeWithoutOsmId, cooperativeFeatureGeojson)

      verify(taskDAL).bulkMergeNewTasks(any(), eqM(user), eqM(challengeWithoutOsmId))(any())
      verify(taskDAL, never()).bulkInsertNewTasks(any(), any(), any())(any())
      verify(challengeDAL, never()).updateTaskPriorities(any(), any())(any(), any())
    }
  }
}

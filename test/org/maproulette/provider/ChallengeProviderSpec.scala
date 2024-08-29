import org.joda.time.DateTime
import org.maproulette.framework.model._
import org.maproulette.provider.ChallengeProvider
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json._
import java.util.UUID

class ChallengeProviderSpec extends PlaySpec with MockitoSugar {
  val repository: ChallengeProvider = new ChallengeProvider(null, null, null, null, null)

  val challengeWithOsmId = Challenge(
    1,
    "ChallengeWithOsmId",
    DateTime.now(),
    DateTime.now(),
    None,
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
    None,
    ChallengeGeneral(101, 1, ""),
    ChallengeCreation(),
    ChallengePriority(),
    ChallengeExtra()
  )

  "featureOSMId" should {
    "return OSM ID from root if present and specified in challenge" in {
      val json = Json.obj("custom_osm_id" -> "singleFeatureId")
      repository.featureOSMId(json, challengeWithOsmId) mustEqual Some("singleFeatureId")
    }

    "return OSM ID from properties if specified in challenge" in {
      val json = Json.obj("properties" -> Json.obj("custom_osm_id" -> "propertyId"))
      repository.featureOSMId(json, challengeWithOsmId) mustEqual Some("propertyId")
    }

    "return None if OSM ID not found in root or properties" in {
      val json = Json.obj("otherField" -> "value")
      repository.featureOSMId(json, challengeWithOsmId) mustEqual None
    }

    "return OSM ID from first feature in list if specified in challenge" in {
      val json = Json.obj("features" -> Json.arr(Json.obj("custom_osm_id" -> "featureId1")))
      repository.featureOSMId(json, challengeWithOsmId) mustEqual Some("featureId1")
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
      val json = Json.obj()
      repository.featureOSMId(json, challengeWithoutOsmId) mustEqual None
    }
  }

  "taskNameFromJsValue" should {
    "return OSM ID from root object if present and specified in challenge" in {
      val json = Json.obj("custom_osm_id" -> "12345")
      repository.taskNameFromJsValue(json, challengeWithOsmId) mustEqual "12345"
    }

    "return OSM ID from first feature if available and specified in challenge" in {
      val json = Json.obj("features" -> Json.arr(Json.obj("custom_osm_id" -> "featureId123")))
      repository.taskNameFromJsValue(json, challengeWithOsmId) mustEqual "featureId123"
    }

    "return random UUID if OSM ID field is specified but not found" in {
      val json   = Json.obj("otherField" -> "value")
      val result = repository.taskNameFromJsValue(json, challengeWithOsmId)
      assert(UUID.fromString(result).toString == result)
    }

    "return random UUID if no valid feature ID is found and no challenge-specific ID is available" in {
      val json   = Json.obj("features" -> Json.arr(Json.obj("otherField" -> "value")))
      val result = repository.taskNameFromJsValue(json, challengeWithoutOsmId)
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

    "return random UUID if properties object is empty and no ID fields are found" in {
      val json   = Json.obj("properties" -> Json.obj())
      val result = repository.taskNameFromJsValue(json, challengeWithoutOsmId)
      assert(UUID.fromString(result).toString == result)
    }

    "return ID field from root object or properties if available" in {
      val jsonRoot  = Json.obj("id"         -> "testId")
      val jsonProps = Json.obj("properties" -> Json.obj("id" -> "testId"))
      repository.taskNameFromJsValue(jsonRoot, challengeWithoutOsmId) mustEqual "testId"
      repository.taskNameFromJsValue(jsonProps, challengeWithoutOsmId) mustEqual "testId"
    }

    "return UUID if ID field is 'null'" in {
      val json   = Json.obj("id" -> null)
      val result = repository.taskNameFromJsValue(json, challengeWithoutOsmId)
      assert(UUID.fromString(result).toString == result)
    }

    "return field from properties if ID field is null and other fields are valid" in {
      val json =
        Json.obj("id" -> null, "properties" -> Json.obj("name" -> "testName", "id" -> "idstring"))
      repository.taskNameFromJsValue(json, challengeWithoutOsmId) mustEqual "idstring"
    }

    "return field from properties if ID field is 'null' and other fields are valid" in {
      val json = Json.obj(
        "name"       -> "string",
        "properties" -> Json.obj("name" -> "testName", "id" -> "idstring")
      )
      repository.taskNameFromJsValue(json, challengeWithoutOsmId) mustEqual "string"
    }
  }
}

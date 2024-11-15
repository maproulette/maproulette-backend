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
}

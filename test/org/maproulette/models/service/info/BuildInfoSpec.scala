package org.maproulette.models.service.info

import org.scalatestplus.play.PlaySpec
import play.api.libs.json.{JsValue, Json};

class BuildInfoSpec extends PlaySpec {
  val buildInfoJson: JsValue = Json.parse(BuildInfo.get.toJson)
  "BuildInfo object" should {
    "have a buildDate" in {
      BuildInfo.get.buildDate mustEqual (buildInfoJson \ "buildDate").validate[String].get
    }
    "have a name" in {
      BuildInfo.get.name mustEqual (buildInfoJson \ "name").validate[String].get
    }
    "have a sbtVersion" in {
      BuildInfo.get.sbtVersion mustEqual (buildInfoJson \ "sbtVersion").validate[String].get
    }
    "have a scalaVersion" in {
      BuildInfo.get.scalaVersion mustEqual (buildInfoJson \ "scalaVersion").validate[String].get
    }
    "have a version" in {
      BuildInfo.get.version mustEqual (buildInfoJson \ "version").validate[String].get
    }
    "have a javaVendor" in {
      BuildInfo.get.javaVendor mustEqual (buildInfoJson \ "javaVendor").validate[String].get
    }
    "have a javaVersion" in {
      BuildInfo.get.javaVersion mustEqual (buildInfoJson \ "javaVersion").validate[String].get
    }
  }
}

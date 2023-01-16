package org.maproulette.info;

import org.scalatestplus.play.PlaySpec
import play.api.libs.json.{JsValue, Json};

class BuildInfoSpec extends PlaySpec {
  val buildInfoJson: JsValue = Json.parse(BuildInfo.toJson)
  "BuildInfo object" should {
    "have a buildDate" in {
      BuildInfo.buildDate mustEqual (buildInfoJson \ "buildDate").validate[String].get
    }
    "have a name" in {
      BuildInfo.name mustEqual (buildInfoJson \ "name").validate[String].get
    }
    "have a sbtVersion" in {
      BuildInfo.sbtVersion mustEqual (buildInfoJson \ "sbtVersion").validate[String].get
    }
    "have a scalaVersion" in {
      BuildInfo.scalaVersion mustEqual (buildInfoJson \ "scalaVersion").validate[String].get
    }
    "have a version" in {
      BuildInfo.version mustEqual (buildInfoJson \ "version").validate[String].get
    }
    "have a javaVendor" in {
      BuildInfo.javaVendor mustEqual (buildInfoJson \ "javaVendor").validate[String].get
    }
    "have a javaVersion" in {
      BuildInfo.javaVersion mustEqual (buildInfoJson \ "javaVersion").validate[String].get
    }
  }
}

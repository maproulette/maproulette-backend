package org.maproulette.models.service.info

import org.scalatestplus.play.PlaySpec
import play.api.libs.json.{JsValue, Json};

class RuntimeInfoSpec extends PlaySpec {
  val runtimeInfo: RuntimeInfo = RuntimeInfo()
  val runtimeInfoJson: JsValue = Json.toJson(runtimeInfo)
  "RuntimeInfo object" should {
    "have a javaVendor" in {
      runtimeInfo.javaVendor mustEqual (runtimeInfoJson \ "javaVendor").validate[String].get
    }
    "have a javaVersion" in {
      runtimeInfo.javaVersion mustEqual (runtimeInfoJson \ "javaVersion").validate[String].get
    }
  }
}

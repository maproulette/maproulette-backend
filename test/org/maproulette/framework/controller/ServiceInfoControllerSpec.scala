/*
 * Copyright (C) 2026 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.controller

import org.maproulette.data.ActionManager
import org.maproulette.models.service.info.{BuildInfo, RuntimeInfo}
import org.maproulette.session.SessionManager
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.JsValue
import play.api.mvc.Result
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

class ServiceInfoControllerSpec extends PlaySpec with MockitoSugar {
  val components = stubControllerComponents()
  val controller = new ServiceInfoController(
    mock[SessionManager],
    mock[ActionManager],
    components.parsers,
    components
  )

  "GET /service/info" should {
    val result: Future[Result] = controller.getServiceInfo(FakeRequest(GET, "/service/info"))

    "return 200 OK with a JSON body" in {
      status(result) mustEqual OK
      contentType(result) mustEqual Some("application/json")
    }

    "return the compiletime build information" in {
      val compiletime: JsValue = contentAsJson(result) \ "compiletime" getOrElse fail(
        "missing compiletime object"
      )
      (compiletime \ "name").validate[String].get mustEqual BuildInfo.get.name
      (compiletime \ "version").validate[String].get mustEqual BuildInfo.get.version
      (compiletime \ "scalaVersion").validate[String].get mustEqual BuildInfo.get.scalaVersion
      (compiletime \ "sbtVersion").validate[String].get mustEqual BuildInfo.get.sbtVersion
      (compiletime \ "buildDate").validate[String].get mustEqual BuildInfo.get.buildDate
      (compiletime \ "javaVersion").validate[String].get mustEqual BuildInfo.get.javaVersion
      (compiletime \ "javaVendor").validate[String].get mustEqual BuildInfo.get.javaVendor
    }

    "return the runtime information" in {
      val runtimeInfo = RuntimeInfo()
      val runtime: JsValue = contentAsJson(result) \ "runtime" getOrElse fail(
        "missing runtime object"
      )
      (runtime \ "javaVersion").validate[String].get mustEqual runtimeInfo.javaVersion
      (runtime \ "javaVendor").validate[String].get mustEqual runtimeInfo.javaVendor
    }
  }
}

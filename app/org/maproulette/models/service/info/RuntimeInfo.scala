package org.maproulette.models.service.info

import play.api.libs.json.{Json, Writes}

import java.time.{LocalDateTime, ZoneOffset}

case class RuntimeInfo(
    javaVersion: String = sys.props("java.version"),
    javaVendor: String = sys.props("java.vendor"),
    startDateTime: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
) {}

object RuntimeInfo {
  implicit val runtimeInfoWrites: Writes[RuntimeInfo] = Json.writes[RuntimeInfo]

}

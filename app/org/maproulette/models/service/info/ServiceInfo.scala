package org.maproulette.models.service.info

import play.api.libs.json.{Json, Writes}

case class ServiceInfo(
    compiletime: BuildInfo = BuildInfo(),
    runtime: RuntimeInfo = RuntimeInfo()
)

object ServiceInfo {
  implicit val buildInfoWrites: Writes[BuildInfo] = Json.writes[BuildInfo]
  implicit val writes: Writes[ServiceInfo]        = Json.writes[ServiceInfo]
}

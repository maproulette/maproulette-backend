package org.maproulette.framework.controller

import org.maproulette.data.ActionManager
import org.maproulette.models.service.info.ServiceInfo
import org.maproulette.session.SessionManager
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, PlayBodyParsers}

import javax.inject.{Inject, Singleton}

@Singleton
class ServiceInfoController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val bodyParsers: PlayBodyParsers,
    components: ControllerComponents
) extends AbstractController(components)
    with MapRouletteController {
  private val serviceInfo: ServiceInfo = ServiceInfo()
  private val serviceInfoJson: JsValue = Json.toJson(serviceInfo)
  def getServiceInfo: Action[AnyContent] = Action {
    Ok(serviceInfoJson)
  }
}

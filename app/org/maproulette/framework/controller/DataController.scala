/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.controller

import javax.inject.Inject
import org.maproulette.data.{Created => _, _}
import org.maproulette.framework.service.{DataService}
import org.maproulette.session.{SearchParameters, SessionManager}
import play.api.libs.json._
import play.api.mvc._

/**
  * @author krotstan
  */
class DataController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val bodyParsers: PlayBodyParsers,
    dataService: DataService,
    components: ControllerComponents
) extends AbstractController(components)
    with MapRouletteController {

  /**
    * Returns metrics grouped by tag for the given task search criteria.
    * @return list of metrics
    */
  def getTagMetrics(): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        Ok(
          Json.toJson(
            this.dataService.getTagMetrics(params)
          )
        )
      }
    }
  }
}

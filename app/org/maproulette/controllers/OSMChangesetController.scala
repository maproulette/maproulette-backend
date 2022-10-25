/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.controllers

import javax.inject.{Inject, Singleton}
import org.maproulette.exception.{StatusMessage, StatusMessages}
import org.maproulette.provider.osm._
import org.maproulette.session.SessionManager
import play.api.libs.json.{JsError, JsValue, Json}
import play.api.mvc._

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}
import scala.xml.Elem

/**
  * @author mcuthbert
  */
@Singleton
class OSMChangesetController @Inject() (
    components: ControllerComponents,
    sessionManager: SessionManager,
    changeService: ChangesetProvider,
    bodyParsers: PlayBodyParsers
) extends AbstractController(components)
    with StatusMessages {

  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val tagChangeReads           = ChangeObjects.tagChangeReads
  implicit val tagChangeResultWrites    = ChangeObjects.tagChangeResultWrites
  implicit val tagChangeSubmissionReads = ChangeObjects.tagChangeSubmissionReads
  implicit val changeReads              = ChangeObjects.changeReads

  /**
    * Returns the changes requested by the user without submitting it to OSM. This will be a json
    * format that will contain before and after results for the requested tag changes
    *
    * @return
    */
  def testTagChange(changeType: String): Action[JsValue] = Action.async(bodyParsers.json) {
    implicit request =>
      this.sessionManager.authenticatedFutureRequest { implicit user =>
        val result = request.body.validate[List[TagChange]]
        result.fold(
          errors => {
            Future {
              BadRequest(Json.toJson(StatusMessage("KO", JsError.toJson(errors))))
            }
          },
          element => {
            val p = Promise[Result]()
            val future = changeType match {
              case OSMChangesetController.CHANGETYPE_OSMCHANGE =>
                val updates = element.map(tagChange => {
                  ElementUpdate(
                    tagChange.osmId,
                    tagChange.osmType,
                    tagChange.version,
                    ElementTagChange(tagChange.updates, tagChange.deletes)
                  )
                })
                changeService.getOsmChange(OSMChange(None, Some(updates)), None)
              case _ => changeService.testTagChange(element)
            }
            future onComplete {
              case Success(res) =>
                changeType match {
                  case OSMChangesetController.CHANGETYPE_OSMCHANGE =>
                    p success Ok(res.asInstanceOf[Elem]).as("text/xml")
                  case _ => p success Ok(Json.toJson(res.asInstanceOf[List[TagChangeResult]]))
                }
              case Failure(f) => p failure f
            }
            p.future
          }
        )
      }
  }

  /**
    * Returns the osmchange XML representing the geometry changes requested by
    * the user without submitting it to OSM. Currently only creation of nodes
    * is supported
    *
    * @return
    */
  def testChange(): Action[JsValue] = Action.async(bodyParsers.json) { implicit request =>
    this.sessionManager.authenticatedFutureRequest { implicit user =>
      val result = request.body.validate[OSMChange]
      result.fold(
        errors => {
          Future {
            BadRequest(Json.toJson(StatusMessage("KO", JsError.toJson(errors))))
          }
        },
        element => {
          val p = Promise[Result]()
          // For now just support creation of new geometries
          changeService.getOsmChange(element, None) onComplete {
            case Success(res) =>
              p success Ok(res.asInstanceOf[Elem]).as("text/xml")
            case Failure(f) => p failure f
          }
          p.future
        }
      )
    }
  }

  // todo create API that will allow user to submit an OSMChange XML directly into the service. The biggest potential
  // downside to this approach, is that there is little control over what is submitted.
}

object OSMChangesetController {
  val CHANGETYPE_OSMCHANGE = "osmchange"
  val CHANGETYPE_DELTA     = "delta"
}

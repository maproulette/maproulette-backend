/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.controllers

import java.sql.Connection

import org.maproulette.exception.{NotFoundException, StatusMessage}
import org.maproulette.framework.model.User
import org.maproulette.models.BaseObject
import org.maproulette.models.dal.ParentDAL
import play.api.libs.json._
import play.api.mvc.{AbstractController, Action, AnyContent}

/**
  * Base controller for parent objects, namely Projects and Challenges. This controller helps in
  * building the children object of the parent. The CRUDController handles all the basic operations
  * of the object
  *
  * @author cuthbertm
  */
trait ParentController[T <: BaseObject[Long], C <: BaseObject[Long]] extends CRUDController[T] {
  this: AbstractController =>

  // The data access layer for the parent
  override protected val dal: ParentDAL[Long, T, C]
  // The CRUD controller of the child, in the case of a Challenge this is technically a ParentController
  protected val childController: CRUDController[C]
  // reads function for the json in the post body to the parent object
  override implicit val tReads: Reads[T]
  // writes function for the parent object to json
  override implicit val tWrites: Writes[T]
  // reads function for the json in the post body to the child object
  protected val cReads: Reads[C]
  // writes function for the child object to json
  protected val cWrites: Writes[C]

  def undelete(id: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.dal.retrieveById(id) match {
        case Some(obj) =>
          Ok(Json.toJson(this.dal.undelete(id, user)))
        case None =>
          throw new NotFoundException(
            s"Object with id [$id] was not found, this is most likely because it has been removed from the database and cannot be undeleted."
          )
      }
    }
  }

  /**
    * Passes off all the work to the createChildren function as that function understands how to
    * update objects as well.
    * Must be authenticated to perform operation
    *
    * @param id The id of the parent
    * @return 201 Created with no content
    */
  def updateChildren(implicit id: Long): Action[JsValue] = this.createChildren

  /**
    * This function is very similar to the batch upload, however it implies a object hierarchy by
    * forcing the top level object to be defined. The entire object does not have to be defined if
    * it has already been created.
    * Must be authenticated to perform operation
    *
    * @param id The id of the parent
    * @return 201 Created with no content
    */
  def createChildren(implicit id: Long): Action[JsValue] = Action.async(bodyParsers.json) {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        this.dal.retrieveById match {
          case Some(parent) =>
            this.extractAndCreate(Json.obj("children" -> request.body), parent, user)
            Created
          case None =>
            val message = s"Bad id, no parent found with supplied id [$id]"
            logger.error(message)
            NotFound(Json.toJson(StatusMessage("KO", JsString(message))))
        }
      }
  }

  /**
    * Function can be implemented to extract more information than just the default create data,
    * to build other objects with the current object at the core. No data will be returned from this
    * function, it purely does work in the background AFTER creating the current object
    *
    * @param body          The Json body of data
    * @param createdObject The object that was created by the create function
    * @param user          the user executing the request
    */
  override def extractAndCreate(body: JsValue, createdObject: T, user: User)(
      implicit c: Option[Connection] = None
  ): Unit = {
    implicit val reads: Reads[C] = cReads
    (body \ "children").asOpt[List[JsValue]] match {
      case Some(children) =>
        children map { child =>
          // add the parent id to the child.
          child.transform(parentAddition(createdObject.id)) match {
            case JsSuccess(value, _) =>
              (value \ "id").asOpt[String] match {
                case Some(identifier) =>
                  this.childController.internalUpdate(
                    this.childController.updateUpdateBody(value, user),
                    user
                  )(identifier, -1)
                case None =>
                  this.childController
                    .updateCreateBody(value, user)
                    .validate[C]
                    .fold(
                      errors => {
                        throw new Exception(JsError.toJson(errors).toString)
                      },
                      element => {
                        try {
                          this.childController.internalCreate(value, element, user)
                        } catch {
                          case e: Exception =>
                            logger.error(e.getMessage, e)
                            throw e
                        }
                      }
                    )
              }
            case JsError(errors) =>
              logger.error(JsError.toJson(errors).toString)
              throw new Exception(JsError.toJson(errors).toString)
          }
        }
      case None => // ignore
    }
  }

  /**
    * Json transformer that will add the parent id into all the child objects that are being
    * created at the same time. It will also overwrite any parent id's that are already there. The
    * parent is defined by the json structure.
    *
    * @param id The id of the parent
    * @return
    */
  def parentAddition(id: Long): Reads[JsObject] = {
    __.json.update(
      __.read[JsObject] map { o =>
        o ++ Json.obj("parent" -> Json.toJson(id))
      }
    )
  }

  /**
    * Lists all the children of a given parent. This could be very costly, if you are listing all
    * the children of a task with no limit.
    *
    * @param id     The parent id
    * @param limit  The limit of how many objects to be returned
    * @param page   for paging
    * @return 200 OK with json array of children objects
    */
  def listChildren(id: Long, limit: Int, page: Int): Action[AnyContent] = Action.async {
    implicit request =>
      implicit val writes: Writes[C] = this.cWrites
      this.sessionManager.userAwareRequest { implicit user =>
        Ok(Json.toJson(this.dal.listChildren(limit, page)(id).map(this.childController.inject)))
      }
  }

  /**
    * This function will list all the children and then place it in a "children" key under the
    * parent object. Ie. return the parent and it's children. The primary workload is completed
    * by the listChildren function, with this function really just retrieving the information of the
    * parent
    *
    * @param id     The parent id
    * @param limit  The limit of how many objects to be returned
    * @param page   page number starting at 0, used to determine offset
    * @return 200 Ok with parent json object containing children objects
    */
  def expandedList(id: Long, limit: Int, page: Int): Action[AnyContent] = Action.async {
    implicit request =>
      implicit val writes: Writes[C] = cWrites
      this.sessionManager.userAwareRequest { implicit user =>
        val offset = limit * page

        // now replace the parent field in the parent with a children array
        Json
          .toJson(this.dal.retrieveById(id))
          .transform(this.childrenAddition(this.dal.listChildren(limit, offset)(id))) match {
          case JsSuccess(value, _) => Ok(value)
          case JsError(errors) =>
            logger.error(JsError.toJson(errors).toString)
            InternalServerError(Json.toJson(StatusMessage("KO", JsError.toJson(errors))))
        }
      }
  }

  /**
    * Adds the child json array to the parent object
    *
    * @param children The list of children objects to add
    * @return
    */
  def childrenAddition(children: List[C]): Reads[JsObject] = {
    implicit val writes: Writes[C] = cWrites
    __.json.update(
      __.read[JsObject] map { o =>
        o ++ Json.obj("children" -> Json.toJson(children))
      }
    )
  }
}

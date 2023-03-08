/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.controllers

import java.sql.Connection
import com.fasterxml.jackson.databind.JsonMappingException
import org.joda.time.DateTime
import org.maproulette.data.{Created => ActionCreated, _}
import org.maproulette.exception.{
  InvalidException,
  MPExceptionUtil,
  NotFoundException,
  StatusMessage
}
import org.maproulette.Config
import org.maproulette.framework.controller.SessionController
import org.maproulette.framework.model.User
import org.maproulette.metrics.Metrics
import org.maproulette.models.BaseObject
import org.maproulette.models.dal.BaseDAL
import org.maproulette.utils.Utils
import play.api.libs.json._
import play.api.mvc.{AbstractController, Action, AnyContent, Request}

/**
  * This is the base controller class that handles all the CRUD operations for the objects in MapRoulette.
  * This includes creation, reading, updating and deleting. It also includes a standard list function
  * and batch upload process.
  *
  * @author cuthbertm
  */
trait CRUDController[T <: BaseObject[Long]] extends SessionController {
  this: AbstractController =>

  val config: Config

  // The default reads that allows the class to read the json from a posted json body
  implicit val tReads: Reads[T]
  // The default writes that allows the class to write the object as json to a response body
  implicit val tWrites: Writes[T]
  // the type of object that the controller is executing against
  implicit val itemType: ItemType

  // Data access layer that has to be instantiated by the class that mixes in the trait
  @deprecated
  protected val dal: BaseDAL[Long, T]

  /**
    * The base create function that most controllers will run through to create the object. The
    * actual work will be passed on to the internalCreate function. This is so that if you want
    * to create your object differently you can keep the standard http functionality around and
    * not have to reproduce a lot of the work done in this function. If the id is supplied in the
    * json body it will pass off the workload to the update function. If no id is supplied then it
    * will check the name to see if it can find any items in the database that match the name.
    * Must be authenticated to perform operation
    *
    * @return 201 Created with the json body of the created object
    */
  def create(): Action[JsValue] = Action.async(bodyParsers.json) { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val result = this.updateCreateBody(request.body, user).validate[T]
      result.fold(
        errors => {
          BadRequest(Json.toJson(StatusMessage("KO", JsError.toJson(errors))))
        },
        element => {
          MPExceptionUtil.internalExceptionCatcher { () =>
            if (element.id > -1) {
              // if you provide the ID in the post method we will send you to the update path
              this.internalUpdate(request.body, user)(element.id.toString, -1) match {
                case Some(value) => Ok(this.inject(value))
                case None        => NotModified
              }
            } else {
              this.internalCreate(request.body, element, user) match {
                case Some(value) => Created(this.inject(value))
                case None        => NotModified
              }
            }
          }
        }
      )
    }
  }

  /**
    * This function allows sub classes to modify the body, primarily this would be used for inserting
    * default elements into the body that shouldn't have to be required to create an object.
    *
    * @param body The incoming body from the request
    * @param user The user executing the request
    * @return
    */
  def updateCreateBody(body: JsValue, user: User): JsValue = {
    var jsonBody = Utils.insertJsonID(body)
    jsonBody =
      Utils.insertIntoJson(jsonBody, "created", DateTime.now())(JodaWrites.JodaDateTimeNumberWrites)
    Utils.insertIntoJson(jsonBody, "modified", DateTime.now())(JodaWrites.JodaDateTimeNumberWrites)
  }

  /**
    * Calls the insert function from the data access layer for the particular object. It will also
    * call the extractAndCreate function after the insert, which by default does nothing. The ParentController
    * will use that function to create any children of the parent. Will also create a "create" action
    * in the database
    *
    * @param requestBody The request body containing the full json payload
    * @param element     The intial object to be created. Ie. if this was the ProjectController then it would be a project object
    * @param user        The user that is executing this request
    * @return The createdObject (not any of it's children if creating multiple objects, only top level)
    */
  def internalCreate(requestBody: JsValue, element: T, user: User)(
      implicit c: Option[Connection] = None
  ): Option[T] = {
    (requestBody \ "parent").asOpt[Long] match {
      case Some(parentId) =>
        if (element.getClass.getSimpleName == Actions.ITEM_TYPE_TASK_NAME) {
          val currentTaskCount = this.dal.getTaskCountBase(parentId);

          if (currentTaskCount > config.maxTasksPerChallenge) {
            logger.warn(
              "challengeId='{}' has exceeded the maximum tasks per challenge (count={} max={})",
              parentId,
              currentTaskCount,
              config.maxTasksPerChallenge
            )
            throw new InvalidException(
              s"Challenges cannot exceed ${config.maxTasksPerChallenge} tasks"
            )
          }
        }

        this.dal.mergeUpdate(element, user)(element.id) match {
          case Some(created) =>
            this.extractAndCreate(requestBody, created, user)
            this.actionManager.setAction(
              Some(user),
              this.itemType.convertToItem(created.id),
              ActionCreated(),
              ""
            )
            Some(created)
          case None => None
        }
      case None => {
        this.dal.mergeUpdate(element, user)(element.id) match {
          case Some(created) =>
            this.extractAndCreate(requestBody, created, user)
            this.actionManager.setAction(
              Some(user),
              this.itemType.convertToItem(created.id),
              ActionCreated(),
              ""
            )
            Some(created)
          case None => None
        }
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
    * @param user          The user that is executing the function
    */
  def extractAndCreate(body: JsValue, createdObject: T, user: User)(
      implicit c: Option[Connection] = None
  ): Unit = {}

  /**
    * The function that does the actual update of the object, it will pass of the updatedObject to
    * the extractAndUpdate function after the object has been updated.
    *
    * @param requestBody The full request body payload pass in the request
    * @param user        The user executing the request
    * @param id          The id of the object being updated
    * @return The object that was updated, None if it was not updated.
    */
  def internalUpdate(
      requestBody: JsValue,
      user: User
  )(implicit id: String, parentId: Long, c: Option[Connection] = None): Option[T] = {
    val updatedObject = if (Utils.isDigit(id)) {
      this.dal.update(requestBody, user)(id.toLong)
    } else {
      this.dal.updateByName(requestBody, user)
    }

    // we are checking to see if payload provides "isArchived".  If so, bypass updating the tasks
    val archiving = (requestBody \ "isArchived").validate[Boolean]
    archiving match {
      case JsSuccess(isArchived, _) => println("isArchived is $isArchived");
      case e: JsError               => this.extractAndUpdate(requestBody, updatedObject, user)
    }

    updatedObject match {
      case Some(obj) =>
        this.actionManager.setAction(Some(user), this.itemType.convertToItem(obj.id), Updated(), "")
      case None => //ignore
    }
    updatedObject
  }

  /**
    * Passes functionality to the extractAndCreate function if the updatedObject is not None,
    * otherwise will do nothing by default
    *
    * @param body          The request body containing the full json payload
    * @param updatedObject The object that was updated.
    * @param user          The user executing the operation
    */
  def extractAndUpdate(body: JsValue, updatedObject: Option[T], user: User): Unit = {
    updatedObject match {
      case Some(updated) => this.extractAndCreate(body, updated, user)
      case None          => // ignore
    }
  }

  /**
    * Base update function for the object. The update function works very similarly to the create
    * function. It does however allow the user to supply only the elements that are needed to updated.
    * Must be authenticated to perform operation
    *
    * @param id The id for the object
    * @return 200 OK with the updated object, 304 NotModified if not updated
    */
  def update(implicit id: Long): Action[JsValue] = Action.async(bodyParsers.json) {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        try {
          this.internalUpdate(updateUpdateBody(request.body, user), user)(id.toString, -1) match {
            case Some(value) => Ok(this.inject(value))
            case None        => throw new NotFoundException(s"No object found with id [$id]")
          }
        } catch {
          case e: JsonMappingException =>
            logger.error(e.getMessage, e)
            BadRequest(Json.toJson(StatusMessage("KO", JsString(e.getMessage))))
        }
      }
  }

  /**
    * In the case where you need to update the update body, usually you would not update it, but
    * just in case.
    *
    * @param body The request body
    * @param user The user executing the request
    * @return The updated request body
    */
  def updateUpdateBody(body: JsValue, user: User): JsValue = body

  /**
    * Retrieves the object from the database or primary storage and writes it as json as a response.
    *
    * @param id The id of the object that is being retrieved
    * @return 200 Ok, 404 if not found
    */
  def read(implicit id: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      this.dal.retrieveById match {
        case Some(value) => Ok(this.inject(value))
        case None        => NotFound
      }
    }
  }

  /**
    * Given the name of the object and the id of the objects parent, the object will be retrieved
    * from the database and returned to the user in JSON form
    *
    * @param id   The id of the parent of the object
    * @param name The name of the object
    * @return 200 Ok, 404 if not found
    */
  def readByName(id: Long, name: String): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      this.dal.retrieveByName(name, id) match {
        case Some(value) => Ok(this.inject(value))
        case None        => NotFound
      }
    }
  }

  /**
    * Deletes an object from the database or primary storage.
    * Must be authenticated to perform operation
    *
    * @param id        The id of the object to delete
    * @param immediate if true will delete it immediately, otherwise will just flag for deletion
    * @return 204 NoContent
    */
  def delete(id: Long, immediate: Boolean): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.dal.delete(id.toLong, user, immediate)
      this.actionManager
        .setAction(Some(user), this.itemType.convertToItem(id.toLong), Deleted(), "")
      val message = if (immediate) {
        JsString(
          s"${Actions.getTypeName(this.itemType.typeId).getOrElse("Unknown Object")} $id deleted by user ${user.id}."
        )
      } else {
        JsString(
          s"${Actions.getTypeName(this.itemType.typeId).getOrElse("Unknown Object")} $id set for delayed deletion by user ${user.id}."
        )
      }
      Ok(Json.toJson(StatusMessage("OK", message)))
    }
  }

  /**
    * Simply lists the objects for the current controller. This can be an especially expensive operation,
    * as if you just list all tasks it will try to list all of the tasks, which could be over 100,000
    * which could cause OOM's but also cause issues with the browser trying to displaying all the data.
    *
    * TODO: This function probably should force authentication, but also use streaming. Possibly
    * only allow super users to do it.
    *
    * @param limit  limit the number of objects returned in the list
    * @param offset For paging, if limit is 10, total 100, then offset 1 will return items 11 - 20
    * @return A list of requested objects
    */
  def list(limit: Int, offset: Int, onlyEnabled: Boolean): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        val result = this.dal.list(limit, offset, onlyEnabled)
        this.itemType match {
          case it: TaskType if user.isDefined =>
            result.foreach(task =>
              this.actionManager
                .setAction(user, this.itemType.convertToItem(task.id), TaskViewed(), "")
            )
          case _ => //ignore, only update view actions if it is a task type
        }
        Ok(Json.toJson(result.map(this.inject)))
      }
  }

  /**
    * Classes can override this function to inject values into the object before it is sent along
    * with the response
    *
    * @param obj the object being sent in the response
    * @return A Json representation of the object
    */
  def inject(obj: T)(implicit request: Request[Any]): JsValue = Json.toJson(obj)

  /**
    * Helper function that does a batch upload and only creates new object, does not update existing ones
    * Must be authenticated to perform operation
    *
    * @return 200 OK basic message saying all items where uploaded
    */
  def batchUploadPost: Action[JsValue] = this.batchUpload(false)

  /**
    * Allows a basic upload batch process of the items from the json payload. This is also leveraged
    * when creating children objects at the same time as the parent.
    * Must be authenticated to perform operation
    *
    * @param update Whether to update any objects found matching in the database
    * @return 200 OK basic message saying all items where uploaded
    */
  def batchUpload(update: Boolean): Action[JsValue] = Action.async(bodyParsers.json) {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        request.body
          .validate[List[JsValue]]
          .fold(
            errors => {
              BadRequest(Json.toJson(StatusMessage("KO", JsError.toJson(errors))))
            },
            items => {
              this.internalBatchUpload(request.body, items, user, update)
              Ok(Json.toJson(StatusMessage("OK", JsString("Items created and updated"))))
            }
          )
      }
  }

  /**
    * Internal method that is used to actually execute the batch upload
    *
    * @param requestBody The full request body
    * @param arr         The array of json objects representing the objects or values of those objects that you want to update/create
    * @param user        The user executing the request
    * @param update      Whether to update the object if a matching object is found, if false will simply do nothing
    */
  def internalBatchUpload(
      requestBody: JsValue,
      arr: List[JsValue],
      user: User,
      update: Boolean = false
  ): Unit = {
    this.dal.getDatabase.withTransaction { implicit c =>
      Metrics.timer("BatchUpload LOOP") { () =>
        arr.foreach(element =>
          (element \ "id").asOpt[String] match {
            case Some(itemID) => if (update) this.internalUpdate(element, user)(itemID, -1)
            case None =>
              (element \ "parent").asOpt[Long] match {
                case Some(parentId) =>
                  val currentTaskCount = this.dal.getTaskCountBase(parentId);
                  if (currentTaskCount < config.maxTasksPerChallenge) {
                    this
                      .updateCreateBody(element, user)
                      .validate[T]
                      .fold(
                        errors =>
                          logger.warn(s"Invalid json for type: ${JsError.toJson(errors).toString}"),
                        validT => this.internalCreate(element, validT, user)
                      )
                  } else {
                    logger.warn(
                      "challengeId='{}' has exceeded the maximum tasks per challenge (count={} max={})",
                      parentId,
                      currentTaskCount,
                      config.maxTasksPerChallenge
                    )
                    throw new InvalidException(
                      s"Challenges cannot exceed ${config.maxTasksPerChallenge} tasks"
                    )
                  }
                case None =>
                  this
                    .updateCreateBody(element, user)
                    .validate[T]
                    .fold(
                      errors =>
                        logger.warn(s"Invalid json for type: ${JsError.toJson(errors).toString}"),
                      validT => this.internalCreate(element, validT, user)
                    )
              }
          }
        )
      }
    }
  }

  /**
    * Helper function that does a batch upload and creates new objects, and updates existing ones.
    * Must be authenticated to perform operation
    *
    * @return 200 OK basic message saying all items where uploaded
    */
  def batchUploadPut: Action[JsValue] = this.batchUpload(true)

  /**
    * Does a basic search on the name of an object
    *
    * @param search      The search string that we are looking for
    * @param limit       limit the number of returned items
    * @param offset      For paging, if limit is 10, total 100, then offset 1 will return items 11 - 20
    * @param onlyEnabled only enabled objects if true
    * @return A list of the requested items in JSON format
    */
  def find(
      search: String,
      parentId: Long,
      limit: Int,
      offset: Int,
      onlyEnabled: Boolean
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(
        Json.toJson(
          this.dal
            .find(search, limit, offset, onlyEnabled, "name", "DESC")(parentId)
            .map(this.inject)
        )
      )
    }
  }
}

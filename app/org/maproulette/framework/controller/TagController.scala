/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.controller

import com.fasterxml.jackson.databind.JsonMappingException
import javax.inject.Inject
import org.maproulette.data.{Created => ActionCreated, _}
import org.maproulette.exception.{MPExceptionUtil, NotFoundException, StatusMessage}
import org.maproulette.framework.model.{Tag, User}
import org.maproulette.framework.psql.Paging
import org.maproulette.framework.service.TagService
import org.maproulette.session.SessionManager
import org.maproulette.utils.Utils
import play.api.libs.json._
import play.api.mvc._

/**
  * @author mcuthbert
  */
class TagController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val bodyParsers: PlayBodyParsers,
    service: TagService,
    components: ControllerComponents
) extends AbstractController(components)
    with MapRouletteController {

  // json reads for automatically reading Tags from a posted json body
  implicit val tagReads: Reads[Tag] = Tag.tagReads
  // json writes for automatically writing Tags to a json body response
  implicit val tagWrites: Writes[Tag] = Tag.tagWrites

  /**
    * Retrieves the tag from the database or primary storage and writes it as json as a response.
    *
    * @param id The id of the object that is being retrieved
    * @return 200 Ok, 404 if not found
    */
  def retrieve(id: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      this.service.retrieve(id) match {
        case Some(value) => Ok(Json.toJson(value))
        case None        => NotFound
      }
    }
  }

  /**
    * Deletes an object from the database or primary storage.
    * Must be authenticated to perform operation
    *
    * @param id        The id of the object to delete
    * @return 204 NoContent
    */
  def delete(id: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.service.delete(id.toLong, user)
      this.actionManager
        .setAction(Some(user), TagType().convertToItem(id.toLong), Deleted(), "")
      Ok(
        Json.toJson(
          StatusMessage(
            "OK",
            JsString(
              s"${Actions.getTypeName(TagType().typeId).getOrElse("Unknown Object")} $id deleted by user ${user.id}."
            )
          )
        )
      )
    }
  }

  /**
    * Creates a Tag object
    *
    * @return 201 Created with the json body of the created object
    */
  def insert(): Action[JsValue] = Action.async(bodyParsers.json) { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val result = this.updateBody(request.body, user).validate[Tag]
      result.fold(
        errors => {
          BadRequest(Json.toJson(StatusMessage("KO", JsError.toJson(errors))))
        },
        element => {
          MPExceptionUtil.internalExceptionCatcher { () =>
            val created = this.service.create(element, user)
            this.actionManager
              .setAction(Some(user), TagType().convertToItem(created.id), ActionCreated(), "")
            Created(Json.toJson(created))
          }
        }
      )
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
  def update(id: Long): Action[JsValue] = Action.async(bodyParsers.json) { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      try {
        this.service.update(id, request.body, user) match {
          case Some(tag) =>
            this.actionManager
              .setAction(Some(user), ProjectType().convertToItem(tag.id), Updated(), "")
            Ok(Json.toJson(tag))
          case None => throw new NotFoundException(s"No project with id $id found.")
        }
      } catch {
        case e: JsonMappingException =>
          logger.error(e.getMessage, e)
          BadRequest(Json.toJson(StatusMessage("KO", JsString(e.getMessage))))
      }
    }
  }

  /**
    * Gets the tags based on a prefix. So if you are looking for all tags that begin with
    * "road_", then set the prefix to "road_"
    *
    * @param prefix The prefix for the tags
    * @param limit  The limit on how many tags to be returned
    * @param offset This is used for page offsets, so if you limit 10 tags and have offset 0, then
    *               changing to offset 1 will return the next set of 10 tags.
    * @return
    */
  def getTags(prefix: String, tagType: String, limit: Int, offset: Int): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        Ok(Json.toJson(this.service.find(prefix, Utils.split(tagType.trim), Paging(limit, offset))))
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
    * Function is primarily called from CRUDController, which is used to handle the actual creation
    * of the tags. The function it overrides does it in a very generic way, so this function is
    * specifically written so that it will update the tags correctly. Specifically tags have to be
    * matched on ids and names, instead of just ids.
    *
    * @param requestBody This is the posted request body in json format.
    * @param arr         The list of Tag objects supplied in the json array from the request body
    * @param user        The id of the user that is executing the request
    * @param update      If an item is found then update it, if parameter set to true, otherwise we skip.
    */
  def internalBatchUpload(
      requestBody: JsValue,
      arr: List[JsValue],
      user: User,
      update: Boolean
  ): Unit = {
    val tagList = arr.flatMap(element =>
      (element \ "id").asOpt[Long] match {
        case Some(itemID) if update =>
          element
            .validate[Tag]
            .fold(
              errors => None,
              value => Some(value)
            )
        case None =>
          Utils
            .insertJsonID(element)
            .validate[Tag]
            .fold(
              errors => None,
              value => Some(value)
            )
      }
    )
    this.service.updateTagList(tagList, user)
  }

  /**
    * Helper function that does a batch upload and only creates new object, does not update existing ones
    * Must be authenticated to perform operation
    *
    * @return 200 OK basic message saying all items where uploaded
    */
  def batchUploadPost: Action[JsValue] = this.batchUpload(false)

  /**
    * Toggles the active status of a keyword. Only super users are authorized to perform this action.
    *
    * @param id The id of the keyword to toggle
    * @return 200 OK with updated keyword, 404 if not found
    */
  def toggleStatus(id: Long): Action[AnyContent] = Action.async { implicit request =>
    implicit val requireSuperUser: Boolean = true
    this.sessionManager.authenticatedRequest { implicit user =>
      this.service.toggleTagStatus(id, user) match {
        case Some(updated) =>
          this.actionManager
            .setAction(Some(user), TagType().convertToItem(updated.id), Updated(), "")
          Ok(Json.toJson(updated))
        case None => NotFound
      }
    }
  }
}

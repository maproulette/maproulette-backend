// Copyright (C) 2016 MapRoulette contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package org.maproulette.controllers.api

import java.net.URLDecoder
import java.sql.Connection
import javax.inject.Inject

import com.vividsolutions.jts.geom.Envelope
import org.maproulette.Config
import org.maproulette.actions._
import org.maproulette.controllers.CRUDController
import org.maproulette.models.dal.{TagDAL, TagDALMixin, TaskDAL}
import org.maproulette.models._
import org.maproulette.exception.{InvalidException, NotFoundException}
import org.maproulette.session.{SearchLocation, SearchParameters, SessionManager, User}
import org.maproulette.utils.Utils
import org.wololo.geojson.{FeatureCollection, GeoJSONFactory}
import org.wololo.jts2geojson.GeoJSONReader
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.WSClient
import play.api.mvc.{Action, AnyContent, Request, Result}

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
  * The Task controller handles all operations for the Task objects.
  * This includes CRUD operations and searching/listing.
  * See {@link org.maproulette.controllers.CRUDController} for more details on CRUD object operations
  *
  * @author cuthbertm
  */
class TaskController @Inject() (override val sessionManager: SessionManager,
                                override val actionManager: ActionManager,
                                override val dal:TaskDAL,
                                override val tagDAL: TagDAL,
                                wsClient:WSClient,
                                config:Config)
  extends CRUDController[Task] with TagsMixin[Task] {

  import scala.concurrent.ExecutionContext.Implicits.global

  // json reads for automatically reading Tasks from a posted json body
  override implicit val tReads: Reads[Task] = Task.TaskFormat
  // json writes for automatically writing Tasks to a json body response
  override implicit val tWrites: Writes[Task] = Task.TaskFormat
  // The type of object that this controller deals with.
  override implicit val itemType = TaskType()
  // json reads for automatically reading Tags from a posted json body
  implicit val tagReads: Reads[Tag] = Tag.tagReads
  implicit val commentReads: Reads[Comment] = Comment.commentReads
  implicit val commentWrites: Writes[Comment] = Comment.commentWrites
  override def dalWithTags:TagDALMixin[Task] = dal

  private def updateGeometryData(body: JsValue) : JsValue = {
    val updatedBody = (body \ "geometries").asOpt[String] match {
      case Some(value) =>
        // if it is a string, then it is either GeoJSON or a WKB
        // just check to see if { is the first character and then we can assume it is GeoJSON
        if (value.charAt(0) != '{') {
          // TODO:
          body
        } else {
          // just return the body because it handles this case correctly
          body
        }
      case None =>
        // if it maps to None then it simply could be that it is a JSON object
        (body \ "geometries").asOpt[JsValue] match {
          case Some(value) =>
            // need to convert to a string for the case class otherwise validation will fail
            Utils.insertIntoJson(body, "geometries", value.toString(), true)
          case None =>
            // if the geometries are not supplied then just leave it
            body
        }
    }
    (updatedBody \ "location").asOpt[String] match {
      case Some(value) => updatedBody
      case None => (updatedBody \ "location").asOpt[JsValue] match {
        case Some(value) =>
          Utils.insertIntoJson(updatedBody, "location", value.toString(), true)
        case None => updatedBody
      }
    }
  }

  /**
    * This function allows sub classes to modify the body, primarily this would be used for inserting
    * default elements into the body that shouldn't have to be required to create an object.
    *
    * @param body The incoming body from the request
    * @return
    */
  override def updateCreateBody(body: JsValue, user:User): JsValue = {
    // add a default priority, this will be updated later when the task is created if there are
    // priority rules defined in the challenge parent
    val updatedBody = Utils.insertIntoJson(body, "priority", Challenge.PRIORITY_HIGH)(IntWrites)
    // We need to update the geometries to make sure that we handle all the different types of
    // geometries that you can deal with like WKB or GeoJSON
    this.updateGeometryData(super.updateCreateBody(updatedBody, user))
  }


  /**
    * In the case where you need to update the update body, usually you would not update it, but
    * just in case.
    *
    * @param body The request body
    * @return The updated request body
    */
  override def updateUpdateBody(body: JsValue, user:User): JsValue =
    this.updateGeometryData(super.updateUpdateBody(body, user))

  /**
    * Function can be implemented to extract more information than just the default create data,
    * to build other objects with the current object at the core. No data will be returned from this
    * function, it purely does work in the background AFTER creating the current object
    *
    * @param body          The Json body of data
    * @param createdObject The object that was created by the create function
    * @param user          The user that is executing the function
    */
  override def extractAndCreate(body: JsValue, createdObject: Task, user: User)
                               (implicit c:Option[Connection]=None): Unit =
    this.extractTags(body, createdObject, User.superUser)

  /**
    * Gets a json list of tags of the task
    *
    * @param id The id of the task containing the tags
    * @return The html Result containing json array of tags
    */
  def getTagsForTask(implicit id: Long) : Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(Json.toJson(this.getTags(id)))
    }
  }

  /**
    * Gets a random task(s) given the provided tags.
    *
    * @param projectSearch Filter on the name of the project
    * @param challengeSearch Filter on the name of the challenge (Survey included)
    * @param challengeTags Filter on the tags of the challenge
    * @param tags A comma separated list of tags to match against
    * @param taskSearch Filter based on the name of the task
    * @param limit The number of tasks to return
    * @param proximityId Id of task that you wish to find the next task based on the proximity of that task
    * @return
    */
  def getRandomTasks(projectSearch:String,
                     challengeSearch:String,
                     challengeTags:String,
                     tags: String,
                     taskSearch: String,
                     limit: Int,
                     proximityId: Long) : Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      val params = SearchParameters(
        projectSearch = Some(projectSearch),
        challengeSearch = Some(challengeSearch),
        challengeTags = Some(challengeTags.split(",").toList),
        taskTags = Some(tags.split(",").toList),
        taskSearch = Some(taskSearch)
      )
      val result = this.dal.getRandomTasks(User.userOrMocked(user), params, limit, None, Utils.negativeToOption(proximityId))
      result.map(task => {
        this.actionManager.setAction(user, this.itemType.convertToItem(task.id), TaskViewed(), "")
        this.inject(task)
      })
      Ok(Json.toJson(result))
    }
  }

  /**
    * Gets all the tasks within a bounding box
    *
    * @param left The minimum latitude for the bounding box
    * @param bottom The minimum longitude for the bounding box
    * @param right The maximum latitude for the bounding box
    * @param top The maximum longitude for the bounding box
    * @param limit Limit for the number of returned tasks
    * @param offset The offset used for paging
    * @return
    */
  def getTasksInBoundingBox(left:Double, bottom:Double, right:Double, top:Double, limit:Int, offset:Int) : Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { p =>
        val params = p.copy(location = Some(SearchLocation(left, bottom, right, top)))
        Ok(Json.toJson(this.dal.getTasksInBoundingBox(params, limit, offset)))
      }
    }
  }

  /**
    * This is the generic function that is leveraged by all the specific functions above. So it
    * sets the task status to the specific status ID's provided by those functions.
    * Must be authenticated to perform operation
    *
    * @param id The id of the task
    * @param status The status id to set the task's status to
    * @return 400 BadRequest if status id is invalid or task with supplied id not found.
    *         If successful then 200 NoContent
    */
  def setTaskStatus(id: Long, status: Int, comment:String="") : Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.customTaskStatus(id, TaskStatusSet(status), user, comment)
      NoContent
    }
  }

  def customTaskStatus(taskId:Long, actionType: ActionType, user:User, comment:String="") = {
    val status = actionType match {
      case t:TaskStatusSet => t.status
      case q:QuestionAnswered => Task.STATUS_ANSWERED
      case _ => Task.STATUS_CREATED
    }

    if (!Task.isValidStatus(status)) {
      throw new InvalidException(s"Cannot set task [$taskId] to invalid status [$status]")
    }
    val task = this.dal.retrieveById(taskId) match {
      case Some(t) => t
      case None => throw new NotFoundException(s"Task with $taskId not found, can not set status.")
    }
    this.dal.setTaskStatus(task, status, user)
    val action = this.actionManager.setAction(Some(user), new TaskItem(task.id), actionType, task.name)
    // add comment if any provided
    if (comment.nonEmpty) {
      val actionId = action match {
        case Some(a) => Some(a.id)
        case None => None
      }
      this.dal.addComment(user, task.id, comment, actionId)
    }
  }

  /**
    * Matches the task to a OSM Changeset, this will only
    *
    * @param taskId the id for the task
    * @return The new Task object
    */
  def matchToOSMChangeSet(taskId:Long) : Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedFutureRequest { implicit user =>
      this.dal.retrieveById(taskId) match {
        case Some(t) =>
          val promise = Promise[Result]
          this.dal.matchToOSMChangeSet(t, user, false) onComplete {
            case Success(response) => promise success Ok(Json.toJson(t))
            case Failure(error) => promise failure error
          }
          promise.future
        case None => throw new NotFoundException("Task not found to update taskId with")
      }
    }
  }

  /**
    * Retrieves a specific comment for the user
    *
    * @param commentId The id of the comment to retrieve
    * @return The comment
    */
  def retrieveComment(commentId:Long) : Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      this.dal.retrieveComment(commentId) match {
        case Some(comment) => Ok(Json.toJson(comment))
        case None => NotFound
      }
    }
  }

  /**
    * Retrieves all the comments for a Task
    *
    * @param taskId The task to retrieve the comments for
    * @return A list of comments
    */
  def retrieveComments(taskId:Long) : Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(Json.toJson(this.dal.retrieveComments(List.empty, List.empty, List(taskId))))
    }
  }

  /**
    * Adds a comment for a specific task
    *
    * @param taskId The id for a task
    * @param comment The comment the user is leaving
    * @param actionId The action if any associated with the comment
    * @return Ok if successful.
    */
  def addComment(taskId:Long, comment:String, actionId:Option[Long]) : Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      Created(Json.toJson(this.dal.addComment(user, taskId, URLDecoder.decode(comment, "UTF-8"), actionId)))
    }
  }

  /**
    * Updates the original comment
    *
    * @param commentId The ID of the comment to update
    * @param comment The comment to update
    * @return
    */
  def updateComment(commentId:Long, comment:String) : Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      Ok(Json.toJson(this.dal.updateComment(user, commentId, URLDecoder.decode(comment, "UTF-8"))))
    }
  }

  /**
    * Deletes a comment from a task
    *
    * @param taskId The id of the task that the comment is associated with
    * @param commentId The id of the comment that is being deleted
    * @return Ok if successful,
    */
  def deleteComment(taskId:Long, commentId:Long) : Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.dal.deleteComment(user, taskId, commentId)
      Ok
    }
  }
}

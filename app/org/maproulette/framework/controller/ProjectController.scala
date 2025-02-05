/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.controller

import com.fasterxml.jackson.databind.JsonMappingException
import javax.inject.Inject
import org.apache.commons.lang3.StringUtils
import org.maproulette.data.{Created => ActionCreated, _}
import org.maproulette.exception.{MPExceptionUtil, NotFoundException, StatusMessage}
import org.maproulette.framework.model.{Challenge, Project, User}
import org.maproulette.framework.psql.{Paging, _}
import org.maproulette.framework.service.{CommentService, ProjectService}
import org.maproulette.models.dal.TaskDAL
import org.maproulette.session.{SearchParameters, SessionManager}
import org.maproulette.utils.Utils
import play.api.libs.json._
import play.api.mvc._

/**
  * @author mcuthbert
  */
class ProjectController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val bodyParsers: PlayBodyParsers,
    projectService: ProjectService,
    commentService: CommentService,
    taskDAL: TaskDAL,
    components: ControllerComponents
) extends AbstractController(components)
    with MapRouletteController {

  // json reads for automatically reading Projects from a posted json body
  implicit val projectReads: Reads[Project] = Project.reads
  // json writes for automatically writing Projects to a json body response
  implicit val projectWrites: Writes[Project] = Project.writes
  // json writes for automatically writing Challenges to a json body response
  implicit val challengeWrites: Writes[Challenge] = Challenge.writes.challengeWrites

  /**
    * Lists all the children of a given Project parent parent.
    *
    * @param id     The parent id
    * @param limit  The limit of how many objects to be returned
    * @param offset For paging
    * @return 200 OK with json array of children objects
    */
  def listChildren(id: Long, limit: Int, offset: Int): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        Ok(Json.toJson(this.projectService.children(id, paging = Paging(limit, offset))))
      }
  }

  /**
    * API function call to create a project
    *
    * @return 201 Created with the json body of the created object
    */
  def insert(): Action[JsValue] = Action.async(bodyParsers.json) { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      var jsonBody = this.updateBody(request.body, user)
      jsonBody = Utils.insertIntoJson(jsonBody, "grants", Array.emptyShortArray)(arrayWrites[Short])
      jsonBody = Utils.insertIntoJson(jsonBody, "owner", user.osmProfile.id, true)(LongWrites)
      jsonBody = Utils.insertIntoJson(jsonBody, "deleted", false)(BooleanWrites)
      jsonBody = Utils.insertIntoJson(jsonBody, "featured", false)(BooleanWrites)
      jsonBody = Utils.insertIntoJson(jsonBody, "enabled", true)(BooleanWrites)
      jsonBody = Utils.insertIntoJson(jsonBody, "isArchived", false)(BooleanWrites)
      jsonBody = Utils.insertIntoJson(jsonBody, "requireConfirmation", false)(BooleanWrites)
      jsonBody
        .validate[Project]
        .fold(
          errors => {
            BadRequest(Json.toJson(StatusMessage("KO", JsError.toJson(errors))))
          },
          element => {
            MPExceptionUtil.internalExceptionCatcher { () =>
              val created = this.projectService.create(element, user)
              this.actionManager
                .setAction(Some(user), ProjectType().convertToItem(created.id), ActionCreated(), "")
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
  def update(implicit id: Long): Action[JsValue] = Action.async(bodyParsers.json) {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        try {
          this.projectService.update(id, request.body, user) match {
            case Some(project) =>
              this.actionManager
                .setAction(Some(user), ProjectType().convertToItem(project.id), Updated(), "")
              Ok(Json.toJson(project))
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
    * Given a specific ID retrieve the project
    *
    * @param id The id of the project to retrieve
    * @return The project, NotFound otherwise
    */
  def retrieve(id: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      this.projectService.retrieve(id) match {
        case Some(value) => Ok(Json.toJson(value))
        case None        => NotFound
      }
    }
  }

  def retrieveList(ids: String): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Utils.toLongList(ids) match {
        case Some(x) => Ok(Json.toJson(this.projectService.list(x)))
        case None    => BadRequest
      }
    }
  }

  /**
    * Retrieve the project by it's name instead of it's Id
    *
    * @param name The name of the project
    * @return The project, NotFound otherwise
    */
  def retrieveByName(name: String): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      this.projectService.retrieveByName(name) match {
        case x if x.isEmpty => NotFound
        case x              => Ok(Json.toJson(x))
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
      this.projectService.delete(id.toLong, user, immediate)
      this.actionManager
        .setAction(Some(user), ProjectType().convertToItem(id.toLong), Deleted(), "")
      val message = if (immediate) {
        JsString(
          s"${Actions.getTypeName(ProjectType().typeId).getOrElse("Unknown Object")} $id deleted by user ${user.id}."
        )
      } else {
        JsString(
          s"${Actions.getTypeName(ProjectType().typeId).getOrElse("Unknown Object")} $id set for delayed deletion by user ${user.id}."
        )
      }
      Ok(Json.toJson(StatusMessage("OK", message)))
    }
  }

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
      limit: Int,
      offset: Int,
      onlyEnabled: Boolean,
      orderColumn: String = "display_name",
      orderDirection: Int = Order.DESC
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(
        Json.toJson(
          this.projectService.find(
            search,
            Paging(limit, offset),
            onlyEnabled,
            Order > (orderColumn, orderDirection)
          )
        )
      )
    }
  }

  /**
    * Retrieves the list of projects managed
    *
    * @param limit        Limit of how many tasks should be returned
    * @param offset       offset for pagination
    * @param onlyEnabled  Only list the enabled projects
    * @param onlyOwned    Only list the projects owned by this user
    * @param searchString basic search string to find specific projects
    * @param sort         An optional column to sort by.
    * @return json list of managed projects
    */
  def listManagedProjects(
      limit: Int,
      offset: Int,
      onlyEnabled: Boolean,
      onlyOwned: Boolean,
      searchString: String,
      sort: String = "display_name"
  ): Action[AnyContent] = Action.async { implicit response =>
    this.sessionManager.authenticatedRequest { implicit user =>
      Ok(
        Json.toJson(
          this.projectService
            .getManagedProjects(
              user,
              Paging(limit, offset),
              onlyEnabled,
              onlyOwned,
              searchString,
              Order > sort
            )
        )
      )
    }
  }

  /**
    * Gets the featured projects
    *
    * @param onlyEnabled Only include enabled projects
    * @param limit       The number of challenges to get
    * @param offset      The offset
    * @return A json array with the featured projects
    */
  def getFeaturedProjects(onlyEnabled: Boolean, limit: Int, offset: Int): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        Ok(Json.toJson(this.projectService.getFeaturedProjects(onlyEnabled, Paging(limit, offset))))
      }
    }

  /**
    * Gets a random task that is an descendant of the project.
    *
    * @param limit       Limit of how many tasks should be returned
    * @param proximityId Id of task that you wish to find the next task based on the proximity of that task
    * @return A list of Tasks that match the supplied filters
    */
  def getRandomTasks(projectId: Long, limit: Int, proximityId: Long): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        SearchParameters.withSearch { params =>
          val result = this.taskDAL.getRandomTasks(
            User.userOrMocked(user),
            params.copy(projectIds = Some(List(projectId))),
            limit,
            None,
            Utils.negativeToOption(proximityId)
          )
          result.foreach(task =>
            this.actionManager
              .setAction(user, ProjectType().convertToItem(task.id), TaskViewed(), "")
          )
          Ok(Json.toJson(result))
        }
      }
    }

  def getSearchedClusteredPoints(limit: Int, page: Int): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        SearchParameters.withSearch { params =>
          Ok(
            Json.toJson(this.projectService.getSearchedClusteredPoints(params, Paging(limit, page)))
          )
        }
      }
  }

  def getClusteredPoints(
      projectId: Long,
      challengeIds: String,
      limit: Int,
      page: Int
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      val pid = if (projectId < 0) {
        None
      } else {
        Some(projectId)
      }
      val cids = if (StringUtils.isEmpty(challengeIds)) {
        List.empty
      } else {
        Utils.split(challengeIds).map(_.toLong)
      }
      Ok(
        Json.toJson(this.projectService.getClusteredPoints(pid, cids, paging = Paging(limit, page)))
      )
    }
  }

  /**
    * Retrieve all the comments for a specific project
    *
    * @param projectId The id of the challenge
    * @return A list of comments that exist for a specific challenge
    */
  def retrieveComments(projectId: Long, limit: Int, page: Int): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        Ok(
          Json.toJson(
            this.commentService.find(List(projectId), List.empty, List.empty, Paging(limit, page))
          )
        )
      }
  }
}

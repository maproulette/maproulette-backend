/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.models.dal

import java.sql.Connection
import java.time.Instant

import anorm.JodaParameterMetaData._
import anorm.SqlParser._
import anorm._
import javax.inject.{Inject, Provider, Singleton}
import org.apache.commons.lang3.StringUtils
import org.joda.time.{DateTime, DateTimeZone}
import org.locationtech.jts.geom.Envelope
import org.maproulette.Config
import org.maproulette.data._
import org.maproulette.exception.{InvalidException, NotFoundException}
import org.maproulette.framework.model.{Challenge, Project, StatusActions, User, GrantTarget, Task}
import org.maproulette.framework.psql.filter.{BaseParameter, SubQueryFilter}
import org.maproulette.framework.psql.{Order, Paging, Query}
import org.maproulette.framework.repository.{ProjectRepository, TaskRepository}
import org.maproulette.framework.service.{ServiceManager, TagService}
import org.maproulette.framework.mixins.TaskParserMixin
import org.maproulette.models._
import org.maproulette.models.dal.mixin.{SearchParametersMixin, TagDALMixin}
import org.maproulette.permissions.Permission
import org.maproulette.provider.websockets.{WebSocketMessages, WebSocketProvider}
import org.maproulette.session.SearchParameters
import org.maproulette.utils.Utils
import org.wololo.geojson.{FeatureCollection, GeoJSONFactory}
import org.wololo.jts2geojson.GeoJSONReader
import play.api.db.Database
import play.api.libs.json.JodaReads._
import play.api.libs.json._
import play.api.libs.ws.WSClient

import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.concurrent.{Future, Promise}
import scala.util.control.Exception.allCatch
import scala.util.{Failure, Success, Try}
import scala.xml.XML

import org.maproulette.framework.mixins.Locking
import org.maproulette.framework.repository.RepositoryMixin

/**
  * The data access layer for the Task objects
  *
  * @author cuthbertm
  */
@Singleton
class TaskDAL @Inject() (
    override val db: Database,
    override val tagService: TagService,
    override val permission: Permission,
    serviceManager: ServiceManager,
    taskRepository: TaskRepository,
    config: Config,
    dalManager: Provider[DALManager],
    webSocketProvider: WebSocketProvider,
    ws: WSClient
) extends RepositoryMixin
    with BaseDAL[Long, Task]
    with TagDALMixin[Task]
    with Locking[Task]
    with SearchParametersMixin
    with TaskParserMixin {

  import scala.concurrent.ExecutionContext.Implicits.global

  val parser = this.getTaskParser(this.taskRepository.updateAndRetrieve)

  // The cache manager for that tasks
  override val cacheManager = this.taskRepository.cacheManager

  // The database table name for the tasks
  override val tableName: String = "tasks"
  implicit val baseTable: String = tableName

  // The columns to be retrieved for the task. Reason this is required is because one of the columns
  // "tasks.location" is a PostGIS object in the database and we want it returned in GeoJSON instead
  // so the ST_AsGeoJSON function is used to convert it to geoJSON
  override val retrieveColumns: String = "*, tasks.geojson::TEXT AS geo_json, " +
    "tasks.cooperative_work_json::TEXT AS cooperative_work, tasks.completion_responses::TEXT AS responses, " +
    "ST_AsGeoJSON(tasks.location) AS geo_location "

  /**
    * Retrieves the object based on the name, this function is somewhat weak as there could be
    * multiple objects with the same name. The database only restricts the same name in combination
    * with a parent. So this will just return the first one it finds. With caching, so if it finds
    * the object in the cache it will return that object without checking the database, otherwise
    * will hit the database directly.
    *
    * @param name The name you are looking up by
    * @return The object that you are looking up, None if not found
    */
  override def retrieveByName(
      implicit name: String,
      parentId: Long = (-1),
      c: Option[Connection] = None
  ): Option[Task] = {
    this.cacheManager.withOptionCaching { () =>
      this.withMRConnection { implicit c =>
        val query = s"SELECT $retrieveColumnsWithReview FROM ${this.tableName} " +
          s"LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id " +
          s"WHERE name = {name} ${this.parentFilter(parentId)}"
        SQL(query).on(Symbol("name") -> name).as(this.parser.*).headOption
      }
    }
  }

  /**
    * This will retrieve the root object in the hierarchy of the object, by default the root
    * object is itself.
    *
    * @param obj Either a id for the challenge, or the challenge itself
    * @param c   The connection if any
    * @return The object that it is retrieving
    */
  def retrieveRootObject(obj: Either[Long, Task], user: User)(
      implicit c: Option[Connection] = None
  ): Option[Project] = {
    val projectParser = ProjectRepository.parser(projectId =>
      this.serviceManager.grant.retrieveGrantsOn(GrantTarget.project(projectId), User.superUser)
    )
    obj match {
      case Left(id) =>
        this.permission.hasReadAccess(TaskType(), user)(id)
        this.serviceManager.project.cacheManager.withOptionCaching { () =>
          this.withMRConnection { implicit c =>
            SQL"""SELECT p.* FROM projects p
             INNER JOIN challenges c ON c.parent_id = p.id
             INNER JOIN task t ON t.parent_id = c.id
             WHERE t.id = $id
           """.as(projectParser.*).headOption
          }
        }
      case Right(task) =>
        this.permission.hasObjectReadAccess(task, user)
        this.serviceManager.project.cacheManager.withOptionCaching { () =>
          this.withMRConnection { implicit c =>
            SQL"""SELECT p.* FROM projects p
             INNER JOIN challenges c ON c.parent_id = p.id
             WHERE c.id = ${task.parent}
           """.as(projectParser.*).headOption
          }
        }
    }
  }

  /**
    * Inserts a new task object into the database
    *
    * @param task The task to be inserted into the database
    * @param user The user executing the task
    * @return The object that was inserted into the database. This will include the newly created id
    */
  override def insert(task: Task, user: User)(implicit c: Option[Connection] = None): Task = {
    this.mergeUpdate(task, user)(-1) match {
      case Some(t) => t
      case None    => throw new Exception("Unknown failure occurred while creating new task.")
    }
  }

  /**
    * Updates a task object in the database.
    *
    * @param value A json object containing fields to be updated for the task
    * @param user  The user executing the task
    * @param id    The id of the object that you are updating
    * @return An optional object, it will return None if no object found with a matching id that was supplied
    */
  override def update(
      value: JsValue,
      user: User
  )(implicit id: Long, c: Option[Connection] = None): Option[Task] = {
    this.cacheManager.withUpdatingCache(Long => retrieveById) { implicit cachedItem =>
      val name     = (value \ "name").asOpt[String].getOrElse(cachedItem.name)
      val parentId = (value \ "parentId").asOpt[Long].getOrElse(cachedItem.parent)
      val instruction =
        (value \ "instruction").asOpt[String].getOrElse(cachedItem.instruction.getOrElse(""))
      // status should probably not be allowed to be set through the update function, and rather
      // it should be forced to use the setTaskStatus function
      val status = (value \ "status").asOpt[Int].getOrElse(cachedItem.status.getOrElse(0))
      if (!Task.isValidStatusProgression(cachedItem.status.getOrElse(0), status) &&
          allCatch.opt(this.permission.hasWriteAccess(TaskType(), user)) == None) {
        throw new InvalidException(
          s"Could not set status for task [$id], " +
            s"progression from ${cachedItem.status.getOrElse(0)} to $status not valid."
        )
      }
      val priority                  = (value \ "priority").asOpt[Int].getOrElse(cachedItem.priority)
      val geometries                = (value \ "geometries").asOpt[String].getOrElse(cachedItem.geometries)
      val cooperativeWorkGeometries = (value \ "cooperativeWork").asOpt[String].getOrElse("")
      val changesetId =
        (value \ "changesetId").asOpt[Long].getOrElse(cachedItem.changesetId.getOrElse(-1L))

      val mappedOn: Option[DateTime] = (value \ "mappedOn") match {
        case m: JsUndefined => cachedItem.mappedOn
        case m              => m.asOpt[DateTime]
      }

      val reviewStatus: Option[Int] = (value \ "reviewStatus") match {
        case r: JsUndefined => cachedItem.review.reviewStatus
        case r              => r.asOpt[Int]
      }

      val reviewRequestedBy = (value \ "reviewRequestedBy") match {
        case r: JsUndefined => cachedItem.review.reviewRequestedBy
        case r              => r.asOpt[Long]
      }

      val reviewedBy = (value \ "reviewedBy") match {
        case r: JsUndefined => cachedItem.review.reviewedBy
        case r              => r.asOpt[Long]
      }

      val reviewedAt = (value \ "reviewedAt") match {
        case r: JsUndefined => cachedItem.review.reviewedAt
        case r              => r.asOpt[DateTime]
      }

      val task = this.mergeUpdate(
        cachedItem.copy(
          name = name,
          parent = parentId,
          instruction = Some(instruction),
          status = Some(status),
          mappedOn = mappedOn,
          review = cachedItem.review.copy(
            reviewStatus = reviewStatus,
            reviewRequestedBy = reviewRequestedBy,
            reviewedBy = reviewedBy,
            reviewedAt = reviewedAt
          ),
          geometries = geometries,
          cooperativeWork = if (StringUtils.isEmpty(cooperativeWorkGeometries)) {
            None
          } else {
            Some(cooperativeWorkGeometries)
          },
          priority = priority,
          changesetId = Some(changesetId)
        ),
        user
      )

      // If we are setting the status back to created, then we need to
      // reset the mapper, bundling, and completion_responses back to null
      if (status == Task.STATUS_CREATED && id > 0) {
        this.withMRConnection { implicit c =>
          SQL"""UPDATE tasks t SET completed_time_spent = NULL, completed_by = NULL,
                                   completion_responses = NULL,
                                   is_bundle_primary = false, bundle_id = NULL
             WHERE t.id = ${id}""".executeUpdate()
        }
      }

      if (status == Task.STATUS_CREATED || status == Task.STATUS_SKIPPED) {
        this.manager.challenge.updateReadyStatus()(parentId)
      } else {
        this.manager.challenge.updateFinishedStatus()(parentId)
      }

      if (status == Task.STATUS_CREATED || status == Task.STATUS_SKIPPED) {
        this.withMRConnection { implicit c =>
          SQL"""UPDATE tasks t SET mapped_on = NULL
             WHERE t.id = ${id}""".executeUpdate()
        }
      }

      if (status == Task.STATUS_FIXED || status == Task.STATUS_ALREADY_FIXED || status == Task.STATUS_TOO_HARD || status == Task.STATUS_FALSE_POSITIVE) {
        this.withMRConnection { implicit c =>
          SQL"""UPDATE tasks t SET mapped_on = ${DateTime.now()}
             WHERE t.id = ${id}""".executeUpdate()
        }
      }

      task match {
        case Some(t) =>
          // If the status is changing and if we have a bundle id, then we need
          // to clear it out along with any other tasks that also have that
          // bundle id -- essentially breaking up the bundle. Otherwise this
          // task could end up with a different status than other tasks
          // in that bundle.
          if (cachedItem.status != t.status && t.bundleId != None) {
            this.serviceManager.taskBundle.deleteTaskBundle(user, t.bundleId.get)
          }

          // Get the latest task data and notify clients of the update
          this.retrieveById(t.id) match {
            case Some(latestTask) =>
              webSocketProvider.sendMessage(
                WebSocketMessages.taskUpdated(latestTask, Some(WebSocketMessages.userSummary(user)))
              )
            case None =>
          }
        case None => // do NOTHING
      }

      task
    }
  }

  /**
    * This is a merge update function that will update the task if it exists otherwise it will
    * insert a new item.
    *
    * @param element The element that needs to be inserted or updated. Although it could be updated,
    *                it requires the element itself in case it needs to be inserted
    * @param user    The user that is executing the function
    * @param id      The id of the element that is being updated/inserted
    * @param c       A connection to execute against
    * @return
    */
  override def mergeUpdate(
      element: Task,
      user: User
  )(implicit id: Long, c: Option[Connection] = None): Option[Task] = {
    this.permission.hasObjectWriteAccess(element, user)
    // get the parent challenge, as we need the priority information
    val parentChallenge = this.manager.challenge.retrieveById(element.parent) match {
      case Some(c) => c
      case None =>
        throw new NotFoundException(
          s"No parent was found for task with parentId [${element.parent}, this should never happen."
        )
    }
    // before clearing the cache grab the cachedItem
    // by setting the delete implicit to true we clear out the cache for the element
    // The cachedItem could be
    val cachedItem = this.cacheManager.withUpdatingCache(Long => retrieveById) {
      implicit cachedItem =>
        Some(cachedItem)
    }(id, true, true)
    this.withMRTransaction { implicit c =>
      val result =
        extractCooperativeWork(element.parent, element.geometries, element.cooperativeWork)
      val geometries      = result._1
      var cooperativeWork = result._2

      val query =
        """SELECT create_update_task({name}, {parentId}, {instruction},
                    {status}, {geojson}::JSONB, {cooperativeWorkJson}::JSONB, {id}, {priority}, {changesetId},
                    {reset}, {mappedOn}, {reviewStatus}, CAST({reviewRequestedBy} AS INTEGER),
                    CAST({reviewedBy} AS INTEGER), {reviewedAt})"""
      val updatedTaskId = SQL(query)
        .on(
          NamedParameter("name", ToParameterValue.apply[String].apply(element.name)),
          NamedParameter("parentId", ToParameterValue.apply[Long].apply(element.parent)),
          NamedParameter(
            "instruction",
            ToParameterValue.apply[String].apply(element.instruction.getOrElse(""))
          ),
          NamedParameter(
            "status",
            ToParameterValue.apply[Option[Int]].apply(element.status)
          ),
          NamedParameter("geojson", ToParameterValue.apply[String].apply(geometries)),
          NamedParameter(
            "cooperativeWorkJson",
            ToParameterValue.apply[String].apply(cooperativeWork.orNull)
          ),
          NamedParameter("id", ToParameterValue.apply[Long].apply(element.id)),
          NamedParameter(
            "priority",
            ToParameterValue.apply[Int].apply(element.getTaskPriority(parentChallenge))
          ),
          NamedParameter(
            "changesetId",
            ToParameterValue.apply[Long].apply(element.changesetId.getOrElse(-1L))
          ),
          NamedParameter(
            "reset",
            ToParameterValue.apply[String].apply(s"${config.taskReset} days")
          ),
          NamedParameter(
            "mappedOn",
            ToParameterValue.apply[Option[DateTime]].apply(element.mappedOn)
          ),
          NamedParameter(
            "reviewStatus",
            ToParameterValue.apply[Option[Int]].apply(element.review.reviewStatus)
          ),
          NamedParameter(
            "reviewRequestedBy",
            ToParameterValue.apply[Option[Long]].apply(element.review.reviewRequestedBy)
          ),
          NamedParameter(
            "reviewedBy",
            ToParameterValue.apply[Option[Long]].apply(element.review.reviewedBy)
          ),
          NamedParameter(
            "reviewedAt",
            ToParameterValue.apply[Option[DateTime]].apply(element.review.reviewedAt)
          )
        )
        .as(long("create_update_task").*)
        .head

      // If we are updating the task review back to None then we need to delete
      // its entry in the task_review table.
      // We also need to always delete a task_review if we are resetting the task
      // back to created.
      cachedItem match {
        case Some(item) =>
          if ((item.review.reviewRequestedBy != None &&
              element.review.reviewRequestedBy == None) ||
              (element.status.getOrElse(Task.STATUS_CREATED) == Task.STATUS_CREATED &&
              element.review.reviewStatus != None)) {
            SQL("DELETE FROM task_review WHERE task_id=" + element.id).execute()
          }
        case None => // ignore
      }

      val updatedElement = element.copy(id = updatedTaskId)
      this.cacheManager.cache.remove(updatedTaskId)
      Some(updatedElement)
    }
  }

  /**
    * Function that extracts the cooperativeWork from the geometries
    *
    * @param parentId     The parent Id of the challenge (for marking this challenge has fixes)
    * @param geometries   The geojson that contains the geometries/cooperativeWork
    * @param cooperativeWork Any top level cooperative work not embedded in geometries
    */
  private def extractCooperativeWork(
      parentId: Long,
      geometries: String,
      cooperativeWork: Option[String]
  )(
      implicit c: Option[Connection] = None
  ): (String, Option[String]) = {
    this.withMRTransaction { implicit c =>
      var cooperativeWorkJson = cooperativeWork

      val geoJson   = Json.parse(geometries)
      var workMatch = (geoJson \\ "cooperativeWork")
      if (workMatch.isEmpty) {
        // Check to see if our cooperative work JSON was changed into a string due
        // to being a feature property (which are always converted to strings)
        val parentMatch = (geoJson \\ "maproulette")
        if (!parentMatch.isEmpty) {
          workMatch = (Json.parse(Utils.unescapeStringifiedJSON(parentMatch.head.toString())) \\ "cooperativeWork")
        }
      }

      if (!workMatch.isEmpty) {
        cooperativeWorkJson = Some(workMatch.head.toString())
      }

      val attachments   = (geoJson \ "attachments").toOption
      val mrTransformer = (__ \ "properties" \ "maproulette").json.prune
      val extractedGeometries = JsArray(
        (geoJson \ "features")
          .as[JsArray]
          .value
          .map {
            case value: JsObject => value.transform(mrTransformer).getOrElse(value)
            case _               => // do nothing
          }
          .asInstanceOf[ArrayBuffer[JsObject]]
      )

      // Set the correct cooperative type on the parent challenge
      cooperativeWorkJson match {
        case Some(work) =>
          val parsed = Json.parse(work)
          // Version 1 formatted work is always a tags-type challenge
          // In version 2, the type is explicitly specified
          val cooperativeType = (parsed \ "meta" \ "version").as[Int] match {
            case 1 => Challenge.COOPERATIVE_TAGS
            case 2 => (parsed \ "meta" \ "type").as[Int]
            case other =>
              throw new InvalidException(
                s"Unsupported cooperative challenge format version ${other}"
              )
          }

          SQL(s"UPDATE challenges SET cooperative_type = ${cooperativeType} WHERE id=${parentId}")
            .execute()
        case None => // do nothing
      }

      (
        JsObject(
          Seq(
            Some("features" -> extractedGeometries),
            Some("type"     -> JsString("FeatureCollection")),
            attachments match {
              case Some(a) => Some("attachments" -> a)
              case None    => None
            }
          ).flatten
        ).toString,
        cooperativeWorkJson
      )
    }
  }

  /**
    * A basic retrieval of the object based on the id. With caching, so if it finds
    * the object in the cache it will return that object without checking the database, otherwise
    * will hit the database directly.
    *
    * @param id The id of the object to be retrieved
    * @return The object, None if not found
    */
  override def retrieveById(implicit id: Long, c: Option[Connection] = None): Option[Task] = {
    this.taskRepository.retrieve(id)
  }

  def manager: DALManager = dalManager.get()

  /**
    * Sets the task for a given user. The user cannot set the status of a task unless the object has
    * been locked by the same user before hand.
    * Will throw an InvalidException if the task status cannot be set due to the current task status
    * Will throw an IllegalAccessException if the user is a guest user, or if the task is locked by
    * a different user.
    *
    * @param tasks               The task to set the status for
    * @param status              The status to set
    * @param user                The user setting the status
    * @param requestReview       Optional boolean to request a review on this task.
    * @param completionResponses Optional json responses provided by user to task instruction questions
    * @return The number of rows updated, should only ever be 1
    */
  def setTaskStatus(
      tasks: List[Task],
      status: Int,
      user: User,
      requestReview: Option[Boolean] = None,
      completionResponses: Option[JsValue] = None,
      bundleId: Option[Long] = None,
      primaryTaskId: Option[Long] = None
  )(implicit c: Connection = null): Int = {
    val tasksLength = tasks.length
    val isBundle    = bundleId.isDefined
    if (tasksLength < 1) {
      throw new InvalidException("Must be at least one task in list to setTaskStatus.")
    }

    var primaryTask  = tasks.head
    var bundleUpdate = ""

    // Find primary task in bundle if we are using a bundle
    // Also check to make sure they aren't cooperative tasks
    bundleId match {
      case Some(b) =>
        bundleUpdate = ", bundle_id = " + b
        for (task <- tasks) {
          primaryTaskId match {
            case Some(p) =>
              if (task.id == p) primaryTask = task
              if (task.cooperativeWork != None) {
                throw new InvalidException(
                  "Cannot set task status as part of a bundle on task: " +
                    task.id + " as it contains cooperative work."
                )
              }
            case _ => // do nothing
          }
        }
      case _ => // not a bundle
    }

    // Allow mappers who have completed the task to change status during revisions
    val allowReset = if (primaryTask.completedBy.getOrElse(-1) == user.id) true else false

    if (!Task.isValidStatusProgression(
          primaryTask.status.getOrElse(Task.STATUS_CREATED),
          status,
          allowReset
        )) {
      throw new InvalidException("Invalid task status supplied.")
    } else if (user.guest) {
      throw new IllegalAccessException("Guest users cannot make edits to tasks.")
    }

    val reviewNeeded = requestReview match {
      case Some(r) => r
      case None =>
        user.settings.needsReview.getOrElse(config.defaultNeedsReview) != User.REVIEW_NOT_NEEDED &&
          status != Task.STATUS_SKIPPED && status != Task.STATUS_DELETED && status != Task.STATUS_DISABLED
    }

    val responses = completionResponses match {
      case Some(r) => r.toString()
      case None    => null
    }

    val oldStatus   = primaryTask.status
    var updatedRows = 0

    this.withMRTransaction { implicit c =>
      for (task <- tasks) {
        if (task.bundleId != None && task.bundleId.get != bundleId.getOrElse(-1) && !allowReset) {
          throw new InvalidException(
            "Cannot set task status on task: " +
              task.id + " as it is already assigned to a bundle."
          )
        }

        // If we are 'skipping' this task and it's in some other status let's honor
        // the other status and not actually update the task status. We should only
        // note the skip by the user in the status actions and remove the lock.
        val skipStatusUpdate =
          (primaryTask.status.getOrElse(Task.STATUS_CREATED) != Task.STATUS_CREATED &&
            status == Task.STATUS_SKIPPED)

        if (!skipStatusUpdate) {
          updatedRows =
            SQL"""UPDATE tasks t SET status = $status, mapped_on = NOW(), completion_responses = ${responses}::JSONB,
                                 completed_by = ${user.id}  #$bundleUpdate
                                 WHERE t.id = (
                                    SELECT t2.id FROM tasks t2
                                    LEFT JOIN locked l on l.item_id = t2.id AND l.item_type = ${task.itemType.typeId}
                                    WHERE t2.id = ${task.id} AND (l.user_id = ${user.id} OR l.user_id IS NULL)
                                  )""".executeUpdate()
          // if returning 0, then this is because the item is locked by a  different user
          if (updatedRows == 0) {
            throw new IllegalAccessException(
              s"This task is locked by another user, cannot update status at this time."
            )
          }
        }

        val startedLock = (SQL"""SELECT created FROM locked l WHERE l.item_id = ${task.id} AND
                                       l.item_type = ${task.itemType.typeId} AND l.user_id = ${user.id}
                             """).as(SqlParser.scalar[DateTime].singleOpt)

        var completedTimeSpent: Option[Long] = None
        if (!skipStatusUpdate) {
          startedLock match {
            case Some(l) => {
              if (isBundle) {
                completedTimeSpent = Some(
                  SQL"""UPDATE tasks SET completed_time_spent =
                  (SELECT ((extract(epoch from NOW()) * 1000 - ${l.getMillis()}) / ${tasksLength}))
                     WHERE id = ${task.id} RETURNING completed_time_spent"""
                    .as(SqlParser.long("completed_time_spent").single)
                )
              } else {
                completedTimeSpent = Some(
                  SQL"""UPDATE tasks SET completed_time_spent =
                  (SELECT (extract(epoch from NOW()) * 1000 - ${l.getMillis()}))
                     WHERE id = ${task.id} RETURNING completed_time_spent"""
                    .as(SqlParser.long("completed_time_spent").single)
                )
              }
            }
            case _ =>
              val completedTime =
                if (isBundle) this.config.baseCompletedTimeSpent / tasksLength
                else this.config.baseCompletedTimeSpent
              completedTimeSpent = Some(
                SQL"""UPDATE tasks SET completed_time_spent = ${completedTime}
                     WHERE id = ${task.id} RETURNING completed_time_spent"""
                  .as(SqlParser.long("completed_time_spent").single)
              )
          }
        }

        this.manager.statusAction.setStatusAction(user, task, status, startedLock)

        if (reviewNeeded && !skipStatusUpdate) {
          task.review.reviewStatus match {
            case Some(rs) =>
              SQL"""UPDATE task_review tr
                      SET review_status = ${Task.REVIEW_STATUS_REQUESTED}, review_requested_by = ${user.id}
                      WHERE tr.task_id = ${task.id}
                 """.executeUpdate()

              // Let's note in the task_review_history table that this task needs review again
              SQL"""INSERT INTO task_review_history
                                 (task_id, requested_by, reviewed_by, review_status, reviewed_at, review_started_at)
                     VALUES (${task.id}, ${user.id}, ${task.review.reviewedBy},
                             ${Task.REVIEW_STATUS_REQUESTED}, NOW(),
                             ${task.review.reviewStartedAt})""".executeUpdate()

              // Create notification only if task has reviewer
              val reviewedBy: Long = task.review.reviewedBy.getOrElse(-1)
              if (reviewedBy != -1) {
                this.serviceManager.notification.createReviewNotification(
                  user,
                  reviewedBy,
                  Task.REVIEW_STATUS_REQUESTED,
                  task,
                  None
                )
              }
            case None =>
              SQL"""INSERT INTO task_review (task_id, review_status, review_requested_by)
                      VALUES (${task.id}, ${Task.REVIEW_STATUS_REQUESTED}, ${user.id})"""
                .executeUpdate()
          }
        }

        if (status == Task.STATUS_CREATED) {
          // If we are moving this task back to a created status, then we don't need to do a review on it.
          SQL(s"DELETE FROM task_review tr WHERE tr.task_id = ${task.id}").executeUpdate()
        }

        // if you set the status successfully on a task you will lose the lock of that task
        try {
          this.unlockItem(user, task)
        } catch {
          case e: Exception => logger.warn(e.getMessage)
        }
        if (config.changeSetEnabled) {
          // try and match the current task with a changeset from the user
          Future {
            c.commit()
            this.matchToOSMChangeSet(task.copy(status = Some(status)), user)
          }
        }

        if (!skipStatusUpdate) {
          // Update the popularity score on the parent challenge
          Future {
            this.manager.challenge.updatePopularity(Instant.now().getEpochSecond())(task.parent)
          }

          if (reviewNeeded) {
            // Let's note in the task_review_history table that this task needs review
            SQL"""INSERT INTO task_review_history (task_id, requested_by, review_status, reviewed_at)
                  VALUES (${task.id}, ${user.id}, ${Task.REVIEW_STATUS_REQUESTED}, NOW())"""
              .executeUpdate()

            this.cacheManager.withOptionCaching { () =>
              Some(
                task.copy(
                  status = Some(status),
                  review = task.review.copy(
                    reviewStatus = Some(Task.REVIEW_STATUS_REQUESTED),
                    reviewRequestedBy = Some(user.id)
                  ),
                  modified = new DateTime(),
                  completionResponses = completionResponses match {
                    case Some(r) => Some(r.toString())
                    case None    => None
                  },
                  bundleId = bundleId,
                  isBundlePrimary =
                    if (bundleId != None) Some(task.id == primaryTask.id)
                    else None
                )
              )
            }
          } else {
            this.cacheManager.withOptionCaching { () =>
              Some(
                task.copy(
                  status = Some(status),
                  modified = new DateTime(),
                  completionResponses = completionResponses match {
                    case Some(r) => Some(r.toString())
                    case None    => None
                  },
                  bundleId = bundleId,
                  isBundlePrimary =
                    if (bundleId != None) Some(task.id == primaryTask.id)
                    else None
                )
              )
            }
          }

          // let's give the user credit for doing this task.
          if (oldStatus.getOrElse(Task.STATUS_CREATED) != status) {
            this.serviceManager.userMetrics.updateUserScore(
              Option(status),
              completedTimeSpent,
              None,
              false,
              false,
              None,
              user.id
            )
          }

          // Get the latest task data and notify clients of the update
          this.retrieveById(task.id) match {
            case Some(latestTask) =>
              webSocketProvider.sendMessage(
                WebSocketMessages.taskUpdated(latestTask, Some(WebSocketMessages.userSummary(user)))
              )

              // Also transmit a task-completion if the status changed
              if (oldStatus.getOrElse(Task.STATUS_CREATED) != status) {
                this.serviceManager.challenge.retrieve(latestTask.parent) match {
                  case Some(challenge) =>
                    this.serviceManager.project.retrieve(challenge.general.parent) match {
                      case Some(project) =>
                        webSocketProvider.sendMessage(
                          WebSocketMessages.taskCompleted(
                            latestTask,
                            WebSocketMessages.challengeSummary(challenge),
                            WebSocketMessages.projectSummary(project),
                            WebSocketMessages.userSummary(user)
                          )
                        )
                      case None =>
                    }
                  case None =>
                }
              }
            case None =>
          }
        }
      }

      // Mark the primary task id from the bundle, if any
      bundleId match {
        case Some(b) =>
          SQL("UPDATE tasks SET is_bundle_primary = true WHERE id = " + primaryTask.id)
            .executeUpdate()
        case None => // not part of a bundle
      }
    }

    this.manager.challenge.updateFinishedStatus()(primaryTask.parent)

    Future {
      this.serviceManager.achievement.awardTaskCompletionAchievements(user, primaryTask, status)
    }

    if (reviewNeeded) {
      webSocketProvider.sendMessage(
        WebSocketMessages.reviewNew(
          WebSocketMessages.ReviewData(
            this.serviceManager.taskReview.getTaskWithReview(primaryTask.id)
          )
        )
      )
    }

    updatedRows
  }

  /**
    * Tries to match a specific changeset in OSM to the task in MapRoulette
    *
    * @param task The task that was fixed
    * @param user The user making the request
    * @param c    An implicit connection
    */
  def matchToOSMChangeSet(task: Task, user: User, immediate: Boolean = true)(
      implicit c: Option[Connection] = None
  ): Future[Boolean] = {
    val result = Promise[Boolean]
    if (config.allowMatchOSM) {
      task.status match {
        case Some(Task.STATUS_FIXED) =>
          val currentDateTimeUTC = DateTime.now(DateTimeZone.UTC)
          val statusAction = this.manager.statusAction
            .getStatusActions(task, user, Some(List(Task.STATUS_FIXED)))
            .headOption
          statusAction match {
            case Some(sa) =>
              this.getSortedChangeList(sa) onComplete {
                case Success(response) =>
                  val responseList = response.map(_.id)
                  response.find(c => {
                    // check the bounding box of the changeset, and make sure that the task geometry
                    // bounding box at the very least intersects with the changeset bounding box
                    if (c.hasMapRouletteComment) {
                      true
                    } else {
                      val feature =
                        GeoJSONFactory.create(task.geometries).asInstanceOf[FeatureCollection]
                      val reader   = new GeoJSONReader()
                      val envelope = new Envelope()
                      feature.getFeatures.foreach(f => {
                        val current = reader.read(f.getGeometry)
                        envelope.expandToInclude(current.getBoundary.getEnvelopeInternal)
                      })

                      val changesetEnvelope = new Envelope(c.minLon, c.maxLon, c.minLat, c.maxLat)
                      changesetEnvelope.intersects(envelope)
                    }
                  }) match {
                    case Some(change) =>
                      this.withMRConnection { implicit c =>
                        SQL(
                          s"""UPDATE tasks SET changeset_id = ${change.id} WHERE id = ${task.id}"""
                        ).executeUpdate()
                      }
                      result success true
                    case None =>
                      this.withMRConnection { implicit c =>
                        // if we can't find any viable option set the id to -2 so that we don't try again
                        // but only set it to -2 if the current time is 1 hour after the set time for the task
                        if (Math.abs(currentDateTimeUTC.getMillis - sa.created.getMillis) > (config.changeSetTimeLimit.toHours * 3600 * 1000)) {
                          SQL(s"""UPDATE tasks SET changeset_id = -2 WHERE id = ${task.id}""")
                            .executeUpdate()
                        }
                      }
                      result success false
                  }
                case Failure(error) => result success false
              }
            case None => result success false
          }
        case _ =>
          // throw some message here about something
          result success false
      }
    } else {
      result success false
    }
    result.future
  }

  /**
    * This gets the sorted list of changesets. The sorting is based on how close a changeset is to the time
    * that the task was fixed. It probably would be more efficient to
    *
    * @param statusAction The StatusActionItem that is for the action that set the task to fixed
    * @return A list of sorted changesets
    */
  private def getSortedChangeList(statusAction: StatusActionItem): Future[Seq[Changeset]] = {
    val result        = Promise[Seq[Changeset]]
    val format        = "YYYY-MM-dd'T'HH:mm:ss'Z'"
    val fixedTimeDiff = statusAction.created.getMillis
    val prevHours =
      statusAction.created.minusHours(config.changeSetTimeLimit.toHours.toInt).toString(format)
    val nextHours =
      statusAction.created.plusHours(config.changeSetTimeLimit.toHours.toInt).toString(format)
    ws.url(
        s"${config.getOSMServer}/api/0.6/changesets?user=${statusAction.osmUserId}&time=$prevHours,$nextHours"
      )
      .withHttpHeaders("User-Agent" -> "MapRoulette")
      .get() onComplete {
      case Success(response) =>
        if (response.status == 200) {
          val changeSetList = ChangesetParser.parse(XML.loadString(response.body)).filter(!_.open)
          val sortedList = changeSetList.sortWith((c1, c2) => {
            Math.abs(c1.createdAt.getMillis - fixedTimeDiff) < Math.abs(
              c2.createdAt.getMillis - fixedTimeDiff
            )
          })
          result success sortedList
        } else {
          result failure new InvalidException(
            s"Response failed with status ${response.status} messages ${response.statusText}"
          )
        }
      case Failure(error) => result failure error
    }
    result.future
  }

  /**
    * Simple query to retrieve the next task in the sequence
    *
    * @param parentId      The parent of the task
    * @param currentTaskId The current task that we are basing our query from
    * @param statusList    the list of status' to filter by
    * @return An optional task, if no more tasks in the list will retrieve the first task
    */
  def getNextTaskInSequence(
      parentId: Long,
      currentTaskId: Long,
      statusList: Option[Seq[Int]] = None
  )(implicit c: Option[Connection] = None): Option[(Task, Lock)] = {
    this.withMRConnection { implicit c =>
      val lp = for {
        task <- parser
        lock <- lockedParser
      } yield task -> lock
      val query =
        s"""SELECT locked.*, tasks.$retrieveColumnsWithReview FROM tasks
                      LEFT JOIN locked ON locked.item_id = tasks.id
                      LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id
                      WHERE tasks.id > $currentTaskId AND tasks.parent_id = $parentId
                      AND status IN ({statusList})
                      ORDER BY tasks.id ASC LIMIT 1"""
      val slist = statusList.getOrElse(Task.statusMap.keys.toSeq) match {
        case Nil => Task.statusMap.keys.toSeq
        case t   => t
      }
      SQL(query).on(Symbol("statusList") -> slist).as(lp.*).headOption match {
        case Some(t) => Some(t)
        case None =>
          val loopQuery =
            s"""SELECT locked.*, tasks.$retrieveColumnsWithReview FROM tasks
                              LEFT JOIN locked ON locked.item_id = tasks.id
                              LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id
                              WHERE tasks.parent_id = $parentId
                              AND status IN ({statusList})
                              ORDER BY tasks.id ASC LIMIT 1"""
          SQL(loopQuery).on(Symbol("statusList") -> slist).as(lp.*).headOption
      }
    }
  }

  /**
    * Simple query to retrieve the previous task in the sequence
    *
    * @param parentId      The parent of the task
    * @param currentTaskId The current task that we are basing our query from
    * @param statusList    the list of status' to filter by
    * @return An optional task, if no more tasks in the list will retrieve the last task
    */
  def getPreviousTaskInSequence(
      parentId: Long,
      currentTaskId: Long,
      statusList: Option[Seq[Int]] = None
  )(implicit c: Option[Connection] = None): Option[(Task, Lock)] = {
    this.withMRConnection { implicit c =>
      val lp = for {
        task <- parser
        lock <- lockedParser
      } yield task -> lock
      val query =
        s"""SELECT locked.*, tasks.$retrieveColumnsWithReview FROM tasks
                      LEFT JOIN locked ON locked.item_id = tasks.id
                      LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id
                      WHERE tasks.id < $currentTaskId AND tasks.parent_id = $parentId
                      AND status IN ({statusList})
                      ORDER BY tasks.id DESC LIMIT 1"""
      val slist = statusList.getOrElse(Task.statusMap.keys.toSeq) match {
        case Nil => Task.statusMap.keys.toSeq
        case t   => t
      }

      SQL(query).on(Symbol("statusList") -> slist).as(lp.*).headOption match {
        case Some(t) => Some(t)
        case None =>
          val loopQuery =
            s"""SELECT locked.*, tasks.$retrieveColumnsWithReview FROM tasks
                              LEFT JOIN locked ON locked.item_id = tasks.id
                              LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id
                              WHERE tasks.parent_id = $parentId
                              AND status IN ({statusList})
                              ORDER BY tasks.id DESC LIMIT 1"""
          SQL(loopQuery).on(Symbol("statusList") -> slist).as(lp.*).headOption
      }
    }
  }

  def getRandomTasksWithPriority(
      user: User,
      params: SearchParameters,
      limit: Int = -1,
      proximityId: Option[Long] = None
  )(implicit c: Option[Connection] = None): List[Task] = {
    val highPriorityTasks = Try(
      this.getRandomTasks(user, params, limit, Some(Challenge.PRIORITY_HIGH), proximityId)
    ) match {
      case Success(res) => res
      case Failure(f)   => List.empty
    }
    if (highPriorityTasks.isEmpty) {
      val mediumPriorityTasks = Try(
        this.getRandomTasks(user, params, limit, Some(Challenge.PRIORITY_MEDIUM), proximityId)
      ) match {
        case Success(res) => res
        case Failure(f)   => List.empty
      }
      if (mediumPriorityTasks.isEmpty) {
        this.getRandomTasks(user, params, limit, Some(Challenge.PRIORITY_LOW), proximityId)
      } else {
        mediumPriorityTasks
      }
    } else {
      highPriorityTasks
    }
  }

  /**
    * Gets a random task. This will first retrieve a random challenge from the list of criteria for the
    * challenges. If a challenge id is provided all other search criteria will be ignored. Generally
    * this would be called to get a single random task, if multiple random tasks are requested all
    * the random tasks will be retrieved from the random challenge that was selected. So for multiple
    * tasks it will be all grouped from the same challenge.
    *
    * @param user        The user executing the request
    * @param params      The search parameters that will define the filters for the random selection
    * @param limit       The amount of tags that should be returned
    * @param priority    An optional priority, so that we only look for tasks in a specific priority range
    * @param proximityId Id of task that you wish to find the next task based on the proximity of that task
    * @return A list of random tags matching the above criteria, an empty list if none match
    */
  def getRandomTasks(
      user: User,
      params: SearchParameters,
      limit: Int = -1,
      priority: Option[Int] = None,
      proximityId: Option[Long] = None
  )(implicit c: Option[Connection] = None): List[Task] = {
    getRandomChallenge(params) match {
      case Some(challengeId) =>
        val taskTagIds = if (params.hasTaskTags) {
          this.tagService
            .retrieveListByName(params.taskParams.taskTags.get.map(_.toLowerCase))
            .map(_.id)
        } else {
          List.empty
        }

        val select =
          s"""SELECT tasks.$retrieveColumnsWithReview FROM tasks
          LEFT JOIN locked l ON l.item_id = tasks.id
          LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id
       """.stripMargin

        val parameters   = new ListBuffer[NamedParameter]()
        val queryBuilder = new StringBuilder
        // The default where clause will check to see if the parents are enabled, that the task is
        // not locked (or if it is, it is locked by the current user) and that the status of the task
        // is either Created or Skipped
        val taskStatusList = params.taskParams.taskStatus match {
          case Some(l) if l.nonEmpty => l
          case _ => {
            config.skipTooHard match {
              case true =>
                List(Task.STATUS_CREATED, Task.STATUS_SKIPPED)
              case false =>
                List(Task.STATUS_CREATED, Task.STATUS_SKIPPED, Task.STATUS_TOO_HARD)
            }
          }
        }
        val whereClause = new StringBuilder(s"""WHERE tasks.parent_id = $challengeId AND
              (l.id IS NULL OR l.user_id = ${user.id}) AND
              tasks.status IN ({statusList})
            """)
        parameters += (Symbol("statusList") -> ToParameterValue
          .apply[List[Int]]
          .apply(taskStatusList))

        // Make sure that the user doesn't see the same task multiple times in
        // the same hour. This prevents users from getting stuck at a priority
        // boundary when there is only one task remaining that they're trying
        // to skip, and also prevents the user from getting bounced between a
        // small number of nearby skipped tasks when loading by proximity
        // Unless a status is created then ignore this check.
        appendInWhereClause(
          whereClause,
          s"""NOT (tasks.status != ${Task.STATUS_CREATED} AND tasks.id IN (
             |SELECT task_id FROM status_actions
             |WHERE osm_user_id IN (${user.osmProfile.id})
             |  AND created >= NOW() - '1 hour'::INTERVAL))""".stripMargin
        )

        priority match {
          case Some(p) => appendInWhereClause(whereClause, s"tasks.priority = $p")
          case None    => //Ignore
        }

        if (taskTagIds.nonEmpty) {
          queryBuilder ++= "INNER JOIN tags_on_tasks tt ON tt.task_id = tasks.id "
          appendInWhereClause(whereClause, "tt.tag_id IN ({tagids})")
          parameters += (Symbol("tagids") -> ToParameterValue.apply[List[Long]].apply(taskTagIds))
        }
        if (params.taskParams.taskSearch.nonEmpty) {
          appendInWhereClause(whereClause, s"${searchField("tasks.name", "taskSearch")(None)}")
          parameters += (Symbol("taskSearch") -> search(params.taskParams.taskSearch.getOrElse("")))
        }

        val proximityOrdering = proximityId match {
          case Some(id) =>
            // Be sure not to serve the task the user just came from
            appendInWhereClause(whereClause, s"tasks.id != $id")
            s"ST_Distance(tasks.location, (SELECT location FROM tasks WHERE id = $id)),"
          case None => ""
        }

        val query = s"$select ${queryBuilder.toString} ${whereClause.toString} " +
          s"ORDER BY $proximityOrdering tasks.status, RANDOM() LIMIT ${this.sqlLimit(limit)}"

        implicit val ids = List[Long]()
        this.cacheManager.withIDListCaching { implicit cachedItems =>
          this.withListLocking(user, Some(TaskType())) { () =>
            this.withMRTransaction { implicit c =>
              sqlWithParameters(query, parameters).as(this.parser.*)
            }
          }
        }
      case None => List.empty
    }
  }

  /**
    * Retrieves a random challenge from the list of possible challenges in the search list
    *
    * @param params The params to search for the random challenge
    * @param c      The connection to the database, will create one if not already created
    * @return The id of the random challenge
    */
  private def getRandomChallenge(
      params: SearchParameters
  )(implicit c: Option[Connection] = None): Option[Long] = {
    params.getChallengeIds match {
      case Some(v) if v.lengthCompare(1) == 0 => Some(v.head)
      case v =>
        withMRConnection { implicit c =>
          val parameters  = new ListBuffer[NamedParameter]()
          val whereClause = new StringBuilder
          val joinClause  = new StringBuilder

          v match {
            case Some(l) if l.nonEmpty =>
              appendInWhereClause(whereClause, s"c.id IN (${l.mkString(",")})")
            case None => // ignore
          }

          if (params.enabledChallenge) {
            appendInWhereClause(whereClause, "c.enabled = true")
          }
          if (params.enabledProject) {
            appendInWhereClause(whereClause, "p.enabled = true")
          }

          parameters ++= addChallengeTagMatchingToQuery(params, whereClause, joinClause)
          parameters ++= addSearchToQuery(params, whereClause)

          //add a where clause that just makes sure that any random challenge retrieved actually has some tasks in it
          appendInWhereClause(
            whereClause,
            "1 = (SELECT 1 FROM tasks WHERE parent_id = c.id LIMIT 1)"
          )

          val query =
            s"""
                        SELECT c.id FROM challenges c
                        INNER JOIN projects p ON p.id = c.parent_id
                        ${joinClause.toString}
                        WHERE ${whereClause.toString}
                        ORDER BY RANDOM() LIMIT 1
                      """
          sqlWithParameters(query, parameters).as(long("id").*).headOption
        }
    }
  }

  /**
    * Retrieve tasks geographically closest to the given task id within the
    * given challenge. Ignores tasks that are complete, locked by other users,
    * or that the current user has worked on in the last hour
    */
  def getNearbyTasks(
      user: User,
      challengeId: Long,
      proximityId: Long,
      excludeSelfLocked: Boolean = false,
      limit: Int = 5
  )(implicit c: Option[Connection] = None): List[Task] = {
    var selfLockedClause = ""
    if (!excludeSelfLocked) {
      selfLockedClause = s"OR l.user_id = ${user.id}"
    }

    val query =
      s"""SELECT tasks.$retrieveColumnsWithReview FROM tasks
      LEFT JOIN locked l ON l.item_id = tasks.id
      LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id
      WHERE tasks.id <> $proximityId AND
            tasks.parent_id = $challengeId AND
            (l.id IS NULL ${selfLockedClause}) AND
            tasks.status IN (0, 3, 6) AND
            NOT tasks.id IN (
                SELECT task_id FROM status_actions
                WHERE osm_user_id = ${user.osmProfile.id} AND created >= NOW() - '1 hour'::INTERVAL)
      ORDER BY ST_Distance(tasks.location, (SELECT location FROM tasks WHERE id = $proximityId)), tasks.status, RANDOM()
      LIMIT ${this.sqlLimit(limit)}"""

    this.withMRTransaction { implicit c =>
      SQL(query).as(this.parser.*)
    }
  }

  /**
    * Returns the list of users that modified the requested task
    *
    * @param user  The user making the request
    * @param id    The id of the task
    * @param limit The number of distinct users that modified the task
    * @param c     An implicit connection to use, if not found will create a new connection
    * @return A list of users that modified the task
    */
  def getLastModifiedUser(user: User, id: Long, limit: Int = 1)(
      implicit c: Option[Connection] = None
  ): List[User] = {
    this.serviceManager.user.query(
      Query.simple(
        List(
          SubQueryFilter(
            User.FIELD_OSM_ID,
            Query.simple(
              List(BaseParameter(StatusActions.FIELD_TASK_ID, id)),
              "SELECT osm_user_id FROM status_actions",
              order = Order > "created",
              paging = Paging(limit)
            )
          )
        )
      ),
      user
    )
  }

  def retrieveTaskSummaries(
      challengeIds: List[Long],
      limit: Int = Config.DEFAULT_LIST_SIZE,
      page: Int = 0,
      params: SearchParameters
  ): (List[TaskSummary], Map[Long, String]) =
    db.withConnection { implicit c =>
      val parser = for {
        taskId              <- long("tasks.id")
        parentId            <- long("tasks.parent_id")
        name                <- str("tasks.name")
        status              <- int("tasks.status")
        priority            <- int("tasks.priority")
        geojson             <- get[Option[String]]("geo_json")
        username            <- get[Option[String]]("users.username")
        mappedOn            <- get[Option[DateTime]]("mapped_on")
        completedTimeSpent  <- get[Option[Long]]("completed_time_spent")
        completedBy         <- get[Option[String]]("completedBy")
        reviewStatus        <- get[Option[Int]]("task_review.review_status")
        reviewRequestedBy   <- get[Option[String]]("reviewRequestedBy")
        reviewedBy          <- get[Option[String]]("reviewedBy")
        reviewedAt          <- get[Option[DateTime]]("task_review.reviewed_at")
        reviewStartedAt     <- get[Option[DateTime]]("task_review.review_started_at")
        additionalReviewers <- get[Option[List[String]]]("additionalReviewers")
        tags                <- get[Option[String]]("tags")
        responses           <- get[Option[String]]("responses")
        bundleId            <- get[Option[Long]]("bundle_id")
        isBundlePrimary     <- get[Option[Boolean]]("is_bundle_primary")
      } yield TaskSummary(
        taskId,
        parentId,
        name,
        status,
        priority,
        username,
        mappedOn,
        completedTimeSpent,
        completedBy,
        reviewStatus,
        reviewRequestedBy,
        reviewedBy,
        reviewedAt,
        reviewStartedAt,
        additionalReviewers,
        tags,
        responses,
        geojson,
        bundleId,
        isBundlePrimary
      )

      val filters    = new StringBuilder()
      val joinClause = new StringBuilder()
      this.updateWhereClause(params, filters, joinClause)

      val commentParser = for {
        taskId   <- long("task_id")
        comments <- str("comments")
      } yield (taskId -> comments)

      val commentsQuery =
        SQL"""
          SELECT tc.task_id, string_agg(CONCAT((SELECT name from users where tc.osm_id = users.osm_id), ': ', comment),
                            CONCAT(chr(10),'---',chr(10))) AS comments
          FROM task_comments tc WHERE tc.challenge_id IN (#${challengeIds.mkString(",")}) GROUP BY tc.task_id
        """
      val allComments = commentsQuery.as(commentParser.*).toMap.withDefaultValue("")

      val query =
        SQL"""SELECT tasks.id, tasks.parent_id, tasks.name, tasks.status, tasks.priority, sa_outer.username, tasks.mapped_on,
                   tasks.completed_time_spent,
                   (SELECT name as completedBy FROM users WHERE users.id = tasks.completed_by),
                   task_review.review_status, tasks.is_bundle_primary, tasks.bundle_id, tasks.geojson::TEXT AS geo_json,
                   (SELECT name as reviewRequestedBy FROM users WHERE users.id = task_review.review_requested_by),
                   (SELECT name as reviewedBy FROM users WHERE users.id = task_review.reviewed_by),
                   task_review.reviewed_at, task_review.review_started_at,
                   (ARRAY(SELECT name FROM users WHERE id IN (SELECT UNNEST(additional_reviewers)))) AS additionalReviewers,
                   (SELECT STRING_AGG(tg.name, ',') AS tags FROM tags_on_tasks tot, tags tg where tot.task_id = tasks.id AND tg.id = tot.tag_id),
                   tasks.completion_responses::TEXT AS responses
            FROM tasks LEFT OUTER JOIN (
              SELECT sa.task_id, sa.status, sa.osm_user_id, u.name AS username
              FROM users u, status_actions sa INNER JOIN (
                SELECT task_id, MAX(created) AS latest
                FROM status_actions
                WHERE challenge_id IN (#${challengeIds.mkString(",")})
                GROUP BY task_id
              ) AS sa_inner
              ON sa.task_id = sa_inner.task_id AND sa.created = sa_inner.latest
              WHERE sa.osm_user_id = u.osm_id
            ) AS sa_outer ON tasks.id = sa_outer.task_id AND tasks.status = sa_outer.status
            INNER JOIN challenges c ON c.id = tasks.parent_id
            LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id
            #${joinClause.toString}
            WHERE tasks.parent_id IN (#${challengeIds.mkString(",")}) AND #${filters.toString}
            ORDER BY tasks.id
            LIMIT #${this.sqlLimit(limit)} OFFSET #${page * limit}
      """
      (query.as(parser.*), allComments)
    }

  /**
    * Retrieves a list of objects from the supplied list of ids. Will check for any objects currently
    * in the cache and those that aren't will be retrieved from the database
    *
    * @param limit  The limit on the number of objects returned. This is not entirely useful as a limit
    *               could be set simply by how many ids you supplied in the list, but possibly useful
    *               for paging
    * @param offset For paging, ie. the page number starting at 0
    * @param ids    The list of ids to be retrieved
    * @return A list of objects, empty list if none found
    */
  override def retrieveListById(
      limit: Int = -1,
      offset: Int = 0
  )(implicit ids: List[Long], c: Option[Connection] = None): List[Task] = {
    if (ids.isEmpty) {
      List.empty
    } else {
      this.cacheManager.withIDListCaching { implicit uncachedIDs =>
        this.withMRConnection { implicit c =>
          val query =
            s"""SELECT ${retrieveColumnsWithReview} FROM ${this.tableName}
                LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id
                WHERE tasks.id IN ({inString})
                LIMIT ${this.sqlLimit(limit)} OFFSET {offset}"""
          SQL(query)
            .on(
              Symbol("inString") -> ToParameterValue
                .apply[List[Long]](s = keyToSQL, p = keyToStatement)
                .apply(uncachedIDs),
              Symbol("offset") -> offset
            )
            .as(this.parser.*)
        }
      }
    }
  }

  /**
    * A temporary solution that will allow us to lazy update the geojson data
    *
    * @param taskId The identifier of the task
    */
  def updateAndRetrieve(
      taskId: Long,
      geojson: Option[String],
      location: Option[String],
      cooperativeWork: Option[String]
  )(implicit c: Option[Connection] = None): (String, Option[String], Option[String]) = {
    this.taskRepository.updateAndRetrieve(taskId, geojson, location, cooperativeWork)
  }

  case class TaskSummary(
      taskId: Long,
      parent: Long,
      name: String,
      status: Int,
      priority: Int,
      username: Option[String],
      mappedOn: Option[DateTime],
      completedTimeSpent: Option[Long],
      completedBy: Option[String],
      reviewStatus: Option[Int],
      reviewRequestedBy: Option[String],
      reviewedBy: Option[String],
      reviewedAt: Option[DateTime],
      reviewStartedAt: Option[DateTime],
      additionalReviewers: Option[List[String]],
      tags: Option[String],
      completionResponses: Option[String],
      geojson: Option[String],
      bundleId: Option[Long],
      isBundlePrimary: Option[Boolean]
  )

}

object TaskDAL {
  val DEFAULT_NUMBER_OF_POINTS = 100
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import anorm.SqlParser.{get, scalar, str}
import anorm.ToParameterValue
import anorm._, postgresql._
import javax.inject.{Inject, Singleton}
import org.maproulette.Config
import org.maproulette.framework.mixins.TaskParserMixin
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.{BaseParameter, CustomParameter}
import org.maproulette.framework.model.{User, Task}
import org.maproulette.cache.CacheManager
import play.api.db.Database
import play.api.libs.json._

@Singleton
class TaskRepository @Inject() (override val db: Database, config: Config)
    extends RepositoryMixin
    with TaskParserMixin {
  implicit val baseTable: String = "tasks"

  // The cache manager for tasks
  val cacheManager =
    new CacheManager[Long, Task](config, Config.CACHE_ID_TASKS)(taskReads, taskReads)

  /**
    * For a given id returns the task
    *
    * @param id The id of the task you are looking for
    * @param c An implicit connection, defaults to none and one will be created automatically
    * @return None if not found, otherwise the Task
    */
  def retrieve(id: Long): Option[Task] = {
    this.cacheManager.withCaching { () =>
      this.withMRConnection { implicit c =>
        val query = s"SELECT $retrieveColumnsWithReview FROM ${this.baseTable} " +
          "LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id " +
          "WHERE tasks.id = {id}"
        SQL(query)
          .on(Symbol("id") -> id)
          .as(this.getTaskParser(this.updateAndRetrieve).singleOpt)
      }
    }(id)
  }

  /**
    * Allows us to lazy update the geojson data
    *
    * @param taskId The identifier of the task
    */
  def updateAndRetrieve(
      taskId: Long,
      geojson: Option[String],
      location: Option[String],
      cooperativeWork: Option[String]
  ): (String, Option[String], Option[String]) = {
    geojson match {
      case Some(g) => (g, location, cooperativeWork)
      case None =>
        this.withMRTransaction { implicit c =>
          SQL("SELECT * FROM update_geometry({id})")
            .on(Symbol("id") -> taskId)
            .as((str("geo") ~ get[Option[String]]("loc") ~ get[Option[String]]("fix_geo")).*)
            .headOption match {
            case Some(values) => (values._1._1, values._1._2, values._2)
            case None         => throw new Exception("Failed to retrieve task data")
          }
        }
    }
  }

  /**
    * Updates the completionResponses on a Task
    *
    * @param task  The task to update
    * @param user  The user making the request
    * @param completionResponses json responses provided by user to task instruction questions
    */
  def updateCompletionResponses(task: Task, user: User, completionResponses: JsValue): Unit = {
    this.withMRTransaction { implicit c =>
      val query = Query.simple(List())

      query
        .build(s"""UPDATE tasks t SET completion_responses = {responses}::JSONB
              WHERE t.id = (
                SELECT t2.id FROM tasks t2
                LEFT JOIN locked l on l.item_id = t2.id AND l.item_type = {itemType}
                WHERE t2.id = {taskId} AND (l.user_id = {userId} OR l.user_id IS NULL)
              )""")
        .on(
          Symbol("responses") -> ToParameterValue
            .apply[String]
            .apply(completionResponses.toString),
          Symbol("itemType") -> ToParameterValue
            .apply[Int]
            .apply(task.itemType.typeId),
          Symbol("taskId") -> ToParameterValue
            .apply[Long]
            .apply(task.id),
          Symbol("userId") -> ToParameterValue
            .apply[Long]
            .apply(user.id)
        )
        .executeUpdate()

    }
  }

  def updatePriority(updatedPriority: Int, id: Long): Unit = {
    this.withMRConnection { implicit c =>
      SQL(
        s"""UPDATE tasks SET priority = ${updatedPriority} WHERE id = ${id}"""
      ).executeUpdate()
    }
  }

  /**
    * Retrieve a task attachment identified by attachmentId
    *
    * @param taskId       The id of the task with the attachment
    * @param attachmentId The id of the attachment
    */
  def getTaskAttachment(taskId: Long, attachmentId: String): Option[JsObject] = {
    withMRConnection { implicit c =>
      Query
        .simple(
          List(
            BaseParameter(Task.FIELD_ID, taskId),
            CustomParameter(s"attachment->>'id' = '$attachmentId'")
          )
        )
        .build(
          s"select attachment from tasks, jsonb_array_elements(geojson -> 'attachments') attachment"
        )
        .as(scalar[JsObject].singleOpt)
    }
  }
}

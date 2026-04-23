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
    * Increment the skip_count on a task. Does NOT change the task's status
    * or touch the lock record — callers should release the lock separately.
    *
    * @param taskId The id of the task being skipped
    * @return The number of rows updated (0 or 1)
    */
  def incrementSkipCount(taskId: Long): Int = {
    this.withMRConnection { implicit c =>
      SQL("UPDATE tasks SET skip_count = skip_count + 1 WHERE id = {id}")
        .on(Symbol("id") -> taskId)
        .executeUpdate()
    }
  }

  /**
    * Delete tasks by id list. Cascade deletes via FK constraints handle
    * task_review, task tags, comments, etc.
    *
    * @param taskIds The task ids to delete
    * @return The number of rows deleted
    */
  def bulkDeleteTasks(taskIds: List[Long]): Int = {
    if (taskIds.isEmpty) return 0
    this.withMRTransaction { implicit c =>
      SQL("DELETE FROM task_review WHERE task_id IN ({ids})")
        .on(Symbol("ids") -> taskIds)
        .executeUpdate()
      SQL("DELETE FROM tasks WHERE id IN ({ids})")
        .on(Symbol("ids") -> taskIds)
        .executeUpdate()
    }
  }

  /**
    * Archive / unarchive tasks in bulk.
    *
    * @param taskIds  The task ids to update
    * @param archived Target archived flag
    * @return Number of rows updated
    */
  def bulkArchiveTasks(taskIds: List[Long], archived: Boolean): Int = {
    if (taskIds.isEmpty) return 0
    this.withMRConnection { implicit c =>
      SQL("UPDATE tasks SET archived = {archived} WHERE id IN ({ids})")
        .on(Symbol("archived") -> archived, Symbol("ids") -> taskIds)
        .executeUpdate()
    }
  }

  /**
    * Reassign the review of a batch of tasks to another user. Only tasks
    * whose reviews are currently "needed" or "requested" (review_status in
    * {0, 3}) are reassigned; other reviews are left alone.
    *
    * @param taskIds Task ids to reassign
    * @param userId  Target reviewer user id
    * @return Number of rows updated
    */
  def bulkReassignReviewer(taskIds: List[Long], userId: Long): Int = {
    if (taskIds.isEmpty) return 0
    this.withMRConnection { implicit c =>
      SQL(
        """UPDATE task_review
           SET reviewed_by = {userId}, review_claimed_by = {userId}, review_claimed_at = NOW()
           WHERE task_id IN ({ids}) AND review_status IN (0, 3)"""
      ).on(Symbol("userId") -> userId, Symbol("ids") -> taskIds)
        .executeUpdate()
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

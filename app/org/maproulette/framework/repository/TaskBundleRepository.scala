/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import org.slf4j.LoggerFactory

import anorm.ToParameterValue
import anorm.SqlParser.scalar

import org.maproulette.cache.CacheManager
import anorm._, postgresql._
import javax.inject.{Inject, Singleton}
import org.maproulette.exception.InvalidException
import org.maproulette.Config
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.BaseParameter
import org.maproulette.framework.model.{Task, TaskBundle, User}
import org.maproulette.framework.mixins.{TaskParserMixin, Locking}
import org.maproulette.framework.model.Task.STATUS_CREATED
import org.maproulette.data.TaskType
import play.api.db.Database

// deprecated and to be removed after conversion
import org.maproulette.models.dal.TaskDAL

@Singleton
class TaskBundleRepository @Inject() (
    override val db: Database,
    config: Config,
    taskRepository: TaskRepository,
    taskDAL: TaskDAL
) extends RepositoryMixin
    with TaskParserMixin
    with Locking[Task] {
  protected val logger                       = LoggerFactory.getLogger(this.getClass)
  implicit val baseTable: String             = Task.TABLE
  val cacheManager: CacheManager[Long, Task] = this.taskRepository.cacheManager

  /**
    * Inserts a new task bundle with the given tasks, assigning ownership of
    * the bundle to the given user
    *
    * @param user    The user who is to own the bundle
    * @param name    The name of the task bundle
    * @param lockedTasks The tasks to be added to the bundle
    */
  def insert(
      user: User,
      name: String,
      primaryId: Option[Long],
      taskIds: List[Long],
      verifyTasks: (List[Task]) => Unit
  ): TaskBundle = {
    this.withMRTransaction { implicit c =>
      val lockedTasks = this.withListLocking(user, Some(TaskType())) { () =>
        this.taskDAL.retrieveListById(-1, 0)(taskIds)
      }

      val failedTaskIds = taskIds.diff(lockedTasks.map(_.id))
      if (failedTaskIds.nonEmpty) {
        throw new InvalidException(
          s"Bundle creation failed because the following task IDs were locked: ${failedTaskIds.mkString(", ")}"
        )
      }

      verifyTasks(lockedTasks)

      val rowId =
        SQL"""INSERT INTO bundles (owner_id, name) VALUES (${user.id}, ${name})""".executeInsert()

      rowId match {
        case Some(bundleId: Long) =>
          // Insert tasks into the task_bundles table
          val sqlInsertTaskBundles =
            s"""INSERT INTO task_bundles (task_id, bundle_id) VALUES ({taskId}, $bundleId)"""

          // Update tasks in the tasks table
          SQL(s"""UPDATE tasks SET bundle_id = $bundleId
        WHERE id IN ({inList})""")
            .on(
              "inList" -> taskIds
            )
            .executeUpdate()
          primaryId match {
            case Some(id) =>
              val sqlQuery = s"""UPDATE tasks SET is_bundle_primary = true WHERE id = $id"""
              SQL(sqlQuery).executeUpdate()
            case None =>
              // Handle the case where primaryId is None
              println("primaryId is not defined")
          }

          val parameters = lockedTasks.map(task => Seq[NamedParameter]("taskId" -> task.id))
          BatchSql(sqlInsertTaskBundles, parameters.head, parameters.tail: _*).execute()

          // Lock each of the new tasks to indicate they are part of the bundle
          for (task <- lockedTasks) {
            try {
              this.lockItem(user, task)
            } catch {
              case e: Exception => this.logger.warn(e.getMessage)
            }
            this.cacheManager.withOptionCaching { () =>
              Some(
                task.copy(
                  bundleId = Some(bundleId),
                  isBundlePrimary = Some(primaryId == task.id)
                )
              )
            }
          }

          TaskBundle(bundleId, user.id, lockedTasks.map(task => {
            task.id
          }), Some(lockedTasks))

        case None =>
          throw new Exception("Bundle creation failed")
      }
    }
  }

  /**
    *  Resets the bundle to the tasks provided, and unlock all tasks removed from current bundle
    *
    * @param bundleId The id of the bundle
    * @param taskIds The task ids the bundle will reset to
    * @param primaryTaskId The primary task id of the bundle
    */
  def resetTaskBundle(
      user: User,
      bundleId: Long,
      primaryTaskId: Long,
      taskIds: List[Long]
  ): Unit = {
    withMRTransaction { implicit c =>
      // Remove tasks from the bundle join table and unlock them if necessary
      val tasks =
        this.retrieveTasks(
          Query.simple(List(BaseParameter("bundle_id", bundleId, table = Some("tb"))))
        )

      // Unset bundle_id for tasks not in the taskids param(this param is the task ids that the bundle will be "resetting" to)
      SQL(
        """UPDATE tasks 
             SET bundle_id = NULL
             WHERE bundle_id = {bundleId} AND id NOT IN ({taskIds})
          """
      ).on(
          "bundleId" -> bundleId,
          "taskIds"  -> taskIds
        )
        .executeUpdate()

      for (task <- tasks) {
        if (!task.isBundlePrimary.getOrElse(false) && !taskIds.contains(task.id)) {
          SQL(
            s"""DELETE FROM task_bundles WHERE bundle_id = $bundleId AND task_id = ${task.id}"""
          ).executeUpdate()
          try {
            this.unlockItem(user, task)
          } catch {
            case e: Exception => logger.warn(e.getMessage)
          }
          this.cacheManager.withOptionCaching { () =>
            Some(
              task.copy(bundleId = None, isBundlePrimary = None)
            )
          }
        }
      }

      // Add tasks to the bundle and lock them
      val existingTasks = SQL(
        """SELECT task_id FROM task_bundles WHERE bundle_id = {bundleId}"""
      ).on(
          "bundleId" -> bundleId
        )
        .as(SqlParser.long("task_id").*)

      // Construct the SQL query to insert task-bundle associations
      val sqlQuery =
        s"""INSERT INTO task_bundles (bundle_id, task_id) VALUES ($bundleId, {taskId})"""

      val tasksToAdd = taskIds.filterNot(existingTasks.contains)

      if (tasksToAdd.nonEmpty) {
        // Construct parameters for the BatchSql operation
        val parameters = tasksToAdd.map(taskId => Seq[NamedParameter]("taskId" -> taskId))

        // Execute the BatchSql operation to insert task-bundle associations
        BatchSql(sqlQuery, parameters.head, parameters.tail: _*).execute()

        SQL(s"""UPDATE tasks SET bundle_id = {bundleId}
            WHERE bundle_id IS NULL
            AND id IN ({inList})""")
          .on(
            "bundleId" -> bundleId,
            "inList"   -> tasksToAdd
          )
          .executeUpdate()

        // Define the fallback value
        val fallbackStatus: Int = 0

        // Retrieve the status of the primary task
        val primaryTaskStatus: Int = SQL(
          """SELECT status FROM tasks WHERE id = {primaryTaskId}"""
        ).on(
            "primaryTaskId" -> primaryTaskId
          )
          .as(scalar[Int].singleOpt)
          .getOrElse(fallbackStatus)

        // Define the fallback value for review status
        val fallbackReviewStatus: Int = 0

        // Retrieve the review status of the primary task
        val primaryTaskReviewStatus: Int = SQL(
          """SELECT review_status FROM task_review WHERE id = {primaryTaskId}"""
        ).on(
            "primaryTaskId" -> primaryTaskId
          )
          .as(scalar[Int].singleOpt)
          .getOrElse(fallbackReviewStatus)

        val lockedTasks = this.withListLocking(user, Some(TaskType())) { () =>
          this.taskDAL.retrieveListById(-1, 0)(tasksToAdd)
        }
        lockedTasks.foreach { task =>
          try {
            this.lockItem(user, task)
          } catch {
            case e: Exception => this.logger.warn(e.getMessage)
          }
          // Update the status of all tasks in the bundle to match the status of the primary task
          SQL(
            """UPDATE tasks 
             SET status = {primaryTaskStatus} 
             WHERE id = {taskId}
          """
          ).on(
              "primaryTaskStatus" -> primaryTaskStatus,
              "taskId"            -> task.id
            )
            .executeUpdate()

          SQL"""INSERT INTO task_review (task_id, review_status) 
                VALUES (${task.id}, ${primaryTaskReviewStatus})""".executeUpdate()

          this.cacheManager.withOptionCaching { () =>
            Some(
              task.copy(
                bundleId = Some(bundleId),
                status = Some(primaryTaskStatus),
                isBundlePrimary = Some(primaryTaskId == task.id)
              )
            )
          }
        }
      }
    }
  }

  /**
    * Removes tasks from a bundle.
    *
    * @param bundleId The id of the bundle
    */
  def unbundleTasks(
      user: User,
      bundleId: Long,
      taskIds: List[Long],
      preventTaskIdUnlocks: List[Long]
  ): Unit = {
    this.withMRConnection { implicit c =>
      // Unset any bundle_id on individual tasks (this is set when task is completed)
      SQL(s"""UPDATE tasks SET bundle_id = NULL
              WHERE bundle_id = {bundleId}
              AND (is_bundle_primary != true OR is_bundle_primary is NULL)
              AND id IN ({inList})""")
        .on(
          Symbol("bundleId") -> bundleId,
          Symbol("inList")   -> ToParameterValue.apply[List[Long]].apply(taskIds)
        )
        .executeUpdate()

      // Remove task from bundle join table.
      val tasks = this.retrieveTasks(
        Query.simple(
          List(
            BaseParameter("bundle_id", bundleId, table = Some("tb"))
          )
        )
      )

      for (task <- tasks) {
        if (!task.isBundlePrimary.getOrElse(false)) {
          taskIds.find(id => id == task.id) match {
            case Some(_) =>
              SQL(s"""DELETE FROM task_bundles
                      WHERE bundle_id = $bundleId AND task_id = ${task.id}""").executeUpdate()
              this.cacheManager.withOptionCaching { () =>
                Some(
                  task.copy(bundleId = None, status = Some(STATUS_CREATED))
                )
              }
              if (!preventTaskIdUnlocks.contains(task.id)) {
                try {
                  this.unlockItem(user, task)
                } catch {
                  case e: Exception => this.logger.warn(e.getMessage)
                }
              }
              // This is in order to pass the filters so the task is displayed as "available" in task searching and maps.
              SQL(s"DELETE FROM task_review tr WHERE tr.task_id = ${task.id}").executeUpdate()

              SQL(
                """UPDATE tasks 
                      SET status = {status} 
                      WHERE id = {taskId}
                  """
              ).on(
                  "taskId" -> task.id,
                  "status" -> STATUS_CREATED
                )
                .executeUpdate()

            case None => // do nothing
          }
        }
      }
    }
  }

  /**
    * Deletes a task bundle.
    *
    * @param bundleId The id of the bundle
    */
  def deleteTaskBundle(user: User, bundle: TaskBundle, primaryTaskId: Option[Long] = None): Unit = {
    this.withMRConnection { implicit c =>
      SQL(
        """UPDATE tasks 
     SET bundle_id = NULL, 
         is_bundle_primary = NULL 
     WHERE bundle_id = {bundleId} OR id = {primaryTaskId}"""
      ).on(
          Symbol("bundleId")      -> bundle.bundleId,
          Symbol("primaryTaskId") -> primaryTaskId
        )
        .executeUpdate()

      val inList = bundle.tasks.getOrElse(Nil).map(_.id).mkString(",")
      SQL(s"UPDATE tasks SET bundle_id = NULL WHERE id IN ($inList)").executeUpdate()

      if (primaryTaskId != None) {
        // unlock tasks (everything but the primary task id)
        val tasks = bundle.tasks match {
          case Some(t) =>
            for (task <- t) {
              if (task.id != primaryTaskId.getOrElse(0)) {
                try {
                  this.unlockItem(user, task)
                } catch {
                  case e: Exception => this.logger.warn(e.getMessage)
                }
              }
              this.cacheManager.withOptionCaching { () =>
                Some(
                  task.copy(
                    bundleId = None,
                    isBundlePrimary = None
                  )
                )
              }
            }
          case None => // no tasks in bundle
        }
      }

      // Delete from task_bundles which will also cascade delete from bundles
      SQL("DELETE FROM task_bundles WHERE bundle_id = {bundleId}")
        .on(Symbol("bundleId") -> bundle.bundleId)
        .executeUpdate()
    }
  }

  /**
    * Fetches the owner of the given TaskBundle with the given bundle id.
    *
    * @param bundleId The id of the bundle
    */
  def retrieveOwner(query: Query): Option[Long] = {
    this.withMRTransaction { implicit c =>
      query
        .build(
          "SELECT distinct owner_id from task_bundles tb " +
            "INNER JOIN bundles ON bundles.id = tb.bundle_id"
        )
        .as(SqlParser.long("owner_id").singleOpt)
    }
  }

  /**
    * Fetches a list of tasks associated with the given bundle id.
    *
    * @param bundleId The id of the bundle
    */
  def retrieveTasks(query: Query): List[Task] = {
    this.withMRConnection { implicit c =>
      query.build(s"""SELECT ${this.retrieveColumnsWithReview} FROM ${baseTable}
            INNER JOIN task_bundles tb on tasks.id = tb.task_id
            LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id
         """).as(this.getTaskParser(this.taskRepository.updateAndRetrieve).*)
    }
  }

  /**
    * Fetches a list of tasks associated with the given bundle id.
    *
    * @param bundleId The id of the bundle
    */
  def lockBundledTasks(user: User, tasks: List[Task]) = {
    this.withMRConnection { implicit c =>
      for (task <- tasks) {
        try {
          this.lockItem(user, task)
        } catch {
          case e: Exception => this.logger.warn(e.getMessage)
        }
      }
    }
  }
}

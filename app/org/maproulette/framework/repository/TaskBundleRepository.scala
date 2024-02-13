/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import org.slf4j.LoggerFactory

import anorm.ToParameterValue
import anorm._, postgresql._
import javax.inject.{Inject, Singleton}
import org.maproulette.exception.InvalidException
import org.maproulette.Config
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.BaseParameter
import org.maproulette.framework.model.{Task, TaskBundle, User}
import org.maproulette.framework.mixins.{TaskParserMixin, Locking}
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
  protected val logger           = LoggerFactory.getLogger(this.getClass)
  implicit val baseTable: String = Task.TABLE
  val cacheManager               = this.taskRepository.cacheManager

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

      for (task <- lockedTasks) {
        try {
          this.lockItem(user, task)
        } catch {
          case e: Exception => this.logger.warn(e.getMessage)
        }
      }

      val rowId =
        SQL"""INSERT INTO bundles (owner_id, name) VALUES (${user.id}, ${name})""".executeInsert()
      rowId match {
        case Some(bundleId) =>
          val sqlQuery =
            s"""INSERT INTO task_bundles (task_id, bundle_id) VALUES ({taskId}, $bundleId)"""
          val parameters = lockedTasks.map(task => {
            Seq[NamedParameter]("taskId" -> task.id)
          })
          BatchSql(sqlQuery, parameters.head, parameters.tail: _*).execute()
          TaskBundle(bundleId, user.id, lockedTasks.map(task => {
            task.id
          }), Some(lockedTasks))

        case None =>
          throw new Exception("Bundle creation failed")
      }
    }
  }

  /**
    * Removes tasks from a bundle.
    *
    * @param bundleId The id of the bundle
    */
  def unbundleTasks(user: User, bundleId: Long, taskIds: List[Long])(): Unit = {
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
                      WHERE bundle_id = ${bundleId} AND task_id = ${task.id}""").executeUpdate()

              try {
                this.unlockItem(user, task)
              } catch {
                case e: Exception => this.logger.warn(e.getMessage)
              }
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
            }
          case None => // no tasks in bundle
        }
      }

      // Update cache for each task in the bundle
      bundle.tasks match {
        case Some(t) =>
          for (task <- t) {
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
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}

import org.maproulette.exception.{InvalidException, NotFoundException}
import org.maproulette.framework.model._
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.BaseParameter
import org.maproulette.framework.repository.TaskBundleRepository
import org.maproulette.framework.service.TaskService
import org.maproulette.permissions.Permission

// deprecated and to be removed after conversion
import org.maproulette.models.dal.ChallengeDAL

/**
  * Service layer for TaskBundle
  *
  * @author krotstan
  */
@Singleton
class TaskBundleService @Inject() (
    repository: TaskBundleRepository,
    taskService: TaskService,
    permission: Permission,
    challengeDAL: ChallengeDAL
) {

  /**
    * Creates a new task bundle with the given tasks, assigning ownership of
    * the bundle to the given user
    *
    * @param user    The user who is to own the bundle
    * @param name    The name of the task bundle
    * @param taskIds The tasks to be added to the bundle
    */
  def createTaskBundle(
      user: User,
      name: String,
      bundlePrimary: Option[Long],
      taskIds: List[Long]
  ): TaskBundle = {

    this.repository.insert(
      user,
      name,
      bundlePrimary,
      taskIds,
      (tasks: List[Task]) => {
        if (tasks.length < 1) {
          throw new InvalidException("Must be at least one task to bundle.")
        }

        val challengeId     = tasks.head.parent
        val cooperativeWork = tasks.head.cooperativeWork.isDefined

        // Verify tasks
        // 1. Must belong to same challenge
        // 2. Must be same task type as main task
        for (task <- tasks) {
          if (cooperativeWork && task.cooperativeWork.isDefined != cooperativeWork) {
            throw new InvalidException(
              "The main task type is Cooperative. All selected tasks must be Cooperative."
            )
          }
          if (!cooperativeWork && task.cooperativeWork.isDefined != cooperativeWork) {
            throw new InvalidException(
              "The main task type is not Cooperative. All selected tasks must not be Cooperative."
            )
          }
          if (task.parent != challengeId) {
            throw new InvalidException(
              "All tasks in the bundle must be part of the same challenge."
            )
          }
          if (task.bundleId.isDefined) {
            throw new InvalidException(
              "Task " + task.id + " already assigned to bundle: " +
                task.bundleId.getOrElse("") + "."
            )
          }
        }
      }
    )
  }

  /**
    *  Resets the bundle to the tasks provided, and unlock all tasks removed from current bundle
    *
    * @param bundleId The id of the bundle
    * @param taskIds The task ids the bundle will reset to
    */
  def resetTaskBundle(
      user: User,
      bundleId: Long,
      taskIds: List[Long]
  ): TaskBundle = {
    val bundle = this.getTaskBundle(user, bundleId)

    if (!permission.isSuperUser(user) && bundle.ownerId != user.id) {
      throw new IllegalAccessException(
        "Only a super user or the original user can reset this bundle."
      )
    }

    this.repository.resetTaskBundle(user, bundleId, taskIds)
    this.getTaskBundle(user, bundleId)
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
  )(): TaskBundle = {
    val bundle = this.getTaskBundle(user, bundleId)

    // Verify permissions to modify this bundle
    if (!permission.isSuperUser(user) && bundle.ownerId != user.id) {
      throw new IllegalAccessException(
        "Only a super user or the original user can delete this bundle."
      )
    }

    this.repository.unbundleTasks(user, bundleId, taskIds, preventTaskIdUnlocks)
    this.getTaskBundle(user, bundleId)
  }

  /**
    * Deletes a task bundle.
    *
    * @param bundleId The id of the bundle
    */
  def deleteTaskBundle(user: User, bundleId: Long): Unit = {
    val bundle = this.getTaskBundle(user, bundleId)

    // Verify permissions to delete this bundle
    if (!permission.isSuperUser(user) && bundle.ownerId != user.id) {
      val challengeId = bundle.tasks.getOrElse(List()).head.parent
      val challenge   = this.challengeDAL.retrieveById(challengeId)
      this.permission.hasObjectWriteAccess(challenge.get, user)
    }

    this.repository.deleteTaskBundle(user, bundle.bundleId)
  }

  /**
    * Fetches a TaskBundle with the given bundle id.
    *
    * @param bundleId The id of the bundle
    */
  def getTaskBundle(user: User, bundleId: Long, lockTasks: Boolean = false): TaskBundle = {
    val filterQuery =
      Query.simple(
        List(
          BaseParameter("bundle_id", bundleId, table = Some("tb"))
        )
      )

    val ownerId = this.repository.retrieveOwner(filterQuery)
    val tasks   = this.repository.retrieveTasks(filterQuery)

    if (ownerId.isEmpty) {
      throw new NotFoundException(s"Task Bundle not found with id $bundleId.")
    }
    if (lockTasks) {
      this.repository.lockBundledTasks(user, tasks)
    }

    TaskBundle(bundleId, ownerId.get, tasks.map(_.id), Some(tasks))
  }
}

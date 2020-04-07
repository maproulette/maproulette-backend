/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import java.net.URLDecoder

import javax.inject.{Inject, Singleton}
import org.apache.commons.lang3.StringUtils
import org.maproulette.exception.{InvalidException, NotFoundException}
import org.maproulette.framework.model.{Comment, User}
import org.maproulette.framework.psql.filter._
import org.maproulette.framework.psql.{OR, Order, Paging, Query}
import org.maproulette.framework.repository.CommentRepository
import org.maproulette.models.TaskBundle
import org.maproulette.models.dal.{NotificationDAL, TaskBundleDAL, TaskDAL}
import org.maproulette.permissions.Permission

/**
  * Service too handle all the requests to the comment. This injects both the NotificationDAL and
  * TaskDAL, this is because things will slowly be moved over to the new framework, piece by piece.
  * Until then certain services will have to rely on deprecated DAL's.
  *
  * @author mcuthbert
  */
@Singleton
class CommentService @Inject() (
    repository: CommentRepository,
    permission: Permission,
    notificationDAL: NotificationDAL,
    taskDAL: TaskDAL,
    taskBundleDAL: TaskBundleDAL
) extends ServiceMixin[Comment] {

  /**
    * Updates the comment
    *
    * @param id The id of the comment
    * @param updatedComment The updated comment
    * @param user The user executing the request
    * @return The newly updated comment
    */
  def update(id: Long, updatedComment: String, user: User): Comment = {
    if (StringUtils.isEmpty(updatedComment)) {
      throw new InvalidException("Invalid empty string supplied.")
    }
    // first get the comment
    this.retrieve(id) match {
      case Some(original) =>
        if (!user.isSuperUser && original.osm_id != user.osmProfile.id) {
          throw new IllegalAccessException(
            "User updating the comment must be a Super user or the original user who made the comment"
          )
        }
        this.repository.update(id, updatedComment)
        original.copy(comment = updatedComment)
      case None => throw new NotFoundException("Original comment does not exist")
    }
  }

  /**
    * Retrieves a single comment from the database based on an Id
    *
    * @param id The id for the comment
    */
  def retrieve(id: Long): Option[Comment] = {
    this.repository
      .query(
        Query.simple(
          List(BaseParameter(s"task_comments.${Comment.FIELD_ID}", id))
        )
      )
      .headOption
  }

  /**
    * Deletes a comment from the database
    *
    * @param commentId The identifier of the comment
    * @param taskId The identifier of the task parent
    * @param user The user deleting the comment
    * @return Boolean if delete was successful
    */
  def delete(commentId: Long, taskId: Long, user: User): Boolean = {
    this.taskDAL.retrieveById(taskId) match {
      case Some(task) =>
        this.permission.hasObjectAdminAccess(task, user)
        this.repository.delete(commentId)
      case None => throw new NotFoundException("Task was not found.")
    }
  }

  /**
    * Add a comment to each task in the bundle
    *
    * @param user The user adding the comments
    * @param bundleId The id of the bundle
    * @param comment The comment to add
    * @param actionId If there is an action associated with it
    * @return
    */
  def addToBundle(
      user: User,
      bundleId: Long,
      comment: String,
      actionId: Option[Long]
  ): TaskBundle = {
    val bundle = this.taskBundleDAL.getTaskBundle(user, bundleId)
    val tasks = bundle.tasks match {
      case Some(t) => t
      case None    => throw new InvalidException("No tasks found in this bundle.")
    }

    for (task <- tasks) {
      this.create(user, task.id, URLDecoder.decode(comment, "UTF-8"), actionId)
    }
    bundle
  }

  /**
    * Adds a comment to a task
    *
    * @param user The user adding the comment
    * @param taskId The id of the task that the user is adding the comment too
    * @param comment The actual comment being added
    * @param actionId If there is any actions associated with this add
    * @return The newly created comment object
    */
  def create(user: User, taskId: Long, comment: String, actionId: Option[Long]): Comment = {
    val task = this.taskDAL.retrieveById(taskId) match {
      case Some(t) => t
      case None =>
        throw new NotFoundException(s"Task with $taskId not found, can not add comment.")
    }
    if (StringUtils.isEmpty(comment)) {
      throw new InvalidException("Invalid empty string supplied. Comment could not be created.")
    }
    val newComment = this.repository.create(user, task.id, comment, actionId)
    this.notificationDAL.createMentionNotifications(user, newComment, task)
    newComment
  }

  /**
    * Retrieves the comments based on the input criteria
    *
    * @param projectIdList Filter by any projects in the project id list
    * @param challengeIdList Filter by any challenges in the challenge id list
    * @param taskIdList Filter by any tasks in the task id list
    * @param paging paging object to handle paging in response
    * @return A list of comments
    */
  def find(
      projectIdList: List[Long],
      challengeIdList: List[Long],
      taskIdList: List[Long],
      paging: Paging = Paging()
  ): List[Comment] = {
    val filter = Filter.simple(
      List(
        FilterParameter.conditional(
          Comment.FIELD_PROJECT_ID,
          projectIdList,
          Operator.IN,
          includeOnlyIfTrue = projectIdList.nonEmpty
        ),
        ConditionalFilterParameter(
          CustomParameter(
            s"""1 IN (SELECT 1 FROM unnest(ARRAY[${projectIdList.mkString(",")}]) AS pIds
                         WHERE pIds IN (SELECT vp.project_id FROM virtual_project_challenges vp
                                        WHERE vp.challenge_id = task_comments.challenge_id))"""
          ),
          projectIdList.nonEmpty
        ),
        FilterParameter.conditional(
          Comment.FIELD_PROJECT_ID,
          projectIdList,
          Operator.IN,
          includeOnlyIfTrue = projectIdList.nonEmpty
        ),
        FilterParameter.conditional(
          Comment.FIELD_CHALLENGE_ID,
          challengeIdList,
          Operator.IN,
          includeOnlyIfTrue = challengeIdList.nonEmpty
        ),
        FilterParameter.conditional(
          Comment.FIELD_TASK_ID,
          taskIdList,
          Operator.IN,
          includeOnlyIfTrue = taskIdList.nonEmpty
        )
      ),
      OR()
    )

    val query = Query(
      filter,
      paging = paging,
      order = Order(
        List(
          Comment.FIELD_PROJECT_ID,
          Comment.FIELD_CHALLENGE_ID,
          s"task_comments.${Comment.FIELD_CREATED}"
        ),
        Order.DESC
      )
    )
    this.query(query)
  }

  override def query(query: Query): List[Comment] = this.repository.query(query)
}

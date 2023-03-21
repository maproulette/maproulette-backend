/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection

import anorm.SqlParser.{get, long}
import anorm._
import javax.inject.Inject
import org.joda.time.DateTime
import org.maproulette.framework.model.{Comment, User}
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.BaseParameter
import play.api.db.Database

/**
  * Repository to handle all the database queries for the Comment object
  *
  * @author mcuthbert
  */
class CommentRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = Comment.TABLE

  /**
    * Query function that allows a user to build their own query against the Comment table
    *
    * @param query The query to execute
    * @param c An implicit connection
    * @return A list of returned Comments
    */
  def query(query: Query)(implicit c: Option[Connection] = None): List[Comment] = {
    withMRConnection { implicit c =>
      query
        .build(s"""SELECT * FROM task_comments
          INNER JOIN users ON users.osm_id = task_comments.osm_id""")
        .as(CommentRepository.parser.*)
    }
  }

  /**
    * query function that fetches comments by user id
    *
    * @param userId The id of the user
    * @return A list of returned Comments
    */
  def queryByUserId(userId: Long)(implicit c: Option[Connection] = None): List[Comment] = {
    withMRConnection { implicit c =>
      val query =
        s"""
           |SELECT c.id, c.project_id, c.challenge_id, c.task_id, c.created, c.action_id, c.comment, u.name, u.avatar_url, c.osm_id FROM TASK_COMMENTS c
           |inner join users as u on c.osm_id = u.osm_id
           |WHERE u.id = ${userId}
       """.stripMargin
      SQL(query)
        .as(CommentRepository.parser.*)
    }
  }

  /**
    * Add comment to a task
    *
    * @param user     The user adding the comment
    * @param taskId     Id of the task that is having the comment added to
    * @param comment  The actual comment
    * @param actionId the id for the action if any action associated
    * @param c        Implicit provided optional connection
    */
  def create(user: User, taskId: Long, comment: String, actionId: Option[Long])(
      implicit c: Option[Connection] = None
  ): Comment = {
    this.withMRTransaction { implicit c =>
      val query =
        s"""
           |INSERT INTO task_comments (osm_id, task_id, comment, action_id)
           |VALUES ({osm_id}, {task_id}, {comment}, {action_id})
           |RETURNING id, project_id, challenge_id, created
         """.stripMargin
      SQL(query)
        .on(
          Symbol("osm_id")    -> user.osmProfile.id,
          Symbol("task_id")   -> taskId,
          Symbol("comment")   -> comment,
          Symbol("action_id") -> actionId
        )
        .as((long("id") ~ long("project_id") ~ long("challenge_id") ~ get[DateTime]("created") map {
          case id ~ projectId ~ challengeId ~ created =>
            Comment(
              id,
              user.osmProfile.id,
              user.osmProfile.displayName,
              user.osmProfile.avatarURL,
              taskId,
              challengeId,
              projectId,
              created,
              comment,
              actionId
            )
        }).single)
    }
  }

  /**
    * Retrieves a specific comment
    *
    * @param id The id for the comment
    * @param c         Implicit provided optional connection
    * @return An optional comment
    */
  def retrieve(id: Long)(implicit c: Option[Connection] = None): Option[Comment] = {
    this.withMRConnection { implicit c =>
      Query
        .simple(List(BaseParameter("id", id)))
        .build("""SELECT * FROM task_comments
              INNER JOIN users ON users.osm_id = task_comments.osm_id""")
        .as(CommentRepository.parser.*)
        .headOption
    }
  }

  /**
    * Updates a comment that a user previously set
    *
    * @param id      The id for the original comment
    * @param updatedComment The new comment
    * @param c              Implicit provided optional connection
    * @return The updated comment
    */
  def update(id: Long, updatedComment: String)(
      implicit c: Option[Connection] = None
  ): Boolean = {
    withMRTransaction { implicit c =>
      SQL("UPDATE task_comments SET comment = {comment} WHERE id = {id} RETURNING *")
        .on(Symbol("comment") -> updatedComment, Symbol("id") -> id)
        .execute()
    }
  }

  /**
    * Deletes a comment from the database
    *
    * @param commentId The id for the comment being deleted
    * @param c         Implicit provided optional connection
    */
  def delete(commentId: Long)(
      implicit c: Option[Connection] = None
  ): Boolean = {
    withMRConnection { implicit c =>
      Query
        .simple(List(BaseParameter("id", commentId)))
        .build("DELETE FROM task_comments")
        .execute()
    }
  }
}

object CommentRepository {
  val parser: RowParser[Comment] = Macro.parser[Comment](
    "task_comments.id",
    "task_comments.osm_id",
    "users.name",
    "users.avatar_url",
    "task_comments.task_id",
    "task_comments.challenge_id",
    "task_comments.project_id",
    "task_comments.created",
    "task_comments.comment",
    "task_comments.action_id"
  )
}

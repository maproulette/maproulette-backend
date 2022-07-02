/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import org.maproulette.framework.model.{Comment, User}
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.{BaseParameter, Operator}
import org.maproulette.framework.service.CommentService
import org.maproulette.framework.util.{CommentRepoTag, FrameworkHelper}
import play.api.Application

/**
  * @author mcuthbert
  */
class CommentRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  val commentRepository: CommentRepository =
    this.application.injector.instanceOf(classOf[CommentRepository])
  val commentService: CommentService = this.application.injector.instanceOf(classOf[CommentService])

  "CommentRepository" should {
    "add comment into database" taggedAs CommentRepoTag in {
      cancel // TODO(ljdelight): This test needs to be fixed.
      val comment =
        this.commentRepository.create(
          User.superUser,
          defaultTask.id,
          "Adding comment into database",
          None
        )

      comment.osm_id mustEqual User.superUser.osmProfile.id
      comment.taskId mustEqual defaultTask.id
      comment.challengeId mustEqual defaultChallenge.id
      comment.projectId mustEqual defaultProject.id
      comment.actionId.isEmpty mustEqual true

      val retrievedComment = this.repositoryGet(comment.id).get
      comment mustEqual retrievedComment
    }

    "update comment in the database" taggedAs CommentRepoTag in {
      cancel // TODO(ljdelight): This test needs to be fixed.
      val comment = this.commentRepository.create(
        User.superUser,
        defaultTask.id,
        "update comment in the database",
        None
      )
      val retrievedComment = this.repositoryGet(comment.id).get
      retrievedComment.comment mustEqual "update comment in the database"
      this.commentRepository.update(retrievedComment.id, "Test Update")
      val updatedComment = this.commentService.retrieve(comment.id).get
      updatedComment.comment mustEqual "Test Update"
    }

    "delete a comment in the database" taggedAs CommentRepoTag in {
      cancel // TODO(ljdelight): This test needs to be fixed.
      val comment = this.commentRepository.create(
        User.superUser,
        defaultTask.id,
        "delete a comment in the database",
        None
      )
      val retrievedComment = this.repositoryGet(comment.id)
      retrievedComment.isDefined mustEqual true
      this.commentRepository.delete(retrievedComment.get.id)
      val deletedComment = this.repositoryGet(comment.id)
      deletedComment.isEmpty mustEqual true
    }

    "find a specific comment" taggedAs CommentRepoTag in {
      cancel // TODO(ljdelight): This test needs to be fixed.
      this.commentRepository.create(User.superUser, defaultTask.id, "find a specific comment", None)
      val comment =
        this.commentRepository.create(
          User.superUser,
          defaultTask.id,
          "find a specific comment 2",
          None
        )
      val comments = this.commentRepository
        .query(Query.simple(List(BaseParameter("comment", "%2", Operator.LIKE))))
      comments.size mustEqual 1
      comments.head.id mustEqual comment.id
      comments.head.comment mustEqual "find a specific comment 2"
    }
  }

  override implicit val projectTestName: String = "CommentRepositorySpecProject"

  private def repositoryGet(id: Long): Option[Comment] = {
    this.commentRepository
      .query(
        Query.simple(
          List(BaseParameter(Comment.FIELD_ID, id))
        )
      )
      .headOption
  }
}

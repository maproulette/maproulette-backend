/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import org.maproulette.exception.{InvalidException, NotFoundException}
import org.maproulette.framework.model.User
import org.maproulette.framework.util.{CommentTag, FrameworkHelper}
import play.api.Application

/**
  * @author mcuthbert
  */
class CommentServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val commentService: CommentService = this.application.injector.instanceOf(classOf[CommentService])

  "CommentService" should {
    "add comment into database" taggedAs CommentTag in {
      cancel() // TODO(ljdelight): This test needs to be fixed.
      val comment          = this.commentService.create(User.superUser, defaultTask.id, "GP Add", None)
      val retrievedComment = this.commentService.retrieve(comment.id)
      retrievedComment.get mustEqual comment
    }

    "update a comment in the database" taggedAs CommentTag in {
      cancel() // TODO(ljdelight): This test needs to be fixed.
      val comment = this.commentService.create(User.superUser, defaultTask.id, "GP update", None)
      this.commentService.update(comment.id, "GP update Test", User.superUser)
      val retrievedComment = this.commentService.retrieve(comment.id)
      retrievedComment.get.comment mustEqual "GP update Test"
    }

    "delete a comment in the database" taggedAs CommentTag in {
      val comment = this.commentService.create(User.superUser, defaultTask.id, "GP delete", None)
      this.commentService.delete(comment.taskId, comment.id, User.superUser)
      this.commentService.retrieve(comment.id).isEmpty mustEqual true
    }

    "Fail on trying to delete a comment with no associated task" taggedAs CommentTag in {
      intercept[NotFoundException] {
        val comment =
          this.commentService.create(User.superUser, defaultTask.id, "GP delete attempt", None)
        this.commentService.delete(12355, comment.id, User.superUser)
      }
    }

    "Fail on trying to update a comment that doesn't exist" taggedAs CommentTag in {
      intercept[NotFoundException] {
        this.commentService.update(894, "UpdateTest", User.superUser)
      }
    }

    "Fail on update when empty string provided" taggedAs CommentTag in {
      intercept[InvalidException] {
        this.commentService.update(-1, "", User.superUser)
      }
    }

    "Fail on update when null string provided" taggedAs CommentTag in {
      intercept[InvalidException] {
        this.commentService.update(-1, null, User.superUser)
      }
    }

    "Only super user or original user can update comment" taggedAs CommentTag in {
      cancel() // TODO(ljdelight): This test needs to be fixed.
      intercept[IllegalAccessException] {
        val comment =
          this.commentService.create(User.superUser, defaultTask.id, "Default comment", None)
        this.commentService.update(comment.id, "New Commet", User.guestUser)
      }
    }

    "Find comments for a specific project, challenge and task" taggedAs CommentTag in {
      cancel() // TODO(ljdelight): This test needs to be fixed.
      val comment =
        this.commentService.create(User.superUser, defaultTask.id, "Default Comment", None)
      val projectComments =
        this.commentService.find(List(comment.projectId), List.empty, List.empty)
      projectComments.head mustEqual comment
      val challengeComments =
        this.commentService.find(List.empty, List(comment.challengeId), List.empty)
      challengeComments.head mustEqual comment
      val taskComments = this.commentService.find(List.empty, List.empty, List(comment.taskId))
      taskComments.head mustEqual comment
      val all = this.commentService
        .find(List(comment.projectId), List(comment.challengeId), List(comment.taskId))
      all.head mustEqual comment
    }
  }

  override implicit val projectTestName: String = "CommentServiceSpecProject"
}

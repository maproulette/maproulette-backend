/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection

import anorm.SQL
import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model.{Challenge, SavedTasks}
import org.maproulette.framework.psql.filter.{
  BaseParameter,
  FilterParameter,
  Operator,
  SubQueryFilter
}
import org.maproulette.framework.psql.{Order, Paging, Query, SQLUtils}
import org.maproulette.models.Task
import org.maproulette.models.dal.{ChallengeDAL, TaskDAL}
import play.api.db.Database

/**
  * Repository handling all the querying that is done to save projects, challenges and tasks to a
  * users profile
  *
  * @author mcuthbert
  */
@Singleton
class UserSavedObjectsRepository @Inject() (
    override val db: Database,
    challengeDAL: ChallengeDAL,
    taskDAL: TaskDAL
) extends RepositoryMixin {

  /**
    * Retreives all the challenges saved to a user profile
    *
    * @param userId The id of the user
    * @param paging Usually not required but if we need to use paging
    * @param order Order should generally not be used, defaults to Created, but can modify if required
    * @param c An implicit connection, will create new if not set
    * @return A list of challenges
    */
  def getSavedChallenges(
      userId: Long,
      paging: Paging = Paging(),
      order: Order = Order(List("created"))
  )(implicit c: Option[Connection] = None): List[Challenge] = {
    this.withMRTransaction { implicit c =>
      val query =
        s"""
           |SELECT ${challengeDAL.retrieveColumns} FROM challenges
           |WHERE id IN (
           |  SELECT challenge_id FROM saved_challenges
           |  WHERE user_id = $userId
           |  ${order.sql()}
           |  ${paging.sql()}
           |)
       """.stripMargin
      SQL(query)
        .on(SQLUtils.buildNamedParameter("id", userId) :: paging.parameters(): _*)
        .as(challengeDAL.parser.*)
    }
  }

  /**
    * Saves the challenge for the user
    *
    * @param userId      The id of the user
    * @param challengeId the id of the challenge
    * @param c           The existing connection if any
    */
  def saveChallenge(userId: Long, challengeId: Long)(
      implicit c: Option[Connection] = None
  ): Unit = {
    this.withMRTransaction { implicit c =>
      SQL(
        s"""INSERT INTO saved_challenges (user_id, challenge_id)
           | VALUES ({uid}, {cid}) ON
           | CONFLICT (user_id, challenge_id) DO NOTHING""".stripMargin
      ).on(Symbol("uid") -> userId, Symbol("cid") -> challengeId).executeInsert()
    }
  }

  /**
    * Unsaves a challenge from the users profile
    *
    * @param userId      The id of the user that has previously saved the challenge
    * @param challengeId The id of the challenge to remove from the user profile
    * @param c           The existing connection if any
    */
  def unsaveChallenge(userId: Long, challengeId: Long)(
      implicit c: Option[Connection] = None
  ): Unit = {
    this.withMRTransaction { implicit c =>
      SQL(
        s"""DELETE FROM saved_challenges WHERE user_id = {uid} AND challenge_id = {cid}"""
      ).on(Symbol("uid") -> userId, Symbol("cid") -> challengeId).execute()
    }
  }

  /**
    * Gets the last X saved tasks for a user
    * TODO this function should eventually call into the TaskService and retrieve the tasks through the standard user service, and it should probably be called directly from the UserService, there is no reason why it should exist here.
    *
    * @param userId       The id of the user you are requesting the saved challenges for
    * @param challengeIds A sequence of challengeId to limit the response to a specific set of challenges
    * @param paging paging object to handle paging in response
    * @param c            The existing connection if any
    * @return a List of challenges
    */
  def getSavedTasks(
      userId: Long,
      challengeIds: Seq[Long] = Seq.empty,
      paging: Paging = Paging(),
      order: Order = Order(List("created"))
  )(implicit c: Option[Connection] = None): List[Task] = {
    this.withMRTransaction { implicit c =>
      Query
        .simple(
          List(
            SubQueryFilter(
              "tasks.id",
              Query
                .simple(
                  List(
                    BaseParameter(SavedTasks.FIELD_USER_ID, userId),
                    FilterParameter.conditional(
                      SavedTasks.FIELD_CHALLENGE_ID,
                      challengeIds,
                      Operator.IN,
                      includeOnlyIfTrue = challengeIds.nonEmpty
                    )
                  ),
                  base = "SELECT task_id FROM saved_tasks",
                  paging = paging,
                  order = order
                )
            )
          )
        )
        .build(s"""
          |SELECT ${taskDAL.retrieveColumnsWithReview} FROM tasks
          |LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id
          |""".stripMargin)
        .as(taskDAL.parser.*)
    }
  }

  /**
    * Saves the task for the user, will validate that the task actually exists first based on the
    * provided id
    *
    * @param userId The id of the user
    * @param taskId the id of the task
    * @param challengeId The id of the task parent, ie. a challenge
    * @param c      The existing connection if any
    */
  def saveTask(userId: Long, taskId: Long, challengeId: Long)(
      implicit c: Option[Connection] = None
  ): Unit = {
    this.withMRTransaction { implicit c =>
      SQL(
        s"""INSERT INTO saved_tasks (user_id, task_id, challenge_id)
               |VALUES ({uid}, {tid}, {cid})
               |ON CONFLICT(user_id, task_id) DO NOTHING""".stripMargin
      ).on(Symbol("uid") -> userId, Symbol("tid") -> taskId, Symbol("cid") -> challengeId)
        .executeInsert()
    }
  }

  /**
    * Unsaves a task from the users profile
    *
    * @param userId  The id of the user that has previously saved the challenge
    * @param taskId The id of the task to remove from the user profile
    * @param c       The existing connection if any
    */
  def unsaveTask(userId: Long, taskId: Long)(
      implicit c: Option[Connection] = None
  ): Boolean = {
    this.withMRTransaction { implicit c =>
      Query
        .simple(
          List(BaseParameter("user_id", userId), BaseParameter("task_id", taskId))
        )
        .build("DELETE FROM saved_tasks")
        .execute()
    }
  }
}

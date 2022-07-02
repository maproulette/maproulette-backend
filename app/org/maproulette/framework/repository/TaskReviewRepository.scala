/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection
import scala.concurrent.duration.FiniteDuration
import scala.collection.mutable

import anorm.SqlParser.get
import anorm.{ToParameterValue, SimpleSql, Row, SqlParser, RowParser, ~, SQL}
import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import org.maproulette.framework.model.{Task, TaskReview, TaskWithReview, User}
import org.maproulette.framework.psql.{Query, Grouping, Order, Paging}
import org.maproulette.framework.mixins.{Locking, TaskParserMixin}
import org.maproulette.framework.service.UserService
import org.maproulette.session.SearchParameters
import play.api.db.Database
import org.slf4j.LoggerFactory

/**
  * For TaskReview
  */
@Singleton
class TaskReviewRepository @Inject() (
    override val db: Database,
    taskRepository: TaskRepository,
    userService: UserService
) extends RepositoryMixin
    with Locking[Task]
    with TaskParserMixin {
  implicit val baseTable: String = TaskReview.TABLE
  protected val logger           = LoggerFactory.getLogger(this.getClass)

  val parser       = this.getTaskParser(this.taskRepository.updateAndRetrieve)
  val reviewParser = this.getTaskWithReviewParser(this.taskRepository.updateAndRetrieve)

  /**
    * Gets a Task object with review data
    *
    * @param taskId
    */
  def getTaskWithReview(taskId: Long): TaskWithReview = {
    this.withMRConnection { implicit c =>
      val query = Query.simple(List())
      query.build(s"""
        SELECT $retrieveColumnsWithReview,
               challenges.name as challenge_name,
               mappers.name as review_requested_by_username,
               reviewers.name as reviewed_by_username
        FROM tasks
        INNER JOIN task_review ON task_review.task_id = tasks.id
        LEFT OUTER JOIN users mappers ON task_review.review_requested_by = mappers.id
        LEFT OUTER JOIN users reviewers ON task_review.reviewed_by = reviewers.id
        INNER JOIN challenges ON challenges.id = tasks.parent_id
        WHERE tasks.id = {taskId}
      """).on(Symbol("taskId") -> taskId).as(this.reviewParser.single)
    }
  }

  /**
    * Claims a task for review.
    *
    * @param task Task
    * @param userId User claiming the task
    */
  def claimTaskReview(taskList: List[Task], user: User): Unit = {
    this.withMRTransaction { implicit c =>
      // Unclaim everything before starting a new task.
      Query
        .simple(List())
        .build(
          """UPDATE task_review SET review_claimed_by = NULL, review_claimed_at = NULL
              WHERE review_claimed_by = {userId}"""
        )
        .on(Symbol("userId") -> user.id)
        .executeUpdate()

      for (task <- taskList) {
        Query
          .simple(List())
          .build(
            """UPDATE task_review SET review_claimed_by = {userId}, review_claimed_at = NOW(), review_started_at = NOW()
                    WHERE task_id = {taskId} AND review_claimed_at IS NULL"""
          )
          .on(Symbol("taskId") -> task.id, Symbol("userId") -> user.id)
          .executeUpdate()

        val lockerId = this.lockItem(user, task)
        if (lockerId != user.id) {
          val lockHolder = this.userService.retrieve(lockerId) match {
            case Some(user) => user.osmProfile.displayName
            case None       => lockerId
          }
          throw new IllegalAccessException(s"Task is currently locked by user ${lockHolder}")
        }

        implicit val id = task.id
        this.taskRepository.cacheManager.withUpdatingCache(this.taskRepository.retrieve) {
          implicit cachedItem =>
            val result =
              Some(task.copy(review = task.review.copy(reviewClaimedBy = Option(user.id.toInt))))
            result
        }(task.id, true, true)
      }
    }
  }

  /**
    * Unclaims task reviews
    *
    * @param task
    * @param user
    */
  def unclaimTaskReview(task: Task, user: User): Unit = {
    this.withMRTransaction { implicit c =>
      val updatedRows =
        Query
          .simple(List())
          .build(
            """UPDATE task_review SET review_claimed_by = NULL, review_claimed_at = NULL
              WHERE task_review.task_id = {taskId}"""
          )
          .on(Symbol("taskId") -> task.id)
          .executeUpdate()

      // if returning 0, then this is because the item is locked by a different user
      if (updatedRows == 0) {
        throw new IllegalAccessException(
          s"This task is locked by another user, cannot cancel review at this time."
        )
      }

      try {
        this.unlockItem(user, task)
      } catch {
        case e: Exception => logger.warn(e.getMessage)
      }

      val updatedTask = task.copy(review = task.review.copy(reviewClaimedBy = None))
      this.taskRepository.cacheManager.withOptionCaching { () =>
        Some(updatedTask)
      }
    }
  }

  // We will try to use some of the known filters to reduce the amount of
  // rows being iterated over by using a WITH clause. Currently it checks
  // in this order:
  // 1. Mapper (owner)
  // 2. Reviewer
  // 3. ReviewStatus
  // 4. Default to ReviewStatus != 5 (unnecessary)
  private def buildWithTable(params: SearchParameters): String = {
    return ""
  }

  private def buildTaskQuery(
      query: Query,
      includeRowNumber: Boolean = false,
      params: Option[SearchParameters] = None
  ): SimpleSql[Row] = {
    val rowNumber = includeRowNumber match {
      case true  => s" ROW_NUMBER() OVER (${query.order.sql()}) as row_num, "
      case false => ""
    }

    val withTable =
      params match {
        case Some(searchParams) =>
          this.buildWithTable(searchParams)
        case None => ""
      }

    query.build(
      s"""
        ${withTable}
        SELECT ${rowNumber}
          tasks.${this.retrieveColumnsWithReview} FROM tasks
          INNER JOIN challenges c ON c.id = tasks.parent_id
          INNER JOIN task_review ON task_review.task_id = tasks.id
          INNER JOIN projects p ON p.id = c.parent_id
       """
    )
  }

  /**
    * Query Tasks
    *
    * @param query
    */
  def queryTasks(query: Query, params: SearchParameters): List[Task] = {
    this.taskRepository.cacheManager.withIDListCaching { implicit cachedItems =>
      this.withMRTransaction { implicit c =>
        this.buildTaskQuery(query, false, Some(params)).as(this.parser.*)
      }
    }
  }

  /**
    * Query tasks with row number
    *
    * @param query
    */
  def queryTasksWithRowNumber(query: Query, taskId: Long): Map[Long, Int] = {
    val rowMap = mutable.Map[Long, Int]()
    val rowNumParser: RowParser[Long] = {
      get[Int]("row_num") ~ get[Long]("tasks.id") map {
        case row ~ id => {
          rowMap.put(id, row)
          id
        }
      }
    }

    this.withMRTransaction { implicit c =>
      this.buildTaskQuery(query, true)
      val baseQuery =
        s"""
          SELECT ROW_NUMBER() OVER (${query.order.sql()}) as row_num,
            tasks.id FROM tasks
            INNER JOIN challenges c ON c.id = tasks.parent_id
            INNER JOIN task_review ON task_review.task_id = tasks.id
            INNER JOIN projects p ON p.id = c.parent_id
         """
      val parameters = query.parameters()
      val sql        = query.sqlWithBaseQuery(baseQuery, false, Grouping())

      SQL(
        s"SELECT row_num, tasks.id FROM (${sql}) tasks WHERE tasks.id = ${taskId}"
      ).on(parameters: _*).as(rowNumParser.*)
    }

    rowMap.toMap
  }

  /**
    * Get the full count of tasks returned by this query.
    *
    * @param query - Query object. Ordering/Paging are ignored
    */
  def queryTaskCount(query: Query, searchParams: SearchParameters): Int = {
    this.withMRTransaction { implicit c =>
      // Remove ordering and paging when querying task count.
      val simpleQuery = query.copy(order = Order(List()), paging = Paging())
      val withTable   = this.buildWithTable(searchParams)

      simpleQuery
        .build(
          s"""
          ${withTable}
          SELECT count(*) FROM tasks
            INNER JOIN challenges c ON c.id = tasks.parent_id
            INNER JOIN task_review ON task_review.task_id = tasks.id
            INNER JOIN projects p ON p.id = c.parent_id
        """
        )
        .as(SqlParser.int("count").single)
    }
  }

  /**
    * Queries tasks joining on the locked table
    *
    * @param query
    */
  def queryTasksWithLocked(query: Query): List[Task] = {
    this.withMRTransaction { implicit c =>
      query.build(s"""SELECT tasks.$retrieveColumnsWithReview FROM tasks
          INNER JOIN challenges c ON c.id = tasks.parent_id
          INNER JOIN task_review ON task_review.task_id = tasks.id
          INNER JOIN projects p ON p.id = c.parent_id
        """).as(this.parser.*)
    }
  }

  /**
    * Marks expired taskReviews as unnecessary.
    *
    * @param duration - age of task reviews to treat as 'expired'
    * @return The number of taskReviews that were expired
    */
  def expireTaskReviews(duration: FiniteDuration)(implicit c: Option[Connection] = None): Int = {
    this.withMRTransaction { implicit c =>
      val query = Query.simple(List())

      // Note in task review history we are moving these tasks to unnecessary
      query
        .build(s"""INSERT INTO task_review_history
                          (task_id, requested_by, reviewed_by,
                           review_status, reviewed_at, review_started_at)
                  SELECT tr.task_id, tr.review_requested_by, NULL,
                        ${Task.REVIEW_STATUS_UNNECESSARY}, NOW(), NULL
                  FROM task_review tr
                  WHERE tr.review_status = ${Task.REVIEW_STATUS_REQUESTED}
                    AND tr.task_id IN (
                      SELECT t.id FROM tasks t
                      WHERE AGE(NOW(), t.mapped_on) > {duration}::INTERVAL
                    )
              """)
        .on(
          Symbol("duration") -> ToParameterValue
            .apply[String]
            .apply(String.valueOf(duration))
        )
        .executeUpdate()

      // Update task review status on old task reviews to "unnecessary"
      query
        .build(s"""UPDATE task_review tr
                SET review_status = ${Task.REVIEW_STATUS_UNNECESSARY},
                    reviewed_at = NOW(),
                    review_started_at = NULL,
                    review_claimed_at = NULL,
                    review_claimed_by = NULL
                WHERE tr.review_status = ${Task.REVIEW_STATUS_REQUESTED}
                  AND tr.task_id IN (
                    SELECT t.id FROM tasks t
                    WHERE AGE(NOW(), t.mapped_on) > {duration}::INTERVAL
                  )""")
        .on(
          Symbol("duration") -> ToParameterValue
            .apply[String]
            .apply(String.valueOf(duration))
        )
        .executeUpdate()
    }
  }

  /**
    * Updates the task review table.
    */
  def updateTaskReview(
      user: User,
      task: Task,
      reviewStatus: Int,
      updateColumn: String,
      updateWithUser: Long,
      additionalReviewers: Option[List[Long]],
      metaReviewStatus: Option[Int] = None,
      errorTags: String = ""
  ): Int = {
    this.withMRTransaction { implicit c =>
      val metaReviewStatusUpdate = metaReviewStatus match {
        case Some(mr) if (mr == Task.REVIEW_STATUS_REQUESTED) =>
          s"meta_review_status = ${mr}, reviewed_at = NOW(), meta_review_started_at = task_review.review_claimed_at, "
        case Some(mr) =>
          s"meta_review_status = ${mr}, meta_reviewed_at = NOW(), meta_review_started_at = task_review.review_claimed_at, "
        case None => "reviewed_at = NOW(), "
      }
      var errorTagString = "";
      if (reviewStatus != Task.REVIEW_STATUS_REQUESTED) {
        errorTagString = s", error_tags = ${if (!errorTags.isEmpty) s"'${errorTags}'" else "NULL"}"
      }
      val updatedRows =
        SQL(s"""UPDATE task_review SET review_status = $reviewStatus,
                                 ${updateColumn} = ${updateWithUser},
                                 review_claimed_at = NULL,
                                 review_claimed_by = NULL,
                                 ${metaReviewStatusUpdate}
                                 additional_reviewers = ${additionalReviewers match {
          case Some(ar) => "ARRAY[" + ar.mkString(",") + "]"
          case None     => "NULL"
        }}${errorTagString}
                             WHERE task_review.task_id = (
                                SELECT tasks.id FROM tasks
                                LEFT JOIN locked l on l.item_id = tasks.id AND l.item_type = ${task.itemType.typeId}
                                WHERE tasks.id = ${task.id} AND (l.user_id = ${user.id} OR l.user_id IS NULL)
                              )""").executeUpdate()
      // if returning 0, then this is because the item is locked by a different user
      if (updatedRows == 0) {
        throw new IllegalAccessException(
          s"This task is locked by another user, cannot update review status at this time."
        )
      }

      // if you set the status successfully on a task you will lose the lock of that task
      try {
        this.unlockItem(user, task)
      } catch {
        case e: Exception => logger.warn(e.getMessage)
      }

      updatedRows
    }
  }

  /**
    * Inserts a row into the task review history table.
    */
  def insertTaskReviewHistory(
      task: Task,
      reviewRequestedBy: Long,
      reviewedBy: Long,
      originalReviewer: Option[Long] = None,
      reviewStatus: Int,
      reviewClaimedAt: DateTime,
      errorTags: String = ""
  ): Unit = {
    this.withMRTransaction { implicit c =>
      val sql = s"""INSERT INTO task_review_history
                        (task_id, requested_by, reviewed_by, review_status,
                         reviewed_at, review_started_at, original_reviewer, error_tags)
            VALUES (${task.id}, ${reviewRequestedBy}, ${reviewedBy},
                    $reviewStatus, NOW(),
                    ${if (reviewClaimedAt != null) s"'${reviewClaimedAt}'"
      else "NULL"},
                    ${if (originalReviewer == None) "NULL" else originalReviewer.get},
                     ${if (!errorTags.isEmpty) s"'${errorTags}'" else "NULL"})"""

      SQL(sql).executeUpdate()
    }
  }

  /**
    * Inserts a row into the task review history table.
    *
    * @param task
    * @param reviewedBy - leave blank if this is meta review, include if it's
    *                     the reviewer requesting another meta review
    * @param metaReviewedBy
    * @param metaReviewStatus
    * @param reviewClaimedAt
    * @param errorTags
    */
  def insertMetaTaskReviewHistory(
      task: Task,
      reviewedBy: Option[Long] = None,
      metaReviewedBy: Long,
      metaReviewStatus: Int,
      reviewClaimedAt: DateTime,
      errorTags: String = ""
  ): Unit = {
    this.withMRTransaction { implicit c =>
      SQL(s"""INSERT INTO task_review_history
                        (task_id, requested_by, reviewed_by, meta_reviewed_by, meta_review_status,
                         meta_reviewed_at, review_started_at, error_tags)
            VALUES (${task.id}, ${task.review.reviewRequestedBy.get},
                    ${if (reviewedBy == None) "NULL" else reviewedBy.get}, ${metaReviewedBy},
                    $metaReviewStatus, NOW(),
                    ${if (reviewClaimedAt != null) s"'${reviewClaimedAt}'"
      else "NULL"}, ${if (!errorTags.isEmpty) s"'${errorTags}'" else "NULL"})
         """).executeUpdate()
    }
  }
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.mixins

import java.sql.Connection
import anorm._

import java.sql.PreparedStatement
import org.joda.time.DateTime
import org.maproulette.data.ItemType
import org.maproulette.exception.{LockConflictException, LockedException}
import org.maproulette.framework.model.{Task, User}
import org.maproulette.framework.psql.TransactionManager
import org.maproulette.models.{BaseObject, Lock}
import org.maproulette.framework.repository.RepositoryMixin

/**
  * A user may hold at most one active edit lock at a time (enforced by a partial unique
  * index on locked(user_id) WHERE NOT is_review_claim). When a task is the primary of a
  * bundle, its lock row's bundled_tasks column lists the other member task ids that the
  * same lock covers, instead of each member getting its own row. Review-claim locks
  * (TaskReviewRepository) are tagged is_review_claim = true and are exempt from the
  * one-lock invariant.
  *
  * @author mcuthbert
  */
trait Locking[T <: BaseObject[_]] extends TransactionManager {
  this: RepositoryMixin =>

  /**
    * Unlocks an item in the database. If the item is a member of a bundle (i.e. covered by
    * another task's bundled_tasks rather than being the item_id itself), this releases the
    * single row that covers the whole bundle.
    *
    * @param user The user requesting to unlock the item
    * @param item The item being unlocked
    * @param c    A sql connection that is implicitly passed in from the calling function, this is an
    *             implicit function because this will always be called from within the code and never
    *             directly from an API call
    * @return true if successful
    */
  def unlockItem(user: User, item: T)(implicit c: Option[Connection] = None): Int =
    this.withMRTransaction { implicit c =>
      val checkQuery =
        s"""SELECT user_id FROM locked
            WHERE (item_id = {itemId} OR {itemId} = ANY(bundled_tasks)) AND item_type = ${item.itemType.typeId}
            FOR UPDATE"""
      SQL(checkQuery)
        .on(Symbol("itemId") -> ParameterValue.toParameterValue(item.id)(p = keyToStatement))
        .as(SqlParser.long("user_id").singleOpt) match {
        case Some(id) =>
          if (id == user.id) {
            val query =
              s"""DELETE FROM locked WHERE user_id = ${user.id}
                  AND (item_id = {itemId} OR {itemId} = ANY(bundled_tasks)) AND item_type = ${item.itemType.typeId}"""
            SQL(query)
              .on(Symbol("itemId") -> ParameterValue.toParameterValue(item.id)(p = keyToStatement))
              .executeUpdate()
          } else {
            throw new LockedException(
              s"Item ${item.id} currently locked by user ${id}"
            )
          }
        case None =>
          throw new LockedException(s"Item ${item.id} trying to unlock does not exist.")
      }
    }

  /**
    * Refreshes an existing lock on an item in the database, extending its allowed duration
    *
    * @param user The user requesting to refresh the lock (and who must also own it)
    * @param item The locked item
    * @param c    A sql connection that is implicitly passed in from the calling function, this is an
    *             implicit function because this will always be called from within the code and never
    *             directly from an API call
    * @return true if successful
    */
  def refreshItemLock(user: User, item: T)(implicit c: Option[Connection] = None): Int =
    this.withMRTransaction { implicit c =>
      val checkQuery =
        s"""SELECT user_id FROM locked
            WHERE (item_id = {itemId} OR {itemId} = ANY(bundled_tasks)) AND item_type = ${item.itemType.typeId}
            FOR UPDATE"""
      SQL(checkQuery)
        .on(Symbol("itemId") -> ParameterValue.toParameterValue(item.id)(p = keyToStatement))
        .as(SqlParser.long("user_id").singleOpt) match {
        case Some(id) =>
          if (id == user.id) {
            val query =
              s"""UPDATE locked set locked_time=NOW()
                  WHERE user_id = ${user.id}
                  AND (item_id = {itemId} OR {itemId} = ANY(bundled_tasks)) AND item_type = ${item.itemType.typeId}"""
            SQL(query)
              .on(Symbol("itemId") -> ParameterValue.toParameterValue(item.id)(p = keyToStatement))
              .executeUpdate()
          } else {
            throw new LockedException(
              s"Item ${item.id} currently locked by user ${id}"
            )
          }
        case None => throw new LockedException(s"Lock on item ${item.id} does not exist.")
      }
    }

  /**
    * Method to lock all items returned in the lambda block. It will first all unlock all items
    * that have been locked by the user.
    *
    * @param user     The user making the request
    * @param itemType The type of item that will be locked
    * @param block    The block of code to execute inbetween unlocking and locking items
    * @param c        The connection
    * @return List of objects
    */
  def withListLocking(user: User, itemType: Option[ItemType] = None)(
      block: () => List[T]
  )(implicit c: Option[Connection] = None): List[T] = {
    this.withMRTransaction { implicit c =>
      // if a user is requesting a task, then we can unlock all other tasks for that user, as only a single
      // task can be locked at a time
      this.unlockAllItems(user, itemType)
      val results = block()
      // once we have the tasks, we need to lock each one, if any fail to lock we just remove
      // them from the list. A guest user will not lock any tasks, but when logged in will be
      // required to refetch the current task, and if it is locked, then will have to get another
      // task
      if (!user.guest) {
        val resultList = results.filter(lockItem(user, _) == user.id)
        if (resultList.isEmpty) {
          List[T]()
        }
        resultList
      } else {
        results
      }
    }
  }

  /**
    * Method to lock a single optional item returned in a lambda block. It will first unlock all items
    * that have been locked by the user
    *
    * @param user     The user making the request
    * @param itemType The type of item that will be locked
    * @param block    The block of code to execute inbetween unlocking and locking items
    * @param c        The connection
    * @return Option object
    */
  def withSingleLocking(user: User, itemType: Option[ItemType] = None)(
      block: () => Option[T]
  )(implicit c: Option[Connection] = None): Option[T] = {
    this.withMRTransaction { implicit c =>
      // if a user is requesting a task, then we can unlock all other tasks for that user, as only a single
      // task can be locked at a time
      this.unlockAllItems(user, itemType)
      val result = block()
      if (!user.guest) {
        result match {
          case Some(r) => lockItem(user, r)
          case None    => // ignore
        }
      }
      result
    }
  }

  /**
    * Locks an item in the database.
    *
    * @param user        The user requesting the lock
    * @param item        The item wanting to be locked
    * @param reviewClaim Whether this lock represents a reviewer's review claim rather than an
    *                    ordinary edit lock. Review-claim locks are exempt from the one-lock-per-user
    *                    invariant.
    * @param c           A sql connection that is implicitly passed in from the calling function, this is an
    *                    implicit function because this will always be called from within the code and never
    *                    directly from an API call
    * @return user id of who now holds the lock
    */
  def lockItem(
      user: User,
      item: T,
      reviewClaim: Boolean = false
  )(implicit c: Option[Connection] = None): Long =
    this.withMRTransaction { implicit c =>
      if (!reviewClaim) {
        this.enforceSingleEditLock(user, item.id.asInstanceOf[Long])
      }

      // first check to see if the item is already locked
      val checkQuery =
        s"""SELECT user_id FROM locked WHERE item_id = {itemId} AND item_type = ${item.itemType.typeId} FOR UPDATE"""
      SQL(checkQuery)
        .on(Symbol("itemId") -> ParameterValue.toParameterValue(item.id)(p = keyToStatement))
        .as(SqlParser.long("user_id").singleOpt) match {
        case Some(id) =>
          if (id == user.id) {
            val query =
              s"UPDATE locked SET locked_time = NOW() WHERE user_id = ${user.id} AND item_id = {itemId} AND item_type = ${item.itemType.typeId}"
            SQL(query)
              .on(Symbol("itemId") -> ParameterValue.toParameterValue(item.id)(p = keyToStatement))
              .executeUpdate()
            user.id
          } else {
            id
          }
        case None =>
          val query =
            s"""INSERT INTO locked (item_type, item_id, user_id, is_review_claim)
                VALUES (${item.itemType.typeId}, {itemId}, ${user.id}, $reviewClaim)"""
          SQL(query)
            .on(Symbol("itemId") -> ParameterValue.toParameterValue(item.id)(p = keyToStatement))
            .executeUpdate()
          user.id
      }
    }

  /**
    * Locks a bundle's primary task, recording the other bundle member task ids in the
    * bundled_tasks column instead of locking each member individually. Releasing, refreshing,
    * or checking the lock on any member task resolves to this single covering row (see
    * unlockItem/refreshItemLock above).
    *
    * @param user          The user requesting the lock
    * @param primaryItem   The bundle's primary task
    * @param memberTaskIds The ids of the other tasks in the bundle (primary excluded automatically)
    * @param c             A sql connection implicitly passed in from the calling function
    * @return user id of who now holds the lock
    */
  def lockBundle(
      user: User,
      primaryItem: Task,
      memberTaskIds: List[Long]
  )(implicit c: Option[Connection] = None): Long =
    this.withMRTransaction { implicit c =>
      this.enforceSingleEditLock(user, primaryItem.id)

      val members = memberTaskIds.filterNot(_ == primaryItem.id).distinct
      val membersLiteral =
        if (members.isEmpty) "'{}'::integer[]" else s"ARRAY[${members.mkString(",")}]::integer[]"

      val checkQuery =
        s"""SELECT user_id FROM locked WHERE item_id = {itemId} AND item_type = ${primaryItem.itemType.typeId} FOR UPDATE"""
      SQL(checkQuery)
        .on(Symbol("itemId") -> ParameterValue.toParameterValue(primaryItem.id)(p = keyToStatement))
        .as(SqlParser.long("user_id").singleOpt) match {
        case Some(id) if id != user.id => id
        case Some(_) =>
          val query =
            s"""UPDATE locked SET locked_time = NOW(), bundled_tasks = $membersLiteral
                WHERE user_id = ${user.id} AND item_id = {itemId} AND item_type = ${primaryItem.itemType.typeId}"""
          SQL(query)
            .on(
              Symbol("itemId") -> ParameterValue.toParameterValue(primaryItem.id)(p = keyToStatement
              )
            )
            .executeUpdate()
          user.id
        case None =>
          val query =
            s"""INSERT INTO locked (item_type, item_id, user_id, bundled_tasks)
                VALUES (${primaryItem.itemType.typeId}, {itemId}, ${user.id}, $membersLiteral)"""
          SQL(query)
            .on(
              Symbol("itemId") -> ParameterValue.toParameterValue(primaryItem.id)(p = keyToStatement
              )
            )
            .executeUpdate()
          user.id
      }
    }

  /**
    * Enforces the one-active-edit-lock-per-user invariant ahead of an insert/refresh on
    * `targetItemId`. If the user already holds a different non-review-claim lock, throws
    * LockConflictException carrying that lock's details - the caller is expected to release
    * it explicitly (e.g. via the task release endpoint) before retrying, rather than this
    * silently swapping the lock out from under them.
    *
    * Must be called from within an already-open withMRTransaction block (hence the plain,
    * non-Option implicit Connection).
    */
  private def enforceSingleEditLock(user: User, targetItemId: Long)(
      implicit c: Connection
  ): Unit = {
    val existingLock =
      SQL("""SELECT item_id, item_type, changeset_id, bundled_tasks, locked_time
             FROM locked WHERE user_id = {userId} AND NOT is_review_claim FOR UPDATE""")
        .on(Symbol("userId") -> user.id)
        .as(
          (SqlParser.long("item_id") ~ SqlParser.int("item_type") ~
            SqlParser.long("changeset_id") ~ SqlParser.get[List[Long]]("bundled_tasks") ~
            SqlParser.get[Option[DateTime]]("locked_time")).singleOpt
        )

    existingLock match {
      case Some(itemId ~ itemType ~ changesetId ~ bundledTasks ~ lockedTime)
          if itemId != targetItemId =>
        throw new LockConflictException(
          s"User ${user.id} already holds a lock on item ${itemId}",
          Lock(lockedTime, itemType, itemId, user.id, changesetId, bundledTasks)
        )
      case _ => // no existing lock, or it already covers the target item - nothing to do
    }
  }

  /**
    * Unlocks all the (non review-claim) edit locks associated with the current user
    *
    * @param user The user
    * @param c    an implicit connection, this function should generally be executed in conjunction
    *             with other requests
    * @return Number of locks removed
    */
  def unlockAllItems(user: User, itemType: Option[ItemType] = None)(
      implicit c: Option[Connection] = None
  ): Int =
    this.withMRTransaction { implicit c =>
      itemType match {
        case Some(it) =>
          SQL"""DELETE FROM locked WHERE user_id = ${user.id} AND item_type = ${it.typeId} AND NOT is_review_claim"""
            .executeUpdate()
        case None =>
          SQL"""DELETE FROM locked WHERE user_id = ${user.id} AND NOT is_review_claim"""
            .executeUpdate()
      }
    }

  /**
    * Our key for our objects are current Long, but can support String if need be. This function
    * handles transforming java objects to SQL for a specific set related to the object key
    *
    * @tparam Key The type of Key, this is currently always Long, but could be changed easily enough in the future
    * @return
    */
  private def keyToStatement[Key]: ToStatement[Key] = {
    new ToStatement[Key] {
      def set(s: PreparedStatement, i: Int, identifier: Key) =
        identifier match {
          case id: String                  => ToStatement.stringToStatement.set(s, i, id)
          case Some(id: String)            => ToStatement.stringToStatement.set(s, i, id)
          case id: Long                    => ToStatement.longToStatement.set(s, i, id)
          case Some(id: Long)              => ToStatement.longToStatement.set(s, i, id)
          case intValue: Integer           => ToStatement.integerToStatement.set(s, i, intValue)
          case list: List[Long @unchecked] => ToStatement.listToStatement[Long].set(s, i, list)
        }
    }
  }
}

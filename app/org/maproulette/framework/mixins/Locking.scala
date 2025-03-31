/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.mixins

import java.sql.Connection
import anorm._

import java.sql.PreparedStatement
import org.maproulette.data.ItemType
import org.maproulette.exception.LockedException
import org.maproulette.framework.model.{Task, User}
import org.maproulette.framework.psql.TransactionManager
import org.maproulette.models.BaseObject
import org.maproulette.framework.repository.RepositoryMixin

/**
  * @author mcuthbert
  */
trait Locking[T <: BaseObject[_]] extends TransactionManager {
  this: RepositoryMixin =>

  /**
    * Unlocks an item in the database
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
        s"""SELECT user_id FROM locked WHERE item_id = {itemId} AND item_type = ${item.itemType.typeId} FOR UPDATE"""
      SQL(checkQuery)
        .on(Symbol("itemId") -> ParameterValue.toParameterValue(item.id)(p = keyToStatement))
        .as(SqlParser.long("user_id").singleOpt) match {
        case Some(id) =>
          if (id == user.id) {
            val query =
              s"""DELETE FROM locked WHERE user_id = ${user.id} AND item_id = {itemId} AND item_type = ${item.itemType.typeId}"""
            SQL(query)
              .on(Symbol("itemId") -> ParameterValue.toParameterValue(item.id)(p = keyToStatement))
              .executeUpdate()
          } else {
            throw new LockedException(
              s"Item ${item.id} currently locked by user ${user.id}"
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
        s"""SELECT user_id FROM locked WHERE item_id = {itemId} AND item_type = ${item.itemType.typeId} FOR UPDATE"""
      SQL(checkQuery)
        .on(Symbol("itemId") -> ParameterValue.toParameterValue(item.id)(p = keyToStatement))
        .as(SqlParser.long("user_id").singleOpt) match {
        case Some(id) =>
          if (id == user.id) {
            val query =
              s"""UPDATE locked set locked_time=NOW() WHERE user_id = ${user.id} AND item_id = {itemId} AND item_type = ${item.itemType.typeId}"""
            SQL(query)
              .on(Symbol("itemId") -> ParameterValue.toParameterValue(item.id)(p = keyToStatement))
              .executeUpdate()
          } else {
            throw new LockedException(
              s"Item ${item.id} currently locked by user ${user.id}"
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
    * @param user The user requesting the lock
    * @param item The item wanting to be locked
    * @param c    A sql connection that is implicitly passed in from the calling function, this is an
    *             implicit function because this will always be called from within the code and never
    *             directly from an API call
    * @return user id of who now holds the lock
    */
  def lockItem(user: User, item: T)(implicit c: Option[Connection] = None): Long =
    this.withMRTransaction { implicit c =>
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
            s"INSERT INTO locked (item_type, item_id, user_id) VALUES (${item.itemType.typeId}, {itemId}, ${user.id})"
          SQL(query)
            .on(Symbol("itemId") -> ParameterValue.toParameterValue(item.id)(p = keyToStatement))
            .executeUpdate()
          user.id
      }
    }

  /**
    * Unlocks all the items that are associated with the current user
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
          SQL"""DELETE FROM locked WHERE user_id = ${user.id} AND item_type = ${it.typeId}"""
            .executeUpdate()
        case None =>
          SQL"""DELETE FROM locked WHERE user_id = ${user.id}""".executeUpdate()
      }
    }

  /**
    * Locks multiple items in a single database transaction.
    *
    * @param user  The user requesting the locks
    * @param items The list of items to be locked
    * @param c     A sql connection that is implicitly passed in from the calling function
    * @return Map of item ids to the user ids that hold the lock for each conflicting item
    */
  def lockItems(user: User, items: List[Task])(
      implicit c: Option[Connection] = None
  ): Map[Long, Long] =
    this.withMRTransaction { implicit c =>
      if (items.isEmpty) {
        Map.empty[Long, Long]
      } else {
        // Build a single query with multiple value sets for better performance
        val valuesList = items
          .map { item =>
            s"(${item.itemType.typeId}, ${item.id}, ${user.id}, NOW())"
          }
          .mkString(", ")

        val query =
          s"""
             |WITH upsert AS (
             |  INSERT INTO locked (item_type, item_id, user_id, locked_time)
             |  VALUES $valuesList
             |  ON CONFLICT (item_type, item_id) 
             |  DO UPDATE SET locked_time = NOW()
             |  WHERE locked.user_id = ${user.id}
             |  RETURNING item_id, user_id
             |)
             |SELECT l.item_id, l.user_id
             |FROM upsert l
           """.stripMargin

        val results = SQL(query).as(
          (SqlParser.long("item_id") ~ SqlParser.long("user_id")).*
        )

        val resultMap = results.map { case id ~ userId => (id -> userId) }.toMap

        // Find items that failed to lock - fix type mismatch by explicitly converting to Long
        val failedToLock = items.filter(item => !resultMap.contains(item.id))
        // Find items locked by wrong user
        val wrongUserLocks = resultMap.filter { case (_, lockUserId) => lockUserId != user.id }

        if (failedToLock.nonEmpty || wrongUserLocks.nonEmpty) {
          val failedItemsMsg = if (failedToLock.nonEmpty) {
            s"Failed to lock items: ${failedToLock.map(_.id).mkString(", ")}"
          } else ""

          val wrongUserMsg = if (wrongUserLocks.nonEmpty) {
            s"Items locked by different users: ${wrongUserLocks.keys.mkString(", ")}"
          } else ""

          val errorMsg = List(failedItemsMsg, wrongUserMsg).filter(_.nonEmpty).mkString(". ")
          throw new IllegalAccessException(s"Lock operation failed. $errorMsg")
        }

        resultMap
      }
    }

  /**
    * Unlocks multiple items in a single database transaction.
    *
    * @param user  The user requesting to unlock the items
    * @param items The list of items to be unlocked
    * @param c     A sql connection that is implicitly passed in from the calling function
    * @return Number of items successfully unlocked
    * @throws LockedException if any items are locked by a different user or not locked at all
    */
  def unlockItems(user: User, items: List[Task])(
      implicit c: Option[Connection] = None
  ): Int =
    this.withMRTransaction { implicit c =>
      if (items.isEmpty) {
        0
      } else {
        // Create a list of item IDs and types for the IN clause
        val itemIds  = items.map(_.id)
        val itemType = items.headOption.map(_.itemType.typeId).getOrElse(0)

        // Check the lock status of all requested items
        val checkQuery =
          s"""
             |SELECT item_id, user_id 
             |FROM locked 
             |WHERE item_id IN (${itemIds.mkString(",")})
             |AND item_type = $itemType
             |FOR UPDATE
           """.stripMargin

        val lockStatus = SQL(checkQuery)
          .as(
            (SqlParser.long("item_id") ~ SqlParser.long("user_id")).*
          )
          .map { case id ~ userId => (id -> userId) }
          .toMap

        // Find items that aren't locked at all
        val notLockedItems = itemIds.filter(id => !lockStatus.contains(id))

        // Find items locked by other users
        val lockedByOthers = lockStatus.filter { case (_, lockUserId) => lockUserId != user.id }

        if (notLockedItems.nonEmpty || lockedByOthers.nonEmpty) {
          val notLockedMsg = if (notLockedItems.nonEmpty) {
            s"Items not locked: ${notLockedItems.mkString(", ")}"
          } else ""

          val lockedByOthersMsg = if (lockedByOthers.nonEmpty) {
            s"Items locked by different users: ${lockedByOthers.keys.mkString(", ")}"
          } else ""

          val errorMsg = List(notLockedMsg, lockedByOthersMsg).filter(_.nonEmpty).mkString(". ")
          throw new LockedException(s"Unlock operation failed for user ${user.id}. $errorMsg")
        }

        // All items are locked by the current user, proceed with unlock
        val deleteQuery =
          s"""
             |DELETE FROM locked 
             |WHERE item_id IN (${itemIds.mkString(",")})
             |AND item_type = $itemType
             |AND user_id = ${user.id}
           """.stripMargin

        SQL(deleteQuery).executeUpdate()
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

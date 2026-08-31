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

      // first check to see if the item is already locked - resolve through bundled_tasks
      // too, since item may be a non-primary member of a bundle whose lock row is keyed
      // on the primary's item_id
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
              s"""UPDATE locked SET locked_time = NOW()
                  WHERE user_id = ${user.id}
                  AND (item_id = {itemId} OR {itemId} = ANY(bundled_tasks)) AND item_type = ${item.itemType.typeId}"""
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
    * Resolves the primary item id and bundled member ids of the lock currently covering
    * the given item, regardless of who holds it. Returns None if the item is unlocked.
    *
    * @param item The item (primary or bundle member) to resolve the covering lock for
    * @param c    A sql connection implicitly passed in from the calling function
    * @return Some((primaryItemId, memberTaskIds)) if a lock covers the item, else None
    */
  def resolveLockBundle(
      item: T
  )(implicit c: Option[Connection] = None): Option[(Long, List[Long])] =
    this.withMRTransaction { implicit c =>
      SQL(
        s"""SELECT item_id, bundled_tasks FROM locked
            WHERE (item_id = {itemId} OR {itemId} = ANY(bundled_tasks)) AND item_type = ${item.itemType.typeId}"""
      ).on(Symbol("itemId") -> ParameterValue.toParameterValue(item.id)(p = keyToStatement))
        .as(
          (SqlParser.long("item_id") ~ SqlParser.get[List[Long]]("bundled_tasks")).singleOpt
        )
        .map { case primaryItemId ~ bundledTasks => (primaryItemId, bundledTasks) }
    }

  /**
    * Resolves who currently holds the lock covering the given item (primary or bundle
    * member), without acquiring or refreshing anything. Used to report lock ownership on
    * plain task reads so a task locked by the current user renders as locked-by-me even in
    * a tab that never issued its own /start (e.g. the same task opened in a second tab).
    *
    * @param item The item (primary or bundle member) to resolve the covering lock for
    * @param c    A sql connection implicitly passed in from the calling function
    * @return Some((holderUserId, primaryItemId, memberTaskIds)) if a lock covers the item, else None
    */
  def resolveLockHolder(
      item: T
  )(implicit c: Option[Connection] = None): Option[(Long, Long, List[Long])] =
    this.withMRTransaction { implicit c =>
      SQL(
        s"""SELECT user_id, item_id, bundled_tasks FROM locked
            WHERE (item_id = {itemId} OR {itemId} = ANY(bundled_tasks)) AND item_type = ${item.itemType.typeId}"""
      ).on(Symbol("itemId") -> ParameterValue.toParameterValue(item.id)(p = keyToStatement))
        .as(
          (SqlParser.long("user_id") ~ SqlParser.long("item_id") ~
            SqlParser.get[List[Long]]("bundled_tasks")).singleOpt
        )
        .map { case userId ~ primaryItemId ~ bundledTasks => (userId, primaryItemId, bundledTasks) }
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
    * @param reviewClaim   Whether this lock represents a reviewer's review claim rather than an
    *                      ordinary edit lock. Review-claim locks are exempt from the
    *                      one-lock-per-user invariant (see lockItem).
    * @param c             A sql connection implicitly passed in from the calling function
    * @return user id of who now holds the lock
    */
  def lockBundle(
      user: User,
      primaryItem: Task,
      memberTaskIds: List[Long],
      reviewClaim: Boolean = false
  )(implicit c: Option[Connection] = None): Long =
    this.withMRTransaction { implicit c =>
      if (!reviewClaim) {
        this.enforceSingleEditLock(user, primaryItem.id)
      }

      val members = memberTaskIds.filterNot(_ == primaryItem.id).distinct
      // Avoid the curly-brace array literal ('{}') here - anorm's SQL() scans the raw query
      // text for {paramName} placeholders, and a literal {} in the empty case gets mistaken
      // for one, corrupting the query. ARRAY[]::integer[] is equivalent and brace-free.
      val membersLiteral =
        if (members.isEmpty) "ARRAY[]::integer[]" else s"ARRAY[${members.mkString(",")}]::integer[]"
      val coveredLiteral = s"ARRAY[${(primaryItem.id :: members).mkString(",")}]::integer[]"

      // Every task this lock will cover has to be free or already ours. Leaving another row
      // covering one of them would mean two rows cover the same task, which breaks the
      // singleOpt lookups in resolveLockHolder/resolveLockBundle.
      val existing =
        SQL(s"""SELECT user_id, item_id FROM locked
                WHERE item_type = ${primaryItem.itemType.typeId}
                  AND (item_id = ANY($coveredLiteral) OR bundled_tasks && $coveredLiteral)
                FOR UPDATE""")
          .as((SqlParser.long("user_id") ~ SqlParser.long("item_id")).*)
          .map { case userId ~ itemId => (userId, itemId) }

      existing.find { case (userId, _) => userId != user.id } match {
        case Some((otherUserId, _)) => otherUserId
        case None                   =>
          // Fold any rows of our own that cover a member into the single primary row
          if (existing.exists { case (_, itemId) => itemId != primaryItem.id }) {
            SQL(s"""DELETE FROM locked
                    WHERE user_id = ${user.id} AND item_type = ${primaryItem.itemType.typeId}
                      AND item_id != {itemId}
                      AND (item_id = ANY($coveredLiteral) OR bundled_tasks && $coveredLiteral)""")
              .on(
                Symbol("itemId") -> ParameterValue.toParameterValue(primaryItem.id)(
                  p = keyToStatement
                )
              )
              .executeUpdate()
          }

          val query =
            if (existing.exists { case (_, itemId) => itemId == primaryItem.id })
              s"""UPDATE locked
                  SET locked_time = NOW(), bundled_tasks = $membersLiteral, is_review_claim = $reviewClaim
                  WHERE user_id = ${user.id} AND item_id = {itemId} AND item_type = ${primaryItem.itemType.typeId}"""
            else
              s"""INSERT INTO locked (item_type, item_id, user_id, bundled_tasks, is_review_claim)
                  VALUES (${primaryItem.itemType.typeId}, {itemId}, ${user.id}, $membersLiteral, $reviewClaim)"""

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
          if itemId != targetItemId && !bundledTasks.contains(targetItemId) =>
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

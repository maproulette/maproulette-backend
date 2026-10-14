/*
 * Copyright (C) 2026 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import java.sql.Connection

import anorm.SqlParser.get
import anorm._
import org.joda.time.DateTime
import org.maproulette.framework.psql.CommonField
import play.api.libs.json.JodaWrites._
import play.api.libs.json.{JsNull, JsObject, JsValue, Json, Writes}

/**
  * One row per lock or unlock event. A session is the lock row plus the unlock
  * that shares `lockId` (the `locked.id` of the covering lock). Duration is
  * unlock.created - lock.created.
  *
  * @param lockId      id of the `locked` row this event belongs to. Kept after
  *                    that row is deleted so lock/unlock pairs can be matched.
  * @param sessionType Map or Custom.
  * @param subType     Optional label for a Custom session. Empty for Map.
  */
case class SessionHistory(
    id: Long,
    created: DateTime,
    userId: Long,
    taskId: Long,
    lockId: Long,
    event: String,
    sessionType: String,
    subType: Option[String]
)

object SessionHistory extends CommonField {
  implicit val writes: Writes[SessionHistory] = Json.writes[SessionHistory]

  val TABLE = "session_history"

  val EVENT_LOCK   = "lock"
  val EVENT_UNLOCK = "unlock"

  val TYPE_MAP    = "Map"
  val TYPE_CUSTOM = "Custom"

  val SUB_TYPE_REVIEW = "Review"

  val parser: RowParser[SessionHistory] = {
    get[Long]("session_history.id") ~
      get[DateTime]("session_history.created") ~
      get[Long]("session_history.user_id") ~
      get[Long]("session_history.task_id") ~
      get[Long]("session_history.lock_id") ~
      get[String]("session_history.event") ~
      get[String]("session_history.session_type") ~
      get[Option[String]]("session_history.sub_type") map {
      case id ~ created ~ userId ~ taskId ~ lockId ~ event ~ sessionType ~ subType =>
        SessionHistory(id, created, userId, taskId, lockId, event, sessionType, subType)
    }
  }

  /**
    * API payload for session events: drop the raw userId and attach the same
    * `{ id, username }` user object the task history feed uses.
    */
  def toJsonWithUsers(entries: List[SessionHistory], usersById: Map[Long, JsObject]): JsValue =
    Json.toJson(entries.map { entry =>
      val base = Json.toJson(entry).as[JsObject] - "userId"
      base + ("user" -> usersById.getOrElse(entry.userId, JsNull))
    })

  def recordLock(
      userId: Long,
      taskId: Long,
      lockId: Long,
      sessionType: String,
      subType: Option[String]
  )(implicit c: Connection): Unit = {
    SQL"""INSERT INTO session_history (user_id, task_id, lock_id, event, session_type, sub_type)
          VALUES (
            $userId,
            $taskId,
            $lockId,
            $EVENT_LOCK,
            $sessionType,
            $subType
          )""".executeUpdate()
    ()
  }
}

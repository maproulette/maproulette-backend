/*
 * Copyright (C) 2026 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.util.UUID

import anorm._
import org.maproulette.framework.model.{SessionHistory, Task, User}
import org.maproulette.framework.util.{FrameworkHelper, SessionHistoryRepoTag}
import org.scalatest.BeforeAndAfterEach
import play.api.Application
import play.api.db.Database

/**
  * Session history is written on lock acquire and on every removal from `locked`
  * (via the delete trigger), paired by lock_id. Type is inferred from
  * reviewClaim: Map for ordinary locks, Custom/Review for review claims.
  */
class SessionHistoryRepositorySpec(implicit val application: Application)
    extends FrameworkHelper
    with BeforeAndAfterEach {
  val repository: SessionHistoryRepository =
    this.application.injector.instanceOf(classOf[SessionHistoryRepository])
  val db: Database = this.application.injector.instanceOf(classOf[Database])

  override def beforeEach(): Unit = {
    this.db.withConnection { implicit c =>
      SQL"DELETE FROM locked WHERE user_id = ${this.defaultUser.id}".executeUpdate()
    }
  }

  "SessionHistoryRepository" should {
    "record a Map lock and matching unlock for /start and /release" taggedAs SessionHistoryRepoTag in {
      val task = this.insertTask()
      this.taskDAL.lockItem(this.defaultUser, task)
      this.taskDAL.unlockItem(this.defaultUser, task)

      val events = this.repository.listForTask(task.id)
      events.map(_.event) mustEqual List(SessionHistory.EVENT_LOCK, SessionHistory.EVENT_UNLOCK)
      events.map(_.sessionType).distinct mustEqual List(SessionHistory.TYPE_MAP)
      events.forall(_.subType.isEmpty) mustEqual true
      events.map(_.lockId).distinct.length mustEqual 1
      events.forall(_.userId == this.defaultUser.id) mustEqual true
    }

    "serialize session events with a nested user object for the read API" taggedAs SessionHistoryRepoTag in {
      val task = this.insertTask()
      this.taskDAL.lockItem(this.defaultUser, task)
      val events     = this.repository.listForTask(task.id)
      val usersById = Map(
        this.defaultUser.id -> play.api.libs.json.Json.obj(
          "id"       -> this.defaultUser.id,
          "username" -> this.defaultUser.name
        )
      )
      val json = SessionHistory.toJsonWithUsers(events, usersById)
      val head = json.as[play.api.libs.json.JsArray].value.head.as[play.api.libs.json.JsObject]
      (head \ "userId").toOption mustEqual None
      (head \ "user" \ "id").as[Long] mustEqual this.defaultUser.id
      (head \ "user" \ "username").as[String] mustEqual this.defaultUser.name
      (head \ "event").as[String] mustEqual SessionHistory.EVENT_LOCK

      this.taskDAL.unlockItem(this.defaultUser, task)
    }

    "not record a new lock when refreshing or re-acquiring an existing lock" taggedAs SessionHistoryRepoTag in {
      val task = this.insertTask()
      this.taskDAL.lockItem(this.defaultUser, task)
      this.taskDAL.refreshItemLock(this.defaultUser, task)
      this.taskDAL.lockItem(this.defaultUser, task)

      val events = this.repository.listForTask(task.id)
      events.map(_.event) mustEqual List(SessionHistory.EVENT_LOCK)

      this.taskDAL.unlockItem(this.defaultUser, task)
    }

    "record Custom/Review events for review-claim locks" taggedAs SessionHistoryRepoTag in {
      val task = this.insertTask()
      this.taskDAL.lockItem(this.defaultUser, task, reviewClaim = true)
      this.taskDAL.unlockItem(this.defaultUser, task)

      val events = this.repository.listForTask(task.id)
      events.map(_.event) mustEqual List(SessionHistory.EVENT_LOCK, SessionHistory.EVENT_UNLOCK)
      events.map(_.sessionType).distinct mustEqual List(SessionHistory.TYPE_CUSTOM)
      events.map(_.subType).distinct mustEqual List(Some(SessionHistory.SUB_TYPE_REVIEW))
      events.map(_.lockId).distinct.length mustEqual 1
    }

    "record a Map lock for lockBundle on the primary task" taggedAs SessionHistoryRepoTag in {
      val primary = this.insertTask()
      val member  = this.insertTask()
      this.taskDAL.lockBundle(this.defaultUser, primary, List(member.id))
      this.taskDAL.unlockItem(this.defaultUser, primary)

      val primaryEvents = this.repository.listForTask(primary.id)
      primaryEvents.map(_.event) mustEqual List(
        SessionHistory.EVENT_LOCK,
        SessionHistory.EVENT_UNLOCK
      )
      primaryEvents.map(_.sessionType).distinct mustEqual List(SessionHistory.TYPE_MAP)
      primaryEvents.map(_.lockId).distinct.length mustEqual 1
      this.repository.listForTask(member.id) mustEqual List.empty
    }

    "record an unlock when a lock row is deleted outside unlockItem" taggedAs SessionHistoryRepoTag in {
      val task = this.insertTask()
      this.taskDAL.lockItem(this.defaultUser, task)
      val lockId = this.repository.listForTask(task.id).head.lockId

      this.db.withConnection { implicit c =>
        SQL"DELETE FROM locked WHERE id = $lockId".executeUpdate()
      }

      val events = this.repository.listForTask(task.id)
      events.map(_.event) mustEqual List(SessionHistory.EVENT_LOCK, SessionHistory.EVENT_UNLOCK)
      events.map(_.lockId).distinct mustEqual List(lockId)
      events.map(_.sessionType).distinct mustEqual List(SessionHistory.TYPE_MAP)
    }

    "record matching unlocks for unlockAllItems" taggedAs SessionHistoryRepoTag in {
      val task = this.insertTask()
      this.taskDAL.lockItem(this.defaultUser, task)
      val lockId = this.repository.listForTask(task.id).head.lockId
      this.taskDAL.unlockAllItems(this.defaultUser)

      val events = this.repository.listForTask(task.id)
      events.map(_.event) mustEqual List(SessionHistory.EVENT_LOCK, SessionHistory.EVENT_UNLOCK)
      events.map(_.lockId).distinct mustEqual List(lockId)
    }

    "copy session_type from the lock event when a review-claim lock is deleted" taggedAs SessionHistoryRepoTag in {
      val task = this.insertTask()
      this.taskDAL.lockItem(this.defaultUser, task, reviewClaim = true)
      val lockId = this.repository.listForTask(task.id).head.lockId
      this.db.withConnection { implicit c =>
        SQL"DELETE FROM locked WHERE id = $lockId".executeUpdate()
      }

      val unlock = this.repository.listForTask(task.id).find(_.event == SessionHistory.EVENT_UNLOCK)
      unlock.map(_.sessionType) mustEqual Some(SessionHistory.TYPE_CUSTOM)
      unlock.flatMap(_.subType) mustEqual Some(SessionHistory.SUB_TYPE_REVIEW)
    }
  }

  override implicit val projectTestName: String = "SessionHistoryRepositorySpecProject"

  private def insertTask(): Task =
    this.taskDAL.insert(this.getTestTask(UUID.randomUUID().toString), User.superUser)
}

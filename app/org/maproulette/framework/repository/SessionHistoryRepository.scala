/*
 * Copyright (C) 2026 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection

import anorm._
import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model.SessionHistory
import play.api.db.Database

/**
  * Reads lock/unlock session events. Lock events are written from the Locking
  * mixin; unlock events are written by the session_history_on_locked_delete
  * trigger whenever a row is removed from locked.
  */
@Singleton
class SessionHistoryRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = SessionHistory.TABLE

  def listForTask(taskId: Long)(implicit c: Option[Connection] = None): List[SessionHistory] = {
    this.withMRConnection { implicit c =>
      SQL"""SELECT * FROM session_history
            WHERE task_id = $taskId
            ORDER BY created ASC, id ASC"""
        .as(SessionHistory.parser.*)
    }
  }
}

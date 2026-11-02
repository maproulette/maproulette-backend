/*
 * Copyright (C) 2026 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import anorm._
import anorm.SqlParser._
import org.maproulette.framework.model.{Challenge, User}
import org.maproulette.framework.util.FrameworkHelper
import play.api.Application
import play.api.db.Database
import play.api.libs.json.{JsValue, Json}

class TaskUpsertSpec(implicit val application: Application) extends FrameworkHelper {
  val db: Database = this.application.injector.instanceOf(classOf[Database])

  override implicit val projectTestName: String = "TaskUpsertSpecProject"

  private def upsert(challenge: Challenge, names: String*): Unit =
    db.withTransaction { implicit c =>
      this.taskDAL.upsertChallengeTasks(
        challenge,
        names.map(this.getTestTask(_, challenge.id)),
        User.superUser
      )
    }

  private def metrics(challenge: Challenge): JsValue =
    db.withConnection { implicit c =>
      Json.parse(
        SQL"SELECT completion_metrics::TEXT FROM challenges WHERE id = ${challenge.id}"
          .as(str(1).single)
      )
    }

  "upsertChallengeTasks" should {
    "insert new tasks and update existing ones matched by name" in {
      val challenge = this.createChallengeStructure("UpsertChallenge", this.defaultProject.id, 0)
      this.upsert(challenge, "A", "B")
      db.withConnection { implicit c =>
        SQL"""UPDATE tasks SET status = 1, mapped_on = NOW()
              WHERE parent_id = ${challenge.id} AND name = 'A'""".executeUpdate()
      }

      this.upsert(challenge, "a", "C")

      db.withConnection { implicit c =>
        SQL"""SELECT name, status, mapped_on IS NOT NULL AS mapped FROM tasks
              WHERE parent_id = ${challenge.id} ORDER BY lower(name)"""
          .as((str("name") ~ int("status") ~ bool("mapped")).map(flatten).*) mustEqual List(
          ("a", 1, true),
          ("B", 0, false),
          ("C", 0, false)
        )
      }
      (metrics(challenge) \ "total").as[Int] mustEqual 3
      (metrics(challenge) \ "available").as[Int] mustEqual 2
      (metrics(challenge) \ "fixed").as[Int] mustEqual 1
    }

    "skip tasks whose geometry is invalid" in {
      val challenge = this.createChallengeStructure("InvalidGeometry", this.defaultProject.id, 0)
      val invalid = this
        .getTestTask("Invalid", challenge.id)
        .copy(geometries = Json.obj("features" -> Json.arr(Json.obj("geometry" -> Json.obj()))))

      db.withTransaction { implicit c =>
        this.taskDAL.upsertChallengeTasks(
          challenge,
          Seq(this.getTestTask("Valid", challenge.id), invalid),
          User.superUser
        )
      }

      db.withConnection { implicit c =>
        SQL"SELECT name FROM tasks WHERE parent_id = ${challenge.id}".as(str(1).*) mustEqual
          List("Valid")
      }
    }

    "keep completion metrics and dirty tiles in step for statement-level changes" in {
      val challenge = this.createChallengeStructure("StatementTriggers", this.defaultProject.id, 0)
      db.withConnection { implicit c =>
        SQL"DELETE FROM tile_dirty_cells".executeUpdate()
      }

      this.upsert(challenge, "A", "B", "C")
      db.withConnection { implicit c =>
        SQL"SELECT COUNT(*) FROM tile_dirty_cells".as(int(1).single) must be >= 1
        SQL"UPDATE tasks SET status = 3 WHERE parent_id = ${challenge.id}".executeUpdate()
      }
      (metrics(challenge) \ "skipped").as[Int] mustEqual 3
      (metrics(challenge) \ "available").as[Int] mustEqual 0

      db.withConnection { implicit c =>
        SQL"DELETE FROM tasks WHERE parent_id = ${challenge.id}".executeUpdate()
      }
      (metrics(challenge) \ "total").as[Int] mustEqual 0
      (metrics(challenge) \ "skipped").as[Int] mustEqual 0
    }
  }
}

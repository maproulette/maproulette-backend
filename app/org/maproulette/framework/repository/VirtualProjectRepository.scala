/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection

import anorm.SQL
import javax.inject.{Inject, Singleton}
import org.maproulette.exception.InvalidException
import org.maproulette.framework.model.VirtualProject
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.BaseParameter
import org.postgresql.util.PSQLException
import play.api.db.Database

/**
  * @author mcuthbert
  */
@Singleton
class VirtualProjectRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = VirtualProject.TABLE

  def addChallenge(projectId: Long, challengeId: Long)(
      implicit c: Option[Connection] = None
  ): Boolean = {
    this.withMRTransaction { implicit c =>
      try {
        val query =
          s"""INSERT INTO virtual_project_challenges (project_id, challenge_id)
                        VALUES ({pid}, {cid})"""
        SQL(query).on(Symbol("pid") -> projectId, Symbol("cid") -> challengeId).execute()
      } catch {
        case e: PSQLException if e.getSQLState == "23505" => false //ignore
        case other: Throwable =>
          throw new InvalidException(
            s"Unable to add challenge $challengeId to Virtual Project $projectId. " +
              other.getMessage
          )
      }
    }
  }

  def removeChallenge(projectId: Long, challengeId: Long)(
      implicit c: Option[Connection] = None
  ): Boolean = {
    this.withMRTransaction { implicit c =>
      Query
        .simple(
          List(
            BaseParameter("project_id", projectId),
            BaseParameter("challenge_id", challengeId)
          )
        )
        .build("DELETE FROM virtual_project_challenges")
        .execute()
    }
  }
}

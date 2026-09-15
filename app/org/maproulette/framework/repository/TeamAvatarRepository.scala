/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.repository

import anorm.SqlParser._
import anorm._
import java.sql.Connection
import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import org.maproulette.framework.model.{TeamAvatar, TeamImageData}
import play.api.db.Database

/**
  * Repository for team avatars. A team has at most one, so every operation is
  * keyed by team id rather than by a row id.
  */
@Singleton
class TeamAvatarRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = TeamAvatar.TABLE

  // The query below aliases the snake_case of every TeamAvatar field, so the
  // macro parser maps them without a hand-written column list. The bytes are
  // excluded on purpose; only `retrieveData` pulls them.
  private val parser: RowParser[TeamAvatar] =
    Macro.namedParser[TeamAvatar](Macro.ColumnNaming.SnakeCase)

  /**
    * Retrieves a team's avatar metadata.
    */
  def retrieve(teamId: Long): Option[TeamAvatar] = {
    this.withMRConnection { implicit c =>
      SQL"""SELECT team_id, content_type, octet_length(data) AS size, uploaded_by, created, modified
            FROM team_avatars WHERE team_id = $teamId"""
        .as(this.parser.singleOpt)
    }
  }

  /**
    * Retrieves the bytes of a team's avatar, for serving it.
    */
  def retrieveData(teamId: Long): Option[TeamImageData] = {
    this.withMRConnection { implicit c =>
      SQL"SELECT content_type, data, modified FROM team_avatars WHERE team_id = $teamId"
        .as(TeamImageRepository.dataParser.singleOpt)
    }
  }

  /**
    * Stores a team's avatar, replacing any avatar it already had. Returns the
    * upload time, which callers use to version the avatar's url so a re-upload
    * is not masked by a cached response.
    *
    * Accepts a caller-supplied connection so storing the bytes and pointing the
    * team's avatar url at them can commit as one unit.
    *
    * @return The modified timestamp of the stored avatar
    */
  def upsert(
      teamId: Long,
      contentType: String,
      data: Array[Byte],
      uploadedBy: Long
  )(implicit c: Option[Connection] = None): DateTime = {
    this.withMRTransaction { implicit c =>
      SQL"""INSERT INTO team_avatars (team_id, content_type, data, uploaded_by)
            VALUES ($teamId, $contentType, $data, $uploadedBy)
            ON CONFLICT (team_id) DO UPDATE
              SET content_type = EXCLUDED.content_type, data = EXCLUDED.data,
                  uploaded_by = EXCLUDED.uploaded_by, modified = NOW()
            RETURNING modified"""
        .as(get[DateTime]("modified").single)
    }
  }

  /**
    * Deletes a team's stored avatar.
    *
    * Accepts a caller-supplied connection for the same reason `upsert` does:
    * dropping the bytes and clearing the team's avatar url are one fact.
    *
    * @return true if the team had a stored avatar to remove
    */
  def delete(teamId: Long)(implicit c: Option[Connection] = None): Boolean = {
    this.withMRTransaction { implicit c =>
      SQL"DELETE FROM team_avatars WHERE team_id = $teamId".executeUpdate() > 0
    }
  }
}

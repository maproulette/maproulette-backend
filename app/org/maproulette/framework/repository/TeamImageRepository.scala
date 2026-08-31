/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.repository

import anorm.SqlParser._
import anorm._
import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import org.maproulette.framework.model.{TeamImage, TeamImageData}
import play.api.db.Database

/**
  * Repository for team-owned challenge images and their review state.
  */
@Singleton
class TeamImageRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = TeamImage.TABLE

  // The image bytes are excluded on purpose so listings stay cheap; only
  // `retrieveData` pulls them.
  private val selectColumns =
    """ti.id, ti.team_id, g.name AS team_name, ti.name, ti.content_type,
       octet_length(ti.data) AS size, ti.status, ti.requested_by,
       requester.name AS requested_by_name, ti.reviewed_by,
       reviewer.name AS reviewed_by_name, ti.reviewed_at, ti.review_comment,
       ti.created, ti.modified"""

  private val fromClause =
    """FROM team_images ti
       INNER JOIN groups g ON g.id = ti.team_id
       LEFT JOIN users requester ON requester.id = ti.requested_by
       LEFT JOIN users reviewer ON reviewer.id = ti.reviewed_by"""

  private val parser: RowParser[TeamImage] = {
    get[Long]("id") ~
      get[Long]("team_id") ~
      get[Option[String]]("team_name") ~
      get[String]("name") ~
      get[String]("content_type") ~
      get[Long]("size") ~
      get[Int]("status") ~
      get[Option[Long]]("requested_by") ~
      get[Option[String]]("requested_by_name") ~
      get[Option[Long]]("reviewed_by") ~
      get[Option[String]]("reviewed_by_name") ~
      get[Option[DateTime]]("reviewed_at") ~
      get[Option[String]]("review_comment") ~
      get[DateTime]("created") ~
      get[DateTime]("modified") map {
      case id ~ teamId ~ teamName ~ name ~ contentType ~ size ~ status ~ requestedBy ~
            requestedByName ~ reviewedBy ~ reviewedByName ~ reviewedAt ~ reviewComment ~
            created ~ modified =>
        TeamImage(
          id,
          teamId,
          teamName,
          name,
          contentType,
          size,
          status,
          requestedBy,
          requestedByName,
          reviewedBy,
          reviewedByName,
          reviewedAt,
          reviewComment,
          created,
          modified
        )
    }
  }

  /**
    * Retrieves a single image's metadata.
    */
  def retrieve(id: Long): Option[TeamImage] = {
    this.withMRConnection { implicit c =>
      SQL(s"SELECT $selectColumns $fromClause WHERE ti.id = {id}")
        .on(Symbol("id") -> id)
        .as(this.parser.singleOpt)
    }
  }

  /**
    * Retrieves the bytes of an image, for serving it.
    */
  def retrieveData(id: Long): Option[TeamImageData] = {
    this.withMRConnection { implicit c =>
      SQL"SELECT content_type, data, modified FROM team_images WHERE id = $id"
        .as(
          (get[String]("content_type") ~ get[Array[Byte]]("data") ~ get[DateTime]("modified") map {
            case contentType ~ data ~ modified => TeamImageData(contentType, data, modified)
          }).singleOpt
        )
    }
  }

  /**
    * Lists a team's images, newest first. Optionally restricted to one status.
    */
  def listForTeam(teamId: Long, status: Option[Int] = None): List[TeamImage] = {
    this.withMRConnection { implicit c =>
      val statusClause = status.map(_ => "AND ti.status = {status}").getOrElse("")
      SQL(
        s"SELECT $selectColumns $fromClause WHERE ti.team_id = {teamId} $statusClause ORDER BY ti.created DESC"
      ).on(Symbol("teamId") -> teamId, Symbol("status") -> status)
        .as(this.parser.*)
    }
  }

  /**
    * Lists images across several teams, newest first. Used to build the set of
    * images a user may choose from across all of their team memberships.
    */
  def listForTeams(teamIds: List[Long], status: Option[Int] = None): List[TeamImage] = {
    if (teamIds.isEmpty) {
      return List()
    }

    this.withMRConnection { implicit c =>
      val statusClause = status.map(_ => "AND ti.status = {status}").getOrElse("")
      SQL(
        s"SELECT $selectColumns $fromClause WHERE ti.team_id IN ({teamIds}) $statusClause ORDER BY g.name, ti.created DESC"
      ).on(Symbol("teamIds") -> teamIds, Symbol("status") -> status)
        .as(this.parser.*)
    }
  }

  /**
    * Lists every image awaiting review, oldest first so the queue is served in
    * the order requests came in.
    */
  def listPending(): List[TeamImage] = {
    this.withMRConnection { implicit c =>
      SQL(s"SELECT $selectColumns $fromClause WHERE ti.status = {status} ORDER BY ti.created ASC")
        .on(Symbol("status") -> TeamImage.STATUS_PENDING)
        .as(this.parser.*)
    }
  }

  /**
    * How many of a team's images are still awaiting review.
    */
  def pendingCountForTeam(teamId: Long): Int = {
    this.withMRConnection { implicit c =>
      SQL"SELECT COUNT(*) FROM team_images WHERE team_id = $teamId AND status = ${TeamImage.STATUS_PENDING}"
        .as(scalar[Int].single)
    }
  }

  /**
    * Stores a new image request for a team, awaiting review.
    *
    * @return The id of the newly created request
    */
  def create(
      teamId: Long,
      name: String,
      contentType: String,
      data: Array[Byte],
      requestedBy: Long
  ): Long = {
    this.withMRTransaction { implicit c =>
      SQL"""INSERT INTO team_images (team_id, name, content_type, data, status, requested_by)
            VALUES ($teamId, $name, $contentType, $data, ${TeamImage.STATUS_PENDING}, $requestedBy)
            RETURNING id"""
        .as(scalar[Long].single)
    }
  }

  /**
    * The ids of the challenges currently using an image. Callers that mutate
    * an image need these to evict the affected challenges from the challenge
    * cache, which detaching via SQL would otherwise leave stale.
    */
  def challengeIdsUsing(imageId: Long): List[Long] = {
    this.withMRConnection { implicit c =>
      SQL"SELECT id FROM challenges WHERE team_image_id = $imageId".as(scalar[Long].*)
    }
  }

  /**
    * Records a review decision. Anything other than an approval also detaches
    * the image from every challenge using it, so rejecting a previously
    * approved image actually removes it from those cards.
    *
    * @return true if the image existed and was updated
    */
  def review(id: Long, status: Int, reviewedBy: Long, comment: Option[String]): Boolean = {
    this.withMRTransaction { implicit c =>
      val updated =
        SQL"""UPDATE team_images
              SET status = $status, reviewed_by = $reviewedBy, reviewed_at = NOW(),
                  review_comment = $comment, modified = NOW()
              WHERE id = $id"""
          .executeUpdate() > 0

      if (updated && status != TeamImage.STATUS_APPROVED) {
        SQL"UPDATE challenges SET team_image_id = NULL WHERE team_image_id = $id".executeUpdate()
      }

      updated
    }
  }

  /**
    * Deletes an image. The challenges foreign key nulls out any references, so
    * cards that were showing it fall back to no image.
    *
    * @return true if an image row was actually removed
    */
  def delete(id: Long): Boolean = {
    this.withMRTransaction { implicit c =>
      SQL"DELETE FROM team_images WHERE id = $id".executeUpdate() > 0
    }
  }
}

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
import org.maproulette.framework.model.{TeamImage, TeamImageData, TeamImageFile}
import org.maproulette.framework.psql.filter.{BaseParameter, FilterParameter, Operator}
import org.maproulette.framework.psql.{Order, OrderField, Query}
import play.api.db.Database

/**
  * Repository for team-owned challenge images and their review state.
  */
@Singleton
class TeamImageRepository @Inject() (override val db: Database) extends RepositoryMixin {
  import TeamImageRepository._

  implicit val baseTable: String = TeamImage.TABLE

  /**
    * Finds 0 or more images that match the filter criteria. Filters should
    * qualify their columns with the [[TeamImageRepository.ALIAS]] table, since
    * the base query joins several tables.
    *
    * @param query The psql query object containing all the filtering and ordering information
    * @param c An implicit connection, that defaults to None
    */
  def query(query: Query)(implicit c: Option[Connection] = None): List[TeamImage] = {
    this.withMRConnection { implicit c =>
      query.build(s"SELECT $selectColumns $fromClause").as(parser.*)
    }
  }

  /**
    * Retrieves a single image's metadata.
    */
  def retrieve(id: Long)(implicit c: Option[Connection] = None): Option[TeamImage] =
    this
      .query(Query.simple(List(BaseParameter(TeamImage.FIELD_ID, id, table = Some(ALIAS)))))
      .headOption

  /**
    * Retrieves only what is needed to decide whether an image may be served
    * and whether the caller's copy is still current. Deliberately avoids the
    * joins and the blob that the full metadata query pulls, because this runs
    * once per challenge card.
    */
  def retrieveFile(id: Long): Option[TeamImageFile] = {
    this.withMRConnection { implicit c =>
      SQL"SELECT team_id, status, content_type, modified FROM team_images WHERE id = $id"
        .as(fileParser.singleOpt)
    }
  }

  /**
    * Retrieves the bytes of an image, for serving it.
    */
  def retrieveData(id: Long): Option[TeamImageData] = {
    this.withMRConnection { implicit c =>
      SQL"SELECT content_type, data, modified FROM team_images WHERE id = $id"
        .as(dataParser.singleOpt)
    }
  }

  /**
    * Lists a team's images, newest first. Optionally restricted to one status.
    */
  def listForTeam(teamId: Long, status: Option[Int] = None): List[TeamImage] =
    this.listForTeams(List(teamId), status)

  /**
    * Lists images across one or more teams, grouped by team and newest first.
    * Used to build the set of images a user may choose from across all of
    * their team memberships.
    */
  def listForTeams(teamIds: List[Long], status: Option[Int] = None): List[TeamImage] = {
    if (teamIds.isEmpty) {
      return List()
    }

    this.query(
      Query.simple(
        List(
          BaseParameter(TeamImage.FIELD_TEAM_ID, teamIds, Operator.IN, table = Some(ALIAS)),
          FilterParameter.conditional(
            TeamImage.FIELD_STATUS,
            status.getOrElse(TeamImage.STATUS_PENDING),
            includeOnlyIfTrue = status.isDefined,
            table = Some(ALIAS)
          )
        ),
        order = Order(
          List(
            OrderField(TeamImage.FIELD_NAME, Order.ASC, Some("g")),
            OrderField(TeamImage.FIELD_CREATED, Order.DESC, Some(ALIAS))
          )
        )
      )
    )
  }

  /**
    * Lists every image awaiting review, oldest first so the queue is served in
    * the order requests came in.
    */
  def listPending(): List[TeamImage] =
    this.query(
      Query.simple(
        List(
          BaseParameter(TeamImage.FIELD_STATUS, TeamImage.STATUS_PENDING, table = Some(ALIAS))
        ),
        order = Order(List(OrderField(TeamImage.FIELD_CREATED, Order.ASC, Some(ALIAS))))
      )
    )

  /**
    * A team's one image in the given review state - the image it is currently
    * using for `STATUS_APPROVED`, the request in front of the reviewers for
    * `STATUS_PENDING`. Rejected images are history rather than a single
    * current thing, so asking for those is not what this is for.
    */
  def currentForTeam(teamId: Long, status: Int): Option[TeamImage] =
    this.listForTeams(List(teamId), Some(status)).headOption

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
    * Records a review decision.
    *
    * Approving hands the team's single image slot to this image: the one the
    * team was using is replaced, and the challenges that were on it move
    * across first, since dropping that row would otherwise null them out
    * through the foreign key and quietly strip the image from those cards.
    * Anything other than an approval instead detaches this image from every
    * challenge using it, so rejecting a previously approved image actually
    * removes it from those cards.
    *
    * The replacement is resolved inside the transaction rather than handed in
    * by the caller, so nothing can slot a second approved image in between.
    *
    * @return None if no such image exists, otherwise the ids of the challenges
    *         whose image changed, which the caller has to evict from the
    *         challenge cache
    */
  def review(
      id: Long,
      status: Int,
      reviewedBy: Long,
      comment: Option[String]
  ): Option[List[Long]] = {
    this.withMRTransaction { implicit c =>
      // Resolved before the status change, while the image being replaced is
      // still the team's approved one and this one is not.
      val replaced =
        if (status == TeamImage.STATUS_APPROVED) {
          SQL"""SELECT replaced.id FROM team_images replaced
                INNER JOIN team_images reviewed ON reviewed.team_id = replaced.team_id
                WHERE reviewed.id = $id AND replaced.id <> $id
                  AND replaced.status = ${TeamImage.STATUS_APPROVED}"""
            .as(scalar[Long].*)
        } else {
          List()
        }

      val moved = replaced.flatMap { replacedId =>
        val affected =
          SQL"SELECT id FROM challenges WHERE team_image_id = $replacedId".as(scalar[Long].*)
        SQL"UPDATE challenges SET team_image_id = $id WHERE team_image_id = $replacedId"
          .executeUpdate()
        SQL"DELETE FROM team_images WHERE id = $replacedId".executeUpdate()
        affected
      }

      val updated =
        SQL"""UPDATE team_images
              SET status = $status, reviewed_by = $reviewedBy, reviewed_at = NOW(),
                  review_comment = $comment, modified = NOW()
              WHERE id = $id"""
          .executeUpdate() > 0

      if (!updated) {
        None
      } else if (status == TeamImage.STATUS_APPROVED) {
        Some(moved)
      } else {
        val detached = SQL"SELECT id FROM challenges WHERE team_image_id = $id".as(scalar[Long].*)
        SQL"UPDATE challenges SET team_image_id = NULL WHERE team_image_id = $id".executeUpdate()
        Some(detached)
      }
    }
  }

  /**
    * Deletes an image. The challenges foreign key nulls out any references, so
    * cards that were showing it fall back to no image.
    *
    * @return None if no such image exists, otherwise the ids of the challenges
    *         that lost the image, which the caller has to evict from the
    *         challenge cache
    */
  def delete(id: Long): Option[List[Long]] = {
    this.withMRTransaction { implicit c =>
      val affected = SQL"SELECT id FROM challenges WHERE team_image_id = $id".as(scalar[Long].*)
      if (SQL"DELETE FROM team_images WHERE id = $id".executeUpdate() > 0) Some(affected) else None
    }
  }
}

object TeamImageRepository {
  // The alias the base query gives team_images, which filters have to qualify
  // their columns with to stay unambiguous against the joined tables.
  val ALIAS = "ti"

  // The image bytes are excluded on purpose so listings stay cheap; only
  // `retrieveData` pulls them. octet_length reads the size out of the TOAST
  // header, so naming `data` here does not detoast it.
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

  // The aliases above are the snake_case of every TeamImage field, so the
  // macro parser maps them without a hand-written column list.
  val parser: RowParser[TeamImage] =
    Macro.namedParser[TeamImage](Macro.ColumnNaming.SnakeCase)

  val fileParser: RowParser[TeamImageFile] =
    get[Long]("team_id") ~ get[Int]("status") ~ get[String]("content_type") ~
      get[DateTime]("modified") map {
      case teamId ~ status ~ contentType ~ modified =>
        TeamImageFile(teamId, status, contentType, modified)
    }

  val dataParser: RowParser[TeamImageData] =
    get[String]("content_type") ~ get[Array[Byte]]("data") ~ get[DateTime]("modified") map {
      case contentType ~ data ~ modified => TeamImageData(contentType, data, modified)
    }
}

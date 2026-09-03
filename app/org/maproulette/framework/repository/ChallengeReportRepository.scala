/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.repository

import anorm.SqlParser._
import anorm._
import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import java.sql.Connection
import org.maproulette.framework.model.{ChallengeReport, User}
import play.api.db.Database

/**
  * Repository for reports filed against a challenge's design and their triage
  * state.
  */
@Singleton
class ChallengeReportRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = ChallengeReport.TABLE

  // The challenge and project are joined in so the dashboard can render a
  // report without a follow-up request per row.
  private val selectColumns =
    """cr.id, cr.challenge_id, c.name AS challenge_name, c.is_archived AS challenge_is_archived,
       c.parent_id AS project_id, p.display_name AS project_name, cr.reporter_id,
       reporter.name AS reporter_name, cr.reporter_email, cr.comment, cr.status,
       cr.reviewed_by, reviewer.name AS reviewed_by_name, cr.reviewed_at,
       cr.review_comment, cr.reported_at"""

  private val fromClause =
    """FROM challenge_reports cr
       INNER JOIN challenges c ON c.id = cr.challenge_id
       INNER JOIN projects p ON p.id = c.parent_id
       LEFT JOIN users reporter ON reporter.id = cr.reporter_id
       LEFT JOIN users reviewer ON reviewer.id = cr.reviewed_by"""

  // `full_count` is always selected: for the single-row reads it is simply 1,
  // and for a page of the listing it is the total number of matches.
  private val parser: RowParser[ChallengeReport] = {
    get[Long]("id") ~
      get[Long]("challenge_id") ~
      get[Option[String]]("challenge_name") ~
      get[Option[Boolean]]("challenge_is_archived") ~
      get[Option[Long]]("project_id") ~
      get[Option[String]]("project_name") ~
      get[Option[Long]]("reporter_id") ~
      get[Option[String]]("reporter_name") ~
      get[Option[String]]("reporter_email") ~
      get[String]("comment") ~
      get[Int]("status") ~
      get[Option[Long]]("reviewed_by") ~
      get[Option[String]]("reviewed_by_name") ~
      get[Option[DateTime]]("reviewed_at") ~
      get[Option[String]]("review_comment") ~
      get[DateTime]("reported_at") ~
      get[Option[Int]]("full_count") map {
      case id ~ challengeId ~ challengeName ~ challengeIsArchived ~ projectId ~ projectName ~
            reporterId ~ reporterName ~ reporterEmail ~ comment ~ status ~ reviewedBy ~
            reviewedByName ~ reviewedAt ~ reviewComment ~ reportedAt ~ fullCount =>
        ChallengeReport(
          id = id,
          challengeId = challengeId,
          comment = comment,
          reportedAt = reportedAt,
          status = status,
          challengeName = challengeName,
          challengeIsArchived = challengeIsArchived,
          projectId = projectId,
          projectName = projectName,
          reporterId = reporterId,
          reporterName = reporterName,
          reporterEmail = reporterEmail,
          reviewedBy = reviewedBy,
          reviewedByName = reviewedByName,
          reviewedAt = reviewedAt,
          reviewComment = reviewComment,
          fullCount = fullCount.getOrElse(0)
        )
    }
  }

  /**
    * Files a new report against a challenge.
    *
    * @param user The user filing the report
    * @param challengeId The challenge being reported
    * @param comment The reporter's explanation of the problem
    * @param reporterEmail An optional contact address for follow-up
    * @return The newly created report
    */
  def create(
      user: User,
      challengeId: Long,
      comment: String,
      reporterEmail: Option[String]
  ): ChallengeReport = {
    this.withMRTransaction { implicit c =>
      val id =
        SQL"""INSERT INTO challenge_reports (challenge_id, reporter_id, reporter_email, comment)
              VALUES ($challengeId, ${user.id}, $reporterEmail, $comment)
              RETURNING id"""
          .as(get[Long]("id").single)

      // Read back through the joined select so the caller gets the same shape
      // the listing returns, challenge and project names included. It has to
      // run on this connection -- the insert is not committed yet.
      this.retrieve(id)(Some(c)).get
    }
  }

  /**
    * Retrieves a single report.
    */
  def retrieve(id: Long)(
      implicit c: Option[Connection] = None
  ): Option[ChallengeReport] = {
    this.withMRConnection { implicit c =>
      SQL(s"SELECT COUNT(*) OVER() AS full_count, $selectColumns $fromClause WHERE cr.id = {id}")
        .on(Symbol("id") -> id)
        .as(this.parser.singleOpt)
    }
  }

  /**
    * Lists reports newest first, for the admin dashboard.
    *
    * @param status Restrict to one triage status
    * @param challengeId Restrict to a single challenge
    * @param activeOnly Only reports whose challenge is neither deleted nor archived,
    *                   which is the view an admin acts on
    * @param limit Page size
    * @param page Zero-based page number
    * @return The matching reports, each carrying the total match count
    */
  def list(
      status: Option[Int] = None,
      challengeId: Option[Long] = None,
      activeOnly: Boolean = false,
      limit: Int = 50,
      page: Int = 0
  ): List[ChallengeReport] = {
    this.withMRConnection { implicit c =>
      val clauses = List(
        status.map(_ => "cr.status = {status}"),
        challengeId.map(_ => "cr.challenge_id = {challengeId}"),
        if (activeOnly) Some("c.deleted = false AND c.is_archived = false") else None
      ).flatten

      val whereClause = if (clauses.isEmpty) "" else s"WHERE ${clauses.mkString(" AND ")}"

      SQL(
        s"""SELECT COUNT(*) OVER() AS full_count, $selectColumns
            $fromClause
            $whereClause
            ORDER BY cr.reported_at DESC
            LIMIT {limit} OFFSET {offset}"""
      ).on(
          Symbol("status")      -> status,
          Symbol("challengeId") -> challengeId,
          Symbol("limit")       -> limit,
          Symbol("offset")      -> (limit.toLong * page)
        )
        .as(this.parser.*)
    }
  }

  /**
    * Retrieves a reporter's still-open report against one challenge, if they
    * have one. This is the only report-reading path open to a non-superuser:
    * it returns nothing but the caller's own report.
    */
  def retrieveOpenForReporter(challengeId: Long, reporterId: Long): Option[ChallengeReport] = {
    this.withMRConnection { implicit c =>
      SQL(
        s"""SELECT COUNT(*) OVER() AS full_count, $selectColumns
            $fromClause
            WHERE cr.challenge_id = {challengeId}
              AND cr.reporter_id = {reporterId}
              AND cr.status = {status}
            ORDER BY cr.reported_at DESC
            LIMIT 1"""
      ).on(
          Symbol("challengeId") -> challengeId,
          Symbol("reporterId")  -> reporterId,
          Symbol("status")      -> ChallengeReport.STATUS_OPEN
        )
        .as(this.parser.singleOpt)
    }
  }

  /**
    * Counts a reporter's still-open reports against one challenge, so the same
    * person cannot file the same complaint repeatedly.
    */
  def countOpenForReporter(challengeId: Long, reporterId: Long): Int = {
    this.withMRConnection { implicit c =>
      SQL"""SELECT COUNT(*) AS count FROM challenge_reports
            WHERE challenge_id = $challengeId
              AND reporter_id = $reporterId
              AND status = ${ChallengeReport.STATUS_OPEN}"""
        .as(get[Int]("count").single)
    }
  }

  /**
    * Records an admin's triage decision on a report.
    *
    * @param id The report being resolved
    * @param status The new triage status
    * @param reviewer The superuser making the decision
    * @param reviewComment An optional note about what was done
    * @return The updated report, or None if it no longer exists
    */
  def updateStatus(
      id: Long,
      status: Int,
      reviewer: User,
      reviewComment: Option[String]
  ): Option[ChallengeReport] = {
    this.withMRTransaction { implicit c =>
      val updated =
        SQL"""UPDATE challenge_reports
              SET status = $status,
                  reviewed_by = ${reviewer.id},
                  reviewed_at = NOW(),
                  review_comment = $reviewComment
              WHERE id = $id"""
          .executeUpdate()

      if (updated == 0) None else this.retrieve(id)(Some(c))
    }
  }
}

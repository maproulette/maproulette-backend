/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.joda.time.DateTime
import play.api.libs.json.JodaWrites._
import play.api.libs.json._

/**
  * A report against a challenge's design -- "this challenge is poorly designed
  * and is causing people to make incorrect edits" -- rather than a bug or a
  * feature request. Any authenticated user can file one; only superusers can
  * read them, because a report names the reporter and may carry the email
  * address they volunteered for follow-up.
  *
  * Reports are resolved, never deleted: an admin marks one actioned (say, after
  * archiving the challenge) or dismissed, so the history of what was reported
  * and what was done about it stays intact.
  */
case class ChallengeReport(
    override val id: Long,
    challengeId: Long,
    comment: String,
    reportedAt: DateTime,
    status: Int = ChallengeReport.STATUS_OPEN,
    challengeName: Option[String] = None,
    challengeIsArchived: Option[Boolean] = None,
    projectId: Option[Long] = None,
    projectName: Option[String] = None,
    reporterId: Option[Long] = None,
    reporterName: Option[String] = None,
    reporterEmail: Option[String] = None,
    reviewedBy: Option[Long] = None,
    reviewedByName: Option[String] = None,
    reviewedAt: Option[DateTime] = None,
    reviewComment: Option[String] = None,
    fullCount: Int = 0
) extends Identifiable

object ChallengeReport {
  implicit val writes: Writes[ChallengeReport] = new Writes[ChallengeReport] {
    def writes(report: ChallengeReport): JsValue =
      Json.obj(
        "id"                  -> report.id,
        "challengeId"         -> report.challengeId,
        "challengeName"       -> report.challengeName,
        "challengeIsArchived" -> report.challengeIsArchived,
        "projectId"           -> report.projectId,
        "projectName"         -> report.projectName,
        "reporterId"          -> report.reporterId,
        "reporterName"        -> report.reporterName,
        "reporterEmail"       -> report.reporterEmail,
        "comment"             -> report.comment,
        "status"              -> report.status,
        "statusName"          -> statusName(report.status),
        "reviewedBy"          -> report.reviewedBy,
        "reviewedByName"      -> report.reviewedByName,
        "reviewedAt"          -> report.reviewedAt,
        "reviewComment"       -> report.reviewComment,
        "reportedAt"          -> report.reportedAt,
        "fullCount"           -> report.fullCount
      )
  }

  val TABLE = "challenge_reports"

  val FIELD_ID           = "id"
  val FIELD_CHALLENGE_ID = "challenge_id"
  val FIELD_STATUS       = "status"
  val FIELD_REPORTED_AT  = "reported_at"

  val STATUS_OPEN      = 0
  val STATUS_ACTIONED  = 1
  val STATUS_DISMISSED = 2

  val statusNames: Map[Int, String] = Map(
    STATUS_OPEN      -> "open",
    STATUS_ACTIONED  -> "actioned",
    STATUS_DISMISSED -> "dismissed"
  )

  def statusName(status: Int): String = statusNames.getOrElse(status, "unknown")

  def isValidStatus(status: Int): Boolean = statusNames.contains(status)

  /**
    * Resolves a status name the client sent ("open", "actioned", "dismissed")
    * to its stored value, so callers never have to hardcode the integers.
    */
  def statusFromName(name: String): Option[Int] =
    statusNames.collectFirst { case (value, n) if n == name.trim.toLowerCase => value }

  // A report has to say enough for an admin to act on it, and these bounds are
  // enforced here rather than only in the form so the endpoint can be trusted
  // on its own.
  val MIN_COMMENT_LENGTH = 100
  val MAX_COMMENT_LENGTH = 1000

  // A reporter cannot pile unlimited open reports onto one challenge.
  val MAX_OPEN_PER_REPORTER_PER_CHALLENGE = 1
}

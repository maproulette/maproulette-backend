/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.apache.commons.lang3.StringUtils
import org.maproulette.Config
import org.maproulette.exception.{InvalidException, NotFoundException}
import org.maproulette.framework.model.{Challenge, ChallengeReport, User}
import org.maproulette.framework.repository.ChallengeReportRepository
import org.maproulette.models.dal.ChallengeDAL
import org.maproulette.permissions.Permission
import org.slf4j.LoggerFactory

/**
  * Handles reports filed against a challenge's design. Filing is open to any
  * authenticated user; reading and triaging is restricted to superusers, since
  * a report carries the reporter's identity and possibly their email address.
  *
  * Filing a report also posts a challenge comment naming the reporter and
  * quoting the report, which is what tells the challenge owner that something
  * was raised -- the report row itself is not visible to them.
  */
@Singleton
class ChallengeReportService @Inject() (
    repository: ChallengeReportRepository,
    commentService: CommentService,
    challengeDAL: ChallengeDAL,
    permission: Permission,
    config: Config
) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  /**
    * Files a report against a challenge and posts the accompanying challenge
    * comment.
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
    val challenge = this.challengeDAL.retrieveById(challengeId) match {
      case Some(c) => c
      case None =>
        throw new NotFoundException(s"Challenge with id $challengeId not found, cannot report it.")
    }

    val trimmed = Option(comment).map(_.trim).getOrElse("")
    if (StringUtils.isEmpty(trimmed)) {
      throw new InvalidException("A report must explain the problem with the challenge.")
    }
    if (trimmed.length < ChallengeReport.MIN_COMMENT_LENGTH) {
      throw new InvalidException(
        s"A report must be at least ${ChallengeReport.MIN_COMMENT_LENGTH} characters."
      )
    }
    if (trimmed.length > ChallengeReport.MAX_COMMENT_LENGTH) {
      throw new InvalidException(
        s"A report cannot be longer than ${ChallengeReport.MAX_COMMENT_LENGTH} characters."
      )
    }

    val email = reporterEmail.map(_.trim).filter(_.nonEmpty)
    email.foreach { address =>
      if (!ChallengeReportService.EMAIL_PATTERN.matcher(address).matches()) {
        throw new InvalidException(s"'$address' is not a valid email address.")
      }
    }

    if (this.repository.countOpenForReporter(challengeId, user.id) >=
          ChallengeReport.MAX_OPEN_PER_REPORTER_PER_CHALLENGE) {
      throw new InvalidException(
        "You already have an open report on this challenge; it is still awaiting review."
      )
    }

    val report = this.repository.create(user, challengeId, trimmed, email)

    // The comment is what surfaces the report to the challenge owner and other
    // mappers. The report itself is already stored at this point, so a failure
    // here is logged rather than propagated -- throwing would report failure
    // for a report that does in fact exist, and would invite the reporter to
    // file it a second time.
    try {
      this.commentService.createChallengeComment(
        user,
        challengeId,
        this.challengeCommentFor(challenge, user, trimmed)
      )
    } catch {
      case e: Exception =>
        this.logger.warn(
          s"Report ${report.id} on challenge $challengeId was stored, but its challenge comment could not be posted: ${e.getMessage}",
          e
        )
    }

    report
  }

  /**
    * Builds the public challenge comment that accompanies a report. Deliberately
    * omits the reporter's email address -- that is for admins only.
    */
  private def challengeCommentFor(challenge: Challenge, user: User, comment: String): String = {
    val frontend  = this.config.getMRFrontend.stripSuffix("/")
    val osmServer = this.config.getOSMServer.stripSuffix("/")
    val userName  = user.osmProfile.displayName
    // Paths match the frontend's routes -- /challenge/:id and /project/:id.
    val challengeUrl = s"$frontend/challenge/${challenge.id}"
    val userUrl      = s"$osmServer/user/${ChallengeReportService.urlEncode(userName)}"
    val projectUrl   = s"$frontend/project/${challenge.general.parent}"

    s"""This challenge, [#${challenge.id} - ${challenge.name}]($challengeUrl) in project [#${challenge.general.parent}]($projectUrl), has been reported by [$userName]($userUrl).
       |
       |Report Content:
       |$comment""".stripMargin
  }

  /**
    * Retrieves the requesting user's own still-open report against a challenge,
    * if they have one, so the UI can show that a report is already pending
    * instead of inviting a duplicate. Needs no elevated permission because it
    * can only ever return the caller's own report.
    *
    * @param user The requesting user
    * @param challengeId The challenge in question
    * @return The user's open report, if there is one
    */
  def retrieveOwnOpenReport(user: User, challengeId: Long): Option[ChallengeReport] =
    this.repository.retrieveOpenForReporter(challengeId, user.id)

  /**
    * Lists reports for the admin dashboard.
    *
    * @param user The requesting user, who must be a superuser
    * @param status Restrict to one triage status
    * @param challengeId Restrict to a single challenge
    * @param activeOnly Only reports on challenges that are still active
    * @param limit Page size
    * @param page Zero-based page number
    * @return The matching reports
    */
  def list(
      user: User,
      status: Option[Int] = None,
      challengeId: Option[Long] = None,
      activeOnly: Boolean = false,
      limit: Int = 50,
      page: Int = 0
  ): List[ChallengeReport] = {
    this.permission.hasSuperAccess(user)
    status.foreach { s =>
      if (!ChallengeReport.isValidStatus(s)) {
        throw new InvalidException(s"'$s' is not a valid report status.")
      }
    }
    this.repository.list(status, challengeId, activeOnly, limit, page)
  }

  /**
    * Retrieves a single report.
    *
    * @param user The requesting user, who must be a superuser
    * @param id The id of the report
    * @return The report, if it exists
    */
  def retrieve(user: User, id: Long): Option[ChallengeReport] = {
    this.permission.hasSuperAccess(user)
    this.repository.retrieve(id)
  }

  /**
    * Records a triage decision on a report.
    *
    * @param user The superuser making the decision
    * @param id The report being resolved
    * @param status The new triage status
    * @param reviewComment An optional note about what was done
    * @return The updated report
    */
  def updateStatus(
      user: User,
      id: Long,
      status: Int,
      reviewComment: Option[String]
  ): ChallengeReport = {
    this.permission.hasSuperAccess(user)
    if (!ChallengeReport.isValidStatus(status)) {
      throw new InvalidException(s"'$status' is not a valid report status.")
    }
    this.repository
      .updateStatus(id, status, user, reviewComment.map(_.trim).filter(_.nonEmpty))
      .getOrElse(throw new NotFoundException(s"Report with id $id not found."))
  }
}

object ChallengeReportService {
  private val EMAIL_PATTERN =
    java.util.regex.Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

  private def urlEncode(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

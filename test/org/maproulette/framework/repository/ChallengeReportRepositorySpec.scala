/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import org.maproulette.framework.model.{Challenge, ChallengeReport}
import org.maproulette.framework.util.{ChallengeReportRepoTag, FrameworkHelper}
import play.api.Application

class ChallengeReportRepositorySpec(implicit val application: Application)
    extends FrameworkHelper {
  val repository: ChallengeReportRepository =
    this.application.injector.instanceOf(classOf[ChallengeReportRepository])

  private val COMMENT = "This challenge asks mappers to delete valid buildings." * 2

  private def report(challenge: Challenge, email: Option[String] = None): ChallengeReport =
    this.repository.create(this.defaultUser, challenge.id, COMMENT, email)

  "ChallengeReportRepository" should {
    "store a report as open, with the reporter and the joined challenge recorded" taggedAs ChallengeReportRepoTag in {
      val created = report(this.defaultChallenge)

      created.challengeId mustEqual this.defaultChallenge.id
      created.challengeName mustEqual Some(this.defaultChallenge.name)
      created.projectId mustEqual Some(this.defaultChallenge.general.parent)
      created.projectName mustBe defined
      created.comment mustEqual COMMENT
      created.status mustEqual ChallengeReport.STATUS_OPEN
      created.reporterId mustEqual Some(this.defaultUser.id)
      created.reporterName mustEqual Some(this.defaultUser.name)
      created.reviewedBy mustEqual None
      created.reviewedAt mustEqual None
    }

    "keep an email only when one was volunteered" taggedAs ChallengeReportRepoTag in {
      report(this.defaultChallenge, Some("mapper@example.com")).reporterEmail mustEqual
        Some("mapper@example.com")
      report(this.defaultChallenge).reporterEmail mustEqual None
    }

    "return None for a report that does not exist" taggedAs ChallengeReportRepoTag in {
      this.repository.retrieve(-12345) mustEqual None
    }

    "record a triage decision and who made it" taggedAs ChallengeReportRepoTag in {
      val created = report(this.defaultChallenge)
      val updated = this.repository
        .updateStatus(
          created.id,
          ChallengeReport.STATUS_ACTIONED,
          this.defaultUser,
          Some("archived the challenge")
        )
        .get

      updated.status mustEqual ChallengeReport.STATUS_ACTIONED
      updated.reviewedBy mustEqual Some(this.defaultUser.id)
      updated.reviewedAt mustBe defined
      updated.reviewComment mustEqual Some("archived the challenge")
    }

    "report nothing updated for a report that does not exist" taggedAs ChallengeReportRepoTag in {
      this.repository.updateStatus(
        -12345,
        ChallengeReport.STATUS_ACTIONED,
        this.defaultUser,
        None
      ) mustEqual None
    }

    "count only a reporter's still-open reports on the challenge in question" taggedAs ChallengeReportRepoTag in {
      val challenge = this.createChallengeStructure("report_open_count", this.defaultProject.id, 1)

      this.repository.countOpenForReporter(challenge.id, this.defaultUser.id) mustEqual 0

      val open = report(challenge)
      this.repository.countOpenForReporter(challenge.id, this.defaultUser.id) mustEqual 1

      // Resolving it frees the reporter to raise the challenge again later.
      this.repository
        .updateStatus(open.id, ChallengeReport.STATUS_DISMISSED, this.defaultUser, None)
      this.repository.countOpenForReporter(challenge.id, this.defaultUser.id) mustEqual 0
    }

    "return a reporter's own open report, and nothing once it is resolved" taggedAs ChallengeReportRepoTag in {
      val challenge = this.createChallengeStructure("report_own_open", this.defaultProject.id, 1)

      this.repository.retrieveOpenForReporter(challenge.id, this.defaultUser.id) mustEqual None

      val open = report(challenge)
      this.repository
        .retrieveOpenForReporter(challenge.id, this.defaultUser.id)
        .map(_.id) mustEqual Some(open.id)

      this.repository
        .updateStatus(open.id, ChallengeReport.STATUS_ACTIONED, this.defaultUser, None)
      this.repository.retrieveOpenForReporter(challenge.id, this.defaultUser.id) mustEqual None
    }

    "filter a listing by status and by challenge, newest first" taggedAs ChallengeReportRepoTag in {
      val challenge = this.createChallengeStructure("report_listing", this.defaultProject.id, 1)

      val first  = report(challenge)
      val second = report(challenge)

      val all = this.repository.list(challengeId = Some(challenge.id))
      all.map(_.id) mustEqual List(second.id, first.id)
      all.head.fullCount mustEqual 2

      this.repository
        .updateStatus(first.id, ChallengeReport.STATUS_DISMISSED, this.defaultUser, None)

      this.repository
        .list(status = Some(ChallengeReport.STATUS_OPEN), challengeId = Some(challenge.id))
        .map(_.id) mustEqual List(second.id)
      this.repository
        .list(status = Some(ChallengeReport.STATUS_DISMISSED), challengeId = Some(challenge.id))
        .map(_.id) mustEqual List(first.id)
    }

    "page a listing, reporting the total match count on each row" taggedAs ChallengeReportRepoTag in {
      val challenge = this.createChallengeStructure("report_paging", this.defaultProject.id, 1)

      val first  = report(challenge)
      val second = report(challenge)

      val page0 = this.repository.list(challengeId = Some(challenge.id), limit = 1, page = 0)
      page0.map(_.id) mustEqual List(second.id)
      page0.head.fullCount mustEqual 2

      val page1 = this.repository.list(challengeId = Some(challenge.id), limit = 1, page = 1)
      page1.map(_.id) mustEqual List(first.id)
      page1.head.fullCount mustEqual 2
    }

    "exclude reports on archived challenges when only active ones are wanted" taggedAs ChallengeReportRepoTag in {
      val challenge = this.createChallengeStructure("report_archived", this.defaultProject.id, 1)
      val created   = report(challenge)

      this.repository
        .list(challengeId = Some(challenge.id), activeOnly = true)
        .map(_.id) mustEqual List(created.id)

      this.serviceManager.challenge.archiveChallenge(challenge.id, true)

      this.repository.list(challengeId = Some(challenge.id), activeOnly = true) mustEqual List()
      this.repository
        .list(challengeId = Some(challenge.id), activeOnly = false)
        .map(_.id) mustEqual List(created.id)
    }
  }

  override implicit val projectTestName: String = "ChallengeReportRepositorySpecProject"
}

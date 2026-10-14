/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import org.maproulette.framework.model.{ChallengeReport, User}
import org.maproulette.framework.util.{ChallengeReportTag, FrameworkHelper}
import play.api.Application

class ChallengeReportServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val service: ChallengeReportService =
    this.application.injector.instanceOf(classOf[ChallengeReportService])

  private val COMMENT = "This challenge asks mappers to delete valid buildings." * 2

  "ChallengeReportService" should {
    "list everyone's reports on a challenge, without the reporter's email" taggedAs ChallengeReportTag in {
      val challenge =
        this.createChallengeStructure("report_service_list", this.defaultProject.id, 1)

      this.service.retrieveReportsForChallenge(challenge.id) mustEqual List()

      val filed =
        this.service.create(this.defaultUser, challenge.id, COMMENT, Some("mapper@example.com"))
      filed.reporterEmail mustEqual Some("mapper@example.com")

      val visible = this.service.retrieveReportsForChallenge(challenge.id)
      visible.map(_.id) mustEqual List(filed.id)
      // The reporter is named -- filing already posted a challenge comment
      // saying so -- but the address they left for follow-up is not.
      visible.head.reporterName mustEqual Some(this.defaultUser.name)
      visible.head.reporterEmail mustEqual None
      visible.head.comment mustEqual COMMENT
    }

    "show that a report was resolved without exposing who ruled on it, or their note" taggedAs ChallengeReportTag in {
      val challenge =
        this.createChallengeStructure("report_service_resolved", this.defaultProject.id, 1)
      val filed = this.service.create(this.defaultUser, challenge.id, COMMENT, None)

      this.service.updateStatus(
        User.superUser,
        filed.id,
        ChallengeReport.STATUS_ACTIONED,
        Some("archived the challenge")
      )

      val visible = this.service.retrieveReportsForChallenge(challenge.id).head
      visible.status mustEqual ChallengeReport.STATUS_ACTIONED
      visible.reviewedAt mustBe defined
      visible.reviewedBy mustEqual None
      visible.reviewedByName mustEqual None
      visible.reviewComment mustEqual None

      // The admin dashboard still sees the whole record.
      val forAdmin = this.service.retrieve(User.superUser, filed.id).get
      forAdmin.reviewedBy mustEqual Some(User.superUser.id)
      forAdmin.reviewComment mustEqual Some("archived the challenge")
    }

    "name the challenges carrying an open report, so the archiver can skip them" taggedAs ChallengeReportTag in {
      val challenge =
        this.createChallengeStructure("report_service_open_ids", this.defaultProject.id, 1)

      this.service.challengeIdsWithOpenReports() must not contain challenge.id

      val filed = this.service.create(this.defaultUser, challenge.id, COMMENT, None)
      this.service.challengeIdsWithOpenReports() must contain(challenge.id)

      this.service
        .updateStatus(User.superUser, filed.id, ChallengeReport.STATUS_DISMISSED, None)
      this.service.challengeIdsWithOpenReports() must not contain challenge.id
    }
  }

  override implicit val projectTestName: String = "ChallengeReportServiceSpecProject"
}

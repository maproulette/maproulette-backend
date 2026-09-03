/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import org.maproulette.framework.model.{Group, MemberObject, TeamImage, User}
import org.maproulette.framework.util.{FrameworkHelper, TeamImageRepoTag}
import play.api.Application

class TeamImageRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  val repository: TeamImageRepository =
    this.application.injector.instanceOf(classOf[TeamImageRepository])

  var teamA: Group = null
  var teamB: Group = null

  private val PNG: Array[Byte] =
    Array(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)

  private def request(team: Group, name: String): Long =
    this.repository.create(team.id, name, "image/png", PNG, this.defaultUser.id)

  private def approve(id: Long): Boolean =
    this.repository.review(id, TeamImage.STATUS_APPROVED, this.defaultUser.id, None)

  // Read the column back through the repository rather than the DAL: the DAL
  // caches challenges, and evicting that cache after a detach is the
  // controller's job (see TeamImageController.withChallengeCacheEviction), not
  // something this repository can or should do.
  private def isAttached(challengeId: Long, imageId: Long): Boolean =
    this.repository.challengeIdsUsing(imageId).contains(challengeId)

  "TeamImageRepository" should {
    "store a request as pending, with the requester recorded" taggedAs TeamImageRepoTag in {
      val id    = request(this.teamA, "logo.png")
      val image = this.repository.retrieve(id).get

      image.teamId mustEqual this.teamA.id
      image.teamName mustEqual Some(this.teamA.name)
      image.name mustEqual "logo.png"
      image.contentType mustEqual "image/png"
      image.size mustEqual PNG.length
      image.status mustEqual TeamImage.STATUS_PENDING
      image.requestedBy mustEqual Some(this.defaultUser.id)
      image.reviewedBy mustEqual None
    }

    "return the bytes only when asked for them" taggedAs TeamImageRepoTag in {
      val id = request(this.teamA, "bytes.png")
      this.repository.retrieveData(id).get.data mustEqual PNG
      this.repository.retrieveData(id).get.contentType mustEqual "image/png"
    }

    "return None for an image that does not exist" taggedAs TeamImageRepoTag in {
      this.repository.retrieve(-12345) mustEqual None
      this.repository.retrieveData(-12345) mustEqual None
    }

    "record an approval and who made it" taggedAs TeamImageRepoTag in {
      val id = request(this.teamA, "approve-me.png")
      approve(id) mustEqual true

      val image = this.repository.retrieve(id).get
      image.status mustEqual TeamImage.STATUS_APPROVED
      image.reviewedBy mustEqual Some(this.defaultUser.id)
      image.reviewedAt mustBe defined
    }

    "record a rejection comment" taggedAs TeamImageRepoTag in {
      val id = request(this.teamA, "reject-me.png")
      this.repository
        .review(id, TeamImage.STATUS_REJECTED, this.defaultUser.id, Some("off brand")) mustEqual
        true

      val image = this.repository.retrieve(id).get
      image.status mustEqual TeamImage.STATUS_REJECTED
      image.reviewComment mustEqual Some("off brand")
    }

    "report nothing reviewed for an image that does not exist" taggedAs TeamImageRepoTag in {
      this.repository.review(-12345, TeamImage.STATUS_APPROVED, this.defaultUser.id, None) mustEqual
        false
    }

    "list a team's images regardless of status, and filter by status on request" taggedAs TeamImageRepoTag in {
      val approved = request(this.teamB, "b-approved.png")
      approve(approved)
      request(this.teamB, "b-pending.png")

      this.repository.listForTeam(this.teamB.id).map(_.name) must contain allOf
        ("b-approved.png", "b-pending.png")

      this.repository
        .listForTeam(this.teamB.id, Some(TeamImage.STATUS_APPROVED))
        .map(_.name) mustEqual List("b-approved.png")
    }

    "list approved images across several teams" taggedAs TeamImageRepoTag in {
      val a = request(this.teamA, "multi-a.png")
      val b = request(this.teamB, "multi-b.png")
      approve(a)
      approve(b)

      val names = this.repository
        .listForTeams(List(this.teamA.id, this.teamB.id), Some(TeamImage.STATUS_APPROVED))
        .map(_.name)

      names must contain allOf ("multi-a.png", "multi-b.png")
    }

    "return nothing when asked about no teams at all" taggedAs TeamImageRepoTag in {
      this.repository.listForTeams(List(), Some(TeamImage.STATUS_APPROVED)) mustEqual List()
    }

    "surface pending requests in the review queue and count them per team" taggedAs TeamImageRepoTag in {
      val before = this.repository.pendingCountForTeam(this.teamA.id)
      val id     = request(this.teamA, "queued.png")

      this.repository.pendingCountForTeam(this.teamA.id) mustEqual before + 1
      this.repository.listPending().map(_.id) must contain(id)

      approve(id)
      this.repository.pendingCountForTeam(this.teamA.id) mustEqual before
      this.repository.listPending().map(_.id) must not contain id
    }

    "detach a rejected image from the challenges using it" taggedAs TeamImageRepoTag in {
      val id = request(this.teamA, "revoke-me.png")
      approve(id)

      val challenge = this.challengeDAL
        .insert(this.getTestChallenge("tiRevokeChallenge"), this.defaultUser)
      this.challengeDAL
        .update(play.api.libs.json.Json.obj("teamImageId" -> id), User.superUser)(challenge.id)
      isAttached(challenge.id, id) mustEqual true

      this.repository.review(id, TeamImage.STATUS_REJECTED, this.defaultUser.id, None)

      isAttached(challenge.id, id) mustEqual false
    }

    "leave challenge references alone when an image is approved" taggedAs TeamImageRepoTag in {
      val id = request(this.teamA, "keep-me.png")
      approve(id)

      val challenge = this.challengeDAL
        .insert(this.getTestChallenge("tiKeepChallenge"), this.defaultUser)
      this.challengeDAL
        .update(play.api.libs.json.Json.obj("teamImageId" -> id), User.superUser)(challenge.id)

      // Re-approving must not disturb challenges already using the image.
      approve(id)
      isAttached(challenge.id, id) mustEqual true
    }

    "detach a deleted image from the challenges using it" taggedAs TeamImageRepoTag in {
      val id = request(this.teamA, "delete-me.png")
      approve(id)

      val challenge = this.challengeDAL
        .insert(this.getTestChallenge("tiDeleteChallenge"), this.defaultUser)
      this.challengeDAL
        .update(play.api.libs.json.Json.obj("teamImageId" -> id), User.superUser)(challenge.id)

      this.repository.challengeIdsUsing(id) mustEqual List(challenge.id)
      this.repository.delete(id) mustEqual true

      this.repository.retrieve(id) mustEqual None
      this.repository.challengeIdsUsing(id) mustEqual List()
    }

    "report nothing removed when the image does not exist" taggedAs TeamImageRepoTag in {
      this.repository.delete(-12345) mustEqual false
    }

    "drop a team's images when the team itself is deleted" taggedAs TeamImageRepoTag in {
      val doomed = this.serviceManager.team
        .create(
          this.getTestTeam("TeamImageRepositorySpec Doomed Team"),
          MemberObject.user(this.defaultUser.id),
          this.defaultUser
        )
        .get
      val id = request(doomed, "doomed.png")

      this.serviceManager.team.deleteTeam(doomed, this.defaultUser)

      this.repository.retrieve(id) mustEqual None
    }
  }

  override implicit val projectTestName: String = "TeamImageRepositorySpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    this.teamA = this.serviceManager.team
      .create(
        this.getTestTeam("TeamImageRepositorySpec Team A"),
        MemberObject.user(this.defaultUser.id),
        this.defaultUser
      )
      .get
    this.teamB = this.serviceManager.team
      .create(
        this.getTestTeam("TeamImageRepositorySpec Team B"),
        MemberObject.user(this.defaultUser.id),
        this.defaultUser
      )
      .get
  }
}

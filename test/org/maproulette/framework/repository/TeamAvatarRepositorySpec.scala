/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import org.maproulette.framework.model.{Group, MemberObject, TeamAvatar}
import org.maproulette.framework.util.{FrameworkHelper, TeamAvatarRepoTag}
import play.api.Application

class TeamAvatarRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  val repository: TeamAvatarRepository =
    this.application.injector.instanceOf(classOf[TeamAvatarRepository])

  var team: Group = null

  private val PNG: Array[Byte] =
    Array(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)
  private val GIF: Array[Byte] = Array(0x47, 0x49, 0x46, 0x38, 0x39, 0x61).map(_.toByte)

  private def newTeam(name: String): Group =
    this.serviceManager.team
      .create(this.getTestTeam(name), MemberObject.user(this.defaultUser.id), this.defaultUser)
      .get

  "TeamAvatarRepository" should {
    "store an avatar, recording who uploaded it" taggedAs TeamAvatarRepoTag in {
      this.repository.upsert(this.team.id, "image/png", PNG, this.defaultUser.id)

      val avatar = this.repository.retrieve(this.team.id).get
      avatar.teamId mustEqual this.team.id
      avatar.contentType mustEqual "image/png"
      avatar.size mustEqual PNG.length
      avatar.uploadedBy mustEqual Some(this.defaultUser.id)
    }

    "return the bytes only when asked for them" taggedAs TeamAvatarRepoTag in {
      val subject = newTeam("TeamAvatarRepositorySpec Bytes Team")
      this.repository.upsert(subject.id, "image/png", PNG, this.defaultUser.id)

      val data = this.repository.retrieveData(subject.id).get
      data.data mustEqual PNG
      data.contentType mustEqual "image/png"
    }

    "return None for a team with no avatar" taggedAs TeamAvatarRepoTag in {
      val bare = newTeam("TeamAvatarRepositorySpec Bare Team")
      this.repository.retrieve(bare.id) mustEqual None
      this.repository.retrieveData(bare.id) mustEqual None
    }

    "replace an existing avatar rather than accumulating one per upload" taggedAs TeamAvatarRepoTag in {
      val subject = newTeam("TeamAvatarRepositorySpec Replace Team")
      this.repository.upsert(subject.id, "image/png", PNG, this.defaultUser.id)
      this.repository.upsert(subject.id, "image/gif", GIF, this.defaultUser.id)

      val avatar = this.repository.retrieve(subject.id).get
      avatar.contentType mustEqual "image/gif"
      avatar.size mustEqual GIF.length
      this.repository.retrieveData(subject.id).get.data mustEqual GIF
    }

    "move the modified stamp forward on replacement, so the avatar url changes" taggedAs TeamAvatarRepoTag in {
      val subject = newTeam("TeamAvatarRepositorySpec Stamp Team")
      val first   = this.repository.upsert(subject.id, "image/png", PNG, this.defaultUser.id)
      val second  = this.repository.upsert(subject.id, "image/gif", GIF, this.defaultUser.id)

      // A cached response keyed on the old url must not survive a re-upload
      second.getMillis must be >= first.getMillis
      TeamAvatar.urlFor(subject.id, second.getMillis) must not equal
        TeamAvatar.urlFor(subject.id, first.getMillis)
    }

    "remove a stored avatar, and report when there was none to remove" taggedAs TeamAvatarRepoTag in {
      val subject = newTeam("TeamAvatarRepositorySpec Delete Team")
      this.repository.upsert(subject.id, "image/png", PNG, this.defaultUser.id)

      this.repository.delete(subject.id) mustEqual true
      this.repository.retrieve(subject.id) mustEqual None
      this.repository.delete(subject.id) mustEqual false
    }

    "drop a team's avatar when the team itself is deleted" taggedAs TeamAvatarRepoTag in {
      val doomed = newTeam("TeamAvatarRepositorySpec Doomed Team")
      this.repository.upsert(doomed.id, "image/png", PNG, this.defaultUser.id)

      this.serviceManager.team.deleteTeam(doomed, this.defaultUser)

      this.repository.retrieve(doomed.id) mustEqual None
    }
  }

  override implicit val projectTestName: String = "TeamAvatarRepositorySpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    this.team = newTeam("TeamAvatarRepositorySpec Team")
  }
}

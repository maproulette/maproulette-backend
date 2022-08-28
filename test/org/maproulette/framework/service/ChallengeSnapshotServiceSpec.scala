/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import org.maproulette.framework.util.{ChallengeSnapshotTag, FrameworkHelper}
import org.maproulette.data.SnapshotManager
import play.api.Application

/**
  * @author krotstan
  */
class ChallengeSnapshotServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val service: ChallengeSnapshotService = this.serviceManager.challengeSnapshot
  var snapshotId: Long                  = -1

  "ChallengeSnapshotService" should {
    "delete a snapshot" taggedAs (ChallengeSnapshotTag) in {
      this.snapshotManager.getChallengeSnapshotList(this.defaultChallenge.id).length mustEqual 1
      this.service.delete(snapshotId, this.defaultUser)
      this.snapshotManager.getChallengeSnapshotList(this.defaultChallenge.id).length mustEqual 0
    }
  }

  override implicit val projectTestName: String = "ChallengeSnapshotSpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    snapshotId = ChallengeSnapshotServiceSpec.setup(this.snapshotManager, this.defaultChallenge.id)
  }
}

object ChallengeSnapshotServiceSpec {
  def setup(snapshotManager: SnapshotManager, defaultChallengeId: Long): Long = {
    snapshotManager.recordChallengeSnapshot(defaultChallengeId, true)
  }
}

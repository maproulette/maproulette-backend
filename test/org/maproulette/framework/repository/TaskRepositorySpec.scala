/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.util.UUID

import org.maproulette.framework.model.{User, Task}
import org.maproulette.framework.util.{TaskTag, FrameworkHelper}
import play.api.Application
import play.api.libs.json.Json

/**
  * @author nrotstan
  */
class TaskRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  val repository: TaskRepository =
    this.application.injector.instanceOf(classOf[TaskRepository])

  "TaskRepository" should {
    "retrieve task attachment" taggedAs TaskTag in {
      val task = this.taskDAL.insert(this.getTestTaskWithAttachments(), User.superUser)
      val attachment =
        this.repository.getTaskAttachment(task.id, "74bc872f-8448-45ff-b8f2-66517a35b41e")

      attachment.isDefined mustEqual true
      (attachment.get \ "id").as[String] mustEqual "74bc872f-8448-45ff-b8f2-66517a35b41e"
    }

    "return None for non-existent attachment" taggedAs TaskTag in {
      val task       = this.taskDAL.insert(this.getTestTaskWithAttachments(), User.superUser)
      val attachment = this.repository.getTaskAttachment(task.id, "no-such-attachment-id")

      attachment.isDefined mustEqual false
    }
  }

  override implicit val projectTestName: String = "TaskRepositorySpecProject"

  protected def getTestTaskWithAttachments(): Task =
    Task(
      -1,
      UUID.randomUUID().toString,
      null,
      null,
      this.defaultChallenge.id,
      geometries = Json.parse(
        """{"features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[[-60.811801,-32.9199812],[-60.8117804,-32.9199856],[-60.8117816,-32.9199896],[-60.8117873,-32.919984]]},"properties":{"osm_id":"OSM_W_378169283_000000_000","pbfHistory":["20200110-043000"]}}], "attachments": [{"id": "74bc872f-8448-45ff-b8f2-66517a35b41e", "kind": "referenceLayer", "type": "geojson", "name": "geojson boundary", "data": {"type": "Feature", "geometry": {"type": "Polygon", "coordinates": [[[-121.0, 48.0],[-120.0, 48.0],[-120.0, 49.0],[-121.0, 49],[-121.0, 48.0]]]}}}]}"""
      )
    )
}

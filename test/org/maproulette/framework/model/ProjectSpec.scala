/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.model

import org.scalatestplus.play.PlaySpec
import play.api.libs.json.Json

class ProjectSpec extends PlaySpec {
  private val projectId = 5L

  private def grantTo(grantee: Grantee, role: Int): Grant =
    Grant(-1, "", grantee, role, GrantTarget.project(projectId))

  private def project(grants: List[Grant]): Project =
    Project(projectId, 1, "project_with_teams", grants = grants)

  "Project json" should {
    "carry the owning team's image url" in {
      val json = Json.toJson(project(List()).copy(ownerTeamId = Some(30)))

      (json \ "ownerTeamId").asOpt[Long] mustEqual Some(30)
      (json \ "avatarUrl").asOpt[String] mustEqual Some(TeamImage.urlForTeam(30))
    }

    "omit the image entirely when no team owns the project, rather than send a dead link" in {
      val json = Json.toJson(project(List(grantTo(Grantee.group(30), Grant.ROLE_ADMIN))))

      (json \ "avatarUrl").asOpt[String] mustEqual None
    }

    "not take an image from a team merely granted a role on the project" in {
      // A team helping run a project is not the team behind it; only ownership
      // puts a picture on the card.
      val granted = project(List(grantTo(Grantee.group(30), Grant.ROLE_ADMIN)))

      (Json.toJson(granted) \ "ownerTeamId").asOpt[Long] mustEqual None
    }

    "still carry the fields it always did" in {
      val json = Json.toJson(project(List()))

      (json \ "id").as[Long] mustEqual projectId
      (json \ "name").as[String] mustEqual "project_with_teams"
    }
  }
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.model

import org.scalatestplus.play.PlaySpec

class TeamAvatarSpec extends PlaySpec {
  "TeamAvatar.urlFor" should {
    "point at the serving endpoint for the team" in {
      TeamAvatar.urlFor(7, 1234) mustEqual "/api/v2/team/7/avatar/file?v=1234"
    }

    "change when the avatar is replaced, so a cached copy is not reused" in {
      TeamAvatar.urlFor(7, 1234) must not equal TeamAvatar.urlFor(7, 5678)
    }
  }

  "TeamAvatar.isStoredAvatarUrl" should {
    "recognize a url this team's avatar is served from" in {
      TeamAvatar.isStoredAvatarUrl("/api/v2/team/7/avatar/file?v=1234", 7) mustEqual true
    }

    "recognize one without a version stamp" in {
      TeamAvatar.isStoredAvatarUrl("/api/v2/team/7/avatar/file", 7) mustEqual true
    }

    "not claim another team's avatar" in {
      TeamAvatar.isStoredAvatarUrl("/api/v2/team/70/avatar/file?v=1234", 7) mustEqual false
    }

    // Removing an uploaded avatar clears the team's url, so mistaking an
    // external url for one of ours would delete something we never stored.
    "not claim an external url" in {
      TeamAvatar.isStoredAvatarUrl("https://example.com/logo.png", 7) mustEqual false
    }
  }
}

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
}

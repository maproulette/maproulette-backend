/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.model

import org.maproulette.framework.model.Task
import org.scalatestplus.play.PlaySpec

class TaskSpec() extends PlaySpec {
  "isValidStatusProgression" should {
    "allow unchanged progressions" in {
      Task.isValidStatusProgression(10, 10) mustEqual true
    }

    "allow deleting and disabling from any status" in {
      Task.isValidStatusProgression(10, Task.STATUS_DELETED) mustEqual true
      Task.isValidStatusProgression(10, Task.STATUS_DISABLED) mustEqual true
    }

    "allow created to anything" in {
      Task.isValidStatusProgression(Task.STATUS_CREATED, Task.STATUS_FALSE_POSITIVE) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_CREATED, Task.STATUS_ALREADY_FIXED) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_CREATED, Task.STATUS_TOO_HARD) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_CREATED, Task.STATUS_FIXED) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_CREATED, Task.STATUS_SKIPPED) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_CREATED, Task.STATUS_ANSWERED) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_CREATED, Task.STATUS_VALIDATED) mustEqual true
    }

    "disallow fix if not allowing change" in {
      Task.isValidStatusProgression(Task.STATUS_FIXED, Task.STATUS_FALSE_POSITIVE) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_FIXED, Task.STATUS_ALREADY_FIXED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_FIXED, Task.STATUS_TOO_HARD) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_FIXED, Task.STATUS_SKIPPED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_FIXED, Task.STATUS_ANSWERED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_FIXED, Task.STATUS_VALIDATED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_FIXED, Task.STATUS_CREATED) mustEqual false
    }

    "allow fix to another completion status if allowing change" in {
      Task.isValidStatusProgression(Task.STATUS_FIXED, Task.STATUS_FALSE_POSITIVE, true) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_FIXED, Task.STATUS_ALREADY_FIXED, true) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_FIXED, Task.STATUS_TOO_HARD, true) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_FIXED, Task.STATUS_SKIPPED, true) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_FIXED, Task.STATUS_ANSWERED, true) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_FIXED, Task.STATUS_VALIDATED, true) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_FIXED, Task.STATUS_CREATED, true) mustEqual false
    }

    "only allow false positive to fixed if not allowing change" in {
      Task.isValidStatusProgression(Task.STATUS_FALSE_POSITIVE, Task.STATUS_FIXED) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_FALSE_POSITIVE, Task.STATUS_ALREADY_FIXED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_FALSE_POSITIVE, Task.STATUS_TOO_HARD) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_FALSE_POSITIVE, Task.STATUS_SKIPPED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_FALSE_POSITIVE, Task.STATUS_ANSWERED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_FALSE_POSITIVE, Task.STATUS_VALIDATED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_FALSE_POSITIVE, Task.STATUS_CREATED) mustEqual false
    }

    "allow false positive to another completion status if allowing change" in {
      Task.isValidStatusProgression(Task.STATUS_FALSE_POSITIVE, Task.STATUS_FIXED, true) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_FALSE_POSITIVE, Task.STATUS_ALREADY_FIXED, true) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_FALSE_POSITIVE, Task.STATUS_TOO_HARD, true) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_FALSE_POSITIVE, Task.STATUS_SKIPPED, true) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_FALSE_POSITIVE, Task.STATUS_ANSWERED, true) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_FALSE_POSITIVE, Task.STATUS_VALIDATED, true) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_FALSE_POSITIVE, Task.STATUS_CREATED, true) mustEqual false
    }

    "allow skipped and too hard to all except created" in {
      Task.isValidStatusProgression(Task.STATUS_SKIPPED, Task.STATUS_FIXED) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_SKIPPED, Task.STATUS_ALREADY_FIXED) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_SKIPPED, Task.STATUS_TOO_HARD) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_SKIPPED, Task.STATUS_FALSE_POSITIVE) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_SKIPPED, Task.STATUS_ANSWERED) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_SKIPPED, Task.STATUS_CREATED) mustEqual false

      Task.isValidStatusProgression(Task.STATUS_TOO_HARD, Task.STATUS_FIXED) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_TOO_HARD, Task.STATUS_ALREADY_FIXED) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_TOO_HARD, Task.STATUS_SKIPPED) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_TOO_HARD, Task.STATUS_FALSE_POSITIVE) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_TOO_HARD, Task.STATUS_ANSWERED) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_TOO_HARD, Task.STATUS_CREATED) mustEqual false
    }

    "only allow deleted back to created" in {
      Task.isValidStatusProgression(Task.STATUS_DELETED, Task.STATUS_FIXED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_DELETED, Task.STATUS_ALREADY_FIXED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_DELETED, Task.STATUS_TOO_HARD) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_DELETED, Task.STATUS_FALSE_POSITIVE) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_DELETED, Task.STATUS_ANSWERED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_DELETED, Task.STATUS_CREATED) mustEqual true
    }

    "disallow already fix if not allowing change" in {
      Task.isValidStatusProgression(Task.STATUS_ALREADY_FIXED, Task.STATUS_FALSE_POSITIVE) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_ALREADY_FIXED, Task.STATUS_TOO_HARD) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_ALREADY_FIXED, Task.STATUS_SKIPPED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_ALREADY_FIXED, Task.STATUS_ANSWERED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_ALREADY_FIXED, Task.STATUS_VALIDATED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_ALREADY_FIXED, Task.STATUS_CREATED) mustEqual false
    }

    "allow already fix to another completion status if allowing change" in {
      Task.isValidStatusProgression(Task.STATUS_ALREADY_FIXED, Task.STATUS_FALSE_POSITIVE, true) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_ALREADY_FIXED, Task.STATUS_FIXED, true) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_ALREADY_FIXED, Task.STATUS_TOO_HARD, true) mustEqual true
      Task.isValidStatusProgression(Task.STATUS_ALREADY_FIXED, Task.STATUS_SKIPPED, true) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_ALREADY_FIXED, Task.STATUS_ANSWERED, true) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_ALREADY_FIXED, Task.STATUS_VALIDATED, true) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_ALREADY_FIXED, Task.STATUS_CREATED, true) mustEqual false
    }

    "disallow answered to change" in {
      Task.isValidStatusProgression(Task.STATUS_ANSWERED, Task.STATUS_FIXED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_ANSWERED, Task.STATUS_FALSE_POSITIVE) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_ANSWERED, Task.STATUS_ALREADY_FIXED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_ANSWERED, Task.STATUS_TOO_HARD) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_ANSWERED, Task.STATUS_SKIPPED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_ANSWERED, Task.STATUS_VALIDATED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_ANSWERED, Task.STATUS_CREATED) mustEqual false
    }

    "disallow validate to change" in {
      Task.isValidStatusProgression(Task.STATUS_VALIDATED, Task.STATUS_FIXED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_VALIDATED, Task.STATUS_FALSE_POSITIVE) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_VALIDATED, Task.STATUS_ALREADY_FIXED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_VALIDATED, Task.STATUS_TOO_HARD) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_VALIDATED, Task.STATUS_SKIPPED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_VALIDATED, Task.STATUS_ANSWERED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_VALIDATED, Task.STATUS_CREATED) mustEqual false
    }

    "allow disabled to only created" in {
      Task.isValidStatusProgression(Task.STATUS_DISABLED, Task.STATUS_FIXED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_DISABLED, Task.STATUS_FALSE_POSITIVE) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_DISABLED, Task.STATUS_ALREADY_FIXED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_DISABLED, Task.STATUS_TOO_HARD) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_DISABLED, Task.STATUS_SKIPPED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_DISABLED, Task.STATUS_ANSWERED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_DISABLED, Task.STATUS_VALIDATED) mustEqual false
      Task.isValidStatusProgression(Task.STATUS_DISABLED, Task.STATUS_CREATED) mustEqual true
    }
  }
}

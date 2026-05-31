/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.models

import org.maproulette.framework.model.{PriorityRule, Task}
import org.scalatestplus.play.PlaySpec
import org.joda.time.DateTime
import play.api.libs.json.Json

/**
  * @author cuthbertm
  */
class ChallengeSpec() extends PlaySpec {
  implicit var challengeID: Long = -1

  "PriorityRule" should {
    "string types should operate correctly" in {
      PriorityRule("equal", "key", "value", "string")
        .doesMatch(Map("key" -> "value"), null) mustEqual true
      PriorityRule("not_equal", "key", "value", "string")
        .doesMatch(Map("key" -> "value2"), null) mustEqual true
      PriorityRule("contains", "key", "Value", "string")
        .doesMatch(Map("key" -> "TheValue"), null) mustEqual true
      PriorityRule("not_contains", "key", "value", "string")
        .doesMatch(Map("key"                                            -> "Nothing"), null) mustEqual true
      PriorityRule("is_empty", "key", "", "string").doesMatch(Map("key" -> ""), null) mustEqual true
      PriorityRule("is_not_empty", "key", "", "string")
        .doesMatch(Map("Key" -> "value"), null) mustEqual true
    }

    "integer types should operate correctly" in {
      PriorityRule("==", "key", "0", "integer").doesMatch(Map("key" -> "0"), null) mustEqual true
      PriorityRule("!=", "key", "0", "integer").doesMatch(Map("key" -> "1"), null) mustEqual true
      PriorityRule("<", "key", "0", "integer").doesMatch(Map("key"  -> "-1"), null) mustEqual true
      PriorityRule("<=", "key", "0", "integer").doesMatch(Map("key" -> "0"), null) mustEqual true
      PriorityRule(">", "key", "0", "integer").doesMatch(Map("key"  -> "1"), null) mustEqual true
      PriorityRule(">=", "key", "0", "integer").doesMatch(Map("Key" -> "0"), null) mustEqual true
    }

    "double types should operate correctly" in {
      PriorityRule("==", "key", "0", "double").doesMatch(Map("key" -> "0"), null) mustEqual true
      PriorityRule("!=", "key", "0", "double").doesMatch(Map("key" -> "1"), null) mustEqual true
      PriorityRule("<", "key", "0", "double").doesMatch(Map("key"  -> "-1"), null) mustEqual true
      PriorityRule("<=", "key", "0", "double").doesMatch(Map("key" -> "0"), null) mustEqual true
      PriorityRule(">", "key", "0", "double").doesMatch(Map("key"  -> "1"), null) mustEqual true
      PriorityRule(">=", "key", "0", "double").doesMatch(Map("Key" -> "0"), null) mustEqual true
    }

    "long types should operate correctly" in {
      PriorityRule("==", "key", "0", "long").doesMatch(Map("key" -> "0"), null) mustEqual true
      PriorityRule("!=", "key", "0", "long").doesMatch(Map("key" -> "1"), null) mustEqual true
      PriorityRule("<", "key", "0", "long").doesMatch(Map("key"  -> "-1"), null) mustEqual true
      PriorityRule("<=", "key", "0", "long").doesMatch(Map("key" -> "0"), null) mustEqual true
      PriorityRule(">", "key", "0", "long").doesMatch(Map("key"  -> "1"), null) mustEqual true
      PriorityRule(">=", "key", "0", "long").doesMatch(Map("Key" -> "0"), null) mustEqual true
    }

    "bounds type should operate correctly" in {
      val location = Some(Json.parse("{\"type\":\"Point\",\"coordinates\":[-120,50]}"))

      val task = Task(1, "Task1", DateTime.now(), DateTime.now(), 1, None, location, Json.obj())

      // format like: bounds = "MinX,MinY,MaxX,MaxY"
      PriorityRule("contains", "location", "0,0,1,1", "bounds")
        .doesMatch(Map(), task) mustEqual false
      PriorityRule("contains", "location", "-130,0,130,100", "bounds")
        .doesMatch(Map(), task) mustEqual true

      PriorityRule("not_contains", "location", "0,0,1,1", "bounds")
        .doesMatch(Map(), task) mustEqual true
      PriorityRule("not_contains", "location", "-130,0,130,100", "bounds")
        .doesMatch(Map(), task) mustEqual false
    }
  }
}

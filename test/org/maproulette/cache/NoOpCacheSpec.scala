/*
 * Copyright (C) 2026 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.cache

import org.maproulette.Config
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json.{JodaReads, JodaWrites, Json, Reads, Writes}

class NoOpCacheSpec extends PlaySpec with JodaWrites with JodaReads {
  implicit val groupWrites: Writes[TestBaseObject] = Json.writes[TestBaseObject]
  implicit val groupReads: Reads[TestBaseObject]   = Json.reads[TestBaseObject]
  implicit val configuration = Configuration.from(
    Map(Config.KEY_CACHING_TYPE -> CacheManager.NOOP_CACHE)
  )
  val manager = new CacheManager[Long, TestBaseObject](new Config())

  "NoOpCache" should {
    "be selected by the noop cache type" in {
      manager.cache mustBe a[NoOpCache[_, _]]
    }

    "never return an added object" in {
      val obj = TestBaseObject(1, "one")
      manager.cache.addObject(obj) mustEqual Some(obj)
      manager.cache.get(1) mustEqual None
      manager.cache.find("one") mustEqual None
      manager.cache.isCached(1) mustEqual false
      manager.cache.size() mustEqual 0
    }

    "run the block on every withCaching call" in {
      var calls = 0
      (1 to 3).foreach { _ =>
        manager.withCaching { () =>
          calls += 1
          Some(TestBaseObject(2, "two"))
        }(2L)
      }
      calls mustEqual 3
    }

    "run the block on every updateNameCache call" in {
      var calls = 0
      (1 to 3).foreach { _ =>
        manager.updateNameCache { _ =>
          calls += 1
          Some(TestBaseObject(3, "three"))
        }("three") mustEqual Some(3L)
      }
      calls mustEqual 3
    }

    "load every id through withIDListCaching" in {
      var requested = List.empty[Long]
      val result = manager.withIDListCaching { ids =>
        requested = ids
        ids.map(id => TestBaseObject(id, s"obj$id"))
      }(List(4L, 5L))
      requested mustEqual List(4L, 5L)
      result.map(_.id) mustEqual List(4L, 5L)
    }
  }
}

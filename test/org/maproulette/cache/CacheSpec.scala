/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.cache

import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.data.{ItemType, ProjectType}
import org.maproulette.models.BaseObject
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json.{JodaReads, JodaWrites, Json, Reads, Writes}

/**
  * @author cuthbertm
  */
case class TestBaseObject(
    override val id: Long,
    override val name: String,
    override val created: DateTime = DateTime.now(),
    override val modified: DateTime = DateTime.now()
) extends BaseObject[Long] {
  override val itemType: ItemType = ProjectType()
}

class CacheSpec extends PlaySpec with JodaWrites with JodaReads {
  implicit val groupWrites: Writes[TestBaseObject] = Json.writes[TestBaseObject]
  implicit val groupReads: Reads[TestBaseObject]   = Json.reads[TestBaseObject]
  implicit val configuration = Configuration.from(
    Map(Config.KEY_CACHING_CACHE_LIMIT -> 6, Config.KEY_CACHING_CACHE_EXPIRY -> 5)
  )
  implicit val manager = new CacheManager[Long, TestBaseObject](new Config())
  val theCache         = manager.cache

  "CacheManager" should {
    "cache element withOptionCaching" in {
      theCache.clear()
      cacheObject(25, "test")
      val cachedObj = theCache.get(25)
      cachedObj.isDefined mustEqual true
      cachedObj.get.name mustEqual "test"
      cachedObj.get.id mustEqual 25
    }

    "delete cached element withCacheIDDeletion" in {
      theCache.clear()
      cacheObject(1, "name1")
      implicit val ids = List(1L)
      manager.withCacheIDDeletion { () =>
        val cachedObj = theCache.get(1)
        cachedObj.isEmpty mustEqual true
      }
    }

    "delete cached elements withCacheIDDeletion" in {
      theCache.clear()
      cacheObject(1, "name1")
      cacheObject(2, "name2")
      cacheObject(3, "name3")
      implicit val ids = List(1L, 2L)
      manager.withCacheIDDeletion { () =>
        theCache.get(1).isEmpty mustEqual true
        theCache.get(2).isEmpty mustEqual true
        theCache.get(3).isDefined mustEqual true
      }
    }

    "delete cached elements withCacheNameDeletion" in {
      theCache.clear()
      cacheObject(1, "name1")
      cacheObject(2, "name2")
      cacheObject(3, "name3")
      implicit val names = List("name1", "name2")
      manager.withCacheNameDeletion { () =>
        theCache.find("name1").isEmpty mustEqual true
        theCache.find("name2").isEmpty mustEqual true
        theCache.find("name3").isDefined mustEqual true
      }
    }

    "cache multiple elements withIDListCaching" in {
      theCache.clear()
      implicit val cacheList = List(1L, 2L, 3L)
      manager.withIDListCaching { implicit uncachedList =>
        uncachedList.size mustEqual 3
        uncachedList.map(id => TestBaseObject(id, s"name$id"))
      }
      theCache.get(1).isDefined mustEqual true
      theCache.get(2).isDefined mustEqual true
      theCache.get(3).isDefined mustEqual true
    }

    "cache multiple elements withNameListCaching" in {
      theCache.clear()
      implicit val cacheNames = List("name1", "name2", "name3")
      manager.withNameListCaching { implicit uncachedList =>
        uncachedList.size mustEqual 3
        uncachedList.map(name => TestBaseObject(name.charAt(4).toString.toInt, name))
      }
      theCache.get(1).isDefined mustEqual true
      theCache.get(2).isDefined mustEqual true
      theCache.get(3).isDefined mustEqual true
    }

    "caching must be able to be disabled" in {
      theCache.clear()
      implicit val caching = false
      manager.withOptionCaching { () =>
        Some(TestBaseObject(1, "name1"))
      }
      theCache.size mustEqual 0
    }

    "cache only the elements that are not already cached withIDListCaching" in {
      theCache.clear()
      cacheObject(1, "name1")
      cacheObject(2, "name2")
      cacheObject(3, "name3")
      implicit val ids = List(2L, 3L, 5L, 6L)
      manager.withIDListCaching { implicit uncachedList =>
        uncachedList.size mustEqual 2
        uncachedList.map(id => TestBaseObject(id, s"name$id"))
      }
      theCache.size mustEqual 5
      theCache.get(5).isDefined mustEqual true
      theCache.get(6).isDefined mustEqual true
    }

    "cache updated changes for element" in {
      implicit val id = 1L
      theCache.clear()
      cacheObject(id, "name1")
      manager.withUpdatingCache(fakeRetrieve) { implicit cached =>
        Some(TestBaseObject(1, "name2"))
      }
      val cachedObj = theCache.get(1)
      cachedObj.isDefined mustEqual true
      cachedObj.get.name mustEqual "name2"
    }

    "cache updated changes for element and retrieve from supplied function" in {
      implicit val id = 3L
      theCache.clear()
      manager.withUpdatingCache(fakeRetrieve) { implicit cached =>
        cached.name mustEqual "testObject"
        Some(TestBaseObject(3, "updated"))
      }
      val cachedObj = theCache.get(3)
      cachedObj.isDefined mustEqual true
      cachedObj.get.name mustEqual "updated"
    }

    "cache must handle size limits correctly" in {
      theCache.clear()
      val insertN = 30L

      // Add a handful of objects to the cache
      for (id <- 0L until insertN) {
        cacheObject(id, s"test$id")
      }

      // Sleep to give the cache time to evict some items
      Thread.sleep(2000)
      cacheObject(27L, s"test27")
      Thread.sleep(2000)

      // The cache may have a thread managing the evictions, so we can't expect an exact size.
      // Since we inserted far more items than the capacity, check that some items were evicted.
      theCache.size must be < 15L

      // Add a handful of new objects to the cache, and keep fetching one to keep it from being evicted
      for (id <- 0L until insertN) {
        cacheObject(100 + id, s"test${100 + id}")
        theCache.get(27L)
        theCache.get(27L)
        theCache.get(27L)
        Thread.sleep(5)
      }

      // id=27 (with a high probability) will still be in the cache
      theCache.get(27L).isDefined mustEqual true
    }

    "cache must expire values correctly" in {
      theCache.clear()
      cacheObject(1L, "test1")
      theCache.addObject(TestBaseObject(2L, "test2"), Some(1))

      // The overridden cache expiry is 5 seconds, sleep at least that long.
      Thread.sleep(6000)

      theCache.get(1L) mustBe None
    }
  }

  private def fakeRetrieve(id: Long): Option[TestBaseObject] = Some(TestBaseObject(3, "testObject"))

  private def cacheObject(id: Long, name: String) =
    manager.withOptionCaching { () =>
      Some(TestBaseObject(id, name))
    }
}

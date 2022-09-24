package org.maproulette.cache

import com.github.blemale.scaffeine.Scaffeine
import org.maproulette.Config
import play.api.Logging

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

class CaffeineCache[Key, Value <: CacheObject[Key]](config: Config)
    extends Cache[Key, Value]
    with Logging {
  override implicit val cacheLimit: Int  = config.cacheLimit
  override implicit val cacheExpiry: Int = config.cacheExpiry
  val caffeineCache: com.github.blemale.scaffeine.Cache[Key, BasicInnerValue[Key, Value]] =
    Scaffeine()
      .recordStats()
      .expireAfterAccess(FiniteDuration(config.cacheExpiry, TimeUnit.SECONDS))
      .maximumSize(config.cacheLimit)
      .build[Key, BasicInnerValue[Key, Value]]()

  /**
    * Checks if an item is cached or not
    *
    * @param key The id of the object to check to see if it is in the cache
    * @return true if the item is found in the cache
    */
  override def isCached(key: Key): Boolean = caffeineCache.getIfPresent(key).nonEmpty

  /**
    * Fully clears the cache, this may not be applicable for non basic in memory caches
    */
  override def clear(): Unit = caffeineCache.invalidateAll()

  /**
    * @return the current size of the cache
    */
  override def size(): Long = caffeineCache.estimatedSize()

  /**
    * Adds an object to the cache, if cache limit has been reached, then will remove the oldest
    * accessed item in the cache
    *
    * @param key   The object to add to the cache
    * @param value You can add a custom expiry to a specific element in seconds
    * @return The object put in the cache, or None if it could not be placed in the cache
    */
  override def add(key: Key, value: Value, localExpiry: Option[Int]): Option[Value] = {
    if (localExpiry.nonEmpty) {
      // NOTE: this code is a hot path and must log at debug to avoid filling the disk
      logger.debug("CaffeineCache does not support localExpiry parameter")
    }
    caffeineCache.put(key, BasicInnerValue(key, value, null, null))
    Some(value)
  }

  /**
    * Search the cache's values and find the first based on the name.
    * IMPORTANT NOTE: There may be multiple values in the cache that match, and only the FIRST is returned.
    *
    * @param name The name of the object you wish to find
    * @return The object from the cache, None if not found
    */
  override def find(name: String): Option[Value] = {
    // This method searches the **entire cache** for a value with a specific name.
    logger.debug(s"INEFFICIENT SEARCH: Checking entire cache values for one matching '$name'")
    this.caffeineCache
      .asMap()
      .values
      .find(it => {
        name.equals(it.value.name)
      }) match {
      case Some(it) => Some(it.value)
      case None     => None
    }
  }

  /**
    * Search the cache's values and remove the first that matches the name.
    * IMPORTANT NOTE: There may be multiple values in the cache that match, and only the FIRST is removed.
    *
    * @param name The name of the object to be removed
    * @return The object removed from the cache, or None if it could not be removed from the cache,
    *         or was not originally in the cache
    */
  override def remove(name: String): Option[Value] = {
    // This method intends to search the **entire cache** for a value with a specific name.
    logger.debug(
      s"INEFFICIENT SEARCH: Checking entire cache values for one matching '$name' to remove"
    )
    this.caffeineCache
      .asMap()
      .values
      .find(it => {
        name.equals(it.value.name)
      }) match {
      case Some(it) => {
        this.caffeineCache.invalidate(it.key)
        Some(it.value)
      }
      case None => None
    }
  }

  override def remove(id: Key): Option[Value] = {
    caffeineCache.getIfPresent(id) match {
      case Some(res) =>
        caffeineCache.invalidate(id)
        Some(res.value)
      case _ => None
    }
  }

  override def get(key: Key): Option[Value] = {
    caffeineCache.getIfPresent(key) match {
      case Some(res) => Some(res.value)
      case _         => None
    }
  }

  /**
    * Unsupported for the CaffeineCache and this will raise an exception. The only caller of 'innerGet' is in the
    * base Cache interface, and the CaffeineCache overrides that method.
    */
  override protected def innerGet(key: Key): Option[BasicInnerValue[Key, Value]] = {
    throw new UnsupportedOperationException("CaffeineCache innerGet is not supported")
  }

  /**
    * Unsupported for the CaffeineCache and this will raise an exception. The only caller of 'isExpired' is in the
    * base Cache interface, and the CaffeineCache overrides that method.
    */
  override protected def isExpired(value: BasicInnerValue[Key, Value]): Boolean = {
    // The expiry is internal to caffeine and the value is not exposed.
    throw new UnsupportedOperationException(
      "CaffeineCache.isExpired is not supported"
    )
  }
}

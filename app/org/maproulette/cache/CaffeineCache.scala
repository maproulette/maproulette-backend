package org.maproulette.cache

import org.maproulette.Config
import play.api.Logging

class CaffeineCache[Key, Value <: CacheObject[Key]](config: Config)
    extends Cache[Key, Value]
    with Logging {
  override implicit val cacheLimit: Int  = config.cacheLimit
  override implicit val cacheExpiry: Int = config.cacheExpiry
  val caffeineCache: com.github.blemale.scaffeine.Cache[Key, BasicInnerValue[Key, Value]] =
    com.github.blemale.scaffeine.Scaffeine().build[Key, BasicInnerValue[Key, Value]]()

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
    * Finds an object from the cache based on the name instead of the id
    *
    * @param name The name of the object you wish to find
    * @return The object from the cache, None if not found
    */
  override def find(name: String): Option[Value] = {
    // This method intends to search the **entire cache** for a value with a specific name.
    // It's extremely inefficient and for now it's unsupported, until we find exactly why this is needed.
    throw new UnsupportedOperationException(
      "CaffeineCache.find by string (not a key) is not supported"
    )
  }

  /**
    * Remove an object from the cache based on the name
    *
    * @param name The name of the object to be removed
    * @return The object removed from the cache, or None if it could not be removed from the cache,
    *         or was not originally in the cache
    */
  override def remove(name: String): Option[Value] = {
    // This method intends to search the **entire cache** for a value with a specific name.
    // It's extremely inefficient and for now it's unsupported, until we find exactly why this is needed.
    throw new UnsupportedOperationException(
      "CaffeineCache.remove by string (not a key) is not supported"
    )
  }

  override def remove(id: Key): Option[Value] = {
    caffeineCache.getIfPresent(id) match {
      case Some(res) => {
        caffeineCache.invalidate(id)
        Some(res.value)
      }
      case _ => None
    }
  }

  override protected def innerGet(key: Key): Option[BasicInnerValue[Key, Value]] = {
    // If something calls this method we really need to throw an exception.
    throw new UnsupportedOperationException("CaffeineCache innerGet is not supported")
  }
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.cache

import org.joda.time.LocalDateTime
import org.maproulette.Config

import scala.collection.mutable.Map

/**
  * This is a very basic Cache Storage class that will store all items in memory. Ultimately this
  * class should be extended to use the Play Cache API
  *
  * cacheLimit - The number of entries allowed in the cache until it starts kicking out the oldest entries
  * cacheExpiry - The number of seconds allowed until a cache item is expired out the cache lazily
  *
  * @author cuthbertm
  */
class BasicCache[Key, Value <: CacheObject[Key]](config: Config) extends Cache[Key, Value] {

  override implicit val cacheLimit: Int            = config.cacheLimit
  override implicit val cacheExpiry: Int           = config.cacheExpiry
  val cache: Map[Key, BasicInnerValue[Key, Value]] = Map.empty

  /**
    * Checks if an item is cached or not
    *
    * @param id The id of the object to check to see if it is in the cache
    * @return true if the item is found in the cache
    */
  def isCached(id: Key): Boolean = this.cache.contains(id)

  /**
    * Fully clears the cache, this may not be applicable for non basic in memory caches
    */
  def clear(): Unit = this.cache.clear()

  /**
    * The current size of the cache
    *
    * @return
    */
  def size(): Long = this.cache.size

  override protected def innerGet(key: Key): Option[BasicInnerValue[Key, Value]] =
    this.cache.get(key)

  /**
    * Adds an object to the cache, if cache limit has been reached, then will remove the oldest
    * accessed item in the cache
    *
    * @param obj         The object to add to the cache
    * @param localExpiry You can add a custom expiry to a specific element in seconds
    * @return The object put in the cache, or None if it could not be placed in the cache
    */
  def add(key: Key, obj: Value, localExpiry: Option[Int] = None): Option[Value] = synchronized {
    if (this.cache.size == cacheLimit) {
      val oldestEntry = this.cache.valuesIterator.reduceLeft((x, y) =>
        if (x.accessTime.isBefore(y.accessTime)) x else y
      )
      remove(oldestEntry.key)
    } else if (this.cache.size > cacheLimit) {
      // something has gone very wrong if the cacheLimit has already been exceeded, this really shouldn't ever happen
      // in this case we go for the nuclear option, basically blow away the whole cache
      this.cache.clear()
    }
    this.cache.put(key, BasicInnerValue(key, obj, new LocalDateTime(), localExpiry)) match {
      case Some(value) => Some(value.value)
      case None        => None
    }
  }

  /**
    * Removes an object from the cache based on the id
    *
    * @param id the id of the object to be removed
    * @return The object removed from the cache, or None if it could not be removed from the cache,
    *         or was not originally in the cache
    */
  def remove(id: Key): Option[Value] = synchronized {
    this.cache.remove(id) match {
      case Some(value) => Some(value.value)
      case None        => None
    }
  }

  /**
    * Remove an object from the cache based on the name
    *
    * @param name The name of the object to be removed
    * @return The object removed from the cache, or None if it could not be removed from the cache,
    *         or was not originally in the cache
    */
  def remove(name: String): Option[Value] = synchronized {
    this.find(name) match {
      case Some(value) => this.remove(value.id)
      case None        => None
    }
  }

  /**
    * Finds an object from the cache based on the name instead of the id
    *
    * @param name The name of the object you wish to find
    * @return The object from the cache, None if not found
    */
  def find(name: String): Option[Value] = synchronized {
    this.cache.find(element => element._2.value.name.equalsIgnoreCase(name)) match {
      case Some(value) =>
        if (isExpired(value._2)) {
          None
        } else {
          Some(value._2.value)
        }
      case None => None
    }
  }
}

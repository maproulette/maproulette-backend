/*
 * Copyright (C) 2026 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.cache

import org.maproulette.Config

class NoOpCache[Key, Value <: CacheObject[Key]](config: Config) extends Cache[Key, Value] {
  override implicit val cacheLimit: Int  = config.cacheLimit
  override implicit val cacheExpiry: Int = config.cacheExpiry

  override def isCached(key: Key): Boolean = false

  override def clear(): Unit = {}

  override def size(): Long = 0

  override def add(key: Key, value: Value, localExpiry: Option[Int]): Option[Value] = Some(value)

  override def get(key: Key): Option[Value] = None

  override def find(name: String): Option[Value] = None

  override def remove(name: String): Option[Value] = None

  override def remove(id: Key): Option[Value] = None

  override protected def innerGet(key: Key): Option[BasicInnerValue[Key, Value]] = None
}

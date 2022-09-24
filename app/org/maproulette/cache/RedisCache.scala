/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.cache

import com.redis.serialization.Parse.Implicits._
import com.redis.{RedisClient, RedisClientPool}
import org.maproulette.Config
import org.maproulette.utils.{Readers, Writers}
import play.api.libs.json.{Json, Reads, Writes}

import scala.util.Try

/**
  * @author mcuthbert
  */
class RedisCache[Key, Value <: CacheObject[Key]](config: Config, prefix: String)(
    implicit r: Reads[Value],
    w: Writes[Value]
) extends Cache[Key, Value]
    with Readers
    with Writers {

  override implicit val cacheLimit: Int  = config.cacheLimit
  override implicit val cacheExpiry: Int = config.cacheExpiry
  private val databaseId                 = Try(prefix.toInt).getOrElse(0)
  private val pool = new RedisClientPool(
    config.redisHost.getOrElse("localhost"),
    config.redisPort.getOrElse(RedisCache.DEFAULT_REDIS_PORT)
  )

  /**
    * Checks if an item is cached or not
    *
    * @param key The id of the object to check to see if it is in the cache
    * @return true if the item is found in the cache
    */
  override def isCached(key: Key): Boolean = this.withClient { client =>
    client.exists(key)
  }

  /**
    * Fully clears the cache, this may not be applicable for non basic in memory caches
    */
  override def clear(): Unit = this.withClient { client =>
    client.keys[String]().foreach(client.del(_))
  }

  /**
    * Retrieve all the keys and then just count it
    *
    * @return
    */
  override def size(): Long = this.withClient { client =>
    client.keys[String]().get.size
  }

  /**
    * Adds an object to the cache, if cache limit has been reached, then will remove the oldest
    * accessed item in the cache
    *
    * @param key   The object to add to the cache
    * @param value You can add a custom expiry to a specific element in seconds
    * @return The object put in the cache, or None if it could not be placed in the cache
    */
  override def add(key: Key, value: Value, localExpiry: Option[Int]): Option[Value] =
    this.withClient { client =>
      client.set(value.name, key.toString)
      client.expire(value.name, this.cacheExpiry)
      if (client.set(key.toString, serialise(key, value))) {
        client.expire(key.toString, this.cacheExpiry)
        Some(value)
      } else {
        None
      }
    }

  private def serialise(key: Key, value: Value): String = {
    Json.toJson(value).toString()
  }

  override def get(key: Key): Option[Value] = this.withClient { client =>
    deserialise(client.get[String](key))
  }

  // ignore this function for Redis
  override def innerGet(key: Key): Option[BasicInnerValue[Key, Value]] = None

  /**
    * Finds an object from the cache based on the name instead of the id
    *
    * @param name The name of the object you wish to find
    * @return The object from the cache, None if not found
    */
  override def find(name: String): Option[Value] = this.withClient { client =>
    client.get[String](name) match {
      case Some(k) => deserialise(client.get[String](k))
      case None    => None
    }
  }

  /**
    * Remove an object from the cache based on the name
    *
    * @param name The name of the object to be removed
    * @return The object removed from the cache, or None if it could not be removed from the cache,
    *         or was not originally in the cache
    */
  override def remove(name: String): Option[Value] = this.withClient { client =>
    client.get[Array[Byte]](name) match {
      case Some(k) =>
        val deserializedObject = deserialise(client.get[String](k))
        client.del(k)
        client.del(name)
        deserializedObject
      case None => None
    }
  }

  override def remove(id: Key): Option[Value] = this.withClient { client =>
    client.get[Array[Byte]](id.toString) match {
      case Some(o) =>
        val deserializedObject = deserialise(Some(o)).get
        client.del(deserializedObject.name)
        client.del(id.toString)
        Some(deserializedObject)
      case None => None
    }
  }

  private def deserialise(value: Option[String]): Option[Value] = {
    value match {
      case Some(s) =>
        Some(Json.fromJson[Value](Json.parse(s)).get)
      case None => None
    }
  }

  private def withClient[T](body: RedisClient => T): T = {
    this.pool.withClient { client =>
      client.select(this.databaseId)
      body(client)
    }
  }
}

object RedisCache {
  val DEFAULT_REDIS_PORT = 6379
}

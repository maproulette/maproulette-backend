/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.provider.osm.objects

import org.joda.time.DateTime
import org.maproulette.cache.CacheObject
import org.maproulette.provider.osm.OSMType
import org.maproulette.provider.osm.OSMType.OSMType

import scala.collection.mutable
import scala.xml.{Attribute, Elem, Null, Text}

/**
  * A class of versioned objects. This is essentially the same object
  *
  * @author mcuthbert
  */
class VersionedObjects[T <: VersionedObject](override val id: Long, override val name: String)
    extends CacheObject[Long] {
  private val versions = mutable.Map[Int, T]()

  def get(version: Int): Option[T] = this.versions.get(version)

  def put(versionedObject: T): Unit = this.versions.put(versionedObject.version, versionedObject)

  def getLatest: Option[T] = {
    if (this.versions.isEmpty) {
      None
    } else {
      Some(this.versions(this.versions.keys.max))
    }
  }
}

trait VersionedObject {
  def name: String

  def id: Long

  def visible: Boolean

  def version: Int

  def changeset: Int

  def timestamp: DateTime

  def user: String

  def uid: Long

  def tags: Map[String, String]

  def toChangeElement(changeSetId: Int): Elem

  def getOSMType: OSMType
}

case class VersionedNode(
    override val name: String,
    override val id: Long,
    override val visible: Boolean,
    override val version: Int,
    override val changeset: Int,
    override val timestamp: DateTime,
    override val user: String,
    override val uid: Long,
    override val tags: Map[String, String],
    lat: Double,
    lon: Double
) extends VersionedObject {

  override def toChangeElement(changesetId: Int): Elem = {
    <node>
      {
      for (tagKV <- tags)
        yield <tag/> % Attribute("k", Text(tagKV._1), Attribute("v", Text(tagKV._2), Null))
    }
    </node> % Attribute(
      "visible",
      Text(visible.toString),
      Attribute(
        "changeset",
        Text(changesetId.toString),
        Attribute(
          "version",
          Text(version.toString),
          Attribute(
            "user",
            Text(user),
            Attribute(
              "uid",
              Text(uid.toString),
              Attribute(
                "id",
                Text(id.toString),
                Attribute("lat", Text(lat.toString), Attribute("lon", Text(lon.toString), Null))
              )
            )
          )
        )
      )
    )
  }

  override def getOSMType: OSMType = OSMType.NODE
}

case class VersionedWay(
    override val name: String,
    override val id: Long,
    override val visible: Boolean,
    override val version: Int,
    override val changeset: Int,
    override val timestamp: DateTime,
    override val user: String,
    override val uid: Long,
    override val tags: Map[String, String],
    nodes: List[Long]
) extends VersionedObject {

  override def toChangeElement(changesetId: Int): Elem = {
    <way>
      {for (nodeRef <- nodes) yield <nd/> % Attribute("ref", Text(nodeRef.toString), Null)}
      {
      for (tagKV    <- tags)
        yield <tag/> % Attribute("k", Text(tagKV._1), Attribute("v", Text(tagKV._2), Null))
    }
    </way> % Attribute(
      "visible",
      Text(visible.toString),
      Attribute(
        "changeset",
        Text(changesetId.toString),
        Attribute(
          "version",
          Text(version.toString),
          Attribute(
            "user",
            Text(user),
            Attribute("uid", Text(uid.toString), Attribute("id", Text(id.toString), Null))
          )
        )
      )
    )
  }

  override def getOSMType: OSMType = OSMType.WAY
}

case class VersionedRelation(
    override val name: String,
    override val id: Long,
    override val visible: Boolean,
    override val version: Int,
    override val changeset: Int,
    override val timestamp: DateTime,
    override val user: String,
    override val uid: Long,
    override val tags: Map[String, String],
    members: List[RelationMember]
) extends VersionedObject {

  override def toChangeElement(changesetId: Int): Elem = {
    <relation>
      {
      for (member <- members)
        yield <member/> % Attribute(
          "type",
          Text(member.osmType),
          Attribute("ref", Text(member.ref.toString), Attribute("role", Text(member.role), Null))
        )
    }{
      for (tagKV <- tags)
        yield <tag/> % Attribute("k", Text(tagKV._1), Attribute("v", Text(tagKV._2), Null))
    }
    </relation> % Attribute(
      "visible",
      Text(visible.toString),
      Attribute(
        "changeset",
        Text(changesetId.toString),
        Attribute(
          "version",
          Text(version.toString),
          Attribute(
            "user",
            Text(user),
            Attribute("uid", Text(uid.toString), Attribute("id", Text(id.toString), Null))
          )
        )
      )
    )
  }

  override def getOSMType: OSMType = OSMType.RELATION
}

case class RelationMember(osmType: String, ref: Long, role: String)

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.models

import org.apache.commons.lang3.StringUtils
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.xml._

/**
  * @author mcuthbert
  */
case class Changeset(
    id: Long,
    user: String,
    userId: Long,
    createdAt: DateTime,
    closedAt: DateTime,
    open: Boolean,
    minLat: Double,
    minLon: Double,
    maxLat: Double,
    maxLon: Double,
    commentsCount: Int,
    tags: Map[String, String],
    hasMapRouletteComment: Boolean
)

object ChangesetParser {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def parse(el: Elem): List[Changeset] = {
    (el \\ "changeset").map { c =>
      this.parse(c)
    }.toList
  }

  def parse(changeSetElement: Node): Changeset = {
    // for some reason the open value is sometimes an empty string, so need to handle that correctly
    val open = (changeSetElement \ "@open").text
    if (StringUtils.isEmpty(open)) {
      logger.debug(s"Invalid changeset provided: ${changeSetElement.toString()}")
    }
    val minLat = (changeSetElement \ "@min_lat").text
    if (StringUtils.isEmpty(minLat)) {
      logger.debug(s"Invalid changeset provided: ${changeSetElement.toString()}")
    }
    val minLon = (changeSetElement \ "@min_lon").text
    val maxLat = (changeSetElement \ "@max_lat").text
    val maxLon = (changeSetElement \ "@max_lon").text

    var mapRouletteTag = false
    val tags = (changeSetElement \\ "tag").map { t =>
      val key   = (t \ "k").text
      val value = (t \ "v").text
      if (StringUtils.equals(key, "comment") && StringUtils.containsIgnoreCase(
            value,
            "maproulette"
          )) {
        mapRouletteTag = true
      }
      key -> value
    }.toMap

    Changeset(
      (changeSetElement \ "@id").text.toLong,
      (changeSetElement \ "@user").text,
      (changeSetElement \ "@uid").text.toLong,
      DateTime.parse((changeSetElement \ "@created_at").text),
      DateTime.parse((changeSetElement \ "@closed_at").text),
      if (StringUtils.isEmpty(open)) {
        false
      } else {
        open.toBoolean
      },
      if (StringUtils.isEmpty(minLat)) {
        0d
      } else {
        minLat.toDouble
      },
      if (StringUtils.isEmpty(minLon)) {
        0d
      } else {
        minLon.toDouble
      },
      if (StringUtils.isEmpty(maxLat)) {
        0d
      } else {
        maxLat.toDouble
      },
      if (StringUtils.isEmpty(maxLon)) {
        0d
      } else {
        maxLon.toDouble
      },
      (changeSetElement \ "@comments_count").text.toInt,
      tags,
      mapRouletteTag
    )
  }
}

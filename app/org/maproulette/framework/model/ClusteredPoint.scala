/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.joda.time.DateTime
import play.api.libs.json.{JsValue, Json, Reads, Writes}
import play.api.libs.json.JodaWrites._
import play.api.libs.json.JodaReads._

/**
  * @author cuthbertm
  */
case class Point(lat: Double, lng: Double)

case class PointReview(
    reviewStatus: Option[Int],
    reviewRequestedBy: Option[Long],
    reviewedBy: Option[Long],
    reviewedAt: Option[DateTime],
    metaReviewStatus: Option[Int],
    metaReviewedBy: Option[Long],
    metaReviewedAt: Option[DateTime],
    reviewStartedAt: Option[DateTime],
    additionalReviewers: Option[List[Long]]
)

/**
  * This is the clustered point that will be displayed on the map. The popup will contain the title
  * of object with a blurb or description of said object. If the object is a challenge then below
  * that will be a start button so you can jump into editing tasks in the challenge
  *
  * @param id         The id of the object for this clustered point
  * @param owner      The osm id of the owner of the object
  * @param ownerName  The name of the owner
  * @param title      The title of the object (or name)
  * @param parentId   The id of the parent, Challenge if Task, and Project if Challenge
  * @param parentName The name of the parent
  * @param point      The latitude and longitude of the point
  * @param blurb      A short descriptive text for the object
  * @param modified   The last time this set of points was modified
  * @param difficulty The difficulty level of this ClusteredPoint (if a challenge)
  * @param type       The type of this ClusteredPoint
  * @param status     The status of the task, only used for task points, ie. not challenge points
  * @param mappedOn   The date this task was mapped
  * @param pointReview a PointReview instance with review data
  * @param bundleId id of bundle task is member of, if any
  * @param isBundlePrimary whether task is primary task in bundle (if a member of a bundle)
  */
case class ClusteredPoint(
    id: Long,
    owner: Long,
    ownerName: String,
    title: String,
    parentId: Long,
    parentName: String,
    point: Point,
    bounding: JsValue,
    blurb: String,
    modified: DateTime,
    difficulty: Int,
    `type`: Int,
    status: Int,
    cooperativeWork: Option[String] = None,
    mappedOn: Option[DateTime],
    completedTimeSpent: Option[Long] = None,
    completedBy: Option[Long] = None,
    pointReview: PointReview,
    priority: Int,
    bundleId: Option[Long] = None,
    isBundlePrimary: Option[Boolean] = None,
    lockedBy: Option[Long] = None
)

object ClusteredPoint {
  implicit val pointWrites: Writes[Point]                   = Json.writes[Point]
  implicit val pointReads: Reads[Point]                     = Json.reads[Point]
  implicit val pointReviewWrites: Writes[PointReview]       = Json.writes[PointReview]
  implicit val pointReviewReads: Reads[PointReview]         = Json.reads[PointReview]
  implicit val clusteredPointWrites: Writes[ClusteredPoint] = Json.writes[ClusteredPoint]
  implicit val clusteredPointReads: Reads[ClusteredPoint]   = Json.reads[ClusteredPoint]
}

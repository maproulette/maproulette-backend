/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.joda.time.DateTime
import org.maproulette.framework.psql.CommonField
import play.api.libs.json.JodaWrites._
import play.api.libs.json._

/**
  * A team's challenge display image, offered to that team's members as the
  * picture for their challenges. Images are moderated: a member uploads one as
  * a request and it only becomes usable once a superuser approves it.
  *
  * A team holds one image at a time. A new request sits alongside the image
  * the team is currently using until it is reviewed, and on approval takes its
  * place - inheriting the challenges the old image was on, so cards keep a
  * picture across the swap. Rejected images stay behind as history, which is
  * why a team can still have several rows here.
  *
  * The raw bytes are deliberately not part of this case class — they are only
  * read when actually serving the image, so listings never pull them out of
  * the database.
  */
case class TeamImage(
    override val id: Long,
    teamId: Long,
    teamName: Option[String] = None,
    name: String,
    contentType: String,
    size: Long,
    status: Int = TeamImage.STATUS_PENDING,
    requestedBy: Option[Long] = None,
    requestedByName: Option[String] = None,
    reviewedBy: Option[Long] = None,
    reviewedByName: Option[String] = None,
    reviewedAt: Option[DateTime] = None,
    reviewComment: Option[String] = None,
    created: DateTime,
    modified: DateTime
) extends Identifiable

object TeamImage extends CommonField {
  implicit val writes: Writes[TeamImage] = new Writes[TeamImage] {
    def writes(image: TeamImage): JsValue =
      Json.obj(
        "id"              -> image.id,
        "teamId"          -> image.teamId,
        "teamName"        -> image.teamName,
        "name"            -> image.name,
        "contentType"     -> image.contentType,
        "size"            -> image.size,
        "status"          -> image.status,
        "statusName"      -> statusName(image.status),
        "requestedBy"     -> image.requestedBy,
        "requestedByName" -> image.requestedByName,
        "reviewedBy"      -> image.reviewedBy,
        "reviewedByName"  -> image.reviewedByName,
        "reviewedAt"      -> image.reviewedAt,
        "reviewComment"   -> image.reviewComment,
        "created"         -> image.created,
        "modified"        -> image.modified,
        // The url the challenge card ultimately renders, so clients never have
        // to know how to assemble it.
        "url" -> urlFor(image.id)
      )
  }

  val TABLE = "team_images"

  val FIELD_TEAM_ID = "team_id"
  val FIELD_STATUS  = "status"

  val STATUS_PENDING  = 0
  val STATUS_APPROVED = 1
  val STATUS_REJECTED = 2

  val statusNames: Map[Int, String] = Map(
    STATUS_PENDING  -> "pending",
    STATUS_APPROVED -> "approved",
    STATUS_REJECTED -> "rejected"
  )

  def statusName(status: Int): String = statusNames.getOrElse(status, "unknown")

  // Bitmap formats only, each paired with the leading bytes that identify it.
  // The browser-supplied content type is only a hint, so this table is both
  // what we accept and how we recognise it - there is no second list to keep
  // in step. SVG is deliberately absent: it can carry script, and we serve
  // these bytes back from our own origin.
  private val signatures: List[(String, Array[Byte] => Boolean)] = List(
    "image/png" -> (
        (data: Array[Byte]) => startsWith(data, 0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    ),
    "image/jpeg" -> ((data: Array[Byte]) => startsWith(data, 0, 0xff, 0xd8, 0xff)),
    "image/webp" -> (
        (data: Array[Byte]) =>
          startsWith(data, 0, 0x52, 0x49, 0x46, 0x46) && startsWith(data, 8, 0x57, 0x45, 0x42, 0x50)
      ),
    "image/gif" -> ((data: Array[Byte]) => startsWith(data, 0, 0x47, 0x49, 0x46, 0x38))
  )

  val ALLOWED_CONTENT_TYPES: Set[String] = signatures.map(_._1).toSet

  // Images are meant to be small card thumbnails, not full photos.
  val MAX_SIZE_BYTES: Int = 2 * 1024 * 1024

  /**
    * Determines the image format from the file's leading bytes. The
    * browser-supplied content type is only a hint, so the magic number is what
    * we trust before storing anything we will later serve back.
    *
    * @param data The raw uploaded bytes
    * @return The detected mime type, or None if the bytes are not a supported image
    */
  def detectContentType(data: Array[Byte]): Option[String] =
    signatures.collectFirst { case (mimeType, matches) if matches(data) => mimeType }

  private def startsWith(data: Array[Byte], offset: Int, signature: Int*): Boolean =
    data.length >= offset + signature.length &&
      signature.zipWithIndex.forall { case (b, i) => (data(offset + i) & 0xff) == b }

  /**
    * The url that serves a particular image's bytes, used where a specific
    * image is the subject - a team reviewing its own requests, a superuser
    * working the queue. Left relative so the same stored value works across
    * environments; an image's bytes never change, so no cache-busting stamp is
    * needed and the endpoint's ETag covers the rest.
    */
  def urlFor(imageId: Long): String = s"/api/v2/teamImage/$imageId/file"

  /**
    * The url that serves whatever image a team currently has approved, which
    * is what a challenge card renders.
    *
    * Addressed by team rather than by image on purpose: a challenge stores
    * only the team that owns it, so replacing the team's image changes every
    * one of its cards at once and no card can be left pointing at an image
    * that is gone. A team with no approved image answers 404 here, which is
    * how a client tells there is no picture to show.
    */
  def urlForTeam(teamId: Long): String = s"/api/v2/team/$teamId/image/file"
}

/**
  * Just enough of an image to decide whether it may be served and whether the
  * caller's cached copy is still current, without touching the bytes.
  */
case class TeamImageFile(teamId: Long, status: Int, contentType: String, modified: DateTime)

/**
  * The bytes of a team image, read only when serving it.
  */
case class TeamImageData(contentType: String, data: Array[Byte], modified: DateTime)

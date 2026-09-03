/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.joda.time.DateTime
import play.api.libs.json.JodaWrites._
import play.api.libs.json._

/**
  * An image belonging to a team, offered to that team's members as a display
  * image for their challenges. Images are moderated: a member uploads one as a
  * request and it only becomes usable once a superuser approves it.
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

object TeamImage {
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

  val FIELD_ID      = "id"
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

  // Bitmap formats only. SVG is deliberately excluded: it can carry script,
  // and we serve these bytes back from our own origin.
  val ALLOWED_CONTENT_TYPES: Set[String] =
    Set("image/png", "image/jpeg", "image/webp", "image/gif")

  // Images are meant to be small card thumbnails, not full photos.
  val MAX_SIZE_BYTES: Int = 2 * 1024 * 1024

  // A team can't hoard an unbounded number of images awaiting review.
  val MAX_PENDING_PER_TEAM: Int = 10

  /**
    * Determines the image format from the file's leading bytes. The
    * browser-supplied content type is only a hint, so the magic number is what
    * we trust before storing anything we will later serve back.
    *
    * @param data The raw uploaded bytes
    * @return The detected mime type, or None if the bytes are not a supported image
    */
  def detectContentType(data: Array[Byte]): Option[String] = {
    def startsWith(offset: Int, signature: Int*): Boolean =
      data.length >= offset + signature.length &&
        signature.zipWithIndex.forall { case (b, i) => (data(offset + i) & 0xff) == b }

    if (startsWith(0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) {
      Some("image/png")
    } else if (startsWith(0, 0xff, 0xd8, 0xff)) {
      Some("image/jpeg")
    } else if (startsWith(0, 0x52, 0x49, 0x46, 0x46) && startsWith(8, 0x57, 0x45, 0x42, 0x50)) {
      Some("image/webp")
    } else if (startsWith(0, 0x47, 0x49, 0x46, 0x38)) {
      Some("image/gif")
    } else {
      None
    }
  }

  /**
    * The url that serves an image's bytes. Left relative so the same stored
    * value works across environments; approved images are immutable, so no
    * cache-busting stamp is needed and the endpoint's ETag covers the rest.
    */
  def urlFor(imageId: Long): String = s"/api/v2/teamImage/$imageId/file"
}

/**
  * The bytes of a team image, read only when serving it.
  */
case class TeamImageData(contentType: String, data: Array[Byte], modified: DateTime)

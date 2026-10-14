/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.mixins

import org.joda.time.DateTime
import org.maproulette.exception.InvalidException
import org.maproulette.framework.model.TeamImage
import play.api.libs.Files
import play.api.mvc.{MultipartFormData, Request, Result, Results}

/**
  * An uploaded image that passed validation, with the filename it arrived
  * under so callers can fall back to it when naming the image.
  */
case class UploadedImage(data: Array[Byte], contentType: String, filename: String)

/**
  * Shared mechanics for the endpoints that take an image in and hand one back.
  *
  * Both of the images a team can have - the moderated one its challenges use
  * and the avatar it is shown by - accept the same formats under the same size
  * limit and are served from our own origin under the same caching and
  * content-type rules. Those rules live here so the two endpoints cannot
  * answer differently, which matters most for the two security headers: a
  * change made in one place and missed in the other is invisible until it is
  * exploited rather than failing loudly.
  */
trait ImageUploadMixin extends Results {

  /**
    * Reads the uploaded image out of a multipart request, rejecting anything
    * too large or not an image we serve.
    *
    * The size is checked before the bytes are read, so an oversized upload is
    * turned away rather than pulled into memory first. The declared content
    * type is caller-supplied and therefore not trusted: the leading bytes
    * decide the format, since we will later serve this back from our own
    * origin.
    *
    * @param request The multipart request carrying the image
    * @param field   The form field the image is expected in
    * @return The validated image
    */
  def readImageUpload(
      request: Request[MultipartFormData[Files.TemporaryFile]],
      field: String = "image"
  ): UploadedImage = {
    val upload = request.body
      .file(field)
      .getOrElse(throw new InvalidException(s"No image file provided in the '$field' field"))

    if (upload.fileSize > TeamImage.MAX_SIZE_BYTES) {
      throw new InvalidException(
        s"Image is larger than the ${TeamImage.MAX_SIZE_BYTES / (1024 * 1024)}MB limit"
      )
    }

    val data = java.nio.file.Files.readAllBytes(upload.ref.path)
    val contentType = TeamImage
      .detectContentType(data)
      .getOrElse(
        throw new InvalidException(
          s"Unsupported image format. Supported formats: ${TeamImage.ALLOWED_CONTENT_TYPES.toList.sorted
            .mkString(", ")}"
        )
      )

    UploadedImage(data, contentType, upload.filename)
  }

  /**
    * Writes an image's bytes out, answering a still-current cached copy with a
    * 304 instead.
    *
    * The ETag is built from the identity and modification time alone, so the
    * caller can settle a revalidation without ever loading the blob - hence
    * `data` being by-name rather than a value.
    *
    * @param etagKey      Identifies the image within its own endpoint
    * @param modified     When the image last changed
    * @param contentType  The image's format
    * @param data         The bytes, evaluated only when they are actually sent
    * @param cacheControl How long, and to whom, the response may be cached
    */
  def serveImageBytes(
      etagKey: String,
      modified: DateTime,
      contentType: String,
      data: => Array[Byte],
      cacheControl: String
  )(implicit request: Request[_]): Result = {
    val etag = s""""$etagKey-${modified.getMillis}""""

    if (request.headers.get("If-None-Match").contains(etag)) {
      NotModified.withHeaders("ETag" -> etag)
    } else {
      Ok(data)
        .as(contentType)
        .withHeaders(
          "ETag"          -> etag,
          "Cache-Control" -> cacheControl,
          // Served from our own origin, so the browser must not be talked into
          // treating these bytes as anything but the image we sniffed them to
          // be, nor into opening them as a page.
          "X-Content-Type-Options" -> "nosniff",
          "Content-Disposition"    -> "inline"
        )
    }
  }
}

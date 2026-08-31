/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.model

import org.scalatestplus.play.PlaySpec

class TeamImageSpec extends PlaySpec {
  private def bytes(values: Int*): Array[Byte] = values.map(_.toByte).toArray

  private val PNG  = bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x01)
  private val JPEG = bytes(0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10)
  private val GIF  = bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)
  private val WEBP =
    bytes(0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50)

  "TeamImage.detectContentType" should {
    "detect png" in {
      TeamImage.detectContentType(PNG) mustEqual Some("image/png")
    }

    "detect jpeg" in {
      TeamImage.detectContentType(JPEG) mustEqual Some("image/jpeg")
    }

    "detect gif" in {
      TeamImage.detectContentType(GIF) mustEqual Some("image/gif")
    }

    "detect webp, which needs the WEBP marker after the RIFF header" in {
      TeamImage.detectContentType(WEBP) mustEqual Some("image/webp")
    }

    "reject a RIFF container that is not webp (e.g. a wav file)" in {
      val wav = bytes(0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x41, 0x56, 0x45)
      TeamImage.detectContentType(wav) mustEqual None
    }

    "reject svg, which could carry script since we serve these bytes from our own origin" in {
      val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>".getBytes("UTF-8")
      TeamImage.detectContentType(svg) mustEqual None
      TeamImage.ALLOWED_CONTENT_TYPES must not contain "image/svg+xml"
    }

    "reject html masquerading as an image" in {
      TeamImage.detectContentType("<html><body>hi</body></html>".getBytes("UTF-8")) mustEqual None
    }

    "reject empty and truncated input rather than reading past the end" in {
      TeamImage.detectContentType(Array.emptyByteArray) mustEqual None
      // A RIFF header with no room for the WEBP marker at offset 8.
      TeamImage.detectContentType(bytes(0x52, 0x49, 0x46, 0x46)) mustEqual None
      TeamImage.detectContentType(bytes(0x89, 0x50)) mustEqual None
    }
  }

  "TeamImage.urlFor" should {
    "build a relative url so the stored value works across environments" in {
      TeamImage.urlFor(42) mustEqual "/api/v2/teamImage/42/file"
    }
  }

  "TeamImage.statusName" should {
    "name each review state" in {
      TeamImage.statusName(TeamImage.STATUS_PENDING) mustEqual "pending"
      TeamImage.statusName(TeamImage.STATUS_APPROVED) mustEqual "approved"
      TeamImage.statusName(TeamImage.STATUS_REJECTED) mustEqual "rejected"
      TeamImage.statusName(99) mustEqual "unknown"
    }
  }

  "TeamImage limits" should {
    "cap uploads at 2MB, matching the client-side check" in {
      TeamImage.MAX_SIZE_BYTES mustEqual 2 * 1024 * 1024
    }

    "bound how many requests a team can queue for review" in {
      TeamImage.MAX_PENDING_PER_TEAM must be > 0
    }
  }
}

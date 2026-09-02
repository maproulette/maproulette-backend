/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.joda.time.DateTime

/**
  * A team's uploaded avatar. There is at most one per team, so the team id is
  * the only identity it needs.
  *
  * The raw bytes are deliberately not part of this case class - they are only
  * read when actually serving the avatar.
  */
case class TeamAvatar(
    teamId: Long,
    contentType: String,
    size: Long,
    uploadedBy: Option[Long] = None,
    created: DateTime,
    modified: DateTime
)

object TeamAvatar {
  val TABLE = "team_avatars"

  // An avatar is a team image like any other, so it is held to the same format
  // and size rules rather than a parallel set that could drift from them.
  val ALLOWED_CONTENT_TYPES: Set[String] = TeamImage.ALLOWED_CONTENT_TYPES
  val MAX_SIZE_BYTES: Int                = TeamImage.MAX_SIZE_BYTES

  def detectContentType(data: Array[Byte]): Option[String] = TeamImage.detectContentType(data)

  /**
    * The url that serves a team's avatar, stored in groups.avatar_url so the
    * rest of the app keeps treating the avatar as a plain url.
    *
    * Unlike an approved team image, an avatar is mutable - the same team id
    * serves different bytes after a re-upload - so the url carries the upload
    * timestamp. That lets the response be cached hard while still changing the
    * moment a new avatar is uploaded.
    */
  def urlFor(teamId: Long, version: Long): String = s"/api/v2/team/$teamId/avatar/file?v=$version"

  /**
    * Whether a stored avatar url is one of ours for the given team, as opposed
    * to an external url the team pasted in.
    */
  def isStoredAvatarUrl(url: String, teamId: Long): Boolean =
    url.startsWith(s"/api/v2/team/$teamId/avatar/file")
}

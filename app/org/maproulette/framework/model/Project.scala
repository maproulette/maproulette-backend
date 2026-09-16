/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.joda.time.DateTime
import org.maproulette.cache.CacheObject
import org.maproulette.data.{ItemType}
import org.maproulette.framework.psql.CommonField
import org.maproulette.framework.model.Identifiable
import play.api.libs.json.{JsObject, JsString, JsValue, Json, Reads, Writes}
import play.api.libs.json.JodaWrites._
import play.api.libs.json.JodaReads._

/**
  * The project object is the root object of hierarchy, it is built to allow users to have personal
  * domains where they can create their own challenges and have a permissions model that allows
  * users to have and give control over what happens within that domain.
  *
  * @author cuthbertm
  */
case class Project(
    override val id: Long,
    owner: Long,
    override val name: String,
    created: DateTime = DateTime.now(),
    modified: DateTime = DateTime.now(),
    description: Option[String] = None,
    grants: List[Grant] = List.empty,
    enabled: Boolean = false,
    displayName: Option[String] = None,
    deleted: Boolean = false,
    isVirtual: Option[Boolean] = Some(false),
    featured: Boolean = false,
    isArchived: Boolean = false,
    requireConfirmation: Boolean = false,
    completionMetrics: CompletionMetrics = CompletionMetrics(),
    // The team that owns the project, if one does. Its owners, admins and
    // managers run the project, and its approved image is the picture on the
    // project's card. Distinct from granting a team a role here, which is one
    // of several teams helping out rather than the one behind the project.
    ownerTeamId: Option[Long] = None
) extends CacheObject[Long]
    with Identifiable {
  def grantsToType(granteeType: ItemType) =
    this.grants.filter(_.grantee.granteeType == granteeType)
}

object Project extends CommonField {
  implicit val grantWrites: Writes[Grant] = Grant.writes
  implicit val grantReads: Reads[Grant]   = Grant.reads
  private val baseWrites: Writes[Project] = Json.writes[Project]

  /**
    * Adds the derived `avatarUrl` a client puts on a project card: the image of
    * the team that owns the project. Named and shaped like the challenge
    * equivalent, so a card renders either the same way, and absent rather than
    * a dead link when no team owns the project -- a client can then tell "no
    * picture" from "picture failed to load".
    */
  implicit val writes: Writes[Project] = new Writes[Project] {
    def writes(project: Project): JsValue = {
      val json = baseWrites.writes(project).as[JsObject]
      project.ownerTeamId match {
        case Some(teamId) => json + ("avatarUrl" -> JsString(TeamImage.urlForTeam(teamId)))
        case None         => json
      }
    }
  }

  implicit val reads: Reads[Project] = Json.using[Json.WithDefaultValues].reads[Project]

  val TABLE              = "projects"
  val KEY_GRANTS         = "grants"
  val FIELD_OWNER        = "owner_id"
  val FIELD_ENABLED      = "enabled"
  val FIELD_DISPLAY_NAME = "display_name"
  val FIELD_DELETED      = "deleted"
  val FIELD_VIRTUAL      = "is_virtual"
  val FIELD_FEATURED     = "featured"
  val FIELD_IS_ARCHIVED  = "is_archived"
  val FIELD_OWNER_TEAM   = "owner_team_id"

  def emptyProject: Project =
    Project(-1, User.DEFAULT_SUPER_USER_ID, "", DateTime.now(), DateTime.now())
}

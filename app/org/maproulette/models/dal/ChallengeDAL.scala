/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.models.dal

import java.sql.Connection

import anorm.SqlParser._
import anorm._
import javax.inject.{Inject, Singleton}
import org.apache.commons.lang3.StringUtils
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.cache.CacheManager
import org.maproulette.data.{Actions, ChallengeType, ProjectType}
import org.maproulette.exception.{InvalidException, NotFoundException, UniqueViolationException}
import org.maproulette.framework.model._
import org.maproulette.framework.repository.{
  ProjectRepository,
  TaskRepository,
  ChallengeListingRepository
}
import org.maproulette.framework.service.{ServiceManager, TagService}
import org.maproulette.models.dal.mixin.{OwnerMixin, TagDALMixin}
import org.maproulette.permissions.Permission
import org.maproulette.session.SearchParameters
import org.maproulette.utils.Utils
import play.api.db.Database
import play.api.libs.json.JodaReads._
import play.api.libs.json.{JsString, JsValue, Json}

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

/**
  * The challenge data access layer handles all calls for challenges going to the database. Most
  * worked is delegated to the ParentDAL and BaseDAL, but a couple of specific function like
  * insert and update found here.
  *
  * @author cuthbertm
  */
@Singleton
class ChallengeDAL @Inject() (
    override val db: Database,
    serviceManager: ServiceManager,
    taskDAL: TaskDAL,
    taskRepository: TaskRepository,
    override val tagService: TagService,
    override val permission: Permission,
    config: Config
) extends ParentDAL[Long, Challenge, Task]
    with TagDALMixin[Challenge]
    with OwnerMixin[Challenge] {

  import scala.concurrent.ExecutionContext.Implicits.global

  // The manager for the challenge cache
  override val cacheManager = new CacheManager[Long, Challenge](config, Config.CACHE_ID_CHALLENGES)
  // The name of the challenge table
  override val tableName: String = "challenges"
  // The name of the table for it's children Tasks
  override val childTable: String = "tasks"
  // The row parser for it's children defined in the TaskDAL
  override val childParser          = taskDAL.parser
  override val childColumns: String = taskDAL.retrieveColumns
  override val retrieveColumns: String =
    "*, ST_AsGeoJSON(location) AS locationJSON, ST_AsGeoJSON(bounding) AS boundingJSON"

  /**
    * The row parser for Anorm to enable the object to be read from the retrieved row directly
    * to the Challenge object.
    */
  override val parser: RowParser[Challenge] = {
    get[Long]("challenges.id") ~
      get[String]("challenges.name") ~
      get[DateTime]("challenges.created") ~
      get[DateTime]("challenges.modified") ~
      get[Option[String]]("challenges.description") ~
      get[Option[String]]("challenges.info_link") ~
      get[Long]("challenges.owner_id") ~
      get[Long]("challenges.parent_id") ~
      get[String]("challenges.instruction") ~
      get[Int]("challenges.difficulty") ~
      get[Option[String]]("challenges.blurb") ~
      get[Boolean]("challenges.enabled") ~
      get[Boolean]("challenges.featured") ~
      get[Int]("challenges.cooperative_type") ~
      get[Option[Int]]("challenges.popularity") ~
      get[Option[String]]("challenges.checkin_comment") ~
      get[Option[String]]("challenges.checkin_source") ~
      get[Option[String]]("challenges.overpass_ql") ~
      get[Option[String]]("challenges.remote_geo_json") ~
      get[Option[String]]("challenges.overpass_target_type") ~
      get[Option[Int]]("challenges.status") ~
      get[Option[String]]("challenges.status_message") ~
      get[Int]("challenges.default_priority") ~
      get[Option[String]]("challenges.high_priority_rule") ~
      get[Option[String]]("challenges.medium_priority_rule") ~
      get[Option[String]]("challenges.low_priority_rule") ~
      get[Int]("challenges.default_zoom") ~
      get[Int]("challenges.min_zoom") ~
      get[Int]("challenges.max_zoom") ~
      get[Option[Int]]("challenges.default_basemap") ~
      get[Option[String]]("challenges.default_basemap_id") ~
      get[Option[String]]("challenges.custom_basemap") ~
      get[Boolean]("challenges.updatetasks") ~
      get[Option[String]]("challenges.exportable_properties") ~
      get[Option[String]]("challenges.osm_id_property") ~
      get[Option[String]]("challenges.task_bundle_id_property") ~
      get[Option[String]]("challenges.preferred_tags") ~
      get[Option[String]]("challenges.preferred_review_tags") ~
      get[Boolean]("challenges.limit_tags") ~
      get[Boolean]("challenges.limit_review_tags") ~
      get[Option[String]]("challenges.task_styles") ~
      get[Option[DateTime]]("challenges.last_task_refresh") ~
      get[Option[DateTime]]("challenges.data_origin_date") ~
      get[Option[String]]("locationJSON") ~
      get[Option[String]]("boundingJSON") ~
      get[Boolean]("challenges.requires_local") ~
      get[Boolean]("deleted") ~
      get[Boolean]("challenges.is_archived") ~
      get[Int]("challenges.review_setting") ~
      get[Option[Int]]("challenges.completion_percentage") ~
      get[Option[Int]]("challenges.tasks_remaining") map {
      case id ~ name ~ created ~ modified ~ description ~ infoLink ~ ownerId ~ parentId ~ instruction ~
            difficulty ~ blurb ~ enabled ~ featured ~ cooperativeType ~ popularity ~ checkin_comment ~
            checkin_source ~ overpassql ~ remoteGeoJson ~ overpassTargetType ~ status ~ statusMessage ~
            defaultPriority ~ highPriorityRule ~ mediumPriorityRule ~ lowPriorityRule ~ defaultZoom ~
            minZoom ~ maxZoom ~ defaultBasemap ~ defaultBasemapId ~ customBasemap ~ updateTasks ~
            exportableProperties ~ osmIdProperty ~ taskBundleIdProperty ~ preferredTags ~ preferredReviewTags ~
            limitTags ~ limitReviewTags ~ taskStyles ~ lastTaskRefresh ~ dataOriginDate ~ location ~ bounding ~
            requiresLocal ~ deleted ~ isArchived ~ reviewSetting ~ completionPercentage ~ tasksRemaining =>
        val hpr = highPriorityRule match {
          case Some(c) if StringUtils.isEmpty(c) || StringUtils.equals(c, "{}") => None
          case r                                                                => r
        }
        val mpr = mediumPriorityRule match {
          case Some(c) if StringUtils.isEmpty(c) || StringUtils.equals(c, "{}") => None
          case r                                                                => r
        }
        val lpr = lowPriorityRule match {
          case Some(c) if StringUtils.isEmpty(c) || StringUtils.equals(c, "{}") => None
          case r                                                                => r
        }
        new Challenge(
          id,
          name,
          created,
          modified,
          description,
          deleted,
          infoLink,
          ChallengeGeneral(
            ownerId,
            parentId,
            instruction,
            difficulty,
            blurb,
            enabled,
            featured,
            cooperativeType,
            popularity,
            checkin_comment.getOrElse(""),
            checkin_source.getOrElse(""),
            None,
            requiresLocal
          ),
          ChallengeCreation(overpassql, remoteGeoJson, overpassTargetType),
          ChallengePriority(defaultPriority, hpr, mpr, lpr),
          ChallengeExtra(
            defaultZoom,
            minZoom,
            maxZoom,
            defaultBasemap,
            defaultBasemapId,
            customBasemap,
            updateTasks,
            exportableProperties,
            osmIdProperty,
            preferredTags,
            preferredReviewTags,
            limitTags,
            limitReviewTags,
            taskStyles,
            taskBundleIdProperty,
            isArchived,
            reviewSetting
          ),
          status,
          statusMessage,
          lastTaskRefresh,
          dataOriginDate,
          location,
          bounding,
          completionPercentage,
          tasksRemaining
        )
    }
  }

  /**
    * The row parser for Anorm to enable the object to be read from the retrieved row directly
    * to the Challenge object.
    */
  val withVirtualParentParser: RowParser[Challenge] = {
    get[Long]("challenges.id") ~
      get[String]("challenges.name") ~
      get[DateTime]("challenges.created") ~
      get[DateTime]("challenges.modified") ~
      get[Option[String]]("challenges.description") ~
      get[Option[String]]("challenges.info_link") ~
      get[Long]("challenges.owner_id") ~
      get[Long]("challenges.parent_id") ~
      get[String]("challenges.instruction") ~
      get[Int]("challenges.difficulty") ~
      get[Option[String]]("challenges.blurb") ~
      get[Boolean]("challenges.enabled") ~
      get[Boolean]("challenges.featured") ~
      get[Int]("challenges.cooperative_type") ~
      get[Option[Int]]("challenges.popularity") ~
      get[Option[String]]("challenges.checkin_comment") ~
      get[Option[String]]("challenges.checkin_source") ~
      get[Option[String]]("challenges.overpass_ql") ~
      get[Option[String]]("challenges.remote_geo_json") ~
      get[Option[String]]("challenges.overpass_target_type") ~
      get[Option[Int]]("challenges.status") ~
      get[Option[String]]("challenges.status_message") ~
      get[Int]("challenges.default_priority") ~
      get[Option[String]]("challenges.high_priority_rule") ~
      get[Option[String]]("challenges.medium_priority_rule") ~
      get[Option[String]]("challenges.low_priority_rule") ~
      get[Int]("challenges.default_zoom") ~
      get[Int]("challenges.min_zoom") ~
      get[Int]("challenges.max_zoom") ~
      get[Option[Int]]("challenges.default_basemap") ~
      get[Option[String]]("challenges.default_basemap_id") ~
      get[Option[String]]("challenges.custom_basemap") ~
      get[Boolean]("challenges.updatetasks") ~
      get[Option[String]]("challenges.exportable_properties") ~
      get[Option[String]]("challenges.osm_id_property") ~
      get[Option[String]]("challenges.task_bundle_id_property") ~
      get[Option[String]]("challenges.preferred_tags") ~
      get[Option[String]]("challenges.preferred_review_tags") ~
      get[Boolean]("challenges.limit_tags") ~
      get[Boolean]("challenges.limit_review_tags") ~
      get[Option[String]]("challenges.task_styles") ~
      get[Option[DateTime]]("challenges.last_task_refresh") ~
      get[Option[DateTime]]("challenges.data_origin_date") ~
      get[Option[String]]("locationJSON") ~
      get[Option[String]]("boundingJSON") ~
      get[Boolean]("challenges.requires_local") ~
      get[Boolean]("deleted") ~
      get[Option[List[Long]]]("virtual_parent_ids") ~
      get[Option[List[String]]]("presets") ~
      get[Boolean]("challenges.is_archived") ~
      get[Int]("challenges.review_setting") ~
      get[Option[DateTime]]("challenges.system_archived_at") ~
      get[Option[Int]]("challenges.completion_percentage") ~
      get[Option[Int]]("challenges.tasks_remaining") map {
      case id ~ name ~ created ~ modified ~ description ~ infoLink ~ ownerId ~ parentId ~ instruction ~
            difficulty ~ blurb ~ enabled ~ featured ~ cooperativeType ~ popularity ~
            checkin_comment ~ checkin_source ~ overpassql ~ remoteGeoJson ~ overpassTargetType ~
            status ~ statusMessage ~ defaultPriority ~ highPriorityRule ~ mediumPriorityRule ~
            lowPriorityRule ~ defaultZoom ~ minZoom ~ maxZoom ~ defaultBasemap ~ defaultBasemapId ~
            customBasemap ~ updateTasks ~ exportableProperties ~ osmIdProperty ~ taskBundleIdProperty ~ preferredTags ~
            preferredReviewTags ~ limitTags ~ limitReviewTags ~ taskStyles ~ lastTaskRefresh ~
            dataOriginDate ~ location ~ bounding ~ requiresLocal ~ deleted ~ virtualParents ~
            presets ~ isArchived ~ reviewSetting ~ systemArchivedAt ~ completionPercentage ~ tasksRemaining =>
        val hpr = highPriorityRule match {
          case Some(c) if StringUtils.isEmpty(c) || StringUtils.equals(c, "{}") => None
          case r                                                                => r
        }
        val mpr = mediumPriorityRule match {
          case Some(c) if StringUtils.isEmpty(c) || StringUtils.equals(c, "{}") => None
          case r                                                                => r
        }
        val lpr = lowPriorityRule match {
          case Some(c) if StringUtils.isEmpty(c) || StringUtils.equals(c, "{}") => None
          case r                                                                => r
        }
        new Challenge(
          id,
          name,
          created,
          modified,
          description,
          deleted,
          infoLink,
          ChallengeGeneral(
            ownerId,
            parentId,
            instruction,
            difficulty,
            blurb,
            enabled,
            featured,
            cooperativeType,
            popularity,
            checkin_comment.getOrElse(""),
            checkin_source.getOrElse(""),
            virtualParents,
            requiresLocal
          ),
          ChallengeCreation(overpassql, remoteGeoJson, overpassTargetType),
          ChallengePriority(defaultPriority, hpr, mpr, lpr),
          ChallengeExtra(
            defaultZoom,
            minZoom,
            maxZoom,
            defaultBasemap,
            defaultBasemapId,
            customBasemap,
            updateTasks,
            exportableProperties,
            osmIdProperty,
            preferredTags,
            preferredReviewTags,
            limitTags,
            limitReviewTags,
            taskStyles,
            taskBundleIdProperty,
            isArchived,
            reviewSetting,
            systemArchivedAt,
            presets
          ),
          status,
          statusMessage,
          lastTaskRefresh,
          dataOriginDate,
          location,
          bounding,
          completionPercentage,
          tasksRemaining
        )
    }
  }
  val pointParser: RowParser[ClusteredPoint] = {
    get[Long]("tasks.id") ~
      get[String]("tasks.name") ~
      get[Long]("tasks.parent_id") ~
      get[String]("challenges.name").? ~
      get[String]("challengeName").? ~
      get[String]("tasks.instruction") ~
      get[String]("location") ~
      get[Int]("tasks.status") ~
      get[Option[DateTime]]("tasks.mapped_on") ~
      get[Option[Long]]("tasks.completed_time_spent") ~
      get[Option[Long]]("tasks.completed_by") ~
      get[Int]("tasks.priority") ~
      get[Option[Long]]("tasks.bundle_id") ~
      get[Option[Boolean]]("tasks.is_bundle_primary") ~
      get[Option[String]]("cooperative_work") ~
      get[Option[Int]]("task_review.review_status") ~
      get[Option[Long]]("task_review.review_requested_by") ~
      get[Option[Long]]("task_review.reviewed_by") ~
      get[Option[DateTime]]("task_review.reviewed_at") ~
      get[Option[DateTime]]("task_review.review_started_at") ~
      get[Option[List[Long]]]("task_review.additional_reviewers") ~
      get[Option[Int]]("task_review.meta_review_status") ~
      get[Option[Long]]("task_review.meta_reviewed_by") ~
      get[Option[DateTime]]("task_review.meta_reviewed_at") map {
      case id ~ name ~ parentId ~ parentName ~ orParentName ~ instruction ~ location ~ status ~
            mappedOn ~ completedTimeSpent ~ completedBy ~ priority ~ bundleId ~
            isBundlePrimary ~ cooperativeWork ~ reviewStatus ~ reviewRequestedBy ~
            reviewedBy ~ reviewedAt ~ reviewStartedAt ~ additionalReviewers ~
            metaReviewStatus ~ metaReviewedBy ~ metaReviewedAt =>
        val locationJSON = Json.parse(location)
        val coordinates  = (locationJSON \ "coordinates").as[List[Double]]
        val point        = Point(coordinates(1), coordinates.head)
        val pointReview =
          PointReview(
            reviewStatus,
            reviewRequestedBy,
            reviewedBy,
            reviewedAt,
            metaReviewStatus,
            metaReviewedBy,
            metaReviewedAt,
            reviewStartedAt,
            additionalReviewers
          )
        ClusteredPoint(
          id,
          -1,
          "",
          name,
          parentId,
          parentName.getOrElse(orParentName.get),
          point,
          JsString(""),
          instruction,
          DateTime.now(),
          -1,
          Actions.ITEM_TYPE_TASK,
          status,
          cooperativeWork,
          mappedOn,
          completedTimeSpent,
          completedBy,
          pointReview,
          priority,
          bundleId,
          isBundlePrimary
        )
    }
  }

  private val DEFAULT_NUM_CHILDREN_LIST = 1000

  /**
    * This will retrieve the root object in the hierarchy of the object, by default the root
    * object is itself.
    *
    * @param obj Either a id for the challenge, or the challenge itself
    * @param c   The connection if any
    * @return The object that it is retrieving
    */
  def retrieveRootObject(obj: Either[Long, Challenge], user: User)(
      implicit c: Option[Connection] = None
  ): Option[Project] = {
    val projectParser = ProjectRepository.parser(projectId =>
      this.serviceManager.grant.retrieveGrantsOn(GrantTarget.project(projectId), User.superUser)
    )
    obj match {
      case Left(id) =>
        this.permission.hasReadAccess(ChallengeType(), user)(id)
        this.serviceManager.project.cacheManager.withOptionCaching { () =>
          this.withMRConnection { implicit c =>
            SQL"""SELECT p.* FROM projects p
             INNER JOIN challenges c ON c.parent_id = p.id
             WHERE c.id = $id
           """.as(projectParser.*).headOption
          }
        }(Some(id))
      case Right(challenge) =>
        this.permission.hasObjectReadAccess(challenge, user)
        this.serviceManager.project.cacheManager.withOptionCaching { () =>
          this.withMRConnection { implicit c =>
            SQL"""SELECT * FROM projects WHERE id = ${challenge.general.parent}"""
              .as(projectParser.*)
              .headOption
          }
        }(Some(challenge.general.parent))
    }
  }

  /**
    * Inserts a new Challenge object into the database. It will also place it in the cache after
    * inserting the object.
    *
    * @param challenge The challenge to insert into the database
    * @return The object that was inserted into the database. This will include the newly created id
    */
  override def insert(challenge: Challenge, user: User)(
      implicit c: Option[Connection] = None
  ): Challenge = {
    this.serviceManager.project.retrieve(challenge.general.parent) match {
      case Some(project) =>
        if (project.isVirtual.getOrElse(false)) {
          throw new InvalidException(s"Challenge cannot be created in a virtual project.")
        }
      case _ => throw new InvalidException(s"Cannot create challenge. Project is invalid.")
    }

    this.permission.hasObjectWriteAccess(challenge, user)
    this.cacheManager.withOptionCaching { () =>
      val insertedChallenge =
        this.withMRTransaction { implicit c =>
          SQL"""INSERT INTO challenges (name, owner_id, parent_id, difficulty, description, info_link, blurb,
                                      instruction, enabled, featured, checkin_comment, checkin_source,
                                      overpass_ql, remote_geo_json, overpass_target_type, status, status_message, default_priority, high_priority_rule,
                                      medium_priority_rule, low_priority_rule, default_zoom, min_zoom, max_zoom,
                                      default_basemap, default_basemap_id, custom_basemap, updatetasks, exportable_properties,
                                      osm_id_property, task_bundle_id_property, last_task_refresh, data_origin_date, preferred_tags, preferred_review_tags,
                                      limit_tags, limit_review_tags, task_styles, requires_local, is_archived, review_setting)
              VALUES (${challenge.name}, ${challenge.general.owner}, ${challenge.general.parent}, ${challenge.general.difficulty},
                      ${challenge.description}, ${challenge.infoLink}, ${challenge.general.blurb}, ${challenge.general.instruction},
                      ${challenge.general.enabled}, ${challenge.general.featured},
                      ${challenge.general.checkinComment}, ${challenge.general.checkinSource}, ${challenge.creation.overpassQL}, ${challenge.creation.remoteGeoJson},
                      ${challenge.creation.overpassTargetType}, ${challenge.status},
                      ${challenge.statusMessage}, ${challenge.priority.defaultPriority}, ${challenge.priority.highPriorityRule},
                      ${challenge.priority.mediumPriorityRule}, ${challenge.priority.lowPriorityRule}, ${challenge.extra.defaultZoom}, ${challenge.extra.minZoom},
                      ${challenge.extra.maxZoom}, ${challenge.extra.defaultBasemap}, ${challenge.extra.defaultBasemapId}, ${challenge.extra.customBasemap}, ${challenge.extra.updateTasks},
                      ${challenge.extra.exportableProperties}, ${challenge.extra.osmIdProperty}, ${challenge.extra.taskBundleIdProperty},
                      ${challenge.lastTaskRefresh.getOrElse(DateTime.now()).toString}::timestamptz,
                      ${challenge.dataOriginDate.getOrElse(DateTime.now()).toString}::timestamptz,
                      ${challenge.extra.preferredTags}, ${challenge.extra.preferredReviewTags}, ${challenge.extra.limitTags},
                      ${challenge.extra.limitReviewTags}, ${challenge.extra.taskStyles}, ${challenge.general.requiresLocal}, ${challenge.extra.isArchived},
                      ${challenge.extra.reviewSetting})
                      ON CONFLICT(parent_id, LOWER(name)) DO NOTHING RETURNING #${this.retrieveColumns}"""
            .as(this.parser.*)
            .headOption
        }

      // Now insert presets if we have any
      insertedChallenge match {
        case Some(newChallenge) =>
          challenge.extra.presets match {
            case Some(ps) => Some(this.insertPresets(newChallenge, ps))
            case None     => insertedChallenge
          }
        case None => insertedChallenge
      }
    } match {
      case Some(value) => value
      case None =>
        throw new UniqueViolationException(
          s"Challenge with name ${challenge.name} already exists in the database"
        )
    }
  }

  private def insertPresets(challenge: Challenge, presets: List[String])(
      implicit c: Option[Connection] = None
  ): Challenge = {
    this.withMRConnection { implicit c =>
      presets.map(preset => {
        SQL(
          """INSERT INTO challenge_presets (challenge_id, preset)
               VALUES ({challengeId}, {preset})"""
        ).on(
            Symbol("challengeId") -> challenge.id,
            Symbol("preset")      -> preset
          )
          .executeUpdate()
      })
      challenge.copy(
        extra = challenge.extra.copy(presets = Some(presets))
      )
    }
  }

  /**
    * Updates a challenge. Uses the updatingCache so will first retrieve the object and make sure
    * to update only values supplied by the json. After updated will update the cache as well
    *
    * @param updates The updates in json format
    * @param id      The id of the object that you are updating
    * @return An optional object, it will return None if no object found with a matching id that was supplied
    */
  override def update(
      updates: JsValue,
      user: User
  )(implicit id: Long, c: Option[Connection] = None): Option[Challenge] = {
    var updatedPriorityRules = false
    val updatedChallenge = this.cacheManager.withUpdatingCache(Long => retrieveById) {
      implicit cachedItem =>
        this.permission.hasObjectWriteAccess(cachedItem, user)
        val highPriorityRule = (updates \ "highPriorityRule")
          .asOpt[String]
          .getOrElse(cachedItem.priority.highPriorityRule.getOrElse("")) match {
          case x if Challenge.isValidRule(Some(x)) => x
          case _                                   => ""
        }
        val mediumPriorityRule = (updates \ "mediumPriorityRule")
          .asOpt[String]
          .getOrElse(cachedItem.priority.mediumPriorityRule.getOrElse("")) match {
          case x if Challenge.isValidRule(Some(x)) => x
          case _                                   => ""
        }
        val lowPriorityRule = (updates \ "lowPriorityRule")
          .asOpt[String]
          .getOrElse(cachedItem.priority.lowPriorityRule.getOrElse("")) match {
          case x if Challenge.isValidRule(Some(x)) => x
          case _                                   => ""
        }
        this.withMRTransaction { implicit c =>
          val name     = (updates \ "name").asOpt[String].getOrElse(cachedItem.name)
          val ownerId  = (updates \ "ownerId").asOpt[Long].getOrElse(cachedItem.general.owner)
          val parentId = (updates \ "parentId").asOpt[Long].getOrElse(cachedItem.general.parent)
          val difficulty =
            (updates \ "difficulty").asOpt[Int].getOrElse(cachedItem.general.difficulty)
          val description =
            (updates \ "description").asOpt[String].getOrElse(cachedItem.description.getOrElse(""))
          val infoLink =
            (updates \ "infoLink").asOpt[String].getOrElse(cachedItem.infoLink.getOrElse(""))
          val blurb =
            (updates \ "blurb").asOpt[String].getOrElse(cachedItem.general.blurb.getOrElse(""))
          val instruction =
            (updates \ "instruction").asOpt[String].getOrElse(cachedItem.general.instruction)
          val enabled = (updates \ "enabled").asOpt[Boolean].getOrElse(cachedItem.general.enabled)
          val featured =
            (updates \ "featured").asOpt[Boolean].getOrElse(cachedItem.general.featured)
          val checkinComment =
            (updates \ "checkinComment").asOpt[String].getOrElse(cachedItem.general.checkinComment)
          val checkinSource =
            (updates \ "checkinSource").asOpt[String].getOrElse(cachedItem.general.checkinSource)
          val overpassQL = (updates \ "overpassQL")
            .asOpt[String]
            .getOrElse(cachedItem.creation.overpassQL.getOrElse(""))
          val remoteGeoJson = (updates \ "remoteGeoJson")
            .asOpt[String]
            .getOrElse(cachedItem.creation.remoteGeoJson.getOrElse(""))
          val overpassTargetType = (updates \ "overpassTargetType")
            .asOpt[String]
            .getOrElse(cachedItem.creation.overpassTargetType.getOrElse(""))
          val dataOriginDate = (updates \ "dataOriginDate")
            .asOpt[DateTime]
            .getOrElse(cachedItem.dataOriginDate.getOrElse(DateTime.now()))
          val status = (updates \ "status")
            .asOpt[Int]
            .getOrElse(cachedItem.status.getOrElse(Challenge.STATUS_NA))
          val statusMessage = (updates \ "statusMessage")
            .asOpt[String]
            .getOrElse(cachedItem.statusMessage.getOrElse(""))
          val defaultPriority =
            (updates \ "defaultPriority").asOpt[Int].getOrElse(cachedItem.priority.defaultPriority)
          // if any of the priority rules have changed then we need to run the update priorities task
          if (!StringUtils.equalsIgnoreCase(
                highPriorityRule,
                cachedItem.priority.highPriorityRule.getOrElse("")
              ) ||
              !StringUtils.equalsIgnoreCase(
                mediumPriorityRule,
                cachedItem.priority.mediumPriorityRule.getOrElse("")
              ) ||
              !StringUtils.equalsIgnoreCase(
                lowPriorityRule,
                cachedItem.priority.lowPriorityRule.getOrElse("")
              ) ||
              defaultPriority != cachedItem.priority.defaultPriority) {
            updatedPriorityRules = true
          }
          val defaultZoom =
            (updates \ "defaultZoom").asOpt[Int].getOrElse(cachedItem.extra.defaultZoom)
          val minZoom = (updates \ "minZoom").asOpt[Int].getOrElse(cachedItem.extra.minZoom)
          val maxZoom = (updates \ "maxZoom").asOpt[Int].getOrElse(cachedItem.extra.maxZoom)
          val defaultBasemap = (updates \ "defaultBasemap")
            .asOpt[Int]
            .getOrElse(cachedItem.extra.defaultBasemap.getOrElse(-1))
          val defaultBasemapId = (updates \ "defaultBasemapId")
            .asOpt[String]
            .getOrElse(cachedItem.extra.defaultBasemapId.getOrElse(""))
          val customBasemap = (updates \ "customBasemap")
            .asOpt[String]
            .getOrElse(cachedItem.extra.customBasemap.getOrElse(""))
          val updateTasks =
            (updates \ "updateTasks").asOpt[Boolean].getOrElse(cachedItem.extra.updateTasks)
          val exportableProperties = (updates \ "exportableProperties")
            .asOpt[String]
            .getOrElse(cachedItem.extra.exportableProperties.getOrElse(""))
          val osmIdProperty = (updates \ "osmIdProperty")
            .asOpt[String]
            .getOrElse(cachedItem.extra.osmIdProperty.getOrElse(""))
          val taskBundleIdProperty = (updates \ "taskBundleIdProperty")
            .asOpt[String]
            .getOrElse(cachedItem.extra.taskBundleIdProperty.getOrElse(""))
          val preferredTags = (updates \ "preferredTags")
            .asOpt[String]
            .getOrElse(cachedItem.extra.preferredTags.getOrElse(null))
          val preferredReviewTags = (updates \ "preferredReviewTags")
            .asOpt[String]
            .getOrElse(cachedItem.extra.preferredReviewTags.getOrElse(null))
          val limitTags = (updates \ "limitTags")
            .asOpt[Boolean]
            .getOrElse(cachedItem.extra.limitTags)
          val limitReviewTags = (updates \ "limitReviewTags")
            .asOpt[Boolean]
            .getOrElse(cachedItem.extra.limitReviewTags)
          val taskStyles = (updates \ "taskStyles").asOpt[JsValue] match {
            case Some(ts) => ts.toString()
            case _        => cachedItem.extra.taskStyles.getOrElse(null)
          }
          val requiresLocal = (updates \ "requiresLocal")
            .asOpt[Boolean]
            .getOrElse(cachedItem.general.requiresLocal)

          val isArchived = (updates \ "isArchived")
            .asOpt[Boolean]
            .getOrElse(cachedItem.extra.isArchived)

          val reviewSetting = (updates \ "reviewSetting")
            .asOpt[Int]
            .getOrElse(cachedItem.extra.reviewSetting)

          val presets: List[String] = (updates \ "presets")
            .asOpt[List[String]]
            .getOrElse(cachedItem.extra.presets.getOrElse(null))

          val updatedChallenge =
            SQL"""UPDATE challenges SET name = $name, owner_id = $ownerId, parent_id = $parentId, difficulty = $difficulty,
                  description = $description, info_link = $infoLink, blurb = $blurb, instruction = $instruction,
                  enabled = $enabled, featured = $featured, checkin_comment = $checkinComment, checkin_source = $checkinSource, overpass_ql = $overpassQL,
                  remote_geo_json = $remoteGeoJson, overpass_target_type = $overpassTargetType, status = $status, status_message = $statusMessage, default_priority = $defaultPriority,
                  data_origin_date = ${dataOriginDate.toString()}::timestamptz,
                  high_priority_rule = ${if (StringUtils.isEmpty(highPriorityRule)) {
              Option.empty[String]
            } else {
              Some(highPriorityRule)
            }},
                  medium_priority_rule = ${if (StringUtils.isEmpty(mediumPriorityRule)) {
              Option.empty[String]
            } else {
              Some(mediumPriorityRule)
            }},
                  low_priority_rule = ${if (StringUtils.isEmpty(lowPriorityRule)) {
              Option.empty[String]
            } else {
              Some(lowPriorityRule)
            }},
                  default_zoom = $defaultZoom, min_zoom = $minZoom, max_zoom = $maxZoom, default_basemap = $defaultBasemap, default_basemap_id = $defaultBasemapId,
                  custom_basemap = $customBasemap, updatetasks = $updateTasks, exportable_properties = $exportableProperties,
                  osm_id_property = $osmIdProperty, task_bundle_id_property = $taskBundleIdProperty, preferred_tags = $preferredTags, preferred_review_tags = $preferredReviewTags,
                  limit_tags = $limitTags, limit_review_tags = $limitReviewTags, task_styles = $taskStyles,
                  requires_local = $requiresLocal, is_archived = $isArchived, review_setting = $reviewSetting
                WHERE id = $id RETURNING #${this.retrieveColumns}""".as(parser.*).headOption

          updatedChallenge match {
            case Some(uc) =>
              if (presets != null) {
                //drop and reinsert presets
                SQL(s"DELETE FROM challenge_presets WHERE challenge_id=${uc.id}").executeUpdate()
                Some(this.insertPresets(uc, presets))
              } else {
                updatedChallenge
              }
            case None => None
          }
        }
    }
    // update the task priorities in the background
    if (updatedPriorityRules) {
      Future {
        updateTaskPriorities(user, overrideValidation = true)
      }
    }

    updatedChallenge match {
      case Some(challenge) =>
        if (challenge.status.getOrElse(Challenge.STATUS_NA) == Challenge.STATUS_READY &&
            challenge.general.enabled) {
          Future {
            this.serviceManager.achievement.awardChallengeCreationAchievements(challenge)
          }
        }
      case None => // nothing to do
    }

    updatedChallenge
  }

  /**
    * Will run through the tasks in batches of 50 and update the priorities based on the rules
    * of the challenge
    *
    * @param user The user executing the request
    * @param id   The id of the challenge
    * @param c    The connection for the request
    */
  def updateTaskPriorities(
      user: User,
      overrideValidation: Boolean = false
  )(implicit id: Long, c: Option[Connection] = None): Unit = {
    this.permission.hasWriteAccess(ChallengeType(), user)
    this.withMRConnection { implicit c =>
      val challenge = this.retrieveById(id) match {
        case Some(c) => c
        case None =>
          throw new NotFoundException(
            s"Could not update priorties for tasks, no challenge with id $id found."
          )
      }
      // make sure that at least one of the challenges is valid
      if (overrideValidation || Challenge.isValidRule(challenge.priority.highPriorityRule) ||
          Challenge.isValidRule(challenge.priority.mediumPriorityRule) ||
          Challenge.isValidRule(challenge.priority.lowPriorityRule)) {
        var pointer                  = 0
        var currentTasks: List[Task] = List.empty
        do {
          currentTasks =
            listChildren(DEFAULT_NUM_CHILDREN_LIST, pointer * DEFAULT_NUM_CHILDREN_LIST)
          val highPriorityIDs = currentTasks
            .filter(_.getTaskPriority(challenge) == Challenge.PRIORITY_HIGH)
            .map(_.id)
            .mkString(",")
          val mediumPriorityIDs = currentTasks
            .filter(_.getTaskPriority(challenge) == Challenge.PRIORITY_MEDIUM)
            .map(_.id)
            .mkString(",")
          val lowPriorityIDs = currentTasks
            .filter(_.getTaskPriority(challenge) == Challenge.PRIORITY_LOW)
            .map(_.id)
            .mkString(",")

          if (highPriorityIDs.nonEmpty) {
            SQL"""UPDATE tasks SET priority = ${Challenge.PRIORITY_HIGH} WHERE id IN (#$highPriorityIDs)"""
              .executeUpdate()
          }
          if (mediumPriorityIDs.nonEmpty) {
            SQL"""UPDATE tasks SET priority = ${Challenge.PRIORITY_MEDIUM} WHERE id IN (#$mediumPriorityIDs)"""
              .executeUpdate()
          }
          if (lowPriorityIDs.nonEmpty) {
            SQL"""UPDATE tasks SET priority = ${Challenge.PRIORITY_LOW} WHERE id IN (#$lowPriorityIDs)"""
              .executeUpdate()
          }

          pointer += 1
        } while (currentTasks.size >= DEFAULT_NUM_CHILDREN_LIST)
        this.taskDAL.clearCaches
      }
    }
  }

  /**
    * Lists the children of the parent, override the base functionality and includes the geojson
    * as part of the query so that it doesn't have to fetch it each and every time.
    *
    * @param limit  limits the number of children to be returned
    * @param offset For paging, ie. the page number starting at 0
    * @param id     The parent ID
    * @return A list of children objects
    */
  override def listChildren(
      limit: Int = Config.DEFAULT_LIST_SIZE,
      page: Int = 0,
      onlyEnabled: Boolean = false,
      searchString: String = "",
      orderColumn: String = "id",
      orderDirection: String = "ASC"
  )(implicit id: Long, c: Option[Connection] = None): List[Task] = {
    // add a child caching option that will keep a list of children for the parent
    this.withMRConnection { implicit c =>
      val geometryParser = this.taskRepository.getTaskParser(this.taskRepository.updateAndRetrieve)
      val offset         = page * limit;
      val query =
        s"""SELECT ${taskDAL.retrieveColumns}
                      FROM tasks
                      LEFT OUTER JOIN task_review ON task_review.task_id = tasks.id
                      WHERE parent_id = {id} ${this.enabled(onlyEnabled)}
                      ${this.searchField("name")}
                      ${this.order(
          orderColumn = Some("tasks." + orderColumn),
          orderDirection = orderDirection,
          nameFix = true
        )}
                      LIMIT ${this.sqlLimit(limit)} OFFSET {offset}"""
      SQL(query)
        .on(
          Symbol("ss")     -> this.search(searchString),
          Symbol("id")     -> ToParameterValue.apply[Long](p = keyToStatement).apply(id),
          Symbol("offset") -> offset
        )
        .as(geometryParser.*)
    }
  }

  override def find(
      searchString: String,
      limit: Int = Config.DEFAULT_LIST_SIZE,
      offset: Int = 0,
      onlyEnabled: Boolean = false,
      orderColumn: String = "id",
      orderDirection: String = "ASC"
  )(implicit parentId: Long = -1, c: Option[Connection] = None): List[Challenge] =
    this.findByType(searchString, limit, offset, onlyEnabled, orderColumn, orderDirection)

  def findByType(
      searchString: String,
      limit: Int = Config.DEFAULT_LIST_SIZE,
      offset: Int = 0,
      onlyEnabled: Boolean = false,
      orderColumn: String = "id",
      orderDirection: String = "ASC",
      challengeType: Int = Actions.ITEM_TYPE_CHALLENGE
  )(implicit parentId: Long = -1, c: Option[Connection] = None): List[Challenge] = {
    this.withMRConnection { implicit c =>
      val query =
        s"""SELECT ${this.retrieveColumns} FROM challenges c
                      INNER JOIN projects p ON p.id = c.parent_id
                      WHERE c.deleted = false AND p.deleted = false
                      ${this.searchField("c.name")}
                      ${this.enabled(onlyEnabled, "p")} ${this.enabled(onlyEnabled, "c")}
                      ${this.parentFilter(parentId)}
                      ${this.order(Some(orderColumn), orderDirection, "c", true)}
                      LIMIT ${this.sqlLimit(limit)} OFFSET {offset}"""
      SQL(query).on(Symbol("ss") -> searchString, Symbol("offset") -> offset).as(this.parser.*)
    }
  }

  def listing(
      projectList: Option[List[Long]] = None,
      limit: Int = Config.DEFAULT_LIST_SIZE,
      offset: Int = 0,
      onlyEnabled: Boolean = false,
      challengeType: Int = Actions.ITEM_TYPE_CHALLENGE
  ): List[ChallengeListing] = {
    this.withMRConnection { implicit c =>
      var projectFilter = ""
      if (projectList != None) {
        implicit val conjunction = None
        projectFilter = s"""AND (${this.getLongListFilter(projectList, "p.id")} OR c.id IN
                 (SELECT challenge_id FROM virtual_project_challenges vp WHERE
                  ${getLongListFilter(projectList, "vp.project_id")}))"""
      }

      val query =
        s"""SELECT c.id, c.parent_id, c.name, c.enabled, array_remove(array_agg(vp.project_id), NULL) AS virtual_parent_ids, c.status, c.is_archived FROM challenges c
                      INNER JOIN projects p ON p.id = c.parent_id
                      LEFT OUTER JOIN virtual_project_challenges vp ON c.id = vp.challenge_id
                      WHERE c.deleted = false AND p.deleted = false
                      ${this.enabled(onlyEnabled, "p")} ${this.enabled(onlyEnabled, "c")}
                      ${projectFilter}
                      GROUP BY c.id
                      LIMIT ${this.sqlLimit(limit)} OFFSET {offset}"""

      SQL(query).on(Symbol("offset") -> offset).as(ChallengeListingRepository.parser.*)
    }
  }

  override def list(
      limit: Int = Config.DEFAULT_LIST_SIZE,
      offset: Int = 0,
      onlyEnabled: Boolean = false,
      searchString: String = "",
      orderColumn: String = "id",
      orderDirection: String = "ASC"
  )(implicit parentId: Long = -1, c: Option[Connection] = None): List[Challenge] = {
    implicit val ids = List.empty
    this.cacheManager.withIDListCaching { implicit uncachedIDs =>
      this.withMRConnection { implicit c =>
        val query =
          s"""SELECT $retrieveColumns FROM challenges c
                        INNER JOIN projects p ON p.id = c.parent_id
                        WHERE 1=1
                        ${this.searchField("c.name")}
                        ${this.enabled(onlyEnabled, "p")} ${this.enabled(onlyEnabled, "c")}
                        ${this.parentFilter(parentId)}
                        ${this.order(Some(orderColumn), orderDirection, "c", true)}
                        LIMIT ${this.sqlLimit(limit)} OFFSET {offset}"""
        SQL(query)
          .on(
            Symbol("ss")     -> this.search(searchString),
            Symbol("offset") -> ToParameterValue.apply[Int].apply(offset)
          )
          .as(this.parser.*)
      }
    }
  }

  /**
    * Gets the featured challenges
    *
    * @param limit       The number of challenges to retrieve
    * @param offset      For paging, ie. the page number starting at 0
    * @param enabledOnly if true will only return enabled challenges
    * @return list of challenges
    */
  def getFeaturedChallenges(limit: Int, offset: Int, enabledOnly: Boolean = true)(
      implicit c: Option[Connection] = None
  ): List[Challenge] = {
    this.withMRConnection { implicit c =>
      val query =
        s"""SELECT ${this.retrieveColumns} FROM challenges c
                      INNER JOIN projects p ON p.id = c.parent_id
                      WHERE c.featured = TRUE ${this.enabled(enabledOnly, "c")} ${this
          .enabled(enabledOnly, "p")}
                      AND c.deleted = false and p.deleted = false
                      AND (c.status = ${Challenge.STATUS_READY})
                      AND c.requires_local = false
                      LIMIT ${this.sqlLimit(limit)} OFFSET $offset"""
      val challenges = SQL(query).as(this.parser.*)
      // Insert the challenges into the cache
      this.cacheManager.cache.addObjectsAsync(challenges)
      challenges
    }
  }

  /**
    * Get the Hot challenges, these are challenges that have proved popular, weighted
    * towards recent activity.
    *
    * @param limit       the number of challenges to retrieve
    * @param offset      For paging, ie. the page number starting at 0
    * @param enabledOnly if true will only return enabled challenges
    * @return List of challenges
    */
  def getHotChallenges(limit: Int, offset: Int, enabledOnly: Boolean = true)(
      implicit c: Option[Connection] = None
  ): List[Challenge] = {
    this.withMRConnection { implicit c =>
      val query =
        s"""SELECT ${this.retrieveColumns} FROM challenges c
                      INNER JOIN projects p ON p.id = c.parent_id
                      WHERE c.deleted = false and p.deleted = false
                      ${this.enabled(enabledOnly, "c")} ${this.enabled(enabledOnly, "p")}
                      AND (c.status = ${Challenge.STATUS_READY})
                      AND c.requires_local = false
                      ORDER BY popularity DESC LIMIT ${this.sqlLimit(limit)} OFFSET $offset"""
      val challenges = SQL(query).as(this.parser.*)
      // Insert the challenges into the cache
      this.cacheManager.cache.addObjectsAsync(challenges)
      challenges
    }
  }

  /**
    * Gets the new challenges
    *
    * @param limit       The number of challenges to retrieve
    * @param offset      For paging ie. the page number starting at 0
    * @param enabledOnly if true will only return enabled challenges
    * @return list of challenges
    */
  def getNewChallenges(limit: Int, offset: Int, enabledOnly: Boolean = true)(
      implicit c: Option[Connection] = None
  ): List[Challenge] = {
    this.withMRConnection { implicit c =>
      val query =
        s"""SELECT ${this.retrieveColumns} FROM challenges c
                      INNER JOIN projects p ON c.parent_id = p.id
                      WHERE ${this.enabled(enabledOnly, "c")(None)} ${this
          .enabled(enabledOnly, "p")}
                      AND c.deleted = false and p.deleted = false
                      AND (c.status = ${Challenge.STATUS_READY})
                      AND c.requires_local = false
                      ${this.order(Some("created"), "DESC", "c", true)}
                      LIMIT ${this.sqlLimit(limit)} OFFSET $offset"""
      val challenges = SQL(query).as(this.parser.*)
      // Insert the challenges into the cache
      this.cacheManager.cache.addObjectsAsync(challenges)
      challenges
    }
  }

  /**
    * Gets the combined geometry of all the tasks that are associated with the challenge
    *
    * @param challengeId        The id for the challenge
    * @param statusFilter       To view the geojson for only challenges with a specific status
    * @param reviewStatusFilter To view the geojson for only challenges with a specific review status
    * @param priorityFilter     To view the geojson for only challenges with a specific priority
    * @param params             SearchParameters for filtering by taskPropertySearch
    * @param timezone           The timezone offset (ie. -07:00)
    * @param c                  The implicit connection for the function
    * @return
    */
  def getChallengeGeometry(
      challengeId: Long,
      statusFilter: Option[List[Int]] = None,
      reviewStatusFilter: Option[List[Int]] = None,
      priorityFilter: Option[List[Int]] = None,
      params: Option[SearchParameters] = None,
      timezone: String = Utils.UTC_TIMEZONE
  )(implicit c: Option[Connection] = None): String = {
    this.withMRConnection { implicit c =>
      val filters = new StringBuilder()

      // Verify timzone offset is valid (eg. -10:00 or +04:00 or 06:30:00)
      val tzOffset =
        timezone.matches("^[\\+\\-]*\\d\\d\\:\\d\\d(\\:\\d\\d)?$") match {
          case true => timezone
          case false =>
            if (timezone.isEmpty)
              Utils.UTC_TIMEZONE
            else
              throw new InvalidException("Timezone is not a valid time zone. [" + timezone + "]")
        }

      statusFilter match {
        case Some(s) => filters.append(s"AND t.status IN (${s.mkString(",")})")
        case None    => //
      }

      reviewStatusFilter match {
        case Some(s) =>
          var searchQuery =
            s"t.id in (SELECT subTR.task_id from task_review subTR where subTR.task_id=t.id AND subTR.review_status IN (${s
              .mkString(",")}))"
          if (s.contains(-1)) {
            // Return items that do not have a review status
            searchQuery = searchQuery + " OR t.id NOT in (SELECT subTR.task_id from task_review subTR where subTR.task_id=t.id)"
          }
          filters.append(s" AND ($searchQuery)")
        case None => //
      }

      priorityFilter match {
        case Some(p) => filters.append(s" AND t.priority IN (${p.mkString(",")})")
        case None    => //
      }

      params match {
        case Some(p) =>
          p.taskParams.taskPropertySearch match {
            case Some(tps) =>
              filters.append(s""" AND t.id IN (
                  SELECT id FROM tasks,
                                 jsonb_array_elements(geojson->'features') features
                  WHERE parent_id IN ($challengeId)
                  AND (${tps.toSQL}))
                 """)
            case None => // do nothing
          }

          p.taskParams.taskId match {
            case Some(tid) => filters.append(s" AND CAST(t.id AS TEXT) LIKE '${tid}%'")
            case _         => // do nothing
          }

          p.reviewer match {
            case Some(r) =>
              filters.append(s""" AND t.id IN (
                  SELECT subTR.task_id FROM task_review subTR
                  INNER JOIN users u2 ON u2.id = subTR.reviewed_by
                  WHERE subTR.task_id=t.id AND
                  LOWER(u2.name) LIKE LOWER('%${r}%')
                )""")
            case _ => // do nothing
          }

          p.owner match {
            case Some(o) =>
              filters.append(s""" AND t.id IN (
                  SELECT subTR.task_id FROM task_review subTR
                  INNER JOIN users u3 ON u3.id = subTR.review_requested_by
                  WHERE subTR.task_id=t.id AND
                  LOWER(u3.name) LIKE LOWER('%${o}%')
                )""")
            case _ => // do nothing
          }
        case None => // do nothing
      }

      val query =
        SQL"""SELECT row_to_json(fc)::text as geometries
            FROM ( SELECT 'FeatureCollection' As type, array_to_json(array_agg(f)) As features
                   FROM ( SELECT 'Feature' As type,
                                  t.geojson_geom::jsonb As geometry,
                                  t.properties::jsonb ||
                                      hstore_to_jsonb(
                                        hstore('mr_taskId', t.tid::text) ||
                                        hstore('mr_challengeId', t.parent_id::text) ||
                                        hstore('mr_taskName', t.name::text) ||
                                        hstore('mr_taskStatus',
                                          (CASE
                                            WHEN t.tstatus = #${Task.STATUS_CREATED} THEN ${Task.STATUS_CREATED_NAME}
                                            WHEN t.tstatus = #${Task.STATUS_FIXED} THEN ${Task.STATUS_FIXED_NAME}
                                            WHEN t.tstatus = #${Task.STATUS_SKIPPED} THEN ${Task.STATUS_SKIPPED_NAME}
                                            WHEN t.tstatus = #${Task.STATUS_DELETED} THEN ${Task.STATUS_DELETED_NAME}
                                            WHEN t.tstatus = #${Task.STATUS_ALREADY_FIXED} THEN ${Task.STATUS_ALREADY_FIXED_NAME}
                                            WHEN t.tstatus = #${Task.STATUS_FALSE_POSITIVE} THEN ${Task.STATUS_FALSE_POSITIVE_NAME}
                                            WHEN t.tstatus = #${Task.STATUS_TOO_HARD} THEN ${Task.STATUS_TOO_HARD_NAME}
                                            WHEN t.tstatus = #${Task.STATUS_ANSWERED} THEN ${Task.STATUS_ANSWERED_NAME}
                                            WHEN t.tstatus = #${Task.STATUS_VALIDATED} THEN ${Task.STATUS_VALIDATED_NAME}
                                            WHEN t.tstatus = #${Task.STATUS_DISABLED} THEN ${Task.STATUS_DISABLED_NAME}
                                           END)) ||
                                        hstore('mr_taskPriority',
                                          (CASE
                                            WHEN t.priority = #${Challenge.PRIORITY_HIGH} THEN ${Challenge.PRIORITY_HIGH_NAME}
                                            WHEN t.priority = #${Challenge.PRIORITY_MEDIUM} THEN ${Challenge.PRIORITY_MEDIUM_NAME}
                                            WHEN t.priority = #${Challenge.PRIORITY_LOW} THEN ${Challenge.PRIORITY_LOW_NAME}
                                           END)) ||
                                        hstore('mr_mappedOn',
                                                TO_CHAR(t.mapped_on::TIMESTAMPTZ at time zone
                                                  (select name from pg_timezone_names where utc_offset='#${tzOffset}' limit 1),
                                                  'YYYY-MM-DD"T"HH24:MI:SS#${tzOffset}'))
                                        ||
                                        hstore('mr_mapper',
                                          (CASE WHEN t.review_requested_by = NULL
                                           THEN (select name from users where osm_id=t.osm_user_id)::text
                                           ELSE (select name from users where id=t.review_requested_by)::text
                                           END)) ||
                                        hstore('mr_reviewStatus',
                                          (CASE
                                            WHEN t.review_status = #${Task.REVIEW_STATUS_REQUESTED} THEN ${Task.REVIEW_STATUS_REQUESTED_NAME}
                                            WHEN t.review_status = #${Task.REVIEW_STATUS_APPROVED} THEN ${Task.REVIEW_STATUS_APPROVED_NAME}
                                            WHEN t.review_status = #${Task.REVIEW_STATUS_APPROVED_WITH_REVISIONS} THEN ${Task.REVIEW_STATUS_APPROVED_NAME}
                                            WHEN t.review_status = #${Task.REVIEW_STATUS_APPROVED_WITH_FIXES_AFTER_REVISIONS} THEN ${Task.REVIEW_STATUS_APPROVED_NAME}
                                            WHEN t.review_status = #${Task.REVIEW_STATUS_REJECTED} THEN ${Task.REVIEW_STATUS_REJECTED_NAME}
                                            WHEN t.review_status = #${Task.REVIEW_STATUS_ASSISTED} THEN ${Task.REVIEW_STATUS_ASSISTED_NAME}
                                            WHEN t.review_status = #${Task.REVIEW_STATUS_DISPUTED} THEN ${Task.REVIEW_STATUS_DISPUTED_NAME}
                                            WHEN t.review_status = #${Task.REVIEW_STATUS_UNNECESSARY} THEN ${Task.REVIEW_STATUS_UNNECESSARY_NAME}
                                           END)) ||
                                        hstore('mr_reviewer', (select name from users where id=t.reviewed_by)::text) ||
                                        hstore('mr_reviewedAt',
                                          TO_CHAR(t.reviewed_at::TIMESTAMPTZ at time zone
                                            (select name from pg_timezone_names where utc_offset='#${tzOffset}' limit 1),
                                            'YYYY-MM-DD"T"HH24:MI:SS#${tzOffset}'))
                                        ||
                                        hstore('mr_reviewTimeSeconds', FLOOR(EXTRACT(EPOCH FROM (t.reviewed_at - t.review_started_at)))::text) ||
                                        hstore('mr_tags', (SELECT STRING_AGG(tg.name, ',') AS tags
                                                            FROM tags_on_tasks tot, tags tg
                                                            WHERE tot.task_id=t.tid AND tg.id = tot.tag_id)) ||
                                        hstore('mr_responses', t.completion_responses::text)
                                      ) AS properties
                          FROM (
                            SELECT *,
                                  elements->'geometry' AS geojson_geom,
                                  elements->'properties' AS properties
                            FROM (
                              SELECT *, t.id AS tid, t.status AS tstatus,
                                  jsonb_array_elements(geojson->'features') AS elements
                             	FROM tasks t
                              LEFT OUTER JOIN status_actions sa ON
                                (sa.task_id = t.id AND extract(epoch from age(sa.created, t.mapped_on)) < 0.1)
                              LEFT OUTER JOIN task_review tr ON t.id = tr.task_id
                              WHERE parent_id = $challengeId #${filters.toString}
                            ) AS subT ) as t
                    ) As f
            )  As fc"""
      val challengeGeometry = query.as(str("geometries").single)
      if (StringUtils.isEmpty(challengeGeometry)) {
        this.updateGeometry(challengeId)
      }
      challengeGeometry
    }
  }

  def updateGeometry(challengeId: Long)(implicit c: Option[Connection] = None): Future[Boolean] = {
    Future {
      logger.info(s"Updating geometry for challenge $challengeId")
      this.db.withTransaction { implicit c =>
        val query = "SELECT update_challenge_geometry({id})"
        SQL(query).on(Symbol("id") -> challengeId).execute()
      }
    }
  }

  /**
    * This retrieves all the tasks geojson as line by line. When using this format it is a lot easier to
    * rebuild a challenge correctly.
    *
    * @param challengeId The id of the challenge
    * @param c           The implicit connection
    * @return A map of Task ID to geojson string
    */
  def getLineByLineChallengeGeometry(
      challengeId: Long
  )(implicit c: Option[Connection] = None): Map[Long, String] = {
    this.withMRConnection { implicit c =>
      SQL"""SELECT id, geojson FROM tasks t
              WHERE t.parent_id = $challengeId
        """.as((long("tasks.id") ~ str("geojson")).*).map(x => x._1 -> x._2).toMap
    }
  }

  /**
    * Retrieves the json that contains the central points for all the tasks
    *
    * @param challengeId  The id of the challenge
    * @param params Filter the displayed task cluster points by search parameters (status, priority)
    * @return A list of clustered point objects
    */
  def getClusteredPoints(
      user: User,
      challengeId: Long,
      params: SearchParameters,
      limit: Int = 2500,
      excludeLocked: Boolean = false
  )(implicit c: Option[Connection] = None): List[ClusteredPoint] = {
    this.withMRConnection { implicit c =>
      val joinClause = new StringBuilder(
        """
          INNER JOIN challenges c ON c.id = t.parent_id
          INNER JOIN projects p ON p.id = c.parent_id
          LEFT OUTER JOIN task_review tr ON tr.task_id = t.id
        """
      )
      val whereClause = new StringBuilder(
        s"""
          t.parent_id = $challengeId
          AND p.deleted = false AND c.deleted = false
          AND ST_AsGeoJSON(t.location) IS NOT NULL
        """
      )

      params.taskParams.taskStatus match {
        case Some(s) => this.appendInWhereClause(whereClause, s"t.status IN (${s.mkString(",")})")
        case None    => ""
      }
      params.location match {
        case Some(sl) =>
          this.appendInWhereClause(
            whereClause,
            s"t.location @ ST_MakeEnvelope (${sl.left}, ${sl.bottom}, ${sl.right}, ${sl.top}, 4326)"
          )
        case None => // do nothing
      }

      if (excludeLocked) {
        joinClause ++= " LEFT JOIN locked l ON l.item_id = t.id "
        this.appendInWhereClause(whereClause, s"(l.id IS NULL OR l.user_id = ${user.id})")
      }

      val query = SQL"""SELECT t.id, t.name, t.instruction, t.status, t.mapped_on,
                   t.parent_id, t.bundle_id, t.is_bundle_primary,
                   tr.review_status, tr.review_requested_by,
                   tr.reviewed_by, tr.reviewed_at, tr.review_started_at,
                   tr.additional_reviewers, tr.meta_review_status, tr.meta_reviewed_by,
                   tr.meta_reviewed_at,
                   t.cooperative_work_json::TEXT as cooperative_work, c.name,
                   ST_AsGeoJSON(t.location) AS location, t.priority
            FROM tasks t
            #${joinClause.toString()}
            WHERE #${whereClause.toString()}
            LIMIT #${sqlLimit(limit)}"""

      val clusteredList = query.as(this.pointParser.*)

      if (clusteredList.isEmpty) {
        this.updateGeometry(challengeId)
      }
      clusteredList
    }
  }

  /**
    * Archive or unarchive a list of challenges
    *
    * @param challengeIds  The list of challengeIds
    * @param archive  boolean determining if challenges should be archived(true) or unarchived(false)
    * @return
    */
  def bulkArchive(challengeIds: List[Long], archive: Boolean)(
      implicit c: Option[Connection] = None
  ): List[Long] = {
    this.withMRConnection { implicit c =>
      try {
        val ids = challengeIds.mkString(",")
        val query =
          s"""UPDATE challenges
             |	SET is_archived = ${archive}
             |	WHERE id IN (${ids});""".stripMargin
        SQL(query).executeUpdate()

        challengeIds
      } catch {
        case e: Exception =>
          logger.error(e.getMessage, e)
          throw e
      }

    }
  }

  /**
    * The summary for a challenge is the status with the number of tasks associated with each status
    * underneath the given challenge
    *
    * @param id The id for the challenge
    * @return Map of status codes mapped to task counts
    */
  def getSummary(id: Long)(implicit c: Option[Connection] = None): Map[Int, Int] = {
    this.withMRConnection { implicit c =>
      val summaryParser = int("count") ~ get[Option[Int]]("tasks.status") map {
        case count ~ status => status.getOrElse(0) -> count
      }
      SQL"""SELECT COUNT(*) as count, status FROM tasks WHERE parent_id = $id GROUP BY status"""
        .as(summaryParser.*)
        .toMap
    }
  }

  /**
    * Removes challenge tasks in CREATED or SKIPPED statuses, intended to be used
    * in preparation for rebuilding with fresh task data
    *
    * @param user The user executing the request
    * @param id   The id of the challenge
    * @param c    The connection for the request
    */
  def removeIncompleteTasks(user: User)(implicit id: Long, c: Option[Connection] = None): Unit = {
    this.permission.hasWriteAccess(ChallengeType(), user)
    this.withMRConnection { implicit c =>
      SQL"""DELETE from tasks WHERE parent_id = ${id} and status IN (${Task.STATUS_CREATED}, ${Task.STATUS_SKIPPED})"""
        .execute()

      this.taskDAL.clearCaches
    }
  }

  /**
    * Get count of tasks in a challenge
    *
    * @param challengeId
    */
  def getTaskCount(challengeId: Long)(implicit c: Option[Connection] = None): Int = {
    this.withMRConnection { implicit c =>
      val countParser = int("count") map {
        case count => count
      }
      SQL"""SELECT COUNT(parent_id) as count FROM tasks WHERE parent_id = ${challengeId}"""
        .as(countParser.*)
        .head
    }
  }

  /**
    * Moves a challenge from one project to another. You are required to have admin access on both
    * the current project and the project you are moving the challenge to
    *
    * @param newParent   The id of the new parent project
    * @param challengeId The id of the challenge that you are moving
    * @param c           an implicit connection
    */
  def moveChallenge(newParent: Long, challengeId: Long, user: User)(
      implicit c: Option[Connection] = None
  ): Option[Challenge] = {
    this.permission.hasAdminAccess(ProjectType(), user)(newParent)
    this.serviceManager.project.retrieve(newParent) match {
      case Some(p) =>
        if (p.isVirtual.getOrElse(false)) {
          throw new InvalidException(s"Cannot move challenge into a virtual project ($newParent).")
        }
      case None =>
        // This shouldn't happen since we already did a permission check.
        throw new NotFoundException(s"No project with id $newParent found.")
    }

    implicit val id = challengeId
    this.retrieveById match {
      case Some(c) => this.permission.hasObjectAdminAccess(c, user)
      case None    => throw new NotFoundException(s"No challenge with id $challengeId found.")
    }
    this.cacheManager.withUpdatingCache(Long => retrieveById) { implicit item =>
      this.withMRTransaction { implicit c =>
        val movedChallenge =
          SQL"UPDATE challenges SET parent_id = $newParent WHERE id = $challengeId RETURNING #${this.retrieveColumns}"
            .as(this.parser.*)
            .headOption

        // Also update status_actions so we don't lose our history
        SQL"UPDATE status_actions SET project_id = $newParent WHERE challenge_id = $challengeId"
          .execute()

        movedChallenge
      }
    }
  }

  /**
    * Moves a list of challenges from one project to another. You are required to have admin access on both
    * the current project and the project you are moving the challenges to
    *
    * @param newParent   The id of the new parent project
    * @param challengeIds The id of the challenge that you are moving
    * @param c           an implicit connection
    */
  def moveChallenges(newParent: Long, challengeIds: List[Long], user: User)(
      implicit c: Option[Connection] = None
  ): List[Long] = {
    this.permission.hasAdminAccess(ProjectType(), user)(newParent)
    this.serviceManager.project.retrieve(newParent) match {
      case Some(p) =>
        if (p.isVirtual.getOrElse(false)) {
          throw new InvalidException(s"Cannot move challenge into a virtual project ($newParent).")
        }
      case None =>
        // This shouldn't happen since we already did a permission check.
        throw new NotFoundException(s"No project with id $newParent found.")
    }

    val movedChallengeIds = ListBuffer[Long]()
    for (challengeId <- challengeIds) {
      implicit val id = challengeId
      this.retrieveById match {
        case Some(c) => this.permission.hasObjectAdminAccess(c, user)
        case None    => throw new NotFoundException(s"No challenge with id $id found.")
      }
      this.cacheManager.withUpdatingCache(Long => retrieveById) { implicit item =>
        this.withMRTransaction { implicit c =>
          val movedChallenge =
            SQL"UPDATE challenges SET parent_id = $newParent WHERE id = $id RETURNING #${this.retrieveColumns}"
              .as(this.parser.*)
              .headOption

          // Also update status_actions so we don't lose our history
          SQL"UPDATE status_actions SET project_id = $newParent WHERE challenge_id = $id"
            .execute()

          movedChallengeIds += id

          movedChallenge
        }
      }
    }

    movedChallengeIds.toList
  }

  /**
    * Update the popularity score of the given challenge following completion of a task.
    * Challenge popularity p is calculated with the simple formula p = (p + t) / 2 where
    * t is the timestamp of the task completion. This favors recent activity.
    *
    * @param id                  The id of the challenge
    * @param completionTimestamp the unix timestamp of the task completion
    */
  def updatePopularity(
      completionTimestamp: Long
  )(implicit id: Long, c: Option[Connection] = None): Option[Challenge] = {
    this.cacheManager.withUpdatingCache(Long => retrieveById) { implicit item =>
      this.withMRTransaction { implicit c =>
        SQL"UPDATE challenges SET popularity=((popularity + $completionTimestamp) / 2) WHERE id = $id RETURNING #${this.retrieveColumns}"
          .as(this.parser.*)
          .headOption
      }
    }
  }

  /**
    * Updates the challenge to a STATUS_FINISHED if the challenge has tasks and
    * there are no tasks remaining in CREATED or SKIPPED status
    *
    * @param id The id of the challenge
    * @param finishOnEmpty Boolean to indicate whether status of an empty challenge should
    *                      still be marked finished. Defaults to false.
    */
  def updateFinishedStatus(
      finishOnEmpty: Boolean = false,
      user: User
  )(implicit id: Long, c: Option[Connection] = None): Unit = {
    this.withMRConnection { implicit c =>
      this.retrieveById(id) match {
        case Some(challenge) =>
          if (challenge.status.getOrElse(Challenge.STATUS_NA) != Challenge.STATUS_FINISHED) {
            this.cacheManager.withUpdatingCache(Long => retrieveById) { implicit item =>
              val emptyChallengeCheck =
                finishOnEmpty match {
                  case true => ""
                  case false =>
                    s"0 < (SELECT COUNT(*) FROM tasks where tasks.parent_id = ${id}) AND"
                }

              // If the challenge has no tasks in the created status it need to be marked finished.
              val updateStatusQuery =
                s"""UPDATE challenges SET status = ${Challenge.STATUS_FINISHED}
                            WHERE id = ${id} AND
                            ${emptyChallengeCheck}
                            0 = (SELECT COUNT(*) AS total FROM tasks
                                      WHERE tasks.parent_id = ${id}
                                      AND status IN (${Task.STATUS_CREATED}, ${Task.STATUS_SKIPPED}))
                            RETURNING ${this.retrieveColumns}"""
              SQL(updateStatusQuery).as(this.parser.*).headOption match {
                case Some(updatedChallenge) =>
                  if (updatedChallenge.status.getOrElse(Challenge.STATUS_NA) == Challenge.STATUS_FINISHED) {
                    val challengeComments =
                      this.serviceManager.comment.findChallengeComments(updatedChallenge.id)
                    var challengeAlreadyCompleted = false

                    challengeComments.foreach(c => {
                      if (c.comment == "Challenge Completed") {
                        challengeAlreadyCompleted = true
                      }
                    })

                    if (!challengeAlreadyCompleted) {
                      Future {
                        this.serviceManager.achievement
                          .awardChallengeCompletionAchievements(updatedChallenge)
                        this.serviceManager.notification
                          .createChallengeCompletionNotification(updatedChallenge)
                      }

                      this.serviceManager.comment.createChallengeComment(
                        user,
                        updatedChallenge.id,
                        "Challenge Completed",
                        false
                      )
                    }
                  }
                  Some(updatedChallenge)
                case None => None
              }
            }

            Option(challenge)
          }
        case None =>
          throw new NotFoundException(s"No challenge found with id $id")
      }
    }
  }

  /**
    * A basic retrieval of the object based on the id. With caching, so if it finds
    * the object in the cache it will return that object without checking the database, otherwise
    * will hit the database directly.
    *
    * @param id The id of the object to be retrieved
    * @return The object, None if not found
    */
  override def retrieveById(implicit id: Long, c: Option[Connection] = None): Option[Challenge] = {
    this._retrieveById()
  }

  def _retrieveById(
      caching: Boolean = true
  )(implicit id: Long, c: Option[Connection] = None): Option[Challenge] = {
    this.cacheManager.withCaching { () =>
      this.withMRConnection { implicit c =>
        val query =
          s"""
            |SELECT c.$retrieveColumns, array_remove(array_agg(vp.project_id), NULL) AS virtual_parent_ids,
            |array_remove(array_agg(cp.preset), NULL) AS presets
            |FROM challenges c
            |LEFT OUTER JOIN virtual_project_challenges vp ON c.id = vp.challenge_id
            |LEFT OUTER JOIN challenge_presets cp ON c.id = cp.challenge_id
            |WHERE c.id = {id}
            |GROUP BY c.id
           """.stripMargin

        SQL(query).on(Symbol("id") -> id).as(this.withVirtualParentParser.singleOpt)
      }
    }(id, caching)
  }

  /**
    * Updates the challenge to a STATUS_READY if there are incomplete tasks left.
    *
    * @param id The id of the challenge
    */
  def updateReadyStatus()(implicit id: Long, c: Option[Connection] = None): Option[Challenge] = {
    this.withMRConnection { implicit c =>
      this.retrieveById(id) match {
        case Some(challenge) =>
          if (challenge.status.getOrElse(Challenge.STATUS_NA) == Challenge.STATUS_FINISHED) {
            this.cacheManager.withUpdatingCache(Long => retrieveById) { implicit item =>
              // If the challenge was finished and any tasks were reset back to created
              // we need to set the challenge status back to ready
              val updateStatusQuery2 =
                s"""UPDATE challenges SET status = ${Challenge.STATUS_READY}
                            WHERE id = $id AND
                            0 < (SELECT COUNT(*) AS total FROM tasks
                                      WHERE tasks.parent_id = $id
                                      AND status IN (${Task.STATUS_CREATED}, ${Task.STATUS_SKIPPED}))
                            RETURNING ${this.retrieveColumns}"""

              SQL(updateStatusQuery2).as(this.parser.*).headOption
            }
          }
          Option(challenge)
        case None =>
          throw new NotFoundException(s"No challenge found with id $id")
      }
    }
  }

  /**
    * Updates the last_task_refresh and data_origin_date columns of a challenge
    * to indicate its task data was just refreshed. If overwrite is false, only
    * last_task_refresh will be updated.
    *
    * @param overwrite Set to true to always overwrite data_origin_date
    * @param id        The id of the challenge
    * @param c         an implicit connection
    */
  def markTasksRefreshed(
      overwrite: Boolean = false,
      dataOriginDate: Option[DateTime] = None
  )(implicit id: Long, c: Option[Connection] = None): Option[Challenge] = {
    this.cacheManager.withUpdatingCache(Long => retrieveById) { implicit item =>
      if (overwrite) {
        val originDate = dataOriginDate match {
          case Some(d) => s"""'${d.toString()}'"""
          case _       => "NOW()"
        }

        this.withMRTransaction { implicit c =>
          SQL"""UPDATE challenges SET last_task_refresh = NOW(), data_origin_date = #$originDate
                WHERE id = $id RETURNING #${this.retrieveColumns}""".as(this.parser.*).headOption
        }
      } else {
        this.withMRTransaction { implicit c =>
          SQL"""UPDATE challenges SET last_task_refresh = NOW()
                WHERE id = $id RETURNING #${this.retrieveColumns}""".as(this.parser.*).headOption
        }
      }
    }
  }

  /**
    * Resets all the Task instructions for the children of the challenge
    *
    * @param challengeId The id of the parent challenge
    * @param user        A super user or the owner/admin of the challenge
    * @param c
    */
  def resetTaskInstructions(user: User, challengeId: Long)(
      implicit c: Option[Connection] = None
  ): Unit = {
    this.withMRConnection { implicit c =>
      this.retrieveById(challengeId) match {
        case Some(challenge) =>
          if (challenge.general.instruction.isEmpty) {
            throw new InvalidException(
              "Cannot reset Task instructions if there is no Challenge instruction available."
            )
          }
          this.permission.hasObjectAdminAccess(challenge, user)
          SQL("UPDATE tasks SET instruction = '' WHERE parent_id = {id}")
            .on(Symbol("id") -> challengeId)
            .executeUpdate()
        case None =>
          throw new NotFoundException(s"No challenge found with id $challengeId")
      }
    }
  }

  /**
    * Deletes all the tasks in a challenge
    *
    * @param user         The user making the deletion request
    * @param challengeId  The id for the parent challenge
    * @param statusFilter Filter the deletion by Task status, if empty will ignore status and just delete all Task children
    * @param c
    */
  def deleteTasks(user: User, challengeId: Long, statusFilter: List[Int] = List.empty)(
      implicit c: Option[Connection] = None
  ): Unit = {
    this.withMRConnection { implicit c =>
      val filter = if (statusFilter.isEmpty) {
        ""
      } else {
        s"AND status IN (${statusFilter.mkString(",")})"
      }

      // Deleting tasks can be time consuming (~1 second per 15-20 tasks), so work in batches
      val query =
        s"""DELETE FROM tasks WHERE id in (SELECT id from tasks WHERE parent_id = {challengeId} $filter LIMIT 50)"""
      var deleteCount = 0
      do {
        deleteCount = SQL(query).on(Symbol("challengeId") -> challengeId).executeUpdate()
      } while (deleteCount > 0)
    }
  }

  /**
    * The extended find function will return a list of challenges based on the search critieria provided in the
    * query string. It will apply ONLY the following parameters:
    * - projectId (pid) = If searching only within a particular project, and the MapRoulette ID for that project
    * - projectSearch (ps) = A text search based on the name of the project, will be ignored if projectId has been set
    * - projectEnabled (pe) = Whether to only include projects that are enabled, will be ignored if projectId has been set, by default will be true
    * - challengeEnabled (ce) = Whether to only include challenges that are enabled, by default will be true
    * - challengeSearch (cs) = A text search based on the name of the challenge
    * - challengeTags (ct) = A comma separated list of tags to check if the challenge contains any of the
    * provided tags, can use challengeTagConjunction (ctt) to switch from inclusive to exclusive
    * - challengeTagConjunction (ctc) = Whether the challenge tag list is inclusive or exclusive. True
    * means exclusive, false inclusive. By default is inclusive
    * - location (tbb) = Provide a bounding box to limit the search window by
    * - bounding (bb) = Provide a bounding box that will search for any challenge bounding box intersections
    * - owner (o) = Optionally can search by all challenges created by a specific owner
    *
    * @param searchParameters The object that contains all the search parameters that were retrieved from the query string
    * @param limit            limit for the number of returned results
    * @param offset           The paging offset
    * @param sort             An optional column to sort by.
    * @param order            Direction of ordering ASC or DESC.
    * @param c                An optional connection, if included will use that connection otherwise will grab one from the pool
    * @return A list of challenges, empty list if not challenges found matching the given criteria
    */
  def extendedFind(
      searchParameters: SearchParameters,
      limit: Int = Config.DEFAULT_LIST_SIZE,
      offset: Int = 0,
      sort: String = "",
      order: String = ""
  )(implicit c: Option[Connection] = None): List[Challenge] = {
    this.withMRConnection { implicit c =>
      val parameters = new ListBuffer[NamedParameter]()
      // never include deleted items in the search
      val whereClause   = new StringBuilder("c.deleted = false AND p.deleted = false")
      val joinClause    = new StringBuilder()
      var orderByClause = ""

      parameters ++= addSearchToQuery(searchParameters, whereClause)

      parameters ++= addChallengeTagMatchingToQuery(searchParameters, whereClause, joinClause)

      searchParameters.owner match {
        case Some(o) if o.nonEmpty =>
          joinClause ++= "INNER JOIN users u ON u.id = c.owner_id"
          this.appendInWhereClause(whereClause, s"u.name = {owner}")
          parameters += (Symbol("owner") -> o)
        case _ => // ignore
      }

      searchParameters.location match {
        case Some(l) =>
          this.appendInWhereClause(
            whereClause,
            s"(c.location && ST_MakeEnvelope(${l.left}, ${l.bottom}, ${l.right}, ${l.top}, 4326))"
          )
        case None =>
      }

      searchParameters.bounding match {
        case Some(b) =>
          this.appendInWhereClause(
            whereClause,
            s"ST_Intersects(c.bounding, ST_MakeEnvelope(${b.left}, ${b.bottom}, ${b.right}, ${b.top}, 4326))"
          )
        case None =>
      }

      searchParameters.projectEnabled match {
        case Some(true) => this.appendInWhereClause(whereClause, this.enabled(true, "p")(None))
        case _          =>
      }

      searchParameters.challengeParams.challengeEnabled match {
        case Some(true) => this.appendInWhereClause(whereClause, this.enabled(true, "c")(None))
        case _          =>
      }

      searchParameters.challengeParams.challengeDifficulty match {
        case Some(v) if v > 0 && v < 4 =>
          this.appendInWhereClause(whereClause, s"c.difficulty = ${v}")
        case _ =>
      }

      searchParameters.challengeParams.challengeStatus match {
        case Some(sl) if sl.nonEmpty =>
          val statusClause = new StringBuilder(s"(c.status IN (${sl.mkString(",")})")
          if (sl.contains(-1)) {
            statusClause ++= " OR c.status IS NULL"
          }
          statusClause ++= ")"
          this.appendInWhereClause(whereClause, statusClause.toString())
        case Some(sl) if sl.isEmpty => //ignore this scenario
        case _                      =>
      }

      if (searchParameters.challengeParams.archived == false) {
        this.appendInWhereClause(whereClause, s"c.is_archived = false")
      }

      searchParameters.challengeParams.requiresLocal match {
        case Some(SearchParameters.CHALLENGE_REQUIRES_LOCAL_EXCLUDE) =>
          this.appendInWhereClause(whereClause, s"c.requires_local = false")
        case Some(SearchParameters.CHALLENGE_REQUIRES_LOCAL_ONLY) =>
          this.appendInWhereClause(whereClause, s"c.requires_local = true")
        case _ =>
      }

      sort match {
        case s if s.nonEmpty =>
          orderByClause = this.order(Some(s), order, "c", false, s == "name")
        case _ => // ignore
      }

      val query =
        s"""
           |SELECT c.${this.retrieveColumns}, array_remove(array_agg(vp.project_id), NULL) AS virtual_parent_ids,
           |array_remove(array_agg(cp.preset), NULL) AS presets
           |FROM challenges c
           |INNER JOIN projects p ON p.id = c.parent_id
           |LEFT OUTER JOIN virtual_project_challenges vp ON c.id = vp.challenge_id
           |LEFT OUTER JOIN challenge_presets cp ON c.id = cp.challenge_id
           |$joinClause
           |${s"WHERE $whereClause"}
           |GROUP BY c.id
           |${orderByClause}
           |LIMIT ${this.sqlLimit(limit)} OFFSET $offset
         """.stripMargin

      sqlWithParameters(query, parameters).as(this.withVirtualParentParser.*)
    }
  }
}

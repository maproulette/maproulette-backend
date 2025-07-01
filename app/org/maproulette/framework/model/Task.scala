/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.apache.commons.lang3.StringUtils
import org.joda.time.DateTime
import org.maproulette.data.{ItemType, TaskType}
import org.maproulette.framework.model.{Challenge, Identifiable, MapillaryImage}
import org.maproulette.framework.psql.CommonField
import org.maproulette.utils.Utils
import play.api.data.format.Formats
import play.api.libs.json._
import play.api.libs.json.JodaWrites._
import play.api.libs.json.JodaReads._

import org.maproulette.models.BaseObject

case class TaskReviewFields(
    reviewStatus: Option[Int] = None,
    reviewRequestedBy: Option[Long] = None,
    reviewedBy: Option[Long] = None,
    reviewedAt: Option[DateTime] = None,
    metaReviewedBy: Option[Long] = None,
    metaReviewStatus: Option[Int] = None,
    metaReviewedAt: Option[DateTime] = None,
    reviewStartedAt: Option[DateTime] = None,
    reviewClaimedBy: Option[Long] = None,
    reviewClaimedAt: Option[DateTime] = None,
    additionalReviewers: Option[List[Long]] = None
) extends DefaultWrites

/**
  * The primary object in MapRoulette is the task, this is the object that defines the actual problem
  * in the OSM data that needs to be fixed. It is a child of a Challenge and has a special one to
  * many relationship with tags. It contains the following parameters:
  *
  * id - A database assigned id for the Task
  * name - The name of the task
  * identifier - TODO: remove
  * parent - The id of the challenge of the task
  * instruction - A detailed instruction on how to fix this particular task
  * location - The direct location of the task
  * geometries - The list of geometries associated with the task
  * status - Status of the Task "Created, Fixed, False_Positive, Skipped, Deleted"
  *
  * TODO: Because the geometries is contained in a separate table, if requesting a large number of
  * tasks all at once it could cause performance issues.
  *
  * @author cuthbertm
  */
case class Task(
    override val id: Long,
    override val name: String,
    override val created: DateTime,
    override val modified: DateTime,
    parent: Long,
    instruction: Option[String] = None,
    location: Option[String] = None,
    geometries: String,
    cooperativeWork: Option[String] = None,
    status: Option[Int] = None,
    mappedOn: Option[DateTime] = None,
    completedTimeSpent: Option[Long] = None,
    completedBy: Option[Long] = None,
    review: TaskReviewFields = TaskReviewFields(),
    priority: Int = Challenge.PRIORITY_HIGH,
    changesetId: Option[Long] = None,
    completionResponses: Option[String] = None,
    bundleId: Option[Long] = None,
    isBundlePrimary: Option[Boolean] = None,
    mapillaryImages: Option[List[MapillaryImage]] = None,
    errorTags: String = ""
) extends BaseObject[Long]
    with DefaultReads
    with LowPriorityDefaultReads
    with Identifiable {
  override val itemType: ItemType = TaskType()

  /**
    * Gets the task priority
    *
    * @param parent The parent Challenge
    * @return Priority HIGH = 0, MEDIUM = 1, LOW = 2
    */
  def getTaskPriority(parent: Challenge, updatedTask: Option[Task] = None): Int = {
    var element = this;
    if (updatedTask.nonEmpty) {
      element = updatedTask.get
    }

    // Check using bounds first, which don't require geometry properties
    if (parent.isWithinBounds(parent.priority.highPriorityBounds, element)) {
      return Challenge.PRIORITY_HIGH
    } else if (parent.isWithinBounds(parent.priority.mediumPriorityBounds, element)) {
      return Challenge.PRIORITY_MEDIUM
    } else if (parent.isWithinBounds(parent.priority.lowPriorityBounds, element)) {
      return Challenge.PRIORITY_LOW
    }

    // Then check using rules
    val matchingList = getGeometryProperties().flatMap { props =>
      if (parent.isHighPriority(props, element)) {
        Some(Challenge.PRIORITY_HIGH)
      } else if (parent.isMediumPriority(props, element)) {
        Some(Challenge.PRIORITY_MEDIUM)
      } else if (parent.isLowRulePriority(props, element)) {
        Some(Challenge.PRIORITY_LOW)
      } else {
        None
      }
    }
    if (matchingList.isEmpty) {
      parent.priority.defaultPriority
    } else if (matchingList.contains(Challenge.PRIORITY_HIGH)) {
      Challenge.PRIORITY_HIGH
    } else if (matchingList.contains(Challenge.PRIORITY_MEDIUM)) {
      Challenge.PRIORITY_MEDIUM
    } else {
      Challenge.PRIORITY_LOW
    }
  }

  def getGeometryProperties(): List[Map[String, String]] = {
    if (StringUtils.isNotEmpty(this.geometries)) {
      val geojson = Json.parse(this.geometries)
      (geojson \ "features")
        .as[List[JsValue]]
        .map(json => Utils.getProperties(json, "properties").as[Map[String, String]])
    } else {
      List.empty
    }
  }
}

object Task extends CommonField {
  // TASK FIELDS
  val TABLE                = "tasks"
  val FIELD_LOCATION       = "location"
  val FIELD_STATUS         = "status"
  val FIELD_PRIORITY       = "priority"
  val FIELD_BUNDLE_ID      = "bundle_id"
  val FIELD_BUNDLE_PRIMARY = "is_bundle_primary"
  val FIELD_MAPPED_ON      = "mapped_on"

  implicit object TaskFormat extends Format[Task] {
    override def writes(o: Task): JsValue = {
      implicit val mapillaryWrites: Writes[MapillaryImage] = Json.writes[MapillaryImage]
      implicit val reviewWrites: Writes[TaskReviewFields]  = Json.writes[TaskReviewFields]
      implicit val taskWrites: Writes[Task]                = Json.writes[Task]
      var original                                         = Json.toJson(o)(Json.writes[Task])
      var updatedLocation = o.location match {
        case Some(l) => Utils.insertIntoJson(original, "location", Json.parse(l), true)
        case None    => original
      }

      original = Utils.insertIntoJson(updatedLocation, "geometries", Json.parse(o.geometries), true)
      var updated = o.cooperativeWork match {
        case Some(cw) => Utils.insertIntoJson(original, "cooperativeWork", Json.parse(cw), true)
        case None     => original
      }

      // Move review fields up to top level
      updated = o.review.reviewStatus match {
        case Some(r) => {
          Utils.insertIntoJson(updated, "reviewStatus", r, true)
        }
        case None => updated
      }
      updated = o.review.reviewRequestedBy match {
        case Some(r) => Utils.insertIntoJson(updated, "reviewRequestedBy", r, true)
        case None    => updated
      }
      updated = o.review.reviewedBy match {
        case Some(r) => Utils.insertIntoJson(updated, "reviewedBy", r, true)
        case None    => updated
      }
      updated = o.review.reviewedAt match {
        case Some(r) => Utils.insertIntoJson(updated, "reviewedAt", r, true)
        case None    => updated
      }
      updated = o.review.metaReviewStatus match {
        case Some(r) => {
          Utils.insertIntoJson(updated, "metaReviewStatus", r, true)
        }
        case None => updated
      }
      updated = o.review.metaReviewedBy match {
        case Some(r) => Utils.insertIntoJson(updated, "metaReviewedBy", r, true)
        case None    => updated
      }
      updated = o.review.metaReviewedAt match {
        case Some(r) => Utils.insertIntoJson(updated, "metaReviewedAt", r, true)
        case None    => updated
      }
      updated = o.review.reviewStartedAt match {
        case Some(r) => Utils.insertIntoJson(updated, "reviewStartedAt", r, true)
        case None    => updated
      }
      updated = o.review.reviewClaimedBy match {
        case Some(r) => Utils.insertIntoJson(updated, "reviewClaimedBy", r, true)
        case None    => updated
      }
      updated = o.review.additionalReviewers match {
        case Some(r) => Utils.insertIntoJson(updated, "additionalReviewers", r, true)
        case None    => updated
      }

      Utils.insertIntoJson(updated, "geometries", Json.parse(o.geometries), true)
    }

    override def reads(json: JsValue): JsResult[Task] = {
      implicit val mapillaryReads: Reads[MapillaryImage] = Json.reads[MapillaryImage]
      implicit val reviewReads: Reads[TaskReviewFields]  = Json.reads[TaskReviewFields]

      val jsonWithReview = Utils.insertIntoJson(json, "review", Map[String, String](), false)
      Json.fromJson[Task](jsonWithReview)(Json.reads[Task])
    }
  }

  implicit val doubleFormatter = Formats.doubleFormat

  val STATUS_CREATED             = 0
  val STATUS_CREATED_NAME        = "Created"
  val STATUS_FIXED               = 1
  val STATUS_FIXED_NAME          = "Fixed"
  val STATUS_FALSE_POSITIVE      = 2
  val STATUS_FALSE_POSITIVE_NAME = "Not_An_Issue"
  val STATUS_SKIPPED             = 3
  val STATUS_SKIPPED_NAME        = "Skipped"
  val STATUS_DELETED             = 4
  val STATUS_DELETED_NAME        = "Deleted"
  val STATUS_ALREADY_FIXED       = 5
  val STATUS_ALREADY_FIXED_NAME  = "Already_Fixed"
  val STATUS_TOO_HARD            = 6
  val STATUS_TOO_HARD_NAME       = "Too_Hard"
  val STATUS_ANSWERED            = 7
  val STATUS_ANSWERED_NAME       = "Answered"
  val STATUS_VALIDATED           = 8
  val STATUS_VALIDATED_NAME      = "Validated"
  val STATUS_DISABLED            = 9
  val STATUS_DISABLED_NAME       = "Disabled"
  val statusMap = Map(
    STATUS_CREATED        -> STATUS_CREATED_NAME,
    STATUS_FIXED          -> STATUS_FIXED_NAME,
    STATUS_SKIPPED        -> STATUS_SKIPPED_NAME,
    STATUS_FALSE_POSITIVE -> STATUS_FALSE_POSITIVE_NAME,
    STATUS_DELETED        -> STATUS_DELETED_NAME,
    STATUS_ALREADY_FIXED  -> STATUS_ALREADY_FIXED_NAME,
    STATUS_TOO_HARD       -> STATUS_TOO_HARD_NAME,
    STATUS_ANSWERED       -> STATUS_ANSWERED_NAME,
    STATUS_VALIDATED      -> STATUS_VALIDATED_NAME,
    STATUS_DISABLED       -> STATUS_DISABLED_NAME
  )

  val REVIEW_STATUS_REQUESTED                                = 0
  val REVIEW_STATUS_REQUESTED_NAME                           = "Requested"
  val REVIEW_STATUS_APPROVED                                 = 1
  val REVIEW_STATUS_APPROVED_NAME                            = "Approved"
  val REVIEW_STATUS_REJECTED                                 = 2
  val REVIEW_STATUS_REJECTED_NAME                            = "Rejected"
  val REVIEW_STATUS_ASSISTED                                 = 3
  val REVIEW_STATUS_ASSISTED_NAME                            = "Approved With Fixes"
  val REVIEW_STATUS_DISPUTED                                 = 4
  val REVIEW_STATUS_DISPUTED_NAME                            = "Disputed"
  val REVIEW_STATUS_UNNECESSARY                              = 5
  val REVIEW_STATUS_UNNECESSARY_NAME                         = "Unnecessary"
  val REVIEW_STATUS_APPROVED_WITH_REVISIONS                  = 6
  val REVIEW_STATUS_APPROVED_WITH_REVISIONS_NAME             = "Approved With Revisions"
  val REVIEW_STATUS_APPROVED_WITH_FIXES_AFTER_REVISIONS      = 7
  val REVIEW_STATUS_APPROVED_WITH_FIXES_AFTER_REVISIONS_NAME = "Approved With Fixes After Revisions"

  // For display purposes
  val REVIEW_STATUS_NOT_REQUESTED      = -1
  val REVIEW_STATUS_NOT_REQUESTED_NAME = ""

  // For Meta Reviews
  val META_REVIEW_STATUS_NOT_SET = -2 //Review status is set but meta-review is not

  val reviewStatusMap = Map(
    REVIEW_STATUS_NOT_REQUESTED                       -> REVIEW_STATUS_NOT_REQUESTED_NAME,
    REVIEW_STATUS_REQUESTED                           -> REVIEW_STATUS_REQUESTED_NAME,
    REVIEW_STATUS_APPROVED                            -> REVIEW_STATUS_APPROVED_NAME,
    REVIEW_STATUS_REJECTED                            -> REVIEW_STATUS_REJECTED_NAME,
    REVIEW_STATUS_ASSISTED                            -> REVIEW_STATUS_ASSISTED_NAME,
    REVIEW_STATUS_DISPUTED                            -> REVIEW_STATUS_DISPUTED_NAME,
    REVIEW_STATUS_UNNECESSARY                         -> REVIEW_STATUS_UNNECESSARY_NAME,
    REVIEW_STATUS_APPROVED_WITH_REVISIONS             -> REVIEW_STATUS_APPROVED_WITH_REVISIONS_NAME,
    REVIEW_STATUS_APPROVED_WITH_FIXES_AFTER_REVISIONS -> REVIEW_STATUS_APPROVED_WITH_FIXES_AFTER_REVISIONS_NAME
  )

  /**
    * Based on the status id, will return a boolean stating whether it is a valid id or not
    *
    * @param status The id to check for validity
    * @return true if status id is valid
    */
  def isValidStatus(status: Int): Boolean = statusMap.contains(status)

  /**
    * A Task must have a valid progression between status. The following rules apply:
    * If current status is created, then can be set to any of the other status's.
    * If current status is fixed, then the status cannot be changed.
    * If current status is false_positive, then it can only be changed to fixed (This is the case where it was accidentally set to false positive.
    * If current status is skipped, then it can set the status to fixed, false_positive or deleted
    * If current statis is deleted, then it can set the status to created. Essentially resetting the task
    *
    * The exception is if we are allowing the task status to be changed by the mapper in case
    * of review revisions and then these completed statuses can also change to another completed status:
    * fixed, false_positive, too_hard, already_fixed
    *
    * @param current The current status of the task
    * @param toSet   The status that the task will be set too
    * @param allowChange Allow an already completed status to be changed to another completed status.
    * @return True if the status can be set without violating any of the above rules
    */
  def isValidStatusProgression(current: Int, toSet: Int, allowChange: Boolean = false): Boolean = {
    if (current == toSet || toSet == STATUS_DELETED || toSet == STATUS_DISABLED) {
      true
    } else {
      current match {
        case STATUS_CREATED => true
        case STATUS_FIXED =>
          if (allowChange)
            toSet == STATUS_FALSE_POSITIVE || toSet == STATUS_ALREADY_FIXED || toSet == STATUS_TOO_HARD
          else false
        case STATUS_FALSE_POSITIVE =>
          if (allowChange)
            toSet == STATUS_FIXED || toSet == STATUS_ALREADY_FIXED || toSet == STATUS_TOO_HARD
          else toSet == STATUS_FIXED
        case STATUS_SKIPPED | STATUS_TOO_HARD =>
          toSet == STATUS_FIXED || toSet == STATUS_FALSE_POSITIVE || toSet == STATUS_ALREADY_FIXED ||
            toSet == STATUS_SKIPPED || toSet == STATUS_TOO_HARD || toSet == STATUS_ANSWERED
        case STATUS_DELETED => toSet == STATUS_CREATED
        case STATUS_ALREADY_FIXED =>
          if (allowChange)
            toSet == STATUS_FIXED || toSet == STATUS_FALSE_POSITIVE || toSet == STATUS_TOO_HARD
          else false
        case STATUS_ANSWERED  => false
        case STATUS_VALIDATED => false
        case STATUS_DISABLED  => toSet == STATUS_CREATED
      }
    }
  }

  /**
    * Gets the string name of the status based on a status id
    *
    * @param status The status id
    * @return None if status id is invalid, otherwise the name of the status
    */
  def getStatusName(status: Int): Option[String] = statusMap.get(status)

  /**
    * Gets the status id based on the status name
    *
    * @param status The status name
    * @return None if status name is invalid, otherwise the id of the status
    */
  def getStatusID(status: String): Option[Int] =
    statusMap.find(_._2.equalsIgnoreCase(status)) match {
      case Some(a) => Some(a._1)
      case None    => None
    }

  /**
    * Based on the review status id, will return a boolean stating whether it is a valid id or not
    *
    * @param reviewStatus The id to check for validity
    * @return true if review status id is valid
    */
  def isValidReviewStatus(reviewStatus: Int): Boolean = reviewStatusMap.contains(reviewStatus)

  /**
    * Gets the string name of the review status based on a status id
    *
    * @param reviewStatus The review status id
    * @return None if review status id is invalid, otherwise the name of the status
    */
  def getReviewStatusName(reviewStatus: Int): Option[String] = reviewStatusMap.get(reviewStatus)

  /**
    * Gets the review status id based on the review status name
    *
    * @param reviewStatus The review status name
    * @return None if review status name is invalid, otherwise the id of the review status
    */
  def getReviewStatusID(reviewStatus: String): Option[Int] =
    reviewStatusMap.find(_._2.equalsIgnoreCase(reviewStatus)) match {
      case Some(a) => Some(a._1)
      case None    => None
    }

  def emptyTask(parentId: Long): Task =
    Task(-1, "", DateTime.now(), DateTime.now(), parentId, Some(""), None, "")
}

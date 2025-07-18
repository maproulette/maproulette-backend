/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import org.apache.commons.lang3.StringUtils
import org.joda.time.DateTime
import org.maproulette.data.{ChallengeType, ItemType}
import org.maproulette.exception.InvalidException
import org.maproulette.framework.psql.CommonField
import org.maproulette.framework.model.{Identifiable, Task}
import org.maproulette.models.BaseObject
import org.maproulette.models.utils.{ChallengeReads, ChallengeWrites}
import play.api.libs.json._
import org.maproulette.utils.Utils

case class PriorityRule(operator: String, key: String, value: String, valueType: String) {
  def doesMatch(properties: Map[String, String], task: Task): Boolean = {
    // For a "bounds" match we need to see if task location is in given bounds value
    if (valueType == "bounds") {
      return locationInBounds(operator, value, task.location)
    }

    properties.find(pair => StringUtils.equalsIgnoreCase(pair._1, key)) match {
      case Some(v) =>
        valueType match {
          case "string" =>
            operator match {
              case "equal"        => StringUtils.equals(v._2, value)
              case "not_equal"    => !StringUtils.equals(v._2, value)
              case "contains"     => StringUtils.contains(v._2, value)
              case "not_contains" => !StringUtils.contains(v._2, value)
              case "is_empty"     => StringUtils.isEmpty(v._2)
              case "is_not_empty" => StringUtils.isNotEmpty(v._2)
              case _              => throw new InvalidException(s"Operator $operator not supported")
            }
          case "double" =>
            operator match {
              case "==" => v._2.toDouble == value.toDouble
              case "!=" => v._2.toDouble != value.toDouble
              case "<"  => v._2.toDouble < value.toDouble
              case "<=" => v._2.toDouble <= value.toDouble
              case ">"  => v._2.toDouble > value.toDouble
              case ">=" => v._2.toDouble >= value.toDouble
              case _    => throw new InvalidException(s"Operator $operator not supported")
            }
          case "integer" | "long" =>
            operator match {
              case "==" => v._2.toLong == value.toLong
              case "!=" => v._2.toLong != value.toLong
              case "<"  => v._2.toLong < value.toLong
              case "<=" => v._2.toLong <= value.toLong
              case ">"  => v._2.toLong > value.toLong
              case ">=" => v._2.toLong >= value.toLong
              case _    => throw new InvalidException(s"Operator $operator not supported")
            }
          case x => throw new InvalidException(s"Type $x not supported by Priority Rules")
        }
      case None => false
    }
  }

  private def locationInBounds(
      operator: String,
      value: String,
      location: Option[String]
  ): Boolean = {
    // eg. Some({"type":"Point","coordinates":[-120.18699365,48.47991855]})
    location match {
      case Some(loc) =>
        // MinX,MinY,MaxX,MaxY
        val bbox: List[Double] = Utils.toDoubleList(value).getOrElse(List(0, 0, 0, 0))
        val coordinates        = (Json.parse(loc) \ "coordinates").as[List[Double]]
        if (coordinates.length == 2) {
          val x        = coordinates(0)
          val y        = coordinates(1)
          val isInBBox = (x > bbox(0) && x < bbox(2) && y > bbox(1) && y < bbox(3))
          operator match {
            case "contains"     => return isInBBox  // loc is in bbox
            case "not_contains" => return !isInBBox // loc is not in bbox
          }
        }
      case _ => // no location to match against
    }

    return false
  }
}

case class ChallengeGeneral(
    owner: Long,
    parent: Long,
    instruction: String,
    difficulty: Int = Challenge.DIFFICULTY_NORMAL,
    blurb: Option[String] = None,
    enabled: Boolean = false,
    featured: Boolean = false,
    cooperativeType: Int = 0,
    popularity: Option[Int] = None,
    checkinComment: String = "",
    checkinSource: String = "",
    virtualParents: Option[List[Long]] = None,
    requiresLocal: Boolean = false
) extends DefaultWrites

case class ChallengeCreation(
    overpassQL: Option[String] = None,
    remoteGeoJson: Option[String] = None,
    overpassTargetType: Option[String] = None
) extends DefaultWrites

case class ChallengePriority(
    defaultPriority: Int = Challenge.PRIORITY_HIGH,
    highPriorityRule: Option[String] = None,
    mediumPriorityRule: Option[String] = None,
    lowPriorityRule: Option[String] = None,
    highPriorityBounds: Option[String] = None,
    mediumPriorityBounds: Option[String] = None,
    lowPriorityBounds: Option[String] = None
) extends DefaultWrites

case class ChallengeExtra(
    defaultZoom: Int = Challenge.DEFAULT_ZOOM,
    minZoom: Int = Challenge.MIN_ZOOM,
    maxZoom: Int = Challenge.MAX_ZOOM,
    defaultBasemap: Option[Int] = None,
    defaultBasemapId: Option[String] = None,
    customBasemap: Option[String] = None,
    updateTasks: Boolean = false,
    exportableProperties: Option[String] = None,
    osmIdProperty: Option[String] = None,
    preferredTags: Option[String] = None,
    preferredReviewTags: Option[String] = None,
    limitTags: Boolean = false,       // If true, only preferred tags should be used
    limitReviewTags: Boolean = false, // If true, only preferred review tags should be used
    taskStyles: Option[String] = None,
    taskBundleIdProperty: Option[String] = None,
    isArchived: Boolean = false,
    reviewSetting: Int = Challenge.REVIEW_SETTING_NOT_REQUIRED,
    taskWidgetLayout: Option[JsValue] = None,
    datasetUrl: Option[String] = None,
    systemArchivedAt: Option[DateTime] = None,
    presets: Option[List[String]] = None,
    requireConfirmation: Boolean = false
) extends DefaultWrites

case class ChallengeListing(
    id: Long,
    parent: Long,
    name: String,
    enabled: Boolean,
    virtualParents: Option[Array[Long]] = None,
    status: Option[Int],
    isArchived: Boolean
)

/**
  * The ChallengeFormFix case class is built so that we can nest the form objects as there is a limit
  * on the number of elements allowed in the form mapping.
  */
case class Challenge(
    override val id: Long,
    override val name: String,
    override val created: DateTime,
    override val modified: DateTime,
    override val description: Option[String] = None,
    deleted: Boolean = false,
    isGlobal: Boolean = false,
    requireConfirmation: Boolean = false,
    requireRejectReason: Boolean = false,
    infoLink: Option[String] = None,
    general: ChallengeGeneral,
    creation: ChallengeCreation,
    priority: ChallengePriority,
    extra: ChallengeExtra,
    status: Option[Int] = Some(0),
    statusMessage: Option[String] = None,
    lastTaskRefresh: Option[DateTime] = None,
    dataOriginDate: Option[DateTime] = None,
    location: Option[String] = None,
    bounding: Option[String] = None,
    completionPercentage: Option[Int] = Some(0),
    tasksRemaining: Option[Int] = Some(0)
) extends BaseObject[Long]
    with DefaultWrites
    with Identifiable {

  override val itemType: ItemType = ChallengeType()

  def isHighPriority(properties: Map[String, String], task: Task): Boolean =
    this.matchesRule(priority.highPriorityRule, properties, task)

  def isMediumPriority(properties: Map[String, String], task: Task): Boolean =
    this.matchesRule(priority.mediumPriorityRule, properties, task)

  def isLowRulePriority(properties: Map[String, String], task: Task): Boolean =
    this.matchesRule(priority.lowPriorityRule, properties, task)

  private def matchesRule(
      rule: Option[String],
      properties: Map[String, String],
      task: Task
  ): Boolean = {
    rule match {
      case Some(r) => matchesJSONRule(Json.parse(r), properties, task)
      case None    => false
    }
  }

  private def matchesJSONRule(
      ruleJSON: JsValue,
      properties: Map[String, String],
      task: Task
  ): Boolean = {
    val cnf = (ruleJSON \ "condition").asOpt[String] match {
      case Some("OR") => false
      case _          => true
    }
    implicit val reads = Writes
    val rules          = (ruleJSON \ "rules").as[List[JsValue]]
    val matched = rules.filter(jsValue => {
      (jsValue \ "rules").asOpt[JsValue] match {
        case Some(nestedRule) => matchesJSONRule(jsValue, properties, task)
        case _ =>
          val keyValue  = (jsValue \ "value").as[String].split("\\.", 2)
          val valueType = (jsValue \ "type").as[String]
          val rule =
            PriorityRule((jsValue \ "operator").as[String], keyValue(0), keyValue(1), valueType)
          rule.doesMatch(properties, task)
      }
    })
    if (cnf && matched.size == rules.size) {
      true
    } else if (!cnf && matched.nonEmpty) {
      true
    } else {
      false
    }
  }

  def isWithinBounds(boundsList: Option[String], task: Task): Boolean = {
    boundsList match {
      case Some(bounds) if bounds.nonEmpty && bounds != "[]" =>
        try {
          // Parse the GeoJSON input which could be an array or a single feature
          val boundsJson = Json.parse(bounds)
          val boundingPolygons = if (boundsJson.isInstanceOf[JsArray]) {
            boundsJson.as[List[JsValue]]
          } else {
            // Single feature case, wrap in a list
            List(boundsJson)
          }

          if (boundingPolygons.isEmpty) {
            return false
          }

          // Extract coordinates from task geometries
          try {
            val geometries = Json.parse(task.geometries)
            val features   = (geometries \ "features").as[List[JsValue]]
            if (features.nonEmpty) {
              // Check if any feature is within bounds (important for ways/relations with multiple features)
              features.exists(feature => {
                val geometry = (feature \ "geometry").get
                checkGeometryWithinBounds(geometry, boundingPolygons)
              })
            } else {
              false
            }
          } catch {
            case _: Exception => false
          }
        } catch {
          case e: Exception =>
            false // Return false on any parsing error
        }
      case _ => false
    }
  }

  /**
    * Helper method to check if a geometry (Point, LineString, Polygon, etc.) is within any of the bounding polygons.
    * This method handles all GeoJSON geometry types including collections.
    *
    * @param geometry The GeoJSON geometry to check
    * @param boundingPolygons List of bounding polygon GeoJSON features
    * @return true if any part of the geometry is within any bounding polygon
    */
  private def checkGeometryWithinBounds(
      geometry: JsValue,
      boundingPolygons: List[JsValue]
  ): Boolean = {
    try {
      (geometry \ "type").asOpt[String] match {
        case Some("Point") =>
          val coordinates = (geometry \ "coordinates").as[List[Double]]
          if (coordinates.length == 2) {
            val x = coordinates(0) // longitude
            val y = coordinates(1) // latitude
            boundingPolygons.exists(polygon => isPointInPolygon(x, y, polygon))
          } else {
            false
          }
        case Some("LineString") =>
          val coordinates = (geometry \ "coordinates").as[List[List[Double]]]
          coordinates.exists(coord => {
            if (coord.length == 2) {
              val x = coord(0) // longitude
              val y = coord(1) // latitude
              boundingPolygons.exists(polygon => isPointInPolygon(x, y, polygon))
            } else {
              false
            }
          })
        case Some("GeometryCollection") =>
          val geometries = (geometry \ "geometries").as[List[JsValue]]
          geometries.exists(subGeometry => checkGeometryWithinBounds(subGeometry, boundingPolygons))
        case _ => false
      }
    } catch {
      case _: Exception => false // Return false on any parsing or processing error
    }
  }

  private def isPointInPolygon(x: Double, y: Double, polygon: JsValue): Boolean = {
    try {
      // Handle both direct geometry and GeoJSON feature
      val geometryCoordinates = if ((polygon \ "type").asOpt[String].contains("Feature")) {
        (polygon \ "geometry" \ "coordinates").as[List[List[List[Double]]]]
      } else {
        (polygon \ "coordinates").as[List[List[List[Double]]]]
      }

      // Use the first ring of the polygon (exterior ring)
      val ring = geometryCoordinates.head

      if (ring.isEmpty) {
        return false
      }

      var inside = false
      var i      = 0
      var j      = ring.size - 1

      // Ray casting algorithm to determine if point is in polygon
      while (i < ring.size) {
        // Get coordinates (ring points are in [longitude, latitude] format)
        val xi = ring(i)(0)
        val yi = ring(i)(1)
        val xj = ring(j)(0)
        val yj = ring(j)(1)

        // Check if the ray crosses this edge
        val intersect = ((yi > y) != (yj > y)) &&
          (x < (xj - xi) * (y - yi) / (yj - yi) + xi)

        if (intersect) {
          inside = !inside
        }

        j = i
        i += 1
      }

      inside
    } catch {
      case e: Exception =>
        // If any error occurs during parsing or calculation, return false
        false
    }
  }
}

object Challenge extends CommonField {
  val writes = new Object with ChallengeWrites
  val reads  = new Object with ChallengeReads

  val DIFFICULTY_EASY   = 1
  val DIFFICULTY_NORMAL = 2
  val DIFFICULTY_EXPERT = 3

  val REVIEW_SETTING_NOT_REQUIRED = 0
  val REVIEW_SETTING_REQUESTED    = 1

  val PRIORITY_HIGH        = 0
  val PRIORITY_HIGH_NAME   = "High"
  val PRIORITY_MEDIUM      = 1
  val PRIORITY_MEDIUM_NAME = "Medium"
  val PRIORITY_LOW         = 2
  val PRIORITY_LOW_NAME    = "Low"
  val priorityMap = Map(
    PRIORITY_HIGH   -> PRIORITY_HIGH_NAME,
    PRIORITY_MEDIUM -> PRIORITY_MEDIUM_NAME,
    PRIORITY_LOW    -> PRIORITY_LOW_NAME
  )

  val DEFAULT_ZOOM = 13
  val MIN_ZOOM     = 1
  val MAX_ZOOM     = 19

  val KEY_PARENT          = "parent"
  val KEY_VIRTUAL_PARENTS = "virtualParents"

  val STATUS_NA               = 0
  val STATUS_BUILDING         = 1
  val STATUS_FAILED           = 2
  val STATUS_READY            = 3
  val STATUS_PARTIALLY_LOADED = 4
  val STATUS_FINISHED         = 5
  val STATUS_DELETING_TASKS   = 6

  val STATUS_MESSAGE_NONE                   = ""
  val STATUS_MESSAGE_UPDATING_TASK_STATUSES = "Updating Task Statuses"

  // COOPERATIVE TYPES
  val COOPERATIVE_NONE       = 0
  val COOPERATIVE_TAGS       = 1
  val COOPERATIVE_CHANGEFILE = 2

  // CHALLENGE FIELDS
  val TABLE           = "challenges"
  val FIELD_PARENT_ID = "parent_id"
  val FIELD_ENABLED   = "enabled"
  val FIELD_ARCHIVED  = "is_archived"
  val FIELD_GLOBAL    = "is_global"
  val FIELD_STATUS    = "status"
  val FIELD_DELETED   = "deleted"

  /**
    * This will check to make sure that the rule string is fully valid.
    *
    * @param rule
    * @return
    */
  def isValidRule(rule: Option[String]): Boolean = {
    rule match {
      case Some(r) if StringUtils.isNotEmpty(r) && !StringUtils.equalsIgnoreCase(r, "{}") =>
        isValidRuleJSON(Json.parse(r))
      case _ => false
    }
  }

  /**
    * This will check to make sure that the json rule is fully valid. The simple check just makes sure
    * that every rule value can be split by "." into two values, with support for nested rules
    *
    * @param ruleJSON
    * @return
    */
  def isValidRuleJSON(ruleJSON: JsValue): Boolean = {
    val rules = (ruleJSON \ "rules")
      .as[List[JsValue]]
      .map(jsValue => {
        (jsValue \ "rules").asOpt[JsValue] match {
          case Some(nestedRule) => isValidRuleJSON(jsValue)
          case _ =>
            val keyValue = (jsValue \ "value").as[String].split("\\.", 2)
            keyValue.size == 2
        }
      })
    !rules.contains(false)
  }

  /**
    * This will check to make sure that the bounds string is fully valid.
    *
    * @param bounds
    * @return
    */
  def isValidBounds(bounds: Option[String]): Boolean = {
    bounds match {
      case Some(b) if StringUtils.isNotEmpty(b) && !StringUtils.equalsIgnoreCase(b, "[]") =>
        try {
          val boundsJson = Json.parse(b)

          // Handle both array of features and single feature
          if (boundsJson.isInstanceOf[JsArray]) {
            val boundsArray = boundsJson.as[List[JsValue]]
            if (boundsArray.isEmpty) {
              return false
            }

            // Each item should be a GeoJSON Feature with a Polygon geometry
            boundsArray.forall(item => isValidFeature(item))
          } else if (boundsJson.isInstanceOf[JsObject]) {
            // Single GeoJSON feature case
            isValidFeature(boundsJson)
          } else {
            false
          }
        } catch {
          case _: Exception => false
        }
      case _ => false
    }
  }

  /**
    * Helper method to validate a GeoJSON feature with Polygon geometry
    */
  private def isValidFeature(item: JsValue): Boolean = {
    (item \ "type").asOpt[String].contains("Feature") &&
    ((item \ "geometry" \ "type").asOpt[String].contains("Polygon") &&
    (item \ "geometry" \ "coordinates").validate[List[List[List[Double]]]].isSuccess)
  }

  def emptyChallenge(ownerId: Long, parentId: Long): Challenge = Challenge(
    -1,
    "",
    DateTime.now(),
    DateTime.now(),
    None,
    false,
    false,
    false,
    false,
    None,
    ChallengeGeneral(-1, -1, ""),
    ChallengeCreation(),
    ChallengePriority(),
    ChallengeExtra()
  )
}

case class ArchivableChallenge(
    val id: Long,
    val created: DateTime,
    val name: String = "",
    val deleted: Boolean,
    val isArchived: Boolean
)

case class ArchivableTask(
    val id: Long,
    val modified: DateTime,
    val status: Long
)

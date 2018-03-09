// Copyright (C) 2016 MapRoulette contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package org.maproulette.models

import org.apache.commons.lang3.StringUtils
import org.joda.time.DateTime
import org.maproulette.actions.{ItemType, TaskType}
import org.maproulette.utils.Utils
import play.api.data.Form
import play.api.data.format.Formats._
import play.api.data.Forms._
import play.api.libs.json._

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
case class Task(override val id:Long,
                override val name: String,
                override val created:DateTime,
                override val modified:DateTime,
                parent: Long,
                instruction: Option[String]=None,
                location: Option[String]=None,
                geometries:String,
                status:Option[Int]=None,
                priority:Int=Challenge.PRIORITY_HIGH,
                changesetId:Option[Long]=None) extends BaseObject[Long] with DefaultReads with LowPriorityDefaultReads {
  override val itemType: ItemType = TaskType()

  def getGeometryProperties() : List[Map[String, String]] = {
    if (StringUtils.isNotEmpty(geometries)) {
      val geojson = Json.parse(geometries)
      (geojson \ "features").as[List[JsValue]].map(json => (json \ "properties").as[Map[String, String]])
    } else {
      List.empty
    }
  }

  /**
    * Gets the task priority
    *
    * @param parent The parent Challenge
    * @return Priority HIGH = 0, MEDIUM = 1, LOW = 2
    */
  def getTaskPriority(parent:Challenge) : Int = {
    val matchingList = getGeometryProperties().flatMap {
      props => if (parent.isHighPriority(props)) {
        Some(Challenge.PRIORITY_HIGH)
      } else if (parent.isMediumPriority(props)) {
        Some(Challenge.PRIORITY_MEDIUM)
      } else if (parent.isLowRulePriority(props)) {
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
}

object Task {
  implicit object TaskFormat extends Format[Task] {
    override def writes(o: Task): JsValue = {
      implicit val taskWrites: Writes[Task] = Json.writes[Task]
      val original = Json.toJson(o)(Json.writes[Task])
      val updated = o.location match {
        case Some(l) => Utils.insertIntoJson(original, "location", Json.parse(l), true)
        case None => original
      }
      Utils.insertIntoJson(updated, "geometries", Json.parse(o.geometries), true)
    }

    override def reads(json: JsValue): JsResult[Task] = {
      Json.fromJson[Task](json)(Json.reads[Task])
    }
  }

  val STATUS_CREATED = 0
  val STATUS_CREATED_NAME = "Created"
  val STATUS_FIXED = 1
  val STATUS_FIXED_NAME = "Fixed"
  val STATUS_FALSE_POSITIVE = 2
  val STATUS_FALSE_POSITIVE_NAME = "False_Positive"
  val STATUS_SKIPPED = 3
  val STATUS_SKIPPED_NAME = "Skipped"
  val STATUS_DELETED = 4
  val STATUS_DELETED_NAME = "Deleted"
  val STATUS_ALREADY_FIXED = 5
  val STATUS_ALREADY_FIXED_NAME = "Already_Fixed"
  val STATUS_TOO_HARD = 6
  val STATUS_TOO_HARD_NAME = "Too_Hard"
  val STATUS_ANSWERED = 7
  val STATUS_ANSWERED_NAME = "Answered"
  val statusMap = Map(
    STATUS_CREATED -> STATUS_CREATED_NAME,
    STATUS_FIXED -> STATUS_FIXED_NAME,
    STATUS_SKIPPED -> STATUS_SKIPPED_NAME,
    STATUS_FALSE_POSITIVE -> STATUS_FALSE_POSITIVE_NAME,
    STATUS_DELETED -> STATUS_DELETED_NAME,
    STATUS_ALREADY_FIXED -> STATUS_ALREADY_FIXED_NAME,
    STATUS_TOO_HARD -> STATUS_TOO_HARD_NAME,
    STATUS_ANSWERED -> STATUS_ANSWERED_NAME
  )

  /**
    * Based on the status id, will return a boolean stating whether it is a valid id or not
    *
    * @param status The id to check for validity
    * @return true if status id is valid
    */
  def isValidStatus(status:Int) : Boolean = statusMap.contains(status)

  /**
    * A Task must have a valid progression between status. The following rules apply:
    * If current status is created, then can be set to any of the other status's.
    * If current status is fixed, then the status cannot be changed.
    * If current status is false_positive, then it can only be changed to fixed (This is the case where it was accidentally set to false positive.
    * If current status is skipped, then it can set the status to fixed, false_positive or deleted
    * If current statis is deleted, then it can set the status to created. Essentially resetting the task
    *
    * @param current The current status of the task
    * @param toSet The status that the task will be set too
    * @return True if the status can be set without violating any of the above rules
    */
  def isValidStatusProgression(current:Int, toSet:Int) : Boolean = {
    if (current == toSet || toSet == STATUS_DELETED) {
      true
    } else {
      current match {
        case STATUS_CREATED => true
        case STATUS_FIXED => false
        case STATUS_FALSE_POSITIVE => toSet == STATUS_FIXED
        case STATUS_SKIPPED | STATUS_TOO_HARD =>
          toSet == STATUS_FIXED || toSet == STATUS_FALSE_POSITIVE || toSet == STATUS_ALREADY_FIXED || toSet == STATUS_SKIPPED || toSet == STATUS_TOO_HARD || toSet == STATUS_ANSWERED
        case STATUS_DELETED => toSet == STATUS_CREATED
        case STATUS_ALREADY_FIXED => false
        case STATUS_ANSWERED => false
      }
    }
  }

  /**
    * Gets the string name of the status based on a status id
    *
    * @param status The status id
    * @return None if status id is invalid, otherwise the name of the status
    */
  def getStatusName(status:Int) : Option[String] = statusMap.get(status)

  /**
    * Gets the status id based on the status name
    *
    * @param status The status name
    * @return None if status name is invalid, otherwise the id of the status
    */
  def getStatusID(status:String) : Option[Int] = statusMap.find(_._2.equalsIgnoreCase(status)) match {
    case Some(a) => Some(a._1)
    case None => None
  }


  val taskForm = Form(
    mapping(
      "id" -> default(longNumber,-1L),
      "name" -> nonEmptyText,
      "created" -> default(jodaDate, DateTime.now()),
      "modified" -> default(jodaDate, DateTime.now()),
      "parent" -> longNumber,
      "instruction" -> optional(text),
      "location" -> optional(text),
      "geometries" -> nonEmptyText,
      "status" -> optional(number),
      "priority" -> default(number, Challenge.PRIORITY_HIGH),
      "changesetId" -> optional(longNumber)
    )(Task.apply)(Task.unapply)
  )

  def emptyTask(parentId:Long) : Task = Task(-1, "", DateTime.now(), DateTime.now(), parentId, Some(""), None, "")
}

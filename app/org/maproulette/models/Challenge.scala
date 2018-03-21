// Copyright (C) 2016 MapRoulette contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package org.maproulette.models

import org.apache.commons.lang3.StringUtils
import org.joda.time.DateTime
import play.api.data._
import play.api.data.Forms._
import org.maproulette.actions.{Actions, ChallengeType, ItemType}
import org.maproulette.models.utils.{ChallengeReads, ChallengeWrites}
import play.api.libs.json._

// Answer cass class for Surveys
case class Answer(id:Long = -1, answer:String)

case class PriorityRule(operator:String, key:String, value:String) {
  def doesMatch(properties:Map[String, String]) : Boolean = {
    properties.find(pair => StringUtils.equalsIgnoreCase(pair._1, key)) match {
      case Some(v) => operator match {
        case "equal" => StringUtils.equals(v._2, value)
        case "not_equal" => !StringUtils.equals(v._2, value)
        case "contains" => StringUtils.contains(v._2, value)
        case "not_contains" => !StringUtils.contains(v._2, value)
        case "is_empty" => StringUtils.isEmpty(v._2)
        case "is_not_empty" => StringUtils.isNotEmpty(v._2)
      }
      case None => false
    }
  }
}

case class ChallengeGeneral(owner:Long,
                            parent:Long,
                            instruction:String,
                            difficulty:Int=Challenge.DIFFICULTY_NORMAL,
                            blurb:Option[String]=None,
                            enabled:Boolean=false,
                            challengeType:Int=Actions.ITEM_TYPE_CHALLENGE,
                            featured:Boolean=false,
                            checkinComment:String="") extends DefaultWrites
case class ChallengeCreation(overpassQL:Option[String]=None, remoteGeoJson:Option[String]=None) extends DefaultWrites
case class ChallengePriority(defaultPriority:Int=Challenge.PRIORITY_HIGH,
                             highPriorityRule:Option[String]=None,
                             mediumPriorityRule:Option[String]=None,
                             lowPriorityRule:Option[String]=None) extends DefaultWrites
case class ChallengeExtra(defaultZoom:Int=Challenge.DEFAULT_ZOOM,
                          minZoom:Int=Challenge.MIN_ZOOM,
                          maxZoom:Int=Challenge.MAX_ZOOM,
                          defaultBasemap:Option[Int]=None,
                          customBasemap:Option[String]=None,
                          updateTasks:Boolean=false) extends DefaultWrites

/**
  * The ChallengeFormFix case class is built so that we can nest the form objects as there is a limit
  * on the number of elements allowed in the form mapping.
  */
case class Challenge(override val id:Long,
                     override val name:String,
                     override val created:DateTime,
                     override val modified:DateTime,
                     override val description:Option[String]=None,
                     deleted:Boolean = false,
                     infoLink:Option[String]=None,
                     general:ChallengeGeneral,
                     creation:ChallengeCreation,
                     priority:ChallengePriority,
                     extra:ChallengeExtra,
                     status:Option[Int]=Some(0),
                     statusMessage:Option[String]=None,
                     location:Option[String]=None,
                     bounding:Option[String]=None) extends BaseObject[Long] with DefaultWrites {

  override val itemType: ItemType = ChallengeType()

  def isHighPriority(properties:Map[String, String]) : Boolean = this.matchesRule(priority.highPriorityRule, properties)

  def isMediumPriority(properties:Map[String, String]) : Boolean = this.matchesRule(priority.mediumPriorityRule, properties)

  def isLowRulePriority(properties:Map[String, String]) : Boolean = this.matchesRule(priority.lowPriorityRule, properties)

  private def matchesRule(rule:Option[String], properties:Map[String, String]) : Boolean = {
    rule match {
      case Some(r) =>
        val ruleJSON = Json.parse(r)
        val cnf = (ruleJSON \ "condition").asOpt[String] match {
          case Some("OR") => false
          case _ => true
        }
        implicit val reads = Writes
        val rules = (ruleJSON \ "rules").as[List[JsValue]].map(jsValue => {
          val keyValue = (jsValue \ "value").as[String].split("\\.")
          PriorityRule((jsValue \ "operator").as[String], keyValue(0), keyValue(1))
        })
        val matched = rules.filter(_.doesMatch(properties))
        if (cnf && matched.size == rules.size) {
          true
        } else if (!cnf && matched.nonEmpty) {
          true
        } else {
          false
        }
      case None => false
    }
  }
}

object Challenge {
  implicit val answerWrites: Writes[Answer] = Json.writes[Answer]
  implicit val answerReads: Reads[Answer] = Json.reads[Answer]

  val writes = new Object with ChallengeWrites
  val reads = new Object with ChallengeReads

  val DIFFICULTY_EASY = 1
  val DIFFICULTY_NORMAL = 2
  val DIFFICULTY_EXPERT = 3

  val PRIORITY_HIGH = 0
  val PRIORITY_MEDIUM = 1
  val PRIORITY_LOW = 2

  val DEFAULT_ZOOM = 13
  val MIN_ZOOM = 1
  val MAX_ZOOM = 19

  val KEY_ANSWER = "answers"
  val KEY_PARENT = "parent"
  val defaultAnswerValid = Answer(-1, "Valid")
  val defaultAnswerInvalid = Answer(-2, "Invalid")

  /**
    * This will check to make sure that the json rule is fully valid. The simple check just makes sure
    * that every rule value is split by "." and contains only two values.
    *
    * @param rule
    * @return
    */
  def isValidRule(rule:Option[String]) : Boolean = {
    rule match {
      case Some(r) if StringUtils.isNotEmpty(r) && !StringUtils.equalsIgnoreCase(r, "{}") =>
        val ruleJSON = Json.parse(r)
        val rules = (ruleJSON \ "rules").as[List[JsValue]].map(jsValue => {
          val keyValue = (jsValue \ "value").as[String].split("\\.")
          keyValue.size == 2
        })
        !rules.contains(false)
      case _ => false
    }
  }

  val challengeForm = Form(
    mapping(
      "id" -> default(longNumber, -1L),
      "name" -> nonEmptyText,
      "created" -> default(jodaDate, DateTime.now()),
      "modified" -> default(jodaDate, DateTime.now()),
      "description" -> optional(text),
      "deleted" -> default(boolean, false),
      "infoLink" -> optional(text),
      "general" -> mapping(
        "owner" -> longNumber,
        "parent" -> longNumber,
        "instruction" -> nonEmptyText,
        "difficulty" -> default(number(min = DIFFICULTY_EASY, max = DIFFICULTY_EXPERT + 1), DIFFICULTY_NORMAL),
        "blurb" -> optional(text),
        "enabled" -> boolean,
        "challengeType" -> default(number, Actions.ITEM_TYPE_CHALLENGE),
        "featured" -> default(boolean, false),
        "checkinComment" -> default(text, "")
      )(ChallengeGeneral.apply)(ChallengeGeneral.unapply),
      "creation" -> mapping(
        "overpassQL" -> optional(text),
        "remoteGeoJson" -> optional(text)
      )(ChallengeCreation.apply)(ChallengeCreation.unapply),
      "priority" -> mapping(
        "defaultPriority" -> default(number(min = PRIORITY_HIGH, max = PRIORITY_LOW + 1), PRIORITY_HIGH),
        "highPriorityRule" -> optional(text),
        "mediumPriorityRule" -> optional(text),
        "lowPriorityRule" -> optional(text)
      )(ChallengePriority.apply)(ChallengePriority.unapply),
      "extra" -> mapping(
        "defaultZoom" -> default(number, DEFAULT_ZOOM),
        "minZoom" -> default(number, MIN_ZOOM),
        "maxZoom" -> default(number, MAX_ZOOM),
        "defaultBasemap" -> optional(number),
        "customBasemap" -> optional(text),
        "updateTasks" -> default(boolean, false)
      )(ChallengeExtra.apply)(ChallengeExtra.unapply),
      "status" -> default(optional(number), None),
      "statusMessage" -> optional(text),
      "location" -> default(optional(text), None),
      "bounding" -> default(optional(text), None)
    )(Challenge.apply)(Challenge.unapply)
  )

  def emptyChallenge(ownerId:Long, parentId:Long) : Challenge = Challenge(
    -1, "", DateTime.now(), DateTime.now(), None, false, None, ChallengeGeneral(-1, -1, ""),
    ChallengeCreation(), ChallengePriority(), ChallengeExtra()
  )

  val STATUS_NA = 0
  val STATUS_BUILDING = 1
  val STATUS_FAILED = 2
  val STATUS_COMPLETE = 3
  val STATUS_PARTIALLY_LOADED = 4
}

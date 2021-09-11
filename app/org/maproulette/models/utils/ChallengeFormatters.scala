/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.models.utils

import org.joda.time.DateTime
import org.maproulette.framework.model.{
  Challenge,
  ChallengeCreation,
  ChallengeExtra,
  ChallengeGeneral,
  ChallengePriority
}
import org.maproulette.models._
import org.maproulette.utils.Utils
import org.maproulette.utils.Utils.{jsonReads, jsonWrites}
import play.api.libs.functional.syntax._
import play.api.libs.json.JodaReads._
import play.api.libs.json.JodaWrites._
import play.api.libs.json._

/**
  * @author cuthbertm
  */
trait ChallengeWrites extends DefaultWrites {
  implicit val challengeGeneralWrites: Writes[ChallengeGeneral]   = Json.writes[ChallengeGeneral]
  implicit val challengeCreationWrites: Writes[ChallengeCreation] = Json.writes[ChallengeCreation]

  implicit object challengePriorityWrites extends Writes[ChallengePriority] {
    override def writes(challengePriority: ChallengePriority): JsValue =
      JsObject(
        Seq(
          "defaultPriority"    -> JsNumber(challengePriority.defaultPriority),
          "highPriorityRule"   -> Json.parse(challengePriority.highPriorityRule.getOrElse("{}")),
          "mediumPriorityRule" -> Json.parse(challengePriority.mediumPriorityRule.getOrElse("{}")),
          "lowPriorityRule"    -> Json.parse(challengePriority.lowPriorityRule.getOrElse("{}"))
        )
      )
  }

  implicit val challengeExtraWrites = new Writes[ChallengeExtra] {
    def writes(o: ChallengeExtra): JsValue = {
      var original = Json.toJson(o)(Json.writes[ChallengeExtra])
      o.taskStyles match {
        case Some(ts) => Utils.insertIntoJson(original, "taskStyles", Json.parse(ts), true)
        case None     => original
      }
    }
  }

  implicit val challengeWrites: Writes[Challenge] = (
    (JsPath \ "id").write[Long] and
      (JsPath \ "name").write[String] and
      (JsPath \ "created").write[DateTime] and
      (JsPath \ "modified").write[DateTime] and
      (JsPath \ "description").writeNullable[String] and
      (JsPath \ "deleted").write[Boolean] and
      (JsPath \ "infoLink").writeNullable[String] and
      JsPath.write[ChallengeGeneral] and
      JsPath.write[ChallengeCreation] and
      JsPath.write[ChallengePriority] and
      JsPath.write[ChallengeExtra] and
      (JsPath \ "status").writeNullable[Int] and
      (JsPath \ "statusMessage").writeNullable[String] and
      (JsPath \ "lastTaskRefresh").writeNullable[DateTime] and
      (JsPath \ "dataOriginDate").writeNullable[DateTime] and
      (JsPath \ "location").writeNullable[String](new jsonWrites("location")) and
      (JsPath \ "bounding").writeNullable[String](new jsonWrites("bounding")) and
      (JsPath \ "completionPercentage").writeNullable[Int] and
      (JsPath \ "tasksRemaining").writeNullable[Int]
  )(unlift(Challenge.unapply))
}

trait ChallengeReads extends DefaultReads {
  implicit val challengeGeneralReads: Reads[ChallengeGeneral]   = Json.reads[ChallengeGeneral]
  implicit val challengeCreationReads: Reads[ChallengeCreation] = Json.reads[ChallengeCreation]
  implicit val challengePriorityReads: Reads[ChallengePriority] = Json.reads[ChallengePriority]

  implicit val challengeExtraReads = new Reads[ChallengeExtra] {
    def reads(json: JsValue): JsResult[ChallengeExtra] = {
      var jsonWithExtras =
        (json \ "taskStyles").asOpt[JsValue] match {
          case Some(value) => Utils.insertIntoJson(json, "taskStyles", value.toString(), true)
          case None        => json
        }

      jsonWithExtras = (jsonWithExtras \ "limitTags").asOpt[JsValue] match {
        case Some(value) => jsonWithExtras
        case None        => Utils.insertIntoJson(jsonWithExtras, "limitTags", false, true)
      }

      jsonWithExtras = (jsonWithExtras \ "limitReviewTags").asOpt[JsValue] match {
        case Some(value) => jsonWithExtras
        case None        => Utils.insertIntoJson(jsonWithExtras, "limitReviewTags", false, true)
      }

      jsonWithExtras = (jsonWithExtras \ "isArchived").asOpt[JsValue] match {
        case Some(value) => jsonWithExtras
        case None        => Utils.insertIntoJson(jsonWithExtras, "isArchived", false, false)
      }

      Json.fromJson[ChallengeExtra](jsonWithExtras)(Json.reads[ChallengeExtra])
    }
  }

  implicit val challengeReads: Reads[Challenge] = (
    (JsPath \ "id").read[Long] and
      (JsPath \ "name").read[String] and
      ((JsPath \ "created").read[DateTime] or Reads.pure(DateTime.now())) and
      ((JsPath \ "modified").read[DateTime] or Reads.pure(DateTime.now())) and
      (JsPath \ "description").readNullable[String] and
      (JsPath \ "deleted").read[Boolean] and
      (JsPath \ "infoLink").readNullable[String] and
      JsPath.read[ChallengeGeneral] and
      JsPath.read[ChallengeCreation] and
      JsPath.read[ChallengePriority] and
      JsPath.read[ChallengeExtra] and
      (JsPath \ "status").readNullable[Int] and
      (JsPath \ "statusMessage").readNullable[String] and
      (JsPath \ "lastTaskRefresh").readNullable[DateTime] and
      (JsPath \ "dataOriginDate").readNullable[DateTime] and
      (JsPath \ "location").readNullable[String](new jsonReads("location")) and
      (JsPath \ "bounding").readNullable[String](new jsonReads("bounding")) and
      (JsPath \ "completionPercentage").readNullable[Int] and
      (JsPath \ "tasksRemaining").readNullable[Int]
  )(Challenge.apply _)
}

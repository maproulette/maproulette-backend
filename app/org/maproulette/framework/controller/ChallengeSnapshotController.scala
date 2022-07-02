/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.controller

import akka.util.ByteString
import javax.inject.Inject
import org.maproulette.data.ActionManager
import org.maproulette.data._
import org.maproulette.framework.service.ChallengeSnapshotService
import org.maproulette.framework.model.{Challenge, Task}
import org.maproulette.session.SessionManager
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.json.Json.JsValueWrapper
import play.api.http.HttpEntity

/**
  * SnapshotController is responsible for handling functionality related to
  * snapshots.
  *
  * @author krotstan
  */
class ChallengeSnapshotController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val bodyParsers: PlayBodyParsers,
    snapshotService: ChallengeSnapshotService,
    components: ControllerComponents,
    snapshotManager: SnapshotManager
) extends AbstractController(components)
    with MapRouletteController {

  implicit val actionWrites           = actionManager.actionItemWrites
  implicit val dateWrites             = Writes.dateWrites("yyyy-MM-dd")
  implicit val dateTimeWrites         = JodaWrites.jodaDateWrites("yyyy-MM-dd'T'HH:mm:ss")
  implicit val actionSummaryWrites    = Json.writes[ActionSummary]
  implicit val challengeSummaryWrites = Json.writes[ChallengeSummary]
  implicit val stringIntMap: Writes[Map[String, Int]] = new Writes[Map[String, Int]] {
    def writes(map: Map[String, Int]): JsValue =
      Json.obj(map.map {
        case (s, i) =>
          val ret: (String, JsValueWrapper) = s.toString -> JsNumber(i)
          ret
      }.toSeq: _*)
  }
  implicit val reviewActionWrites = Json.writes[ReviewActions]
  implicit val snapshotWrites     = Json.writes[Snapshot]

  /**
    * Retrieves a snapshot
    *
    * @param snapshotId
    */
  def retrieve(snapshotId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(Json.toJson(snapshotManager.getChallengeSnapshot(snapshotId)))
    }
  }

  /**
    * Records a new snapshot for a challenge.
    *
    * @param challengeId
    */
  def recordChallengeSnapshot(challengeId: Long): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        Ok(Json.toJson(snapshotManager.recordChallengeSnapshot(challengeId)))
      }
  }

  /**
    * Deletes a challenges snapshot. User must have write privileges to challenge.
    *
    * @param snapshotId Id of snapshot to delete.
    */
  def delete(snapshotId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.snapshotService.delete(snapshotId, user)
      Ok
    }
  }

  /**
    * Gets a list of snapshots for a challenge.
    *
    * @param challengeId
    * @param includeAllData Boolean indicating whether all snapshot data should
    *                       be included or just a brief summary of each.
    */
  def getSnapshotList(challengeId: Long, includeAllData: Boolean): Action[AnyContent] =
    Action.async { implicit request =>
      this.sessionManager.userAwareRequest { implicit user =>
        if (includeAllData) {
          Ok(Json.toJson(snapshotManager.getAllChallengeSnapshots(challengeId)))
        } else {
          Ok(Json.toJson(snapshotManager.getChallengeSnapshotList(challengeId)))
        }
      }
    }

  /**
    * Returns a csv export of challenge snapshots.
    *
    * @param challengeId
    */
  def exportChallengeSnapshots(challengeId: Long): Action[AnyContent] = Action.async {
    implicit request =>
      this.sessionManager.authenticatedRequest { implicit user =>
        Result(
          header = ResponseHeader(
            OK,
            Map(
              CONTENT_DISPOSITION -> s"attachment; filename=challenge_${challengeId}_snapshots.csv"
            )
          ),
          body = HttpEntity.Strict(
            ByteString(
              "Snapshot_Time,Snapshot_ID,Challenge_ID,Challenge_Name,Challenge_Status," +
                "Total," + Task.statusMap.values.mkString(",") + "," +
                "High_Total," + Task.statusMap.values.map(v => "High_" + v).mkString(",") + "," +
                "Medium_Total," + Task.statusMap.values
                .map(v => "Medium_" + v)
                .mkString(",") + "," +
                "Low_Total," + Task.statusMap.values.map(v => "Low_" + v).mkString(",") +
                "\n"
            ).concat(ByteString(extractSnapshots(challengeId).mkString("\n"))),
            Some("text/csv; header=present")
          )
        )
      }
  }

  private def extractSnapshots(challengeId: Long): Seq[String] = {
    val snapshots = snapshotManager.getAllChallengeSnapshots(challengeId)

    snapshots.map(snapshot => {
      val priorityActions = snapshot.priorityActions.get

      s"${snapshot.created},${snapshot.id},${snapshot.itemId},${snapshot.name},${snapshot.status.getOrElse("")}," +
        snapshot.actions.get.values.mkString(",") + "," +
        priorityActions(Challenge.PRIORITY_HIGH.toString).values.mkString(",") + "," +
        priorityActions(Challenge.PRIORITY_MEDIUM.toString).values.mkString(",") + "," +
        priorityActions(Challenge.PRIORITY_LOW.toString).values.mkString(",")
    })
  }
}

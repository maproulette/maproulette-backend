/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.mixins

import play.api.libs.json._

import org.maproulette.framework.service.ServiceManager
import org.maproulette.framework.model.{Tag, ClusteredPoint, Task}

import org.maproulette.utils.Utils
import org.maproulette.models.dal.TaskDAL

/**
  * TaskJSONMixin provides a method to insert data into the task ClusteredPoint JSON
  */
trait TaskJSONMixin {
  val serviceManager: ServiceManager
  val taskDAL: TaskDAL

  implicit val pointReviewWrites = ClusteredPoint.pointReviewWrites

  /**
    * Fetches and inserts usernames for 'reviewRequestedBy', 'reviewBy',
    * 'completedBy', 'metaReviewedBy', 'additionalReviewers', 'geometries'
    * and 'tags'
    */
  def insertExtraTaskJSON(
      tasks: List[ClusteredPoint],
      includeGeometries: Boolean = false,
      includeTags: Boolean = false
  ): JsValue = {
    if (tasks.isEmpty) {
      Json.toJson(List[JsValue]())
    } else {
      val mappers = Some(
        this.serviceManager.user
          .retrieveListById(tasks.map(t => t.completedBy.getOrElse(0L)))
          .map(u => u.id -> Json.obj("username" -> u.name, "id" -> u.id))
          .toMap
      )

      val reviewRequesters = Some(
        this.serviceManager.user
          .retrieveListById(tasks.map(t => t.pointReview.reviewRequestedBy.getOrElse(0L)))
          .map(u => u.id -> Json.obj("username" -> u.name, "id" -> u.id))
          .toMap
      )

      val allReviewers = tasks.flatMap(t => {
        List(t.pointReview.reviewedBy.map(r => r)).flatMap(r => r) ++
          t.pointReview.additionalReviewers.getOrElse(List()) ++
          List(t.pointReview.metaReviewedBy.map(r => r)).flatMap(r => r)
      })

      val reviewers = Some(
        this.serviceManager.user
          .retrieveListById(allReviewers.toList)
          .map(u => u.id -> Json.obj("username" -> u.name, "id" -> u.id))
          .toMap
      )

      val taskDetailsMap: Map[Long, Task] =
        includeGeometries match {
          case true =>
            val taskDetails = this.taskDAL.retrieveListById()(tasks.map(t => t.id))
            taskDetails.map(t => t.id -> t).toMap
          case false => null
        }

      val tagsMap: Map[Long, List[Tag]] = includeTags match {
        case true => this.serviceManager.tag.listByTasks(tasks.map(t => t.id))
        case _    => null
      }

      val jsonList = tasks.map { task =>
        var updated         = Json.toJson(task)
        var reviewPointJson = Json.toJson(task.pointReview).as[JsObject]

        if (task.completedBy.getOrElse(0) != 0) {
          val mappersJson = Json.toJson(mappers.get(task.completedBy.get)).as[JsObject]
          updated = Utils.insertIntoJson(updated, "completedBy", mappersJson, true)
        }

        if (task.pointReview.reviewRequestedBy.getOrElse(0) != 0) {
          val reviewRequestersJson =
            Json.toJson(reviewRequesters.get(task.pointReview.reviewRequestedBy.get)).as[JsObject]
          reviewPointJson = Utils
            .insertIntoJson(reviewPointJson, "reviewRequestedBy", reviewRequestersJson, true)
            .as[JsObject]
          updated = Utils.insertIntoJson(updated, "pointReview", reviewPointJson, true)
        }

        if (task.pointReview.reviewedBy.getOrElse(0) != 0) {
          var reviewerJson =
            Json.toJson(reviewers.get(task.pointReview.reviewedBy.get)).as[JsObject]
          reviewPointJson =
            Utils.insertIntoJson(reviewPointJson, "reviewedBy", reviewerJson, true).as[JsObject]
          updated = Utils.insertIntoJson(updated, "pointReview", reviewPointJson, true)
        }

        if (task.pointReview.metaReviewedBy.getOrElse(0) != 0) {
          var reviewerJson =
            Json.toJson(reviewers.get(task.pointReview.metaReviewedBy.get)).as[JsObject]
          reviewPointJson =
            Utils.insertIntoJson(reviewPointJson, "metaReviewedBy", reviewerJson, true).as[JsObject]
          updated = Utils.insertIntoJson(updated, "pointReview", reviewPointJson, true)
        }

        if (task.pointReview.additionalReviewers != None) {
          val additionalReviewerJson = Json
            .toJson(
              Map(
                "additionalReviewers" ->
                  task.pointReview.additionalReviewers.getOrElse(List()).map(r => reviewers.get(r))
              )
            )
            .as[JsObject]
          reviewPointJson = Utils
            .insertIntoJson(
              reviewPointJson,
              "additionalReviewers",
              (additionalReviewerJson \ "additionalReviewers").get,
              true
            )
            .as[JsObject]
          updated = Utils.insertIntoJson(updated, "pointReview", reviewPointJson, true)
        }

        if (includeGeometries) {
          updated =
            Utils.insertIntoJson(updated, "geometries", taskDetailsMap(task.id).geometries, true)
        }

        if (includeTags) {
          if (tagsMap.contains(task.id)) {
            val tagsJson = Json.toJson(tagsMap(task.id))
            updated = Utils.insertIntoJson(updated, "tags", tagsJson, true)
          }
        }

        updated
      }
      Json.toJson(jsonList)
    }
  }
}

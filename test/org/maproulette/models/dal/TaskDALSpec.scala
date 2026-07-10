/*
 * Copyright (C) 2026 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.models.dal

import org.joda.time.DateTime
import org.maproulette.framework.model.Task
import org.maproulette.framework.repository.TaskRepository
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.{JsString, Json}

class TaskDALSpec extends PlaySpec with MockitoSugar {
  val taskDAL: TaskDAL =
    new TaskDAL(null, null, null, null, mock[TaskRepository], null, null, null, null)

  private val busStopFeature = Json.obj(
    "type" -> "Feature",
    "geometry" -> Json
      .obj("type" -> "Point", "coordinates" -> Json.arr(-111.8653810, 40.7123196)),
    "properties" -> Json.obj("name" -> "900 E @ 2709 S", "highway" -> "bus_stop")
  )

  private def taskWithGeometries(name: String, geometries: play.api.libs.json.JsObject): Task =
    Task(-1, name, DateTime.now(), DateTime.now(), 1, Some(""), None, geometries)

  private val featureCollection =
    Json.obj("type" -> "FeatureCollection", "features" -> Json.arr(busStopFeature))

  "looksCooperative" should {
    "return false for a plain FeatureCollection" in {
      taskDAL.looksCooperative(featureCollection) mustEqual false
    }

    "return true when a feature has an embedded cooperativeWork object" in {
      val cooperativeFeature = busStopFeature ++ Json.obj(
        "cooperativeWork" -> Json.obj("meta" -> Json.obj())
      )
      val geometries =
        Json.obj("type" -> "FeatureCollection", "features" -> Json.arr(cooperativeFeature))

      taskDAL.looksCooperative(geometries) mustEqual true
    }

    "return true when a feature property has an embedded maproulette marker" in {
      val cooperativeFeature = busStopFeature ++ Json.obj(
        "properties" -> Json.obj(
          "name"        -> "900 E @ 2709 S",
          "maproulette" -> Json.obj("cooperativeWork" -> Json.obj("meta" -> Json.obj()))
        )
      )
      val geometries =
        Json.obj("type" -> "FeatureCollection", "features" -> Json.arr(cooperativeFeature))

      taskDAL.looksCooperative(geometries) mustEqual true
    }
  }

  "dedupeTasksByName" should {
    "return all tasks unchanged when names are unique" in {
      val tasks = List(
        taskWithGeometries("first", featureCollection),
        taskWithGeometries("second", featureCollection)
      )

      taskDAL.dedupeTasksByName(tasks) mustEqual tasks
    }

    "keep only the last task when names collide, case-insensitively" in {
      val first  = taskWithGeometries("Duplicate", featureCollection)
      val second = taskWithGeometries("duplicate", featureCollection)

      taskDAL.dedupeTasksByName(List(first, second)) mustEqual List(second)
    }

    "preserve the position of the first occurrence" in {
      val firstA  = taskWithGeometries("a", featureCollection)
      val firstB  = taskWithGeometries("b", featureCollection)
      val secondA = taskWithGeometries("a", featureCollection)

      val result = taskDAL.dedupeTasksByName(List(firstA, firstB, secondA))

      result mustEqual List(secondA, firstB)
    }
  }

  "pruneMapRouletteProperties" should {
    "handle a features array built with Json.arr" in {
      // Regression test for #1243: Json.arr is array-backed rather than
      // ArrayBuffer-backed, and the previous implementation cast the mapped
      // sequence to ArrayBuffer, throwing ClassCastException
      val geometries =
        Json.obj("type" -> "FeatureCollection", "features" -> Json.arr(busStopFeature))

      val result = taskDAL.pruneMapRouletteProperties(geometries)

      result.value.size mustEqual 1
      result.value.head mustEqual busStopFeature
    }

    "handle a features array parsed from a JSON string" in {
      val geometries = Json
        .parse(
          s"""{"type": "FeatureCollection", "features": [${Json.stringify(busStopFeature)}]}"""
        )
        .as[play.api.libs.json.JsObject]

      val result = taskDAL.pruneMapRouletteProperties(geometries)

      result.value.size mustEqual 1
      result.value.head mustEqual busStopFeature
    }

    "prune the maproulette object from feature properties" in {
      val cooperativeFeature = busStopFeature ++ Json.obj(
        "properties" -> Json.obj(
          "name"        -> "900 E @ 2709 S",
          "maproulette" -> Json.obj("cooperativeWork" -> Json.obj("meta" -> Json.obj()))
        )
      )
      val geometries =
        Json.obj("type" -> "FeatureCollection", "features" -> Json.arr(cooperativeFeature))

      val result = taskDAL.pruneMapRouletteProperties(geometries)

      result.value.size mustEqual 1
      (result.value.head \ "properties" \ "maproulette").toOption mustEqual None
      (result.value.head \ "properties" \ "name").as[String] mustEqual "900 E @ 2709 S"
    }

    "drop non-object entries from the features array" in {
      val geometries = Json.obj(
        "type"     -> "FeatureCollection",
        "features" -> Json.arr(JsString("not-a-feature"), busStopFeature)
      )

      val result = taskDAL.pruneMapRouletteProperties(geometries)

      result.value.size mustEqual 1
      result.value.head mustEqual busStopFeature
    }

    "return an empty array when there are no features" in {
      val geometries = Json.obj("type" -> "FeatureCollection", "features" -> Json.arr())
      taskDAL.pruneMapRouletteProperties(geometries).value mustBe empty
    }
  }
}

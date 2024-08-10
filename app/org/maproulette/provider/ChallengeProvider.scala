/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.provider

import java.util.UUID
import javax.inject.{Inject, Singleton}
import org.apache.commons.lang3.StringUtils
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.exception.InvalidException
import org.maproulette.framework.model.{Challenge, Task, User}
import org.maproulette.models.dal.{ChallengeDAL, TaskDAL}
import org.maproulette.utils.Utils
import org.slf4j.LoggerFactory
import play.api.db.Database
import play.api.http.Status
import play.api.libs.json._
import play.api.libs.ws.WSClient

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

/**
  * @author cuthbertm
  */
@Singleton
class ChallengeProvider @Inject() (
    challengeDAL: ChallengeDAL,
    taskDAL: TaskDAL,
    config: Config,
    ws: WSClient,
    db: Database
) extends DefaultReads {

  import scala.concurrent.ExecutionContext.Implicits.global

  private val RS = 0x1E.toChar // RS (record separator) control character

  private val logger = LoggerFactory.getLogger(this.getClass)

  def rebuildTasks(user: User, challenge: Challenge, removeUnmatched: Boolean = false): Boolean =
    this.buildTasks(user, challenge, None, removeUnmatched)

  def buildTasks(
      user: User,
      challenge: Challenge,
      json: Option[String] = None,
      removeUnmatched: Boolean = false
  ): Boolean = {
    if (!challenge.creation.overpassQL.getOrElse("").isEmpty) {
      this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_BUILDING), user)(challenge.id)
      Future {
        logger.debug("Creating tasks for overpass query: " + challenge.creation.overpassQL.get)
        if (removeUnmatched) {
          this.challengeDAL.removeIncompleteTasks(user)(challenge.id)
        }

        this.buildOverpassQLTasks(challenge, user)
      }
      true
    } else {
      val usingLocalJson = json match {
        case Some(value) if StringUtils.isNotEmpty(value) =>
          val splitJson = value.split("\n")
          this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_BUILDING), user)(
            challenge.id
          )
          Future {
            if (removeUnmatched) {
              this.challengeDAL.removeIncompleteTasks(user)(challenge.id)
            }

            if (isLineByLineGeoJson(splitJson)) {
              val failedLines = splitJson.zipWithIndex.flatMap(line => {
                try {
                  val jsonData = Json.parse(normalizeRFC7464Sequence(line._1))
                  this.createNewTask(
                    user,
                    taskNameFromJsValue(jsonData, challenge),
                    challenge,
                    jsonData
                  )
                  None
                } catch {
                  case e: Exception =>
                    Some(line._2)
                }
              })
              if (failedLines.nonEmpty) {
                this.challengeDAL.update(
                  Json.obj(
                    "status"        -> Challenge.STATUS_PARTIALLY_LOADED,
                    "statusMessage" -> s"GeoJSON lines [${failedLines.mkString(",")}] failed to parse"
                  ),
                  user
                )(challenge.id)
              } else {
                this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_READY), user)(
                  challenge.id
                )
                this.challengeDAL.markTasksRefreshed()(challenge.id)
              }
            } else {
              this.createTasksFromJson(user, challenge, value)
            }

            //we need to reapply task priority rules since task locations were updated
            Future {
              this.challengeDAL.updateTaskPriorities(user)(challenge.id)
            }
          }
          true
        case _ => false
      }
      if (!usingLocalJson) {
        // lastly try remote
        challenge.creation.remoteGeoJson match {
          case Some(url) if StringUtils.isNotEmpty(url) =>
            this.buildTasksFromRemoteJson(url, 1, challenge, user, removeUnmatched)
            true
          case _ => false
        }
      } else {
        false
      }
    }
  }

  /**
    * Create a single task from some provided GeoJSON. Unlike when creating multiple tasks, this
    * will not create the tasks in a future
    *
    * @param user      The user executing the task
    * @param challenge The challenge to create the task under
    * @param json      The geojson for the task
    * @return
    */
  def createTaskFromJson(user: User, challenge: Challenge, json: String): Option[Task] = {
    try {
      this
        .createTasksFromFeatures(
          user,
          challenge,
          Json.parse(this.normalizeRFC7464Sequence(json)),
          true
        )
        .headOption
    } catch {
      case e: Exception =>
        this.challengeDAL.update(
          Json.obj("status" -> Challenge.STATUS_FAILED, "statusMessage" -> e.getMessage),
          user
        )(challenge.id)
        throw e
    }

  }

  /**
    * Create multiple tasks from some provided GeoJSON. It will execute the creation in a future
    *
    * @param user      The user executing the task
    * @param challenge The challenge to create the task under
    * @param json      The geojson for the task
    * @return
    */
  def createTasksFromJson(
      user: User,
      challenge: Challenge,
      json: String,
      currentTaskCount: Int = 0
  ): List[Task] = {
    try {
      this.createTasksFromFeatures(user, challenge, Json.parse(json), false, currentTaskCount)
    } catch {
      case e: Exception =>
        this.challengeDAL.update(
          Json.obj("status" -> Challenge.STATUS_FAILED, "statusMessage" -> e.getMessage),
          user
        )(challenge.id)
        throw e
    }
  }

  /**
    * Builds all the tasks from the remote json, it will check for multiple files from the geojson.
    *
    * @param filePrefix The url or file prefix of the remote geojson
    * @param fileNumber The current file number
    * @param challenge  The challenge to build the tasks in
    * @param user       The user creating the tasks
    */
  def buildTasksFromRemoteJson(
      filePrefix: String,
      fileNumber: Int,
      challenge: Challenge,
      user: User,
      removeUnmatched: Boolean
  ): Unit = {
    this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_BUILDING), user)(challenge.id)
    if (removeUnmatched) {
      this.challengeDAL.removeIncompleteTasks(user)(challenge.id)
    }

    val url     = filePrefix.replace("{x}", fileNumber.toString)
    val seqJSON = filePrefix.contains("{x}")
    this.ws
      .url(url)
      .withRequestTimeout(this.config.getOSMQLProvider.requestTimeout)
      .get() onComplete {
      case Success(resp) =>
        logger.debug("Creating tasks from remote GeoJSON file")
        try {
          val splitJson = resp.body.split("\n")

          if (this.isLineByLineGeoJson(splitJson)) {
            val splitJsonLength = resp.body.split("\n").length;
            if (splitJsonLength > config.maxTasksPerChallenge) {
              logger.warn(
                "Cannot add {} tasks to challengeId='{}' because it would exceed the maximum tasks per challenge (max={})",
                splitJsonLength,
                challenge.id,
                config.maxTasksPerChallenge
              )

              val statusMessage =
                s"Tasks were not accepted. Your feature list size must be under ${config.maxTasksPerChallenge}."
              this.challengeDAL.update(
                Json.obj("status" -> Challenge.STATUS_FAILED, "statusMessage" -> statusMessage),
                user
              )(challenge.id)
            } else {
              splitJson.foreach { line =>
                val jsonData = Json.parse(normalizeRFC7464Sequence(line))
                this.createNewTask(
                  user,
                  taskNameFromJsValue(jsonData, challenge),
                  challenge,
                  jsonData
                )
              }
              this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_READY), user)(
                challenge.id
              )

              this.challengeDAL.markTasksRefreshed()(challenge.id)
            }
          } else {
            this.createTasksFromFeatures(user, challenge, Json.parse(resp.body))
          }
        } catch {
          case e: Exception =>
            this.challengeDAL.update(
              Json.obj("status" -> Challenge.STATUS_FAILED, "statusMessage" -> e.getMessage),
              user
            )(challenge.id)
        }
        if (seqJSON) {
          this.buildTasksFromRemoteJson(filePrefix, fileNumber + 1, challenge, user, false)
        } else {
          this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_READY), user)(challenge.id)
          this.challengeDAL.markTasksRefreshed()(challenge.id)

          //we need to reapply task priority rules since task locations were updated
          Future {
            this.challengeDAL.updateTaskPriorities(user)(challenge.id)
          }
        }
      case Failure(f) =>
        if (fileNumber > 1) {
          // todo need to figure out if actual failure or if not finding the next file
          this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_READY), user)(challenge.id)
        } else {
          this.challengeDAL.update(
            Json.obj("status" -> Challenge.STATUS_FAILED, "StatusMessage" -> f.getMessage),
            user
          )(challenge.id)
        }
    }
  }

  /**
    * Extracts the OSM id from the given JsValue based on the `osmIdProperty`
    * challenge field. Returns None if either the challenge has not specified an
    * osmIdProperty or if the JsValue contains neither a field nor property with
    * the specified name. If the JsValue represents a collection of features,
    * each feature will be checked and the first OSM id found returned
    */
  private def featureOSMId(value: JsValue, challenge: Challenge): Option[String] = {
    challenge.extra.osmIdProperty match {
      case Some(osmIdName) =>
        // Whether `value` represents multiple features or just one, process as List
        val features = (value \ "features").asOpt[List[JsValue]].getOrElse(List(value))
        features
          .map(feature =>
            // First look for a matching field on the feature itself. If not found, then
            // look at the feature's properties
            (feature \ osmIdName).asOpt[String] match {
              case Some(matchingIdField) => Some(matchingIdField)
              case None =>
                (feature \ "properties").asOpt[JsObject] match {
                  case Some(properties) =>
                    (properties \ osmIdName).asOpt[String] match {
                      case Some(matchingIdProperty) => Some(matchingIdProperty)
                      case None                     => None // feature doesn't have the id property
                    }
                  case None => None // feature doesn't have any properties
                }
            }
          )
          .find(_.isDefined) match { // first feature that has a match
          case Some(featureWithId) => featureWithId
          case None                => None // No features found with matching id field or property
        }
      case None => None // No osmIdProperty defined on challenge
    }
  }

  /**
    * Extracts an appropriate task name from the given JsValue, looking for any
    * of multiple suitable id fields, or finally defaulting to a random UUID if
    * no acceptable field is found
    */
  private def taskNameFromJsValue(value: JsValue, challenge: Challenge): String = {
    // Use field/property specified by challenge, if available. Otherwise look
    // for commonly used id fields/properties
    if (!challenge.extra.osmIdProperty.getOrElse("").isEmpty) {
      return featureOSMId(value, challenge) match {
        case Some(osmId) => osmId
        case None        => UUID.randomUUID().toString // task does not contain id property
      }
    }

    val featureList = (value \ "features").asOpt[List[JsValue]]
    if (featureList.isDefined) {
      taskNameFromJsValue(featureList.get.head, challenge) // Base name on first feature
    } else {
      val nameKeys = List.apply("id", "@id", "osmid", "osm_id", "name")
      nameKeys.collectFirst {
        case x if (value \ x).asOpt[JsValue].isDefined =>
          // Support both string and numeric ids. If it's a string, use it.
          // Otherwise convert the value to a string
          (value \ x).asOpt[String] match {
            case Some(stringValue) => stringValue
            case None              => (value \ x).asOpt[JsValue].get.toString
          }
      } match {
        case Some(n) => n
        case None =>
          (value \ "properties").asOpt[JsObject] match {
            // See if we can find an id field on the feature properties
            case Some(properties) => taskNameFromJsValue(properties, challenge)
            case None             =>
              // if we still don't find anything, create a UUID for it. The
              // caveat to this is that if you upload the same file again, it
              // will create duplicate tasks
              UUID.randomUUID().toString
          }
      }
    }
  }

  /**
    * Creates task(s) from a geojson file
    *
    * @param user     The user executing the request
    * @param parent   The challenge where the task should be created
    * @param jsonData The json data for the task
    * @param single   if true, then will use the json provided and create a single task
    */
  private def createTasksFromFeatures(
      user: User,
      parent: Challenge,
      jsonData: JsValue,
      single: Boolean = false,
      currentTaskCount: Int = 0
  ): List[Task] = {
    this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_BUILDING), user)(parent.id)
    val featureList       = (jsonData \ "features").as[List[JsValue]]
    val featureListLength = (jsonData \ "features").as[List[JsValue]].length
    try {
      if (featureListLength + currentTaskCount > config.maxTasksPerChallenge) {
        logger.warn(
          "Cannot add {} tasks to challengeId='{}' because it would exceed the maximum tasks per challenge (count={} max={})",
          featureListLength,
          parent.id,
          currentTaskCount,
          config.maxTasksPerChallenge
        )

        if (currentTaskCount == 0) {
          val statusMessage =
            s"Tasks were not accepted. Your feature list size must be under ${config.maxTasksPerChallenge}."
          this.challengeDAL.update(
            Json.obj("status" -> Challenge.STATUS_FAILED, "statusMessage" -> statusMessage),
            user
          )(parent.id)
          List.empty
        } else {
          throw new InvalidException(
            s"Total challenge tasks would exceed cap of ${config.maxTasksPerChallenge}"
          )
        }
      } else {
        val createdTasks = featureList.flatMap { value =>
          if (!single) {
            this.createNewTask(
              user,
              taskNameFromJsValue(value, parent),
              parent,
              (value \ "geometry").as[JsObject],
              Utils.getProperties(value, "properties")
            )
          } else {
            None
          }
        }

        this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_READY), user)(parent.id)
        this.challengeDAL.markTasksRefreshed()(parent.id)
        if (single) {
          this.createNewTask(user, taskNameFromJsValue(jsonData, parent), parent, jsonData) match {
            case Some(t) => List(t)
            case None    => List.empty
          }
        } else {
          logger.debug(s"${featureList.size} tasks created from json file.")
          createdTasks
        }
      }
    } catch {
      case e: Exception =>
        this.challengeDAL.update(
          Json.obj("status" -> Challenge.STATUS_FAILED, "statusMessage" -> e.getMessage),
          user
        )(parent.id)
        logger.error(s"${featureList.size} tasks failed to be created from json file.", e)
        List.empty
    }
  }

  /**
    * Based on the supplied overpass query this will generate the tasks for the challenge
    *
    * @param challenge The challenge to create the tasks under
    * @param user      The user executing the query
    */
  private def buildOverpassQLTasks(challenge: Challenge, user: User) = {
    this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_BUILDING), user)(challenge.id)
    challenge.creation.overpassQL match {
      case Some(ql) if StringUtils.isNotEmpty(ql) =>
        // run the query and then create the tasks
        val osmQLProvider  = config.getOSMQLProvider
        val timeoutPattern = "\\[timeout:([\\d]*)\\]".r
        val timeout = timeoutPattern.findAllIn(ql).matchData.toList.headOption match {
          case Some(m) => Duration(m.group(1).toInt, "seconds")
          case None    => osmQLProvider.requestTimeout
        }

        val modifiedQuery = rewriteQuery(ql)
        logger.info(modifiedQuery)

        val jsonFuture =
          this.ws.url(osmQLProvider.providerURL).withRequestTimeout(timeout).post(modifiedQuery)
        jsonFuture onComplete {
          case Success(result) =>
            if (result.status == Status.OK) {
              val contentType = result.header("Content-Type")

              // check that the Overpass API returned JSON
              if (contentType.isDefined && contentType != Some("application/json")) {
                this.challengeDAL.update(
                  Json.obj(
                    "status"        -> Challenge.STATUS_FAILED,
                    "statusMessage" -> s"""
                    |Overpass API returned response with Content-Type: ${contentType.get}
                    |
                    |MapRoulette requires OverpassQL queries to return JSON.
                    |
                    |If your query contained [out:xml] or [out:csv], replace it with [out:json] and try again.
                    """.stripMargin
                  ),
                  user
                )(challenge.id)
                throw new InvalidException(
                  s"Overpass API returned unexpected Content-Type: ${contentType.get}"
                )
              }

              this.db.withTransaction { implicit c =>
                var partial          = false
                val payload          = result.json
                var targetTypeFailed = false

                // parse the results. Overpass has its own format and is not geojson
                val elements = (payload \ "elements").as[List[JsValue]]
                try {
                  elements.foreach { element =>
                    // Verify target type if we are given one.
                    challenge.creation.overpassTargetType match {
                      case Some(targetType) if StringUtils.isNotEmpty(targetType) =>
                        (element \ "type").asOpt[String] match {
                          case Some("way") =>
                            if (targetType != "way") {
                              targetTypeFailed = true
                              throw new InvalidException(
                                "Element type 'way' does not match target type of '" + targetType + "'"
                              )
                            }
                          case Some("relation") =>
                            if (targetType != "relation") {
                              targetTypeFailed = true
                              throw new InvalidException(
                                "Element type 'relation' does not match target type of '" + targetType + "'"
                              )
                            }
                          case Some("node") =>
                            if (targetType != "node") {
                              targetTypeFailed = true
                              throw new InvalidException(
                                "Element type 'node' does not match target type of '" + targetType + "'"
                              )
                            }
                          case x =>
                            targetTypeFailed = true
                            throw new InvalidException(
                              "Element type " + x + " does not match target type of '" + targetType + "'"
                            )
                        }
                      case _ => // do not validate
                    }

                    try {
                      val geometry = (element \ "center").asOpt[JsObject] match {
                        case Some(center) =>
                          Some(
                            Json.obj(
                              "type" -> "Point",
                              "coordinates" -> List(
                                (center \ "lon").as[Double],
                                (center \ "lat").as[Double]
                              )
                            )
                          )
                        case None =>
                          (element \ "type").asOpt[String] match {
                            case Some("way") =>
                              // TODO: ways do not have a "geometry" property, instead they have a list of "nodes"
                              // referencing other elements in the array. So this code does not do the job.
                              val points = (element \ "geometry").as[List[JsValue]].map { geom =>
                                List((geom \ "lon").as[Double], (geom \ "lat").as[Double])
                              }
                              Some(Json.obj("type" -> "LineString", "coordinates" -> points))
                            case Some("relation") =>
                              // Function to recursively extract geometries from relations
                              def extractGeometries(member: JsValue): Option[JsObject] = {
                                (member \ "type").asOpt[String] match {
                                  case Some("way") =>
                                    val points = (member \ "geometry").as[List[JsValue]].map {
                                      geom =>
                                        List((geom \ "lon").as[Double], (geom \ "lat").as[Double])
                                    }
                                    Some(Json.obj("type" -> "LineString", "coordinates" -> points))

                                  case Some("node") =>
                                    Some(
                                      Json.obj(
                                        "type" -> "Point",
                                        "coordinates" -> List(
                                          (member \ "lon").as[Double],
                                          (member \ "lat").as[Double]
                                        )
                                      )
                                    )

                                  case Some("relation") =>
                                    // If it's another relation, recursively extract geometries from it
                                    val geometries = (member \ "members").as[List[JsValue]].map {
                                      member =>
                                        extractGeometries(member)
                                    }
                                    val geometryCollection = Json.obj(
                                      "type"       -> "GeometryCollection",
                                      "geometries" -> geometries
                                    )

                                    Some(geometryCollection)

                                  case _ =>
                                    None
                                }
                              }

                              // Extract geometries from each member of the relation
                              val geometries = (element \ "members").as[List[JsValue]].map {
                                member =>
                                  extractGeometries(member)
                              }

                              // Create a GeometryCollection
                              val geometryCollection = Json.obj(
                                "type"       -> "GeometryCollection",
                                "geometries" -> geometries
                              )

                              Some(geometryCollection)

                            case Some("node") =>
                              Some(
                                Json.obj(
                                  "type" -> "Point",
                                  "coordinates" -> List(
                                    (element \ "lon").as[Double],
                                    (element \ "lat").as[Double]
                                  )
                                )
                              )
                            case _ => None
                          }
                      }

                      geometry match {
                        case Some(geom) =>
                          this.createNewTask(
                            user,
                            s"${(element \ "id").as[Long]}",
                            challenge,
                            geom,
                            Utils.getProperties(element, "tags")
                          )
                        case None => None
                      }
                    } catch {
                      case e: Exception =>
                        partial = true
                        logger.error(e.getMessage, e)
                    }
                  }
                  partial match {
                    case true =>
                      this.challengeDAL.update(
                        Json.obj("status" -> Challenge.STATUS_PARTIALLY_LOADED),
                        user
                      )(challenge.id)
                    case false =>
                      this.challengeDAL.update(Json.obj("status" -> Challenge.STATUS_READY), user)(
                        challenge.id
                      )
                      this.challengeDAL.markTasksRefreshed(true)(challenge.id)
                      // If no tasks were created by this overpass query or all tasks are
                      // fixed, then we need to update the status to finished.
                      this.challengeDAL.updateFinishedStatus(true, user = user)(challenge.id)
                  }
                } catch {
                  case e: Exception =>
                    this.challengeDAL.update(
                      Json.obj(
                        "status"        -> Challenge.STATUS_FAILED,
                        "statusMessage" -> s"${e.getMessage}"
                      ),
                      user
                    )(challenge.id)
                    throw e
                }
              }
            } else {
              this.challengeDAL.update(
                Json.obj(
                  "status"        -> Challenge.STATUS_FAILED,
                  "statusMessage" -> s"${result.statusText}:${result.body}"
                ),
                user
              )(challenge.id)
              throw new InvalidException(s"${result.statusText}: ${result.body}")
            }
          case Failure(f) =>
            this.challengeDAL.update(
              Json.obj("status" -> Challenge.STATUS_FAILED, "statusMessage" -> f.getMessage),
              user
            )(challenge.id)
            throw f
        }
      case None =>
        this.challengeDAL.update(
          Json
            .obj("status" -> Challenge.STATUS_FAILED, "statusMessage" -> "overpass query not set"),
          user
        )(challenge.id)
    }
  }

  private def createNewTask(
      user: User,
      name: String,
      parent: Challenge,
      json: JsValue
  ): Option[Task] = {
    this._createNewTask(
      user,
      name,
      parent,
      Task(-1, name, DateTime.now(), DateTime.now(), parent.id, Some(""), None, json.toString)
    )
  }

  private def createNewTask(
      user: User,
      name: String,
      parent: Challenge,
      geometry: JsObject,
      properties: JsValue
  ): Option[Task] = {
    val newTask = Task(
      -1,
      name,
      DateTime.now(),
      DateTime.now(),
      parent.id,
      Some(""),
      None,
      Json
        .obj(
          "type" -> "FeatureCollection",
          "features" -> Json.arr(
            Json.obj(
              "id"         -> name,
              "type"       -> "Feature",
              "geometry"   -> geometry,
              "properties" -> properties
            )
          )
        )
        .toString
    )
    this._createNewTask(user, name, parent, newTask)
  }

  private def _createNewTask(
      user: User,
      name: String,
      parent: Challenge,
      newTask: Task
  ): Option[Task] = {
    try {
      this.taskDAL.mergeUpdate(newTask, user)(newTask.id)
    } catch {
      // this task could fail on unique key violation, we need to ignore them
      case e: Exception =>
        logger.error(e.getMessage)
        None
    }
  }

  /**
    * rewrite the user-provided OverpassQL query, adding output format and timeout settings if they are missing.
    *
    * @param query the input query as a string
    * @return a modified query string
    */
  private def rewriteQuery(query: String): String = {
    val osmQLProvider = config.getOSMQLProvider
    val timeout       = osmQLProvider.requestTimeout.toSeconds

    val split = query.trim.split("\n", 2)
    var (firstLine, restOfQuery) = {
      split.length match {
        case 0 => ("", "")
        case 1 => (split(0).trim, "")
        case _ => (split(0).trim, split(1))
      }
    }

    if (firstLine.startsWith("[") && firstLine.endsWith(";")) {
      // first line looks like OverpassQL settings statement
      // https://wiki.openstreetmap.org/wiki/Overpass_API/Overpass_QL#Settings

      if (!firstLine.contains("[out:")) {
        // if no output format was given by the user, add [out:json]
        firstLine = "[out:json]" + firstLine
      }
      if (!firstLine.contains("[timeout:")) {
        // if no timeout was specified by the user, add one
        firstLine = s"[timeout:${timeout}]" + firstLine
      }

      s"${firstLine}\n${restOfQuery}"
    } else {
      // first line doesn't look like OverpassQL settings, so assume no settings were provided,
      // and prepend the required ones.
      // NOTE: this branch will incorrectly be reached if the query started with a comment,
      // or if the settings were split across multiple lines (OverpassQL allows both)
      s"[out:json][timeout:${timeout}];\n$query"
    }

    // TODO: execute regex matching against {{data:string}}, {{geocodeId:name}}, {{geocodeArea:name}}, {{geocodeBbox:name}}, {{geocodeCoords:name}}
    // see https://wiki.openstreetmap.org/wiki/Overpass_turbo/Extended_Overpass_Queries
  }

  /**
    * Determine if this represents line-by-line GeoJSON. It either must be
    * RFC 7464 compliant or consist of complete JSON objects on 2 or more
    * separate lines
    */
  private def isLineByLineGeoJson(splitJson: Array[String]): Boolean = {
    splitJson.length match {
      case 0 => false
      case 1 => this.isRFC7464Sequence(splitJson(0))
      case _ =>
        this.isRFC7464Sequence(splitJson(0)) ||
          (isCompleteJSON(splitJson(0)) && isCompleteJSON(splitJson(1)))
    }
  }

  /**
    * Basic check for a [RFC 7464](https://tools.ietf.org/html/rfc7464) sequence,
    * basically ensuring the line starts with an RS control character. This does
    * not attempt to validate any subsequent data, e.g. to ensure it is correct
    * JSON as required by the RFC
    */
  private def isRFC7464Sequence(line: String): Boolean =
    line.length > 1 && line(0) == RS

  /**
    * Very rudimentary check to see if the string looks like complete json data
    */
  private def isCompleteJSON(json: String): Boolean =
    json.startsWith("{") && json.endsWith("}")

  /**
    * Normalize a RFC 7464 sequence, i.e. strip out any record separators at the
    * beginning of the string. This is safe to call even on strings containing
    * ordinary JSON data
    */
  private def normalizeRFC7464Sequence(line: String): String =
    line.replaceAll(s"^${RS}+", "")
}

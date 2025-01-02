/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.provider

import java.net.URLEncoder

import javax.inject.{Inject, Singleton}
import org.apache.commons.lang3.StringUtils
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.framework.model._
import org.maproulette.framework.psql.TransactionManager
import org.maproulette.framework.service.ProjectService
import org.maproulette.models.dal.{ChallengeDAL, TaskDAL}
import org.maproulette.utils.Utils
import org.slf4j.LoggerFactory
import play.api.db.Database
import play.api.http.Status
import play.api.libs.ws.WSClient

import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

case class KeepRightError(id: Int, name: String, tags: List[String])

case class KeepRightBox(
    country: String,
    iso: String,
    longMin: Double,
    latMin: Double,
    longMax: Double,
    latMax: Double,
    wrapped: Boolean
)

case class KeepRightTask(
    id: Long,
    errorType: Int,
    name: String,
    description: String,
    lat: Double,
    lon: Double
)

/**
  * This integrates KeepRight with MapRoulette. It will generate 1 Challenge per country per KeepRight Error Check
  * inside of a special KeepRight Project that is managed by the super user. It will update on a schedule from
  * the job scheduler.
  *
  * All KeepRight challenges are tagged "KeepRight".
  *
  * @author mcuthbert
  */
@Singleton
class KeepRightProvider @Inject() (
    projectService: ProjectService,
    challengeDAL: ChallengeDAL,
    taskDAL: TaskDAL,
    config: Config,
    wsClient: WSClient,
    override val db: Database
) extends TransactionManager {

  import scala.concurrent.ExecutionContext.Implicits.global

  lazy val rootProjectId = this.projectService.retrieveByName(KeepRightProvider.NAME) match {
    case Some(p) => p.id
    case None =>
      this.projectService
        .create(
          Project(
            id = -1,
            owner = User.DEFAULT_SUPER_USER_ID,
            name = KeepRightProvider.NAME,
            created = DateTime.now(),
            modified = DateTime.now(),
            enabled = true,
            displayName = Some(KeepRightProvider.DISPLAY_NAME)
          ),
          User.superUser
        )
        .id
  }
  val errorList: List[KeepRightError] = Utils.tryOptional(() =>
    this.config.config.underlying.getConfigList(KeepRightProvider.KEY_ERRORS)
  ) match {
    case Some(cl) =>
      val errors = cl.asScala map { item =>
        KeepRightError(
          item.getInt(KeepRightProvider.KEY_ID),
          item.getString(KeepRightProvider.KEY_NAME),
          item.getString(KeepRightProvider.KEY_TAGS).split(",").toList
        )
      }
      errors.toList
    case None => List[KeepRightError]()
  }
  val boundingBoxes: List[KeepRightBox] = Utils.tryOptional(() =>
    config.config.underlying.getStringList(KeepRightProvider.KEY_BOUNDING)
  ) match {
    case Some(bb) =>
      val boxes = bb.asScala map { item =>
        val csv = item.split(",")
        KeepRightBox(
          csv(KeepRightProvider.COLUMN_COUNTRY),
          csv(KeepRightProvider.COLUMN_ISO),
          csv(KeepRightProvider.COLUMN_LONGMIN).toDouble,
          csv(KeepRightProvider.COLUMN_LATMIN).toDouble,
          csv(KeepRightProvider.COLUMN_LONGMAX).toDouble,
          csv(KeepRightProvider.COLUMN_LATMAX).toDouble,
          csv.size < KeepRightProvider.COLUMN_WRAPPED + 1 || !csv(KeepRightProvider.COLUMN_WRAPPED).isEmpty
        )
      }
      boxes.toList
    case None => List[KeepRightBox]()
  }
  private val logger = LoggerFactory.getLogger(this.getClass)

  def getError(id: Int): Option[KeepRightError] = errorList.find(_.id == id)

  def getCountryBounding(country: String): Option[KeepRightBox] =
    boundingBoxes.find(box =>
      StringUtils.equalsIgnoreCase(box.country, country) || StringUtils
        .equalsIgnoreCase(box.iso, country)
    )

  // This will create a challenge for each KeepRight Check and each country
  def integrate(checkIDs: List[Int] = List.empty, bounding: KeepRightBox): Future[Boolean] = {
    val p = Promise[Boolean]()
    val cidList = URLEncoder.encode(
      this.errorList
        .filter(cid => checkIDs.isEmpty || checkIDs.contains(cid.id))
        .map(_.id)
        .mkString(","),
      "UTF-8"
    )
    val timeout = Duration(
      this.config.config
        .getOptional[String](KeepRightProvider.KEY_TIMEOUT)
        .getOrElse(KeepRightProvider.DEFAULT_TIMEOUT)
    )
    val url =
      s"""https://keepright.at/export.php?format=gpx&ch=$cidList&
         |left=${bounding.latMin}&bottom=${bounding.longMin}&
         |right=${bounding.latMax}&top=${bounding.longMax}""".stripMargin
    logger.debug(s"Executing KeepRight export request for URL: $url")
    wsClient.url(url).withRequestTimeout(timeout).get() onComplete {
      case Success(result) =>
        if (result.status == Status.OK) {
          try {
            if (!StringUtils.equalsIgnoreCase(result.body, "no errors found")) {
              val wpts = result.xml \ KeepRightProvider.KEY_WPT
              if (wpts.nonEmpty) {
                val wptItem = wpts
                  .map(task => {
                    KeepRightTask(
                      (task \ KeepRightProvider.KEY_WPT_EXT \ KeepRightProvider.KEY_WPT_ID).text.toLong,
                      (task \ KeepRightProvider.KEY_WPT_EXT \ KeepRightProvider.KEY_WPT_ERROR_TYPE).text.toInt,
                      (task \ KeepRightProvider.KEY_WPT_NAME).text,
                      (task \ KeepRightProvider.KEY_WPT_DESC).text,
                      (task \ s"@${KeepRightProvider.KEY_WPT_LAT}").text.toDouble,
                      (task \ s"@${KeepRightProvider.KEY_WPT_LON}").text.toDouble
                    )
                  })
                  .groupBy(_.errorType)
                // for each error type create a challenge
                wptItem.map(errors => {
                  logger.info(
                    s"Creating KeepRight Challenge ${KeepRightProvider.challengeName(errors._1, bounding.iso)}"
                  )
                  val challenge = this.createChallenge(errors._1, bounding.iso)
                  // add all the tasks
                  this.withMRTransaction {
                    implicit c =>
                      val totalTasks = errors._2.map(kpError => {
                        val geometry =
                          s"""
                    {"type":"FeatureCollection",
                      "features":[{
                        "geometry":{"type":"Point","coordinates":[${kpError.lat}, ${kpError.lon}]},
                        "type":"Feature",
                        "properties":{}
                      }]
                    }
                  """
                        this.taskDAL.mergeUpdate(
                          Task(
                            -1,
                            s"${kpError.id}",
                            DateTime.now(),
                            DateTime.now(),
                            challenge.id,
                            Some(kpError.description),
                            Some(geometry),
                            geometry
                          ),
                          User.superUser
                        )(-1)
                      })
                      logger.info(
                        s"$totalTasks created for KeepRight challenge ${challenge.name} [${challenge.id}]"
                      )
                      totalTasks
                  }
                })
              }
            }
            p success true
          } catch {
            case e: Exception =>
              logger.warn(s"Request for KeepRight challenge failed: ${e.getMessage}")
              p success false
          }
        } else {
          logger.warn(
            s"Failed to parse KeepRight tasks for challenge, invalid response status ${result.status}"
          )
          p success false
        }
      case Failure(f) =>
        logger.warn(s"Failed to get response from server: ${f.getMessage}")
        p success false
    }
    p.future
  }

  def createChallenge(errorId: Int, country: String): Challenge = {
    val challengeName = KeepRightProvider.challengeName(errorId, country)
    this.challengeDAL.retrieveByName(challengeName) match {
      case Some(c) => c
      case None =>
        this.challengeDAL.insert(
          Challenge(
            -1,
            challengeName,
            DateTime.now(),
            DateTime.now(),
            None,
            false,
            false,
            None,
            ChallengeGeneral(User.superUser.id, rootProjectId, ""),
            ChallengeCreation(),
            ChallengePriority(),
            ChallengeExtra()
          ),
          User.superUser
        )
    }
  }
}

object KeepRightProvider {
  val NAME          = "KeepRight"
  val DISPLAY_NAME  = "Keep Right"
  val KEY_KEEPRIGHT = "keepright"
  val KEY_ENABLED   = s"$KEY_KEEPRIGHT.enabled"
  val KEY_SLIDING   = s"$KEY_KEEPRIGHT.sliding"
  val KEY_TIMEOUT   = s"$KEY_KEEPRIGHT.timeout"
  val KEY_ERRORS    = s"$KEY_KEEPRIGHT.errors"
  val KEY_BOUNDING  = s"$KEY_KEEPRIGHT.bounding"
  val KEY_ID        = "id"
  val KEY_NAME      = "name"
  val KEY_TAGS      = "tags"
  // WPT
  val KEY_WPT                = "wpt"
  val KEY_WPT_NAME           = "name"
  val KEY_WPT_DESC           = "desc"
  val KEY_WPT_EXT            = "extensions"
  val KEY_WPT_ERROR_TYPE     = "error_type"
  val KEY_WPT_LAT            = "lat"
  val KEY_WPT_LON            = "lon"
  val KEY_WPT_ID             = "id"
  val DEFAULT_TIMEOUT        = "120s"
  val DEFAULT_SLIDING        = 5
  private val COLUMN_COUNTRY = 0
  private val COLUMN_ISO     = 1
  private val COLUMN_LONGMIN = 2
  private val COLUMN_LATMIN  = 3
  private val COLUMN_LONGMAX = 4
  private val COLUMN_LATMAX  = 5
  private val COLUMN_WRAPPED = 6

  def challengeName(errorId: Int, country: String): String = s"${country}_$errorId"
}

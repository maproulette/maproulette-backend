/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.data

import anorm.SqlParser._
import anorm._
import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.framework.model.{User, Identifiable}
import org.maproulette.models.utils.DALHelper
import org.slf4j.LoggerFactory
import play.api.Application
import play.api.db.Database
import play.api.libs.json.{Json, Reads, Writes}
import play.api.libs.json.JodaWrites._
import play.api.libs.json.JodaReads._

import scala.collection.mutable.ListBuffer

/**
  * This file handles retrieving the action summaries from the database. This primarily revolves
  * around how task status' have changed, but would also include creation, updating and deletion
  * of any of the objects in the system. Is primarily used for statistics.
  *
  * @author cuthbertm
  */
case class ActionItem(
    id: Long = (-1),
    created: Option[DateTime] = None,
    osmUserId: Option[Long] = None,
    typeId: Option[Int] = None,
    parentId: Option[Long] = None,
    parentName: Option[String] = None,
    itemId: Option[Long] = None,
    action: Option[Int] = None,
    status: Option[Int] = None,
    extra: Option[String] = None
) extends Identifiable

/**
  * @param columns      The columns that you want returned limited to UserId = 0, typeId = 1, itemId = 2, action = 3 and status = 4
  * @param timeframe    You can specify whether you want the data returned per Hour = 0, Day = 1, Week = 2, Month = 3 and Year = 4
  * @param osmUserLimit Filter the query based on a set of user id's
  * @param typeLimit    Filter the query based on the type of item Project = 0, Challenge = 1, Task = 2
  * @param itemLimit    Filter the query based on a set of item id's
  * @param statusLimit  Filter the query based on status Created = 0, Fixed = 1, FalsePositive = 2, Skipped = 3, Deleted = 4
  * @param actionLimit  Filter based on the specific actions
  * @param startTime    Filter where created time for action is greater than the provided start time
  * @param endTime      Filter where created time for action is less than the provided end time
  */
case class ActionLimits(
    columns: List[Int] = List.empty,
    timeframe: Option[Int] = None,
    osmUserLimit: List[Long] = List.empty,
    typeLimit: List[Int] = List.empty,
    itemLimit: List[Long] = List.empty,
    statusLimit: List[Int] = List.empty,
    actionLimit: List[Int] = List.empty,
    startTime: Option[DateTime] = None,
    endTime: Option[DateTime] = None
)

@Singleton
class ActionManager @Inject() (config: Config, db: Database)(implicit application: Application)
    extends DALHelper {

  // Columns
  val userId = 0
  val typeId = 1
  val itemId = 2
  val action = 3
  val status = 4
  // timeframe
  val HOUR  = 0
  val DAY   = 1
  val WEEK  = 2
  val MONTH = 3
  val YEAR  = 4

  implicit val actionItemWrites: Writes[ActionItem] = Json.writes[ActionItem]
  implicit val actionItemReads: Reads[ActionItem]   = Json.reads[ActionItem]

  private val logger = LoggerFactory.getLogger(this.getClass)

  /**
    * Special row parser that gets the parent information of the action item as well.
    *
    */
  implicit val parser: RowParser[ActionItem] = {
    get[Long]("actions.id") ~
      get[Option[DateTime]]("created") ~
      get[Option[Long]]("actions.osm_user_id") ~
      get[Option[Int]]("actions.type_id") ~
      get[Option[Long]]("actions.item_id") ~
      get[Option[Int]]("actions.action") ~
      get[Option[Int]]("actions.status") ~
      get[Option[String]]("actions.extra") ~
      get[Option[Long]]("parent_id") ~
      get[Option[String]]("parent_name") map {
      case id ~ created ~ osmUserId ~ typeId ~ itemId ~ action ~ status ~ extra ~ parentId ~ parentName => {
        new ActionItem(
          id,
          created,
          osmUserId,
          typeId,
          parentId,
          parentName,
          itemId,
          action,
          status,
          extra
        )
      }
    }
  }

  /**
    * A anorm row parser for the actions table
    */
  implicit val baseParser: RowParser[ActionItem] = {
    get[Long]("actions.id") ~
      get[Option[DateTime]]("created") ~
      get[Option[Long]]("actions.osm_user_id") ~
      get[Option[Int]]("actions.type_id") ~
      get[Option[Long]]("actions.item_id") ~
      get[Option[Int]]("actions.action") ~
      get[Option[Int]]("actions.status") ~
      get[Option[String]]("actions.extra") map {
      case id ~ created ~ osmUserId ~ typeId ~ itemId ~ action ~ status ~ extra => {
        new ActionItem(
          id = id,
          created = created,
          osmUserId = osmUserId,
          typeId = typeId,
          itemId = itemId,
          action = action,
          status = status,
          extra = extra
        )
      }
    }
  }

  /**
    * Creates an action in the database
    *
    * @param user   The user executing the request
    * @param item   The item that the action was performed on
    * @param action The action that was performed
    * @param extra  And extra information that you want to send along with the creation of the action
    * @return true if created
    */
  def setAction(
      user: Option[User] = None,
      item: Item with ItemType,
      action: ActionType,
      extra: String
  ): Option[ActionItem] = {
    if (action.getLevel > config.actionLevel) {
      logger.trace("Action not logged, action level higher than threshold in configuration.")
      None
    } else {
      db.withTransaction { implicit c =>
        val statusId = action match {
          case t: TaskStatusSet => t.status
          case _                => 0
        }
        val userId = user match {
          case Some(u) => Some(u.osmProfile.id)
          case None    => None
        }
        val query =
          """
               INSERT INTO actions (osm_user_id, type_id, item_id, action, status, extra)
               VALUES ({userId}, {typeId}, {itemId}, {actionId}, {statusId}, {extra}) RETURNING *"""
        SQL(query)
          .on(
            Symbol("userId")   -> userId,
            Symbol("typeId")   -> item.typeId,
            Symbol("itemId")   -> item.itemId,
            Symbol("actionId") -> action.getId,
            Symbol("statusId") -> statusId,
            Symbol("extra")    -> extra
          )
          .as(baseParser.*)
          .headOption
      }
    }
  }

  /**
    * Helper function for getActivity list that gets the recent activity for a specific user, and
    * will only retrieve activity where status was set for tasks.
    *
    * @param osmUserIds The OSM ids of users to get activity for
    * @param limit  limit the number of returned items
    * @param offset paging, starting at 0
    * @return
    */
  def getRecentActivity(
      osmUserIds: List[Long],
      limit: Int = Config.DEFAULT_LIST_SIZE,
      offset: Int = 0
  ): List[ActionItem] =
    getActivityList(
      limit,
      offset,
      ActionLimits(
        osmUserLimit = osmUserIds,
        actionLimit =
          List(Actions.ACTION_TYPE_TASK_STATUS_SET, Actions.ACTION_TYPE_QUESTION_ANSWERED)
      )
    )

  /**
    * Gets the activity list from the actions table
    *
    * @param limit        The number of activities to list
    * @param offset       the paging starting at 0
    * @param actionLimits Not all action limits are used. Here only typeQuery, itemQuery and actionLimit are used
    * @return
    */
  def getActivityList(
      limit: Int = Config.DEFAULT_LIST_SIZE,
      offset: Int = 0,
      actionLimits: ActionLimits
  ): List[ActionItem] = {
    val parameters  = new ListBuffer[NamedParameter]()
    val whereClause = new StringBuilder()
    if (actionLimits.typeLimit.nonEmpty) {
      parameters += (Symbol("typeIds") -> actionLimits.typeLimit)
      whereClause ++= "type_id IN ({typeIds})"
    }

    if (actionLimits.itemLimit.nonEmpty) {
      parameters += (Symbol("itemIds") -> actionLimits.itemLimit)
      if (whereClause.nonEmpty) {
        whereClause ++= " AND "
      }
      whereClause ++= "item_id IN ({itemIds})"
    }

    if (actionLimits.actionLimit.nonEmpty) {
      parameters += (Symbol("actions") -> actionLimits.actionLimit)
      if (whereClause.nonEmpty) {
        whereClause ++= " AND "
      }
      whereClause ++= "action IN ({actions})"
    }

    if (actionLimits.osmUserLimit.nonEmpty) {
      parameters += (Symbol("userIds") -> actionLimits.osmUserLimit)
      if (whereClause.nonEmpty) {
        whereClause ++= " AND "
      }
      whereClause ++= "osm_user_id IN ({userIds})"
    }

    val query =
      s"""
         |SELECT *, a[1]::Int AS parent_id, a[2] AS parent_name FROM (
         |  SELECT *,
         |    CASE type_id
         |      WHEN 1 | 4 THEN (SELECT REGEXP_SPLIT_TO_ARRAY(c.parent_id || ',' || REPLACE(p.name, ',', ''), ',') FROM challenges c
         |                        INNER JOIN projects p ON p.id = c.parent_id WHERE c.id = item_id)
         |      WHEN 2 THEN (SELECT REGEXP_SPLIT_TO_ARRAY(t.parent_id || ',' || REPLACE(c.name, ',', ''), ',') FROM tasks t
         |                        INNER JOIN challenges c ON c.id = t.parent_id WHERE t.id = item_id)
         |      ELSE NULL
         |    END AS a
         |  FROM actions
         |  ${if (whereClause.nonEmpty) {
           s"WHERE ${whereClause.toString}"
         }}
         |  ORDER BY CREATED DESC
         |  LIMIT ${sqlLimit(limit)} OFFSET $offset
         |) AS core
       """.stripMargin
    db.withConnection { implicit c =>
      sqlWithParameters(query, parameters).as(parser.*)
    }
  }
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.models.dal

import java.sql.Connection

import anorm.SqlParser._
import anorm._
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.cache.CacheManager
import org.maproulette.exception.NotFoundException
import org.maproulette.framework.model.User
import org.maproulette.framework.psql.TransactionManager
import org.maproulette.models.utils.DALHelper
import org.maproulette.models.{BaseObject, Lock}
import org.maproulette.permissions.Permission
import org.maproulette.utils.{Readers, Writers}
import org.slf4j.LoggerFactory
import play.api.db.Database
import play.api.libs.json.JsValue

/**
  * Base Data access layer that handles all the deletes, retrievals and listing. Insert and Update
  * functions are required to be implemented by the classes that mix this trait in.
  *
  * @author cuthbertm
  */
trait BaseDAL[Key, T <: BaseObject[Key]]
    extends DALHelper
    with TransactionManager
    with Readers
    with Writers {
  // Service that handles all the permissions for the objects
  val permission: Permission
  // Manager to handle all the caching for this particular layer
  val cacheManager: CacheManager[Key, T]
  // The name of the table in the database
  val tableName: String
  // The object parser specific for this data access layer
  val parser: RowParser[T]
  // where caching is turned on or off by default.
  implicit val caching: Boolean = true
  // this allows for columns used in the retrieve functions to be optionally built
  val retrieveColumns: String = "*"
  // Database that should be injected in any implementing classes
  val db: Database
  protected val logger = LoggerFactory.getLogger(this.getClass)

  def clearCaches: Unit = cacheManager.clearCaches

  implicit val lockedParser: RowParser[Lock] = {
    get[Option[DateTime]]("locked.locked_time") ~
      get[Option[Int]]("locked.item_type") ~
      get[Option[Long]]("locked.item_id") ~
      get[Option[Long]]("locked.user_id") ~
      get[Option[Long]]("locked.changeset_id") map {
      case locked_time ~ itemType ~ itemId ~ userId ~ changesetId =>
        locked_time match {
          case Some(d) =>
            Lock(locked_time, itemType.get, itemId.get, userId.get, changesetId.getOrElse(-1))
          case None => Lock.emptyLock
        }
    }
  }

  def getDatabase: Database = this.db

  /**
    * Insert function that must be implemented by the class that mixes in this trait
    *
    * @param element The element that you are inserting to the database
    * @param user    The user executing the task
    * @return The object that was inserted into the database. This will include the newly created id
    */
  def insert(element: T, user: User)(implicit c: Option[Connection] = None): T

  /**
    * Update function that must be implemented by the class that mixes in this trait
    *
    * @param updates The updates in json form
    * @param user    The user executing the task
    * @param id      The id of the object that you are updating
    * @return An optional object, it will return None if no object found with a matching id that was supplied
    */
  def update(updates: JsValue, user: User)(
      implicit id: Key,
      c: Option[Connection] = None
  ): Option[T]

  /**
    * This is a merge update function that will update the function if it exists otherwise it will
    * insert a new item. By default unless the implementing class overrides this function, the
    * mergeUpdate will simply attempt an insert
    *
    * @param element The element that needs to be inserted or updated. Although it could be updated,
    *                it requires the element itself in case it needs to be inserted
    * @param user    The user that is executing the function
    * @param id      The id of the element that is being updated/inserted
    * @param c       A connection to execute against
    * @return
    */
  def mergeUpdate(
      element: T,
      user: User
  )(implicit id: Key, c: Option[Connection] = None): Option[T] =
    Some(this.insert(element, user))

  /**
    * Update function that must be implemented by the class that mixes in this trait. This update
    * function updates based on the name instead of the id. This is the default update function,
    * the downside of this function is that it will first retrieve the item from cache and then
    * attempt to update it. For a more efficient method, the implementing class would need to
    * override this and then execute the update function filtering on the name and parentId.
    *
    * @param updates The updates in json form
    * @param user    The user executing the update
    * @param name    The name of the object that you are updating
    * @return An optional object, it will return None if no object found with a matching id that was supplied
    */
  def updateByName(
      updates: JsValue,
      user: User
  )(implicit name: String, parentId: Long = (-1), c: Option[Connection] = None): Option[T] =
    this.cacheManager.updateNameCache(String => retrieveByName) match {
      case Some(objID) => this.update(updates, user)(objID)
      case None        => None
    }

  /**
    * Retrieves the object based on the name, this function is somewhat weak as there could be
    * multiple objects with the same name. The database only restricts the same name in combination
    * with a parent. So this will just return the first one it finds. With caching, so if it finds
    * the object in the cache it will return that object without checking the database, otherwise
    * will hit the database directly.
    *
    * @param name The name you are looking up by
    * @return The object that you are looking up, None if not found
    */
  def retrieveByName(
      implicit name: String,
      parentId: Long = (-1),
      c: Option[Connection] = None
  ): Option[T] = {
    this.cacheManager.withOptionCaching { () =>
      this.withMRConnection { implicit c =>
        val query =
          s"SELECT ${this.retrieveColumns} FROM ${this.tableName} WHERE name = {name} ${this.parentFilter(parentId)}"
        SQL(query).on(Symbol("name") -> name).as(this.parser.*).headOption
      }
    }
  }

  /**
    * Deletes an item from the database
    *
    * @param id        The id that you want to delete
    * @param user      The user executing the task
    * @param immediate Ignored for the base function, only used by the ParentDAL which override it and uses it
    * @return Count of deleted row(s)
    */
  def delete(id: Key, user: User, immediate: Boolean = false)(
      implicit c: Option[Connection] = None
  ): T = {
    implicit val key = id
    val deletedItem = this.cacheManager.withDeletingCache(Long => retrieveById) {
      implicit deletedItem =>
        this.permission.hasObjectAdminAccess(deletedItem.asInstanceOf[BaseObject[Long]], user)
        this.withMRTransaction { implicit c =>
          val query = s"DELETE FROM ${this.tableName} WHERE id = {id}"
          SQL(query)
            .on(Symbol("id") -> ToParameterValue.apply[Key](p = keyToStatement).apply(id))
            .executeUpdate()
          Some(deletedItem)
        }
    }

    deletedItem match {
      case Some(item) => item
      case None       => throw new NotFoundException(s"No object with id $id found to delete")
    }
  }

  /**
    * A basic retrieval of the object based on the id. With caching, so if it finds
    * the object in the cache it will return that object without checking the database, otherwise
    * will hit the database directly.
    *
    * @param id The id of the object to be retrieved
    * @return The object, None if not found
    */
  def retrieveById(implicit id: Key, c: Option[Connection] = None): Option[T] = {
    this.cacheManager.withCaching { () =>
      this.withMRConnection { implicit c =>
        val query = s"SELECT $retrieveColumns FROM ${this.tableName} WHERE id = {id}"
        SQL(query)
          .on(Symbol("id") -> ToParameterValue.apply[Key](p = keyToStatement).apply(id))
          .as(this.parser.singleOpt)
      }
    }
  }

  /**
    * Retrieves a list of objects from the supplied list of ids. Will check for any objects currently
    * in the cache and those that aren't will be retrieved from the database
    *
    * @param limit  The limit on the number of objects returned. This is not entirely useful as a limit
    *               could be set simply by how many ids you supplied in the list, but possibly useful
    *               for paging
    * @param offset For paging, ie. the page number starting at 0
    * @param ids    The list of ids to be retrieved
    * @return A list of objects, empty list if none found
    */
  def retrieveListById(
      limit: Int = -1,
      offset: Int = 0
  )(implicit ids: List[Key], c: Option[Connection] = None): List[T] = {
    if (ids.isEmpty) {
      List.empty
    } else {
      this.cacheManager.withIDListCaching { implicit uncachedIDs =>
        this.withMRConnection { implicit c =>
          val query =
            s"""SELECT ${this.retrieveColumns} FROM ${this.tableName}
                          WHERE id IN ({inString})
                          LIMIT ${this.sqlLimit(limit)} OFFSET {offset}"""
          SQL(query)
            .on(
              Symbol("inString") -> ToParameterValue
                .apply[List[Key]](s = keyToSQL, p = keyToStatement)
                .apply(uncachedIDs),
              Symbol("offset") -> offset
            )
            .as(this.parser.*)
        }
      }
    }
  }

  def keyToSQL[Key]: ToSql[Key] = {
    new ToSql[Key] {
      override def fragment(value: Key): (String, Int) =
        value match {
          case v: List[Long @unchecked] => ToSql.listToSql[Long].fragment(v)
          case _ =>
            throw new Exception(
              "Invalid use of function keyToSQL, should only be used for List[Long]] type"
            )
        }
    }
  }

  /**
    * Same as retrieveListById except no paging built in. Will check for any objects currently in the
    * cache and those that aren't will be retrieved from the database.
    *
    * @param names The names to be retrieved
    * @return List of objects, empty list if none found
    */
  def retrieveListByName(
      implicit names: List[String],
      parentId: Long = -1,
      c: Option[Connection] = None
  ): List[T] = {
    if (names.isEmpty) {
      List.empty
    } else {
      this.cacheManager.withNameListCaching { implicit uncachedNames =>
        this.withMRConnection { implicit c =>
          val query =
            s"SELECT ${this.retrieveColumns} FROM ${this.tableName} WHERE name IN ({inString}) ${this
              .parentFilter(parentId)}"
          SQL(query)
            .on(Symbol("inString") -> ToParameterValue.apply[List[String]].apply(uncachedNames))
            .as(this.parser.*)
        }
      }
    }
  }

  /**
    * This function will hit the database every time, so could be costly, and might be worthwhile
    * to use the cache. Only problem with using the cache is that it some what requires all the
    * tags to be available, and you don't necessarily want to load all the tags into memory. Although
    * maybe you do.
    *
    * @param prefix The prefix of the "name" field in the database
    * @param limit  Limit the number of results to be returned
    * @return A list of tags that contain the supplied prefix
    */
  def retrieveListByPrefix(
      prefix: String,
      limit: Int = Config.DEFAULT_LIST_SIZE,
      offset: Int = 0,
      onlyEnabled: Boolean = false,
      orderColumn: String = "id",
      orderDirection: String = "ASC"
  )(implicit parentId: Long = -1, c: Option[Connection] = None): List[T] =
    this.find(s"$prefix%", limit, offset, onlyEnabled, orderColumn, orderDirection)

  /**
    * Same database concerns as retrieveListByPrefix. This find function will search the "name"
    * field for any references of the search string. So will be wrapped by %%, eg. LIKE %test%
    *
    * @param searchString The string to search for within the name field
    * @param limit        Limit the number of results to be returned
    * @param offset       For paging, ie. the page number starting at 0
    * @return A list of tags that contain the supplied prefix
    */
  def find(
      searchString: String,
      limit: Int = Config.DEFAULT_LIST_SIZE,
      offset: Int = 0,
      onlyEnabled: Boolean = false,
      orderColumn: String = "id",
      orderDirection: String = "ASC"
  )(implicit parentId: Long = -1, c: Option[Connection] = None): List[T] = {
    this.withMRConnection { implicit c =>
      val query =
        s"""SELECT ${this.retrieveColumns} FROM ${this.tableName}
                      WHERE ${this.searchField("name")(None)} ${this.enabled(onlyEnabled)}
                      ${this.parentFilter(parentId)}
                      ${this.order(
          orderColumn = Some(orderColumn),
          orderDirection = orderDirection,
          nameFix = true
        )}
                      LIMIT ${this.sqlLimit(limit)} OFFSET {offset}"""
      SQL(query).on(Symbol("ss") -> searchString, Symbol("offset") -> offset).as(this.parser.*)
    }
  }

  /**
    * List all of the objects for the type
    *
    * @param limit          limit the number of objects returns, default 10
    * @param offset         The paging offset when limiting returned objects
    * @param onlyEnabled    Whether to returned only enabled objects, otherwise all will be returned
    * @param searchString   Search string to filter the returned objects by
    * @param orderColumn    How to order the objects
    * @param orderDirection Which direction to order the objects. ASC or DESC
    * @param parentId       The parent object id if required
    * @param c              The implicit connection to use for the quer
    * @return A list of objects
    */
  def list(
      limit: Int = Config.DEFAULT_LIST_SIZE,
      offset: Int = 0,
      onlyEnabled: Boolean = false,
      searchString: String = "",
      orderColumn: String = "id",
      orderDirection: String = "ASC"
  )(implicit parentId: Long = -1, c: Option[Connection] = None): List[T] = {
    implicit val ids = List.empty
    this.cacheManager.withIDListCaching { implicit uncachedIDs =>
      this.withMRConnection { implicit c =>
        val query =
          s"""SELECT ${this.retrieveColumns} FROM ${this.tableName}
                        WHERE ${this.searchField("name")(None)}
                        ${this.enabled(onlyEnabled)} ${this.parentFilter(parentId)}
                        ${this.order(
            orderColumn = Some(orderColumn),
            orderDirection = orderDirection,
            nameFix = true,
            ignoreCase = (orderColumn == "name" || orderColumn == "display_name")
          )}
                        LIMIT ${this.sqlLimit(limit)} OFFSET {offset}"""
        SQL(query)
          .on(
            Symbol("ss")     -> this.search(searchString),
            Symbol("offset") -> ToParameterValue.apply[Int].apply(offset)
          )
          .as(this.parser.*)
      }
    }
  }

  /**
    * Get count of tasks in a challenge, needed in baseDal for batchUpload services
    *
    * @param challengeId
    */
  def getTaskCountBase(challengeId: Long)(implicit c: Option[Connection] = None): Int = {
    this.withMRConnection { implicit c =>
      val countParser = int("count") map {
        case count => count
      }
      SQL"""SELECT COUNT(parent_id) as count FROM tasks WHERE parent_id = ${challengeId}"""
        .as(countParser.*)
        .head
    }
  }
}

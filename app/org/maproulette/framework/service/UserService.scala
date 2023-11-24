/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import java.util.UUID
import javax.inject.{Inject, Singleton}
import org.apache.commons.lang3.StringUtils
import org.joda.time.DateTime
import org.locationtech.jts.geom.{Coordinate, GeometryFactory}
import org.locationtech.jts.io.WKTWriter
import org.maproulette.Config
import org.maproulette.cache.CacheManager
import org.maproulette.data.{GroupType, UserType}
import org.maproulette.exception.{InvalidException, NotFoundException}
import org.maproulette.framework.model._
import org.maproulette.framework.psql._
import org.maproulette.framework.psql.filter._
import org.maproulette.framework.repository.{UserRepository, UserSavedObjectsRepository}
import org.maproulette.models.dal.TaskDAL
import org.maproulette.permissions.Permission
import org.maproulette.session.SearchParameters
import org.maproulette.utils.{Crypto, Utils, Writers}
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsString, JsValue, Json}

/**
  * @author mcuthbert
  */
@Singleton
class UserService @Inject() (
    repository: UserRepository,
    savedObjectsRepository: UserSavedObjectsRepository,
    serviceManager: ServiceManager,
    grantService: GrantService,
    projectService: ProjectService,
    taskDAL: TaskDAL,
    config: Config,
    permission: Permission,
    crypto: Crypto
) extends ServiceMixin[User]
    with Writers {
  private val logger = LoggerFactory.getLogger(this.getClass)

  // The cache manager for the users
  val cacheManager = new CacheManager[Long, User](config, Config.CACHE_ID_USERS)
  val superUsers   = scala.collection.mutable.Set[Long]()

  // On class initialization (called when Play initializes), seed the super user from the existing database entries
  seedSuperUserIds()

  /**
    * Check if a given maproulette user id is a superuser
    * @param maprouletteUserId the id of the user to check
    * @return true if the user is a superuser, false otherwise
    */
  def isSuperUser(maprouletteUserId: Long): Boolean = {
    superUsers.contains(maprouletteUserId)
  }

  def promoteUserToSuperUser(user: User, grantor: User): Boolean = {
    if (isSuperUser(user.id)) {
      logger.info(
        s"MapRoulette uid=${user.id} (osm_id=${user.osmProfile.id}) is already a superuser, skipping role promotion"
      )
      return true
    }

    logger.warn(s"Adding superuser role to uid=${user.id} (osm_id=${user.osmProfile.id})")
    val grantName =
      s"Grant superuser role on uid=${user.id} (osm_id=${user.osmProfile.id}), requested by uid=${grantor.id}"
    val superUserGrant =
      new Grant(-1, grantName, Grantee.user(user.id), Grant.ROLE_SUPER_USER, GrantTarget.project(0))

    serviceManager.grant.createGrant(superUserGrant, grantor) match {
      case Some(grant) =>
        superUsers += grant.grantee.granteeId
        clearCache(user.id)
        true
      case _ =>
        // The grant.createGrant function will throw an exception if it fails, so we should never get here. But just in case...
        logger.warn(
          s"Failed to add superuser role to uid=${user.id} requested by uid=${grantor.id}"
        )
        false
    }
  }

  def demoteSuperUserToUser(user: User): Boolean = {
    if (!isSuperUser(user.id)) {
      logger.info(
        s"MapRoulette uid=${user.id} (osm_id=${user.osmProfile.id}) is not a superuser, skipping role demotion"
      )
      return true
    }

    logger.warn(s"Removing superuser role for uid=${user.id} (osm_id=${user.osmProfile.id})")
    if (serviceManager.grant.deleteSuperUserFromDatabase(user.id)) {
      superUsers -= user.id
      clearCache(user.id)
      true
    } else {
      logger.warn(
        s"Failed to remove superuser role for uid=${user.id} (osm_id=${user.osmProfile.id})"
      )
      false
    }
  }

  def promoteSuperUsersInConfig(): Unit = {
    // Look at config.superAccounts and ensure that all of those users are superusers. The below List will contain
    // both the promoted users and the users that were already superusers.
    val superusersFromConfig: List[User] = config.superAccounts
      .filter(_ != "*")
      .flatMap { osmId =>
        retrieveByOSMId(osmId.toLong) match {
          case Some(it) => Some(it)
          case None =>
            logger.warn(
              s"osm_id=${osmId.toLong} has not logged in to this MapRoulette instance! Superuser promotion is not possible as there is no associated MapRoulette User"
            )
            None
        }
      }
      .flatMap { user =>
        if (promoteUserToSuperUser(user, User.superUser)) Some(user) else None
      }
  }

  private def seedSuperUserIds(): Unit = {
    superUsers ++= grantService.getSuperUserIdsFromDatabase
  }

  /**
    * Find the User based on an API key, the API key is unique in the database.
    *
    * @param apiKey The APIKey to match against
    * @param id     The id of the user
    * @return The matched user, None if User not found
    */
  def retrieveByAPIKey(id: Long, apiKey: String, user: User): Option[User] =
    this.cacheManager.withOptionCaching { () =>
      val idFilterGroup = FilterGroup(
        List(BaseParameter(User.FIELD_ID, id), BaseParameter(User.FIELD_OSM_ID, id)),
        OR()
      )
      val apiFilterGroup = FilterGroup(List(BaseParameter(User.FIELD_API_KEY, apiKey)))
      val query          = Query(Filter(List(idFilterGroup, apiFilterGroup)))

      this.repository.query(query).headOption match {
        case Some(u) =>
          this.permission.hasObjectReadAccess(u, user)
          Some(u)
        case None => None
      }
    }

  def retrieveByUsernameAndAPIKey(username: String, apiKey: String): Option[User] =
    this.cacheManager.withOptionCaching { () =>
      this.repository
        .query(
          Query.simple(
            List(
              BaseParameter(User.FIELD_NAME, username),
              BaseParameter(User.FIELD_API_KEY, apiKey)
            )
          )
        )
        .headOption
    }

  /**
    * Helper function to allow users to be retrieve by just the OSM username, for security we only
    * allow super users access to this function
    *
    * @param username The username that is being searched for
    * @param user     The user making the request
    * @return An optional user object, if none then not found
    */
  def retrieveByOSMUsername(username: String, user: User): Option[User] =
    this.cacheManager.withOptionCaching { () =>
      // only execute this kind of request if the user is a super user
      if (permission.isSuperUser(user)) {
        this
          .query(
            Query
              .simple(
                List(BaseParameter(User.FIELD_NAME, username, Operator.ILIKE))
              ),
            user
          )
          .headOption
      } else {
        throw new IllegalAccessException(
          "Only Superuser allowed to look up users by just OSM username"
        )
      }
    }

  /**
    * Fetches a list of users, for security we only
    * allow super users access to this function
    *
    * @param user     The user making the request
    * @return An optional user object, if none then not found
    */
  def extendedFind(user: User): List[User] =
    // only execute this kind of request if the user is a super user
    if (permission.isSuperUser(user)) {
      this.query(
        Query.simple(
          List(BaseParameter(User.FIELD_ID, -999, Operator.NE))
        ),
        user
      )
    } else {
      throw new IllegalAccessException(
        "Only Superusers are allowed to use this service"
      )
    }

  /**
    * Allow users to search for other users by OSM username.
    *
    * @param username The username or username fragment to search for.
    * @param paging   The maximum number of results to retrieve.
    * @param params   Search Parameters to help narrow results.
    *                 Only taskId is currently supported and will search
    *                 for users who have commented or changed the status of the task.
    * @return A (possibly empty) list of UserSearchResult objects.
    */
  def searchByOSMUsername(
      username: String,
      paging: Paging = Paging(),
      params: SearchParameters = SearchParameters()
  ): List[User] = {
    var query =
      Query.simple(
        List(
          BaseParameter(User.FIELD_NAME, SQLUtils.search(username), Operator.ILIKE)
        ),
        paging = paging,
        order = Order > (User.FIELD_NAME)
      )

    this.repository.query(
      params.taskParams.taskId match {
        case Some(taskId) =>
          query.addFilterGroup(
            FilterGroup(
              List(
                SubQueryFilter(
                  User.FIELD_OSM_ID,
                  Query.simple(
                    List(
                      BaseParameter("task_id", params.taskParams.taskId.getOrElse(-1))
                    ),
                    "SELECT osm_user_id from status_actions"
                  ),
                  operator = Operator.IN
                ),
                SubQueryFilter(
                  User.FIELD_OSM_ID,
                  Query.simple(
                    List(
                      BaseParameter("task_id", params.taskParams.taskId.getOrElse(-1))
                    ),
                    "SELECT osm_id from task_comments"
                  ),
                  operator = Operator.IN
                )
              ),
              OR()
            )
          )
        case None => query
      }
    )
  }

  /**
    * Match the user based on the token and id for the user.
    *
    * @param id           The id of the user
    * @param requestToken The osm oauth2 token
    * @return The matched user, None if User not found
    */
  def matchByRequestToken(id: Long, requestToken: String, user: User): Option[User] = {
    val requestedUser = this.cacheManager.withCaching { () =>
      this
        .query(
          Query.simple(
            List(
              FilterParameter.conditional(User.FIELD_ID, id, includeOnlyIfTrue = id > 0),
              BaseParameter(User.FIELD_OAUTH_TOKEN, requestToken)
            )
          ),
          user
        )
        .headOption
    }(id = id)
    requestedUser match {
      case Some(u) =>
        // double check that the token still matches, in case it came from the cache
        if (StringUtils.equals(u.osmProfile.requestToken, requestToken)) {
          this.permission.hasObjectReadAccess(u, user)
          Some(u)
        } else {
          None
        }
      case None => None
    }
  }

  /**
    * "Upsert" function that will insert a new user into the database, if the user already exists in
    * the database it will simply update the user with new information. A user is considered to exist
    * in the database if the id or osm_id is found in the users table. During an insert any grants
    * for the user will be ignored, to update grants or add/remove grants, the specific functions
    * for that must be used. Or the update method which has a specific mechanism for updating
    * grants
    *
    * @param user The user to update
    * @return None if failed to update or create.
    */
  def create(item: User, user: User): User = {
    this.cacheManager.withOptionCaching { () =>
      this.permission.hasObjectAdminAccess(item, user)
      val newAPIKey = crypto.encrypt(this.generateAPIKey)
      val ewkt = new WKTWriter().write(
        new GeometryFactory().createPoint(
          new Coordinate(
            item.osmProfile.homeLocation.latitude,
            item.osmProfile.homeLocation.longitude
          )
        )
      )
      Some(this.repository.upsert(item, newAPIKey, ewkt))
    }.get
  }

  /**
    * Generates a new API key for the user
    *
    * @param apiKeyUser The user that is requesting that their key be updated.
    * @return An optional variable that will contain the updated user if successful
    */
  def generateAPIKey(apiKeyUser: User, user: User): Option[User] = {
    this.permission.hasAdminAccess(UserType(), user)(apiKeyUser.id)
    this.cacheManager
      .withUpdatingCache(this.retrieve) { implicit cachedItem =>
        val newAPIKey = crypto.encrypt(this.generateAPIKey)
        Some(this.repository.updateAPIKey(apiKeyUser.id, newAPIKey))
      }(id = apiKeyUser.id)
  }

  private def generateAPIKey: String = UUID.randomUUID().toString

  /**
    * Retrieves all the objects based on the search criteria
    *
    * @param query The query to match against to retrieve the objects
    * @return The list of objects
    */
  override def query(query: Query): List[User] = ???

  /**
    * This is a specialized update that is accessed via the API that allows users to update only the
    * fields that are available for update. This is so that there is no accidental update of OSM username
    * or anything retrieved from the OSM API.
    *
    * The function will simply recreate a JSON object with only the allowed fields. Any APIKey's must
    * be updated separately
    *
    * @param settings   The user settings that have been pulled from the request object
    * @param properties Any extra properties that a client wishes to store alongside the user object
    * @param user       The user making the update request
    * @param id         The id of the user being updated
    * @return An optional user, if user with supplied ID not found, then will return empty optional
    */
  def managedUpdate(
      id: Long,
      settings: UserSettings,
      properties: Option[JsValue],
      user: User
  ): Option[User] = {
    val updateBody = Utils.insertIntoJson(Json.parse("{}"), "settings", Json.toJson(settings))
    this.update(id, properties match {
      case Some(p) => Utils.insertIntoJson(updateBody, "properties", JsString(p.toString()))
      case None    => updateBody
    }, user)
  }

  /**
    * Only certain values are allowed to be updated for the user. Namely apiKey, displayName,
    * description, avatarURL, token and theme.
    *
    * @param value The json object containing the fields to update
    * @param id    The id of the user to update
    * @return The user that was updated, None if no user was found with the id
    */
  def update(id: Long, value: JsValue, user: User): Option[User] = {
    this.cacheManager.withUpdatingCache(this.retrieve) { implicit cachedItem =>
      this.permission.hasObjectAdminAccess(cachedItem, user)

      val displayName = (value \ "osmProfile" \ "displayName")
        .asOpt[String]
        .getOrElse(cachedItem.osmProfile.displayName)
      val description = (value \ "osmProfile" \ "description")
        .asOpt[String]
        .getOrElse(cachedItem.osmProfile.description)
      val avatarURL = (value \ "osmProfile" \ "avatarURL")
        .asOpt[String]
        .getOrElse(cachedItem.osmProfile.avatarURL)
      val token = (value \ "osmProfile" \ "token")
        .asOpt[String]
        .getOrElse(cachedItem.osmProfile.requestToken)
      // todo: allow to insert in WKT, WKB or latitude/longitude
      val latitude = (value \ "osmProfile" \ "homeLocation" \ "latitude")
        .asOpt[Double]
        .getOrElse(cachedItem.osmProfile.homeLocation.latitude)
      val longitude = (value \ "osmProfile" \ "homeLocation" \ "longitude")
        .asOpt[Double]
        .getOrElse(cachedItem.osmProfile.homeLocation.longitude)
      val ewkt = new WKTWriter()
        .write(new GeometryFactory().createPoint(new Coordinate(latitude, longitude)))
      val defaultEditor = (value \ "settings" \ "defaultEditor")
        .asOpt[Int]
        .getOrElse(cachedItem.settings.defaultEditor.getOrElse(-1))
      val defaultBasemap = (value \ "settings" \ "defaultBasemap")
        .asOpt[Int]
        .getOrElse(cachedItem.settings.defaultBasemap.getOrElse(-1))
      val defaultBasemapId = (value \ "settings" \ "defaultBasemapId")
        .asOpt[String]
        .getOrElse(cachedItem.settings.defaultBasemapId.getOrElse(""))
      val locale = (value \ "settings" \ "locale")
        .asOpt[String]
        .getOrElse(cachedItem.settings.locale.getOrElse("en"))
      val email = (value \ "settings" \ "email")
        .asOpt[String]
        .getOrElse(cachedItem.settings.email.getOrElse(""))
      val emailOptIn = (value \ "settings" \ "emailOptIn")
        .asOpt[Boolean]
        .getOrElse(cachedItem.settings.emailOptIn.getOrElse(false))
      val leaderboardOptOut = (value \ "settings" \ "leaderboardOptOut")
        .asOpt[Boolean]
        .getOrElse(cachedItem.settings.leaderboardOptOut.getOrElse(false))
      var needsReview = (value \ "settings" \ "needsReview")
        .asOpt[Int]
        .getOrElse(cachedItem.settings.needsReview.getOrElse(config.defaultNeedsReview))
      val isReviewer = (value \ "settings" \ "isReviewer")
        .asOpt[Boolean]
        .getOrElse(cachedItem.settings.isReviewer.getOrElse(false))
      val allowFollowing = (value \ "settings" \ "allowFollowing")
        .asOpt[Boolean]
        .getOrElse(cachedItem.settings.allowFollowing.getOrElse(true))
      val seeTagFixSuggestions = (value \ "settings" \ "seeTagFixSuggestions")
        .asOpt[Boolean]
        .getOrElse(cachedItem.settings.seeTagFixSuggestions.getOrElse(true))
      val theme = (value \ "settings" \ "theme")
        .asOpt[Int]
        .getOrElse(cachedItem.settings.theme.getOrElse(-1))
      val properties =
        (value \ "properties").asOpt[String].getOrElse(cachedItem.properties.getOrElse("{}"))

      val customBasemaps = (value \ "settings" \ "customBasemaps").asOpt[List[CustomBasemap]]

      // If this user always requires a review, then they are not allowed to change it (except super users)
      if (user.settings.needsReview.getOrElse(0) == User.REVIEW_MANDATORY) {
        if (!permission.isSuperUser(user)) {
          needsReview = User.REVIEW_MANDATORY
        }
      }

      val updated = Some(
        this.repository.update(
          User(
            id,
            DateTime.now(),
            DateTime.now(),
            OSMProfile(
              -1,
              displayName,
              description,
              avatarURL,
              null,
              DateTime.now(),
              token
            ),
            settings = UserSettings(
              Some(defaultEditor),
              Some(defaultBasemap),
              Some(defaultBasemapId),
              Some(locale),
              Some(email),
              Some(emailOptIn),
              Some(leaderboardOptOut),
              Some(needsReview),
              Some(isReviewer),
              Some(allowFollowing),
              Some(theme),
              customBasemaps,
              Some(seeTagFixSuggestions)
            ),
            properties = Some(properties)
          ),
          ewkt
        )
      )

      // If the user is no longer allowing following, clear out any existing
      // followers
      if (!allowFollowing && cachedItem.settings.allowFollowing.getOrElse(true)) {
        this.serviceManager.follow.clearFollowers(updated.get, User.superUser)
      }

      updated
    }(id = id)
  }

  /**
    * Deletes a user from the database based on a specific user id
    *
    * @param id The user to delete
    * @return The rows that were deleted
    */
  def delete(id: Long, user: User): Boolean = {
    this.permission.hasSuperAccess(user)
    retrieve(id) match {
      case Some(u) => this.grantService.deleteGrantsTo(Grantee.user(u.id), user)
      case None    => //no user, so can just ignore
    }
    this.cacheManager.withCacheIDDeletion { () =>
      this.repository.delete(id)
    }(ids = List(id))
  }

  /**
    * Retrieves an object of that type
    *
    * @param id The identifier for the object
    * @return An optional object, None if not found
    */
  override def retrieve(id: Long): Option[User] = this.retrieveListById(List(id)).headOption

  /**
    * Retrieves a list of objects from the supplied list of ids. Will check for any objects currently
    * in the cache and those that aren't will be retrieved from the database
    *
    * @param ids The list of ids to be retrieved
    * @param paging paging object to handle paging in response
    * @return A list of objects, empty list if none found
    */
  def retrieveListById(ids: List[Long], paging: Paging = Paging()): List[User] = {
    if (ids.isEmpty) {
      List.empty
    } else {
      this.cacheManager.withIDListCaching { implicit uncachedIDs =>
        this.query(
          Query.simple(
            List(BaseParameter(User.FIELD_ID, uncachedIDs, Operator.IN)),
            paging = paging
          ),
          User.superUser
        )
      }(ids = ids)
    }
  }

  /**
    * Delete a user based on their OSM ID
    *
    * @param osmId The OSM ID for the user
    * @param user  The user deleting the user
    * @return
    */
  def deleteByOsmID(osmId: Long, user: User): Boolean = {
    this.permission.hasSuperAccess(user)

    val item = this.retrieveByOSMId(osmId) match {
      case Some(i) =>
        this.grantService.deleteGrantsTo(Grantee.user(i.id), user)
        i
      case None => throw new NotFoundException(s"No user with OSM ID $osmId found")
    }
    this.cacheManager.withCacheIDDeletion { () =>
      this.repository.deleteByOSMID(osmId)
    }(ids = List(item.id))
    true
  }

  /**
    * Anonymizes all user data in the database
    *
    * @param osmId The OSM id of the user you wish to anonymize
    * @param user  The user requesting action, can only be a super user
    */
  def anonymizeUser(osmId: Long, user: User): Unit = {
    this.permission.hasSuperAccess(user)
    this.repository.anonymizeUser(osmId)
  }

  /**
    * Removes a user's granted role on a project
    *
    * @param osmId     The OSM ID of the user
    * @param projectId The id of the project to remove the user from
    * @param role      The role to remove -1 - all, 1 - Admin, 2 - Write, 3 - Read
    * @param user      The user making the request
    */
  def removeUserFromProject(osmId: Long, projectId: Long, role: Option[Int], user: User): Unit = {
    this.permission.hasProjectAccess(this.projectService.retrieve(projectId), user)

    this.cacheManager
      .withUpdatingCache(this.retrieveByOSMId) { cachedUser =>
        // Don't remove the last administrator from the project
        if (!this.grantService
              .retrieveMatchingGrants(
                role = Some(Grant.ROLE_ADMIN),
                target = Some(GrantTarget.project(projectId)),
                user = User.superUser
              )
              .exists { g =>
                g.grantee.granteeType == UserType() && g.grantee != Grantee.user(cachedUser.id)
              }) {
          throw new InvalidException(
            "Cannot remove user from project: projects must have at least one administrator"
          )
        }

        this.grantService.deleteMatchingGrants(
          grantee = Some(Grantee.user(cachedUser.id)),
          role = role,
          target = Some(GrantTarget.project(projectId)),
          user = User.superUser
        )
        Some(
          cachedUser.copy(
            grants = this.grantService.retrieveGrantsTo(Grantee.user(cachedUser.id), User.superUser)
          )
        )
      }(id = osmId)
      .get

    this.projectService.clearCache(projectId)
  }

  /**
    * Initializes the home project for the user. If the project already exists, then we are
    * good.
    *
    * @param user The user to initialize the home project
    */
  def initializeHomeProject(user: User): User = {
    val homeName = s"Home_${user.osmProfile.id}"
    val homeProjectId = this.projectService.retrieveByName(homeName) match {
      case Some(project) => project.id
      case None =>
        this.projectService
          .create(
            Project(
              id = -1,
              owner = user.osmProfile.id,
              name = homeName,
              created = DateTime.now(),
              modified = DateTime.now(),
              description = Some(s"Home project for user ${user.name}"),
              displayName = Some(s"${user.osmProfile.displayName}'s Project")
            ),
            user
          )
          .id
    }

    // Return user refreshed with new grants
    this.cacheManager
      .withUpdatingCache(osmId => Some(user)) { cachedUser =>
        Some(
          cachedUser.copy(
            grants = this.grantService.retrieveGrantsTo(Grantee.user(cachedUser.id), User.superUser)
          )
        )
      }(id = user.osmProfile.id)
      .get
  }

  /**
    * Adds a user to a project
    *
    * @param osmId     The OSM ID of the user to add to the project
    * @param projectId The project that user is being added too
    * @param role      The type of role to add 1 - Admin, 2 - Write, 3 - Read
    * @param user      The user that is adding the user to the project
    */
  def addUserToProject(
      osmId: Long,
      projectId: Long,
      role: Int,
      user: User,
      clear: Boolean = false
  ): User = {
    this.permission.hasProjectAccess(this.projectService.retrieve(projectId), user)
    val addedUser = this.cacheManager
      .withUpdatingCache(this.retrieveByOSMId) { cachedUser =>
        if (clear) {
          this.grantService.deleteMatchingGrants(
            grantee = Some(Grantee.user(cachedUser.id)),
            target = Some(GrantTarget.project(projectId)),
            user = User.superUser
          )
        }
        this.grantService.createGrant(
          Grant(-1, "", Grantee.user(cachedUser.id), role, GrantTarget.project(projectId)),
          User.superUser
        )
        Some(
          cachedUser.copy(
            grants = this.grantService.retrieveGrantsTo(Grantee.user(cachedUser.id), User.superUser)
          )
        )
      }(id = osmId)
      .get

    this.projectService.clearCache(projectId)
    addedUser
  }

  /**
    * Find the user based on the user's osm ID. If found on cache, will return cached object
    * instead of hitting the database
    *
    * @param id   The user's osm ID
    * @return The matched user, None if User not found
    */
  def retrieveByOSMId(id: Long): Option[User] =
    this.cacheManager.withOptionCaching { () =>
      this
        .query(Query.simple(List(BaseParameter(User.FIELD_OSM_ID, id))), User.superUser)
        .headOption
    }

  /**
    * Retrieves a list of users from the supplied list of OSM ids
    *
    * @param osmIds The list of OSM ids for users to be retrieved
    * @param paging paging object to handle paging in response
    * @return A list of users, empty list if none found
    */
  def retrieveListByOSMId(osmIds: List[Long], paging: Paging = Paging()): List[User] = {
    if (osmIds.isEmpty) {
      return List.empty
    }

    this.query(
      Query.simple(
        List(BaseParameter(User.FIELD_OSM_ID, osmIds, Operator.IN)),
        paging = paging
      ),
      User.superUser
    )
  }

  def query(query: Query, user: User): List[User] = this.repository.query(query)

  /**
    * Retrieves the user's home project
    *
    * @param user The user to search for the home project
    * @return
    */
  def getHomeProject(user: User): Project = {
    val homeName = s"Home_${user.osmProfile.id}"
    this.projectService.retrieveByName(homeName) match {
      case Some(project) => project
      case None =>
        throw new NotFoundException(
          "You should never get this exception, Home project should always exist for user."
        )
    }
  }

  /**
    * Gets the last X saved challenges for a user
    *
    * @param userId The id of the user you are requesting the saved challenges for
    * @param user   The user making the request
    * @param paging paging object to handle paging in response
    * @return a List of challenges
    */
  def getSavedChallenges(
      userId: Long,
      user: User,
      paging: Paging = Paging()
  ): List[Challenge] = {
    this.permission.hasReadAccess(UserType(), user)(userId)
    this.savedObjectsRepository.getSavedChallenges(userId)
  }

  /**
    * Saves a challenge to a users profile
    *
    * @param userId The id of the user to save the challenge too
    * @param challengeId The id of the challenge to save
    * @param user The user making the actual request
    */
  def saveChallenge(userId: Long, challengeId: Long, user: User): Unit = {
    this.permission.hasWriteAccess(UserType(), user)(userId)
    this.savedObjectsRepository.saveChallenge(userId, challengeId)
  }

  /**
    * "Unsave" a challenge from a users profile
    *
    * @param userId The id of the user to unsave the challenge from
    * @param challengeId The id of the challenge to unsave
    * @param user The user making the actual request
    */
  def unsaveChallenge(userId: Long, challengeId: Long, user: User): Unit = {
    this.permission.hasWriteAccess(UserType(), user)(userId)
    this.savedObjectsRepository.unsaveChallenge(userId, challengeId)
  }

  /**
    * Retrieve all the tasks that have been saved
    *
    * @param userId The id of the user to get all the tasks for
    * @param user The user making the actual request
    * @param challengeIds A list of challenge ids that we want to filter on
    * @param paging paging object to handle paging in response
    * @return A list of Tasks that have been saved to the users profile
    */
  def getSavedTasks(
      userId: Long,
      user: User,
      challengeIds: Seq[Long] = Seq.empty,
      paging: Paging = Paging()
  ): List[Task] = {
    this.permission.hasReadAccess(UserType(), user)(userId)
    this.savedObjectsRepository.getSavedTasks(userId, challengeIds, paging)
  }

  /**
    * Saves the task for the user, will validate that the task actually exists first based on the
    * provided id
    *
    * @param userId The id of the user
    * @param taskId the id of the task
    * @param user   the user executing the request
    */
  def saveTask(userId: Long, taskId: Long, user: User): Unit = {
    this.permission.hasWriteAccess(UserType(), user)(userId)
    this.taskDAL.retrieveById(taskId) match {
      case Some(task) => this.savedObjectsRepository.saveTask(userId, task.id, task.parent)
      case None       => throw new NotFoundException(s"No task found with ID $taskId")
    }
  }

  /**
    * Unsaves a task from the users profile
    *
    * @param userId  The id of the user that has previously saved the challenge
    * @param taskId The id of the task to remove from the user profile
    * @param user    The user executing the unsave function
    */
  def unsaveTask(userId: Long, taskId: Long, user: User): Unit = {
    this.permission.hasWriteAccess(UserType(), user)(userId)
    this.savedObjectsRepository.unsaveTask(userId, taskId)
  }

  /**
    * Adds a Following group to a User
    *
    * @param follower The user whose is to get a following group added
    * @param user     The user making the request
    */
  def addFollowingGroup(follower: User, user: User): Option[Long] = {
    this.cacheManager
      .withUpdatingCache(this.retrieve) { implicit cachedItem =>
        cachedItem.followingGroupId match {
          case Some(groupId) => Some(cachedItem) // group already exists
          case None          => Some(this.repository.addFollowingGroup(cachedItem))
        }
      }(id = follower.id)
      .get
      .followingGroupId
  }

  /**
    * Adds a Followers group to a User
    *
    * @param followed The user who is to get a followers group added
    * @param user     The user making the request
    */
  def addFollowersGroup(followed: User, user: User): Option[Long] = {
    this.cacheManager
      .withUpdatingCache(this.retrieve) { implicit cachedItem =>
        cachedItem.followersGroupId match {
          case Some(groupId) => Some(cachedItem) // group already exists
          case None          => Some(this.repository.addFollowersGroup(cachedItem))
        }
      }(id = followed.id)
      .get
      .followersGroupId
  }

  /**
    * Retrieve list of all users possessing a granted role on the project
    *
    * @param projectId    The project
    * @param osmIdFilter  A filter for manager OSM ids
    * @param user         The user making the request
    * @param includeTeams If true, also include indirect managers via teams
    * @return A list of ProjectManager objects.
    */
  def getUsersManagingProject(
      projectId: Long,
      osmIdFilter: Option[List[Long]] = None,
      user: User,
      includeTeams: Boolean = true
  ): List[ProjectManager] = {
    val project = this.projectService.retrieve(projectId) match {
      case Some(p) => p
      case None    => throw new NotFoundException(s"No project found with id $projectId")
    }
    this.permission.hasProjectAccess(Some(project), user, Grant.ROLE_READ_ONLY)

    // Get users directly granted a role on the project
    val userIds = project.grantsToType(UserType()).map(_.grantee.granteeId)

    if (!includeTeams) {
      return this.retrieveListById(userIds.distinct).map(u => ProjectManager.fromUser(u, projectId))
    }

    // Get users indirectly granted a role on the project via their teams
    val teamGrants = project.grantsToType(GroupType())
    val teamUsers = this.serviceManager.team.teamUsersByTeamIds(
      teamGrants.map(_.grantee.granteeId),
      User.superUser
    )

    // Fetch both types of users, filter, and convert to project managers. If a
    // user is in both types (or on multiple teams), consolidate all relevant
    // granted roles
    val users = this.serviceManager.user.retrieveListById(
      (userIds ++ teamUsers.map(_.userId)).distinct
    )
    val matchingUsers = osmIdFilter match {
      case Some(osmIds) if !osmIds.isEmpty => users.filter(u => osmIds.contains(u.osmProfile.id))
      case _                               => users
    }

    matchingUsers.map(u => {
      val teamIds = teamUsers.filter(_.userId == u.id).map(_.teamId)
      val grants  = teamGrants.filter(g => teamIds.contains(g.grantee.granteeId))
      ProjectManager(
        projectId,
        u.id,
        u.osmProfile.id,
        u.osmProfile.displayName,
        u.osmProfile.avatarURL,
        (u.grantsForProject(projectId) ++ grants).map(_.role).distinct
      )
    })
  }

  /**
    * Returns a list of users that have completed tasks for a challenge.
    *
    * @param challengeId - The challenge id of the challenge
    * @return mappers as a list of UserSearchResults
    */
  def getChallengeMappers(challengeId: Long): List[UserSearchResult] = {
    this.repository
      .query(
        Query.simple(
          List(
            SubQueryFilter(
              "osm_id",
              Query.simple(
                List(
                  BaseParameter(
                    "challenge_id",
                    challengeId,
                    Operator.EQ,
                    useValueDirectly = true
                  ),
                  BaseParameter(
                    "osm_user_id",
                    "'-1'",
                    Operator.NE,
                    useValueDirectly = true
                  ),
                  CustomParameter("old_status <> status"),
                  BaseParameter(
                    "status",
                    List(
                      Task.STATUS_FIXED,
                      Task.STATUS_FALSE_POSITIVE,
                      Task.STATUS_TOO_HARD,
                      Task.STATUS_ALREADY_FIXED
                    ).mkString(","),
                    Operator.IN,
                    useValueDirectly = true
                  )
                ),
                "SELECT osm_user_id from status_actions"
              )
            )
          )
        )
      )
      .map(u => { u.toSearchResult })
  }

  /**
    * Clears the users cache
    *
    * @param id If id is supplied will only remove the user with that id
    */
  def clearCache(id: Long = -1): Unit = {
    if (id > -1) {
      this.cacheManager.cache.remove(id)
    } else {
      this.cacheManager.clearCaches
    }
  }
}

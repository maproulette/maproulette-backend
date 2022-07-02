/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection

import anorm.SqlParser._
import anorm._
import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory, Point}
import com.vividsolutions.jts.io.WKTReader
import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.framework.model._
import org.maproulette.framework.service.{ServiceManager, GrantService}
import org.maproulette.framework.psql.filter._
import org.maproulette.framework.psql.{Query, SQLUtils}
import org.maproulette.models.dal.ChallengeDAL
import play.api.db.Database
import play.api.libs.oauth.RequestToken
import play.api.libs.json.{JsResultException, Json}

/**
  * The User repository handles all the sql queries that are executed against the database for the
  * User object
  *
  * @author mcuthbert
  */
@Singleton
class UserRepository @Inject() (
    override val db: Database,
    serviceManager: ServiceManager,
    grantService: GrantService,
    challengeDAL: ChallengeDAL,
    config: Config
) extends RepositoryMixin {
  import org.maproulette.utils.AnormExtension._

  implicit val baseTable: String = User.TABLE

  def upsert(user: User, apiKey: String, ewkt: String)(
      implicit c: Option[Connection] = None
  ): User = {
    this.withMRTransaction { implicit c =>
      val query =
        s"""WITH upsert AS (UPDATE users SET osm_id = {osmID}, osm_created = {osmCreated},
                              name = {name}, description = {description}, avatar_url = {avatarURL},
                              oauth_token = {token}, oauth_secret = {secret},  home_location = ST_GeomFromEWKT({wkt})
                            WHERE id = {id} OR osm_id = {osmID} RETURNING ${UserRepository.standardColumns})
            INSERT INTO users (api_key, osm_id, osm_created, name, description,
                               avatar_url, oauth_token, oauth_secret, home_location)
            SELECT {apiKey}, {osmID}, {osmCreated}, {name}, {description}, {avatarURL}, {token}, {secret}, ST_GeomFromEWKT({wkt})
            WHERE NOT EXISTS (SELECT * FROM upsert)"""
      SQL(query)
        .on(
          Symbol("apiKey")      -> apiKey,
          Symbol("osmID")       -> user.osmProfile.id,
          Symbol("osmCreated")  -> user.osmProfile.created,
          Symbol("name")        -> user.osmProfile.displayName,
          Symbol("description") -> user.osmProfile.description,
          Symbol("avatarURL")   -> user.osmProfile.avatarURL,
          Symbol("token")       -> user.osmProfile.requestToken.token,
          Symbol("secret")      -> user.osmProfile.requestToken.secret,
          Symbol("wkt")         -> s"SRID=4326;$ewkt",
          Symbol("id")          -> user.id
        )
        .executeUpdate()
    }
    this.query(Query.simple(List(BaseParameter(User.FIELD_OSM_ID, user.osmProfile.id)))).head
  }

  /**
    * Finds 0 or more users that match the filter criteria
    *
    * @param query The psql query object containing all the filtering, paging and ordering information
    * @param c An implicit connection, that defaults to None
    * @return The list of projects that match the filter criteria
    */
  def query(query: Query)(implicit c: Option[Connection] = None): List[User] = {
    this.withMRTransaction { implicit c =>
      query
        .build(s"""SELECT ${UserRepository.standardColumns}, score, achievements FROM users
                      LEFT JOIN user_metrics ON users.id = user_metrics.user_id""")
        .as(this.parser().*)
    }
  }

  def parser(): RowParser[User] =
    UserRepository.parser(
      this.config.defaultNeedsReview,
      id =>
        this.grantService.retrieveGrantsTo(Grantee.user(id), User.superUser) ++
          this.serviceManager.team.projectGrantsForUser(id, User.superUser)
    )

  def update(
      user: User,
      ewkt: String
  )(implicit c: Option[Connection] = None): User = {
    this.withMRTransaction { implicit c =>
      updateCustomBasemaps(user)

      val query =
        s"""UPDATE users SET name = {name}, description = {description},
                                          avatar_url = {avatarURL}, oauth_token = {token}, oauth_secret = {secret},
                                          home_location = ST_SetSRID(ST_GeomFromEWKT({wkt}),4326), default_editor = {defaultEditor},
                                          default_basemap = {defaultBasemap}, default_basemap_id = {defaultBasemapId},
                                          locale = {locale}, email = {email}, email_opt_in = {emailOptIn}, leaderboard_opt_out = {leaderboardOptOut},
                                          needs_review = {needsReview}, is_reviewer = {isReviewer}, theme = {theme}, allow_following = {allowFollowing},
                                          properties = {properties}, see_tag_fix_suggestions = {seeTagFixSuggestions}
                        WHERE id = {id} RETURNING ${UserRepository.standardColumns},
                        (SELECT score FROM user_metrics um WHERE um.user_id = ${user.id}) as score,
                        (SELECT achievements FROM user_metrics um WHERE um.user_id = ${user.id}) as achievements"""
      SQL(query)
        .on(
          Symbol("name")                 -> user.osmProfile.displayName,
          Symbol("description")          -> user.osmProfile.description,
          Symbol("avatarURL")            -> user.osmProfile.avatarURL,
          Symbol("token")                -> user.osmProfile.requestToken.token,
          Symbol("secret")               -> user.osmProfile.requestToken.secret,
          Symbol("wkt")                  -> s"SRID=4326;$ewkt",
          Symbol("id")                   -> user.id,
          Symbol("defaultEditor")        -> user.settings.defaultEditor,
          Symbol("defaultBasemap")       -> user.settings.defaultBasemap,
          Symbol("defaultBasemapId")     -> user.settings.defaultBasemapId,
          Symbol("locale")               -> user.settings.locale,
          Symbol("email")                -> user.settings.email,
          Symbol("emailOptIn")           -> user.settings.emailOptIn,
          Symbol("leaderboardOptOut")    -> user.settings.leaderboardOptOut,
          Symbol("needsReview")          -> user.settings.needsReview,
          Symbol("isReviewer")           -> user.settings.isReviewer,
          Symbol("theme")                -> user.settings.theme,
          Symbol("allowFollowing")       -> user.settings.allowFollowing,
          Symbol("properties")           -> user.properties,
          Symbol("seeTagFixSuggestions") -> user.settings.seeTagFixSuggestions
        )
        .as(this.parser().*)
        .head
    }

  }

  /**
    * Updates a users api key
    *
    * @param id The id of the user
    * @param apiKey The apiKey to update too
    * @param c An implicit connection
    * @return The updated User object
    */
  def updateAPIKey(id: Long, apiKey: String)(implicit c: Option[Connection] = None): User = {
    this.withMRTransaction { implicit c =>
      val query =
        s"""UPDATE users SET api_key = {apiKey} WHERE id = {id}
                        RETURNING ${UserRepository.standardColumns},
                        (SELECT score FROM user_metrics um WHERE um.user_id = {id}) as score,
                        (SELECT achievements FROM user_metrics um WHERE um.user_id = {id}) as achievements"""
      SQL(query)
        .on(Symbol("apiKey") -> apiKey, Symbol("id") -> id)
        .as(this.parser().*)
        .head
    }
  }

  def addFollowingGroup(follower: User): User = {
    follower.followingGroupId match {
      case Some(groupId) => follower // already setup, do nothing
      case None =>
        val group = this.serviceManager.group
          .create(
            Group(-1, s"User ${follower.id} Following", groupType = Group.GROUP_TYPE_FOLLOWING)
          )
          .get
        this.setupGroup("following_group", group.id, follower.id)
    }
  }

  def addFollowersGroup(followed: User): User = {
    followed.followersGroupId match {
      case Some(groupId) => followed // already setup, do nothing
      case None =>
        val group = this.serviceManager.group
          .create(
            Group(-1, s"User ${followed.id} Followers", groupType = Group.GROUP_TYPE_FOLLOWERS)
          )
          .get
        this.setupGroup("followers_group", group.id, followed.id)
    }
  }

  def delete(id: Long)(implicit c: Option[Connection] = None): Boolean = {
    this.withMRTransaction { implicit c =>
      Query.simple(List(BaseParameter(User.FIELD_ID, id))).build("DELETE FROM users").execute()
    }
  }

  def deleteByOSMID(id: Long)(implicit c: Option[Connection] = None): Boolean = {
    this.withMRTransaction { implicit c =>
      Query.simple(List(BaseParameter(User.FIELD_OSM_ID, id))).build("DELETE FROM users").execute()
    }
  }

  def anonymizeUser(osmId: Long)(implicit c: Option[Connection] = None): Unit = {
    this.withMRTransaction { implicit c =>
      // anonymize all status actions set
      Query
        .simple(List(BaseParameter(StatusActions.FIELD_OSM_USER_ID, osmId)))
        .build("UPDATE status_actions SET osm_user_id = -1")(baseTable = StatusActions.TABLE)
        .executeUpdate()
      // set all comments made to "COMMENT_DELETED"
      Query
        .simple(List(BaseParameter("osm_id", osmId)))
        .build("UPDATE task_comments SET comment = '*COMMENT DELETED*', osm_id = -1")(baseTable =
          Comment.TABLE
        )
        .executeUpdate()
    }
  }

  def updateUserScore(userId: Long, updates: List[Parameter[_]])(
      implicit c: Option[Connection] = None
  ): Boolean = {
    if (updates.isEmpty) {
      false
    } else {
      this.withMRTransaction { implicit c =>
        // We need to make sure the user is in the database first.
        ensureUserMetrics(userId)

        val updateString = updates.map(update => update.sql()).mkString(",")
        val updateScoreQuery =
          s"""UPDATE user_metrics SET $updateString WHERE user_id = {uid} """
        SQL(updateScoreQuery).on(Symbol("uid") -> userId).execute()
      }
    }
  }

  def getUserTaskCounts(userId: Long, dateFilter: DateParameter)(
      implicit c: Option[Connection] = None
  ): Map[String, Int] = {
    this.withMRTransaction { implicit c =>
      val taskCountsParser: RowParser[Map[String, Int]] = {
        get[Int]("total") ~
          get[Int]("total_fixed") ~
          get[Int]("total_false_positive") ~
          get[Int]("total_already_fixed") ~
          get[Int]("total_too_hard") ~
          get[Int]("total_skipped") ~
          get[Double]("total_time_spent") ~
          get[Int]("tasks_with_time") map {
          case total ~ fixed ~ falsePositive ~ alreadyFixed ~ tooHard ~ skipped ~
                totalTimeSpent ~ tasksWithTime =>
            Map(
              "total"         -> total,
              "fixed"         -> fixed,
              "falsePositive" -> falsePositive,
              "alreadyFixed"  -> alreadyFixed,
              "tooHard"       -> tooHard,
              "skipped"       -> skipped,
              "avgTimeSpent"  -> (if (tasksWithTime > 0) (totalTimeSpent / tasksWithTime) else 0).toInt
            )
        }
      }

      val taskCountsQuery = Query
        .simple(
          List(dateFilter),
          s"""SELECT COUNT(tasks.id) AS total,
             COALESCE(SUM(CASE WHEN status_actions.status = ${Task.STATUS_FIXED} then 1 else 0 end), 0) total_fixed,
             COALESCE(SUM(CASE WHEN status_actions.status = ${Task.STATUS_FALSE_POSITIVE} then 1 else 0 end), 0) total_false_positive,
             COALESCE(SUM(CASE WHEN status_actions.status = ${Task.STATUS_ALREADY_FIXED} then 1 else 0 end), 0) total_already_fixed,
             COALESCE(SUM(CASE WHEN status_actions.status = ${Task.STATUS_TOO_HARD} then 1 else 0 end), 0) total_too_hard,
             COALESCE(SUM(CASE WHEN status_actions.status = ${Task.STATUS_SKIPPED} then 1 else 0 end), 0) total_skipped,
             COALESCE(SUM(CASE WHEN (status_actions.created IS NOT NULL AND
                                     status_actions.started_at IS NOT NULL AND
                                     status_actions.status != ${Task.STATUS_SKIPPED})
                      THEN (EXTRACT(EPOCH FROM (status_actions.created - status_actions.started_at)) * 1000)
                      ELSE 0 END), 0) as total_time_spent,
             COALESCE(SUM(CASE WHEN (status_actions.created IS NOT NULL AND
                                     status_actions.started_at IS NOT NULL AND
                                     status_actions.status != ${Task.STATUS_SKIPPED})
                      THEN 1 ELSE 0 END), 0) as tasks_with_time
           FROM tasks
           INNER JOIN status_actions ON status_actions.task_id = tasks.id AND status_actions.status = tasks.status
           INNER JOIN users ON users.osm_id = status_actions.osm_user_id AND users.id={uid}"""
        )
      SQL(taskCountsQuery.sql())
        .on(SQLUtils.buildNamedParameter("uid", userId) :: taskCountsQuery.parameters(): _*)
        .as(taskCountsParser.single)
    }
  }

  /**
    * Add the given achievements to the user's metrics. Only new achievements
    * not already possessed by the user will be added
    *
    * @param userId       The id of the user who is to receive the achievements
    * @param achievements List of the achievements to award to the user
    */
  def addAchievements(userId: Long, achievements: List[Int])(
      implicit c: Option[Connection] = None
  ) = {
    this.withMRTransaction { implicit c =>
      // We need to make sure the user is in the database first
      ensureUserMetrics(userId)

      val achievementArray = s"'{${achievements.mkString(",")}}'::int[]"
      SQL"""
        UPDATE user_metrics
        SET achievements=array_distinct(achievements || #$achievementArray)
        WHERE user_id = ${userId}
      """.executeUpdate()
    }
  }

  /**
    * Assigns a group id to a group column on a user, returning the updated User
    *
    * @param groupColumn The name of the group column to set
    * @param groupId     The id of the group to set
    * @param userId      The id of the user on which the group is to be set
    */
  private def setupGroup(groupColumn: String, groupId: Long, userId: Long): User = {
    this.withMRTransaction { implicit c =>
      val query =
        s"""UPDATE users SET ${groupColumn} = {groupId} WHERE id = {userId}
            RETURNING ${UserRepository.standardColumns},
            (SELECT score FROM user_metrics um WHERE um.user_id = {userId}) as score,
            (SELECT achievements FROM user_metrics um WHERE um.user_id = {userId}) as achievements"""
      SQL(query)
        .on(
          Symbol("groupId") -> groupId,
          Symbol("userId")  -> userId
        )
        .as(this.parser().*)
        .head
    }
  }

  /**
    * Private method that updates the user's custom basemaps in the database.
    * This will treat what's given as a full and complete list -- so any basemaps
    * not appearing in list will be deleted.
    *
    * @param user
    */
  private def updateCustomBasemaps(user: User): Unit = {
    this.withMRTransaction { implicit c =>
      user.settings.customBasemaps match {
        case Some(bm) =>
          bm.foreach(basemap => {
            if (basemap.id == -1) {
              val insertBasemapQuery =
                s"""INSERT INTO user_basemaps (user_id, name, url, overlay)
                    VALUES ({user_id}, {name}, {url}, {overlay})
                 """
              SQL(insertBasemapQuery)
                .on(
                  Symbol("user_id") -> user.id,
                  Symbol("name")    -> basemap.name,
                  Symbol("url")     -> basemap.url,
                  Symbol("overlay") -> basemap.overlay
                )
                .executeUpdate()
            } else {
              val updateBasemapQuery =
                s"""UPDATE user_basemaps
                      SET modified=NOW(), name={name}, url={url}, overlay={overlay}
                      WHERE id={id} AND user_id={user_id}
                 """
              SQL(updateBasemapQuery)
                .on(
                  Symbol("id")      -> basemap.id,
                  Symbol("user_id") -> user.id,
                  Symbol("name")    -> basemap.name,
                  Symbol("url")     -> basemap.url,
                  Symbol("overlay") -> basemap.overlay
                )
                .executeUpdate()
            }
          })

          // Delete all custom basemaps not in list. (Or delete everything if
          // list is empty.)
          if (bm.nonEmpty) {
            SQL(s"""DELETE from user_basemaps WHERE user_id={user_id} AND
                    name NOT IN ({name_list})
                """)
              .on(
                Symbol("user_id")   -> user.id,
                Symbol("name_list") -> bm.map(_.name)
              )
              .executeUpdate()
          } else {
            SQL(s"""DELETE from user_basemaps WHERE user_id={user_id}""")
              .on(
                Symbol("user_id") -> user.id
              )
              .executeUpdate()
          }
        case None => // no custom basemaps
      }
    }
  }

  /**
    * Ensure a row exists in the user_metrics table for the user
    */
  private def ensureUserMetrics(userId: Long) = {
    this.withMRTransaction { implicit c =>
      SQL("""INSERT INTO user_metrics (user_id, score, total_fixed, total_false_positive,
              total_already_fixed, total_too_hard, total_skipped)
              VALUES ({uid}, 0, 0, 0, 0, 0, 0)
              ON CONFLICT (user_id) DO NOTHING""").on(Symbol("uid") -> userId).executeUpdate()
    }
  }
}

object UserRepository {
  case class CustomBasemapList(
      customBasemaps: Option[List[CustomBasemap]]
  )

  implicit val columnToCustomBasemapList: Column[CustomBasemapList] =
    anorm.Column.nonNull[CustomBasemapList] { (value, meta) =>
      val MetaDataItem(qualified, nullable, clazz) = meta
      value match {
        case data: org.postgresql.util.PGobject =>
          try {
            val basemapData = Json.parse(data.getValue())

            val ids      = (basemapData \ "id").as[List[Long]]
            val urls     = (basemapData \ "url").as[List[String]]
            val names    = (basemapData \ "name").as[List[String]]
            val overlays = (basemapData \ "overlay").as[List[Boolean]]

            val basemaps =
              ids.zipWithIndex.map(basemapIdWithIndex =>
                CustomBasemap(
                  basemapIdWithIndex._1.toLong,
                  names(basemapIdWithIndex._2),
                  urls(basemapIdWithIndex._2),
                  overlays(basemapIdWithIndex._2)
                )
              )

            Right(CustomBasemapList(Some(basemaps)))
          } catch {
            // If we reach here, then there are no custom basemaps for this user
            case e: JsResultException => Right(CustomBasemapList(None))
          }
        case _ =>
          Left(
            TypeDoesNotMatch(
              s"Cannot convert $value: ${value.asInstanceOf[AnyRef].getClass} to CustomBasemap for column $qualified"
            )
          )
      }
    }

  // The Anorm row parser to convert user records to project manager
  val projectManagerParser: RowParser[ProjectManager] = {
    get[Long]("project_id") ~
      get[Long]("users.id") ~
      get[Long]("users.osm_id") ~
      get[String]("users.name") ~
      get[Option[String]]("users.avatar_url") ~
      get[List[Int]]("roles") map {
      case projectId ~ userId ~ osmId ~ displayName ~ avatarURL ~ roles =>
        ProjectManager(projectId, userId, osmId, displayName, avatarURL.getOrElse(""), roles)
    }
  }

  private val standardColumns = "*, ST_AsText(users.home_location) AS home, " +
    """
      (SELECT  json_build_object(
        'id', json_agg(id),
        'name', json_agg(name),
        'url', json_agg(url),
        'overlay', json_agg(overlay)
       )
       FROM user_basemaps WHERE user_id = users.id) AS custom_basemaps
      """

  // The anorm row parser to convert user records from the database to user objects
  def parser(defaultNeedsReview: Int, grantFunc: (Long) => List[Grant]): RowParser[User] = {
    get[Long]("users.id") ~
      get[Long]("users.osm_id") ~
      get[DateTime]("users.created") ~
      get[DateTime]("users.modified") ~
      get[DateTime]("users.osm_created") ~
      get[String]("users.name") ~
      get[Option[String]]("users.description") ~
      get[Option[String]]("users.avatar_url") ~
      get[Option[String]]("home") ~
      get[Option[String]]("users.api_key") ~
      get[String]("users.oauth_token") ~
      get[String]("users.oauth_secret") ~
      get[Option[Int]]("users.default_editor") ~
      get[Option[Int]]("users.default_basemap") ~
      get[Option[String]]("users.default_basemap_id") ~
      get[Option[CustomBasemapList]]("custom_basemaps") ~
      get[Option[String]]("users.email") ~
      get[Option[Boolean]]("users.email_opt_in") ~
      get[Option[Boolean]]("users.leaderboard_opt_out") ~
      get[Option[Int]]("users.needs_review") ~
      get[Option[Boolean]]("users.is_reviewer") ~
      get[Option[String]]("users.locale") ~
      get[Option[Int]]("users.theme") ~
      get[Option[String]]("properties") ~
      get[Option[Int]]("score") ~
      get[List[Int]]("achievements").? ~
      get[Option[Boolean]]("users.allow_following") ~
      get[Option[Long]]("users.following_group") ~
      get[Option[Long]]("users.followers_group") ~
      get[Option[Boolean]]("users.see_tag_fix_suggestions") map {
      case id ~ osmId ~ created ~ modified ~ osmCreated ~ displayName ~ description ~ avatarURL ~
            homeLocation ~ apiKey ~ oauthToken ~ oauthSecret ~ defaultEditor ~ defaultBasemap ~
            defaultBasemapId ~ customBasemapList ~
            email ~ emailOptIn ~ leaderboardOptOut ~ needsReview ~ isReviewer ~ locale ~ theme ~
            properties ~ score ~ achievements ~ allowFollowing ~ followingGroupId ~ followersGroupId ~ seeTagFixSuggestions =>
        val locationWKT = homeLocation match {
          case Some(wkt) => new WKTReader().read(wkt).asInstanceOf[Point]
          case None      => new GeometryFactory().createPoint(new Coordinate(0, 0))
        }

        val setNeedsReview = needsReview match {
          case Some(_) => needsReview
          case None    => Option(defaultNeedsReview)
        }

        val customBasemaps: Option[List[CustomBasemap]] =
          customBasemapList match {
            case Some(bm) => bm.customBasemaps
            case None     => None
          }

        new User(
          id,
          created,
          modified,
          OSMProfile(
            osmId,
            displayName,
            description.getOrElse(""),
            avatarURL.getOrElse(""),
            Location(locationWKT.getX, locationWKT.getY),
            osmCreated,
            RequestToken(oauthToken, oauthSecret)
          ),
          grantFunc.apply(id),
          apiKey,
          false,
          UserSettings(
            defaultEditor,
            defaultBasemap,
            defaultBasemapId,
            locale,
            email,
            emailOptIn,
            leaderboardOptOut,
            setNeedsReview,
            isReviewer,
            allowFollowing,
            theme,
            customBasemaps,
            seeTagFixSuggestions
          ),
          properties,
          score,
          followingGroupId,
          followersGroupId,
          achievements
        )
    }
  }
}

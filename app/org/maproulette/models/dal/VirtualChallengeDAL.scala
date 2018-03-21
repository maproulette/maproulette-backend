package org.maproulette.models.dal

import java.sql.Connection
import javax.inject.Inject

import anorm.SqlParser._
import anorm._
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.actions.{Actions, TaskType, VirtualChallengeType}
import org.maproulette.cache.CacheManager
import org.maproulette.exception.InvalidException
import org.maproulette.models._
import org.maproulette.models.utils.DALHelper
import org.maproulette.permissions.Permission
import org.maproulette.session.{SearchLocation, SearchParameters, User}
import play.api.db.Database
import play.api.libs.json.{JsString, JsValue, Json}

/**
  * @author mcuthbert
  */
class VirtualChallengeDAL @Inject() (override val db:Database,
                                     override val permission:Permission,
                                     val taskDAL:TaskDAL,
                                     val config:Config)
  extends BaseDAL[Long, VirtualChallenge] with DALHelper with Locking[Task] {

  import scala.concurrent.ExecutionContext.Implicits.global

  override val cacheManager = new CacheManager[Long, VirtualChallenge]
  override val tableName: String = "virtual_challenges"

  implicit val locationWrites = Json.writes[SearchLocation]
  implicit val locationReads = Json.reads[SearchLocation]
  implicit val paramsWrites = Json.writes[SearchParameters]
  implicit val paramsReads = Json.reads[SearchParameters]

  override val parser: RowParser[VirtualChallenge] = {
    get[Long]("virtual_challenges.id") ~
      get[String]("virtual_challenges.name") ~
      get[DateTime]("virtual_challenges.created") ~
      get[DateTime]("virtual_challenges.modified") ~
      get[Option[String]]("virtual_challenges.description") ~
      get[Long]("virtual_challenges.owner_id") ~
      get[String]("virtual_challenges.search_parameters") ~
      get[DateTime]("virtual_challenges.expiry") map {
      case id ~ name ~ created ~ modified ~ description ~ ownerId ~ searchParameters ~ expiry =>
        new VirtualChallenge(id, name, created, modified, description, ownerId, Json.parse(searchParameters).as[SearchParameters], expiry)
    }
  }

  /**
    * The insert function for virtual challenges needs to create a new challenge and then find all the
    * tasks based on the search parameters in the virtual challenge
    *
    * @param element The element that you are inserting to the database
    * @param user    The user executing the task
    * @return The object that was inserted into the database. This will include the newly created id
    */
  override def insert(element: VirtualChallenge, user: User)(implicit c: Option[Connection]=None): VirtualChallenge = {
    this.cacheManager.withOptionCaching { () =>
      withMRTransaction { implicit c =>
        // check if any virtual challenges with the same name need to expire
        // calling the retrieve function will also remove any expired virtual challenges
        this.retrieveListByName(List(element.name))
        val validParameters = element.taskIdList match {
          case Some(ids) => ids.nonEmpty
          case None => element.searchParameters.location match {
            case Some(box) if (box.right - box.left) * (box.top - box.bottom) < config.virtualChallengeLimit => true
            case None => false
          }
        }

        if (validParameters) {
          val query = """INSERT INTO virtual_challenges (owner_id, name, description, search_parameters, expiry)
                             VALUES ({owner}, {name}, {description}, {parameters}, {expiry}::timestamp)
                             RETURNING *"""
          val newChallenge = SQL(query).on(
            'owner -> user.osmProfile.id,
            'name -> element.name,
            'description -> element.description,
            'parameters -> Json.toJson(element.searchParameters).toString(),
            'expiry -> ParameterValue.toParameterValue(String.valueOf(element.expiry))
          ).as(this.parser.single)
          c.commit()
          element.taskIdList match {
            case Some(ids) => this.createVirtualChallengeFromIds(newChallenge.id, ids)
            case None => this.rebuildVirtualChallenge(newChallenge.id, element.searchParameters, user)
          }
          Some(newChallenge)
        } else {
          throw new InvalidException(s"Bounding Box that has an area smaller than ${config.virtualChallengeLimit} required to create virtual challenge.")
        }
      }
    }.get
  }

  /**
    * The update function is limited in that you can only update the superficial elements, name
    * and description. The only value of consequence that you can update is expiry, which by default
    * is set to a day but can be extended by the user.
    *
    * @param updates The updates in json form
    * @param user The user executing the task
    * @param id The id of the object that you are updating
    * @param c
    * @return An optional object, it will return None if no object found with a matching id that was supplied
    */
  override def update(updates: JsValue, user: User)(implicit id: Long, c: Option[Connection]=None): Option[VirtualChallenge] = {
    permission.hasWriteAccess(VirtualChallengeType(), user)
    this.cacheManager.withUpdatingCache(Long => retrieveById) { implicit cachedItem =>
      withMRTransaction { implicit c =>
        // first if the virtual challenge has expired then just delete it
        val expiry = (updates \ "expiry").asOpt[DateTime].getOrElse(cachedItem.expiry)
        if (DateTime.now().isAfter(expiry)) {
          this.delete(cachedItem.id, user)
          throw new InvalidException("Could not update virtual challenge as it has already expired")
        } else {
          val name = (updates \ "name").asOpt[String].getOrElse(cachedItem.name)
          val description = (updates \ "description").asOpt[String].getOrElse(cachedItem.description.getOrElse(""))
          val query = """UPDATE virtual_challenges
               SET name = {name}, description = {description}, expiry = {expiry}::timestamp
               WHERE id = {id} RETURNING *"""
          SQL(query)
              .on(
                'name -> name,
                'description -> description,
                'expiry -> ParameterValue.toParameterValue(String.valueOf(expiry)),
                'id -> id
              ).as(this.parser.*).headOption
        }
      }
    }
  }

  /**
    * This function will rebuild the virtual challenge based on the search parameters that have been stored with the object
    *
    * @param id The id of the virtual challenge
    * @param user The user making the request
    * @param c implicit connection
    * @return
    */
  def rebuildVirtualChallenge(id:Long, params:SearchParameters, user:User)(implicit c:Option[Connection]=None) : Unit = {
    permission.hasWriteAccess(VirtualChallengeType(), user)(id)
    withMRTransaction { implicit c =>
      this.taskDAL.getTasksInBoundingBox(params, -1, 0).grouped(config.virtualChallengeBatchSize).foreach(batch => {
        val insertRows = batch.map(point => s"(${point.id}, $id)").mkString(",")
        SQL"""
           INSERT INTO virtual_challenge_tasks (task_id, virtual_challenge_id) VALUES #$insertRows
         """.execute()
        c.commit()
      })
    }
  }

  private def createVirtualChallengeFromIds(id:Long, idList:List[Long])(implicit c:Option[Connection]=None) : Unit = {
    withMRTransaction { implicit c =>
      val insertRows = idList.map(taskId => s"($taskId, $id)").mkString(",")
      SQL"""
         INSERT INTO virtual_challenge_tasks (task_id, virtual_challenge_id) VALUES #$insertRows
      """.execute()
      c.commit()
    }
  }

  def listTasks(id:Long, user:User, limit:Int, offset:Int)(implicit c:Option[Connection]=None) : List[Task] = {
    permission.hasReadAccess(VirtualChallengeType(), user)(id)
    withMRTransaction { implicit c =>
      SQL"""SELECT tasks.#${taskDAL.retrieveColumns} FROM tasks
            INNER JOIN virtual_challenge_tasks vct ON vct.task_id = tasks.id
            WHERE virtual_challenge_id = $id
            LIMIT #${sqlLimit(limit)} OFFSET $offset""".as(taskDAL.parser.*)
    }
  }

  /**
    * Gets a random task from the list of tasks associated with the virtual challenge
    *
    * @param id The id of the virtual challenge
    * @param params The search parameters, most of the parameters will not be used
    * @param user The user making the request
    * @param proximityId Id of the task to find the closest next task
    * @param c
    * @return An optional Task, None if no tasks available
    */
  def getRandomTask(id:Long, params:SearchParameters, user:User, proximityId:Option[Long] = None)
                   (implicit c:Option[Connection]=None) : Option[Task] = {
    permission.hasReadAccess(VirtualChallengeType(), user)(id)
    // The default where clause will check to see if the parents are enabled, that the task is
    // not locked (or if it is, it is locked by the current user) and that the status of the task
    // is either Created or Skipped
    val taskStatusList = params.taskStatus match {
      case Some(l) if l.nonEmpty => l
      case _ => List(Task.STATUS_CREATED, Task.STATUS_SKIPPED, Task.STATUS_TOO_HARD)
    }

    val whereClause = new StringBuilder(s"""
      WHERE vct.virtual_challenge_id = $id AND
      (l.id IS NULL OR l.user_id = ${user.id}) AND
      tasks.status IN ({statusList})
    """)

    val proximityOrdering = proximityId match {
      case Some(id) =>
        appendInWhereClause(whereClause, s"tasks.id != $id")
        s"ST_Distance(tasks.location, (SELECT location FROM tasks WHERE id = $id)),"
      case None => ""
    }

    val query = s"""SELECT tasks.${taskDAL.retrieveColumns} FROM tasks
            LEFT JOIN locked l ON l.item_id = tasks.id
            INNER JOIN virtual_challenge_tasks vct ON vct.task_id = tasks.id
            ${whereClause.toString}
            ORDER BY $proximityOrdering tasks.status, RANDOM() LIMIT 1"""

    this.withSingleLocking(user, Some(TaskType())) { () =>
      withMRTransaction { implicit c =>
        SQL(query)
          .on(
            'statusList -> ParameterValue.toParameterValue(taskStatusList)
          ).as(taskDAL.parser.*).headOption
      }
    }
  }

  /**
    * Simple query to retrieve the next task in the sequence
    *
    * @param id      The parent of the task
    * @param currentTaskId The current task that we are basing our query from
    * @return An optional task, if no more tasks in the list will retrieve the first task
    */
  def getSequentialNextTask(id:Long, currentTaskId:Long)(implicit c: Option[Connection] = None): Option[(Task, Lock)] = {
    this.withMRConnection { implicit c =>
      val lp = for {
        task <- taskDAL.parser
        lock <- lockedParser
      } yield task -> lock
      val query =
        s"""SELECT locked.*, tasks.${taskDAL.retrieveColumns} FROM tasks
                      LEFT JOIN locked ON locked.item_id = tasks.id
                      WHERE tasks.id = (SELECT task_id
                                        FROM virtual_challenge_tasks
                                        WHERE task_id > $currentTaskId AND virtual_challenge_id = $id
                                        LIMIT 1)
          """
      SQL(query).as(lp.*).headOption match {
        case Some(t) => Some(t)
        case None =>
          val loopQuery =
            s"""SELECT locked.*, tasks.${taskDAL.retrieveColumns} FROM tasks
                              LEFT JOIN locked ON locked.item_id = tasks.id
                              WHERE tasks.id = (SELECT task_id
                                                FROM virtual_challenge_tasks
                                                WHERE virtual_challenge_id = $id
                                                ORDER BY id ASC LIMIT 1)
              """
          SQL(loopQuery).as(lp.*).headOption
      }
    }
  }

  /**
    * Simple query to retrieve the previous task in the sequence
    *
    * @param id      The parent of the task
    * @param currentTaskId The current task that we are basing our query from
    * @return An optional task, if no more tasks in the list will retrieve the last task
    */
  def getSequentialPreviousTask(id:Long, currentTaskId:Long)(implicit c: Option[Connection] = None): Option[(Task, Lock)] = {
    this.withMRConnection { implicit c =>
      val lp = for {
        task <- taskDAL.parser
        lock <- lockedParser
      } yield task -> lock
      val query =
        s"""SELECT locked.*, tasks.${taskDAL.retrieveColumns} FROM tasks
                      LEFT JOIN locked ON locked.item_id = tasks.id
                      WHERE tasks.id = (SELECT task_id
                                        FROM virtual_challenge_tasks
                                        WHERE task_id < $currentTaskId AND virtual_challenge_id = $id
                                        LIMIT 1)
          """
      SQL(query).as(lp.*).headOption match {
        case Some(t) => Some(t)
        case None =>
          val loopQuery =
            s"""SELECT locked.*, tasks.${taskDAL.retrieveColumns} FROM tasks
                              LEFT JOIN locked ON locked.item_id = tasks.id
                              WHERE tasks.id = (SELECT task_id
                                                FROM virtual_challenge_tasks
                                                WHERE virtual_challenge_id = $id
                                                ORDER BY id DESC LIMIT 1)
              """
          SQL(loopQuery).as(lp.*).headOption
      }
    }
  }

  /**
    * Gets the combined geometry of all the tasks that are associated with the virtual challenge
    * NOTE* Due to the way this function finds the geometries, it could be quite slow.
    *
    * @param challengeId The id for the virtual challenge
    * @param statusFilter To view the geojson for only tasks with a specific status
    * @param c The implicit connection for the function
    * @return A JSON string representing the geometry
    */
  def getChallengeGeometry(challengeId:Long, statusFilter:Option[List[Int]]=None)(implicit c:Option[Connection]=None) : String = {
    this.withMRConnection { implicit c =>
      val filter = statusFilter match {
        case Some(s) => s"AND status IN (${s.mkString(",")}"
        case None => ""
      }
      SQL"""SELECT row_to_json(fc)::text as geometries
            FROM ( SELECT 'FeatureCollection' As type, array_to_json(array_agg(f)) As features
                   FROM ( SELECT 'Feature' As type,
                                  ST_AsGeoJSON(lg.geom)::json As geometry,
                                  hstore_to_json(lg.properties) As properties
                          FROM task_geometries As lg
                          WHERE task_id IN
                          (SELECT DISTINCT id FROM tasks WHERE id IN
                            (SELECT task_id FROM virtual_challenge_tasks
                              WHERE virtual_challenge_id = $challengeId)) #$filter
                    ) As f
            )  As fc""".as(str("geometries").single)
    }
  }

  /**
    * Retrieves the json that contains the central points for all the tasks in the virtual challenge.
    * One caveat to Virtual Challenges, is that if a project or challenge is flagged as deleted that has tasks
    * in the virtual challenge, then those tasks will remain part of the virtual challenge until the
    * tasks are cleared from the database.
    *
    * @param challengeId The id of the virtual challenge
    * @param statusFilter Filter the displayed task cluster points by their status
    * @return A list of clustered point objects
    */
  def getClusteredPoints(challengeId:Long, statusFilter:Option[List[Int]]=None)
                        (implicit c:Option[Connection]=None) : List[ClusteredPoint] = {
    this.withMRConnection { implicit c =>
      val filter = statusFilter match {
        case Some(s) => s"AND status IN (${s.mkString(",")}"
        case None => ""
      }
      val pointParser = long("id") ~ str("name") ~ str("instruction") ~ str("location") ~ int("status") ~ int("priority") map {
        case id ~ name ~ instruction ~ location ~ status ~ priority =>
          val locationJSON = Json.parse(location)
          val coordinates = (locationJSON \ "coordinates").as[List[Double]]
          val point = Point(coordinates(1), coordinates.head)
          ClusteredPoint(id, -1, "", name, -1, "", point, JsString(""),
            instruction, DateTime.now(), -1, Actions.ITEM_TYPE_TASK, status, priority)
      }
      SQL"""SELECT id, name, instruction, status,
                      ST_AsGeoJSON(location) AS location, priority
              FROM tasks WHERE id IN
              (SELECT task_id FROM virtual_challenge_tasks
                WHERE virtual_challenge_id = $challengeId) #$filter"""
        .as(pointParser.*)
    }
  }

  // --- FOLLOWING FUNCTION OVERRIDE BASE FUNCTION TO SIMPLY REMOVE ANY RETRIEVED VIRTUAL CHALLENGES
  // --- THAT ARE EXPIRED
  override def retrieveById(implicit id: Long, c: Option[Connection]=None): Option[VirtualChallenge] = {
    super.retrieveById match {
      case Some(vc) if vc.isExpired =>
        this.delete(id, User.superUser)
        None
      case x => x
    }
  }

  /**
    * For Virtual Challenges the retrieveByName function won't quite work as expected, there is a possibility
    * that there are multiple Virtual Challenges with the same name. This function will simply return the
    * first one. Generally retrieveListByName should be used instead.
    *
    * @param name The name you are looking up by
    * @param parentId
    * @param c
    * @return The object that you are looking up, None if not found
    */
  override def retrieveByName(implicit name: String, parentId: Long, c: Option[Connection]=None): Option[VirtualChallenge] = {
    super.retrieveByName match {
      case Some(vc) if vc.isExpired =>
        this.delete(vc.id, User.superUser)
        None
      case x => x
    }
  }

  override def retrieveListById(limit: Int, offset: Int)(implicit ids: List[Long], c: Option[Connection]=None): List[VirtualChallenge] =
    this.removeExpiredFromList(super.retrieveListById(limit, offset))

  override def retrieveListByName(implicit names: List[String], parentId: Long, c: Option[Connection]=None): List[VirtualChallenge] =
    this.removeExpiredFromList(super.retrieveListByName)

  override def retrieveListByPrefix(prefix: String, limit: Int, offset: Int, onlyEnabled: Boolean, orderColumn: String, orderDirection: String)
                                   (implicit parentId: Long, c: Option[Connection]=None): List[VirtualChallenge] =
    this.removeExpiredFromList(super.retrieveListByPrefix(prefix, limit, offset, onlyEnabled, orderColumn, orderDirection))

  override def find(searchString: String, limit: Int, offset: Int, onlyEnabled: Boolean, orderColumn: String, orderDirection: String)
                   (implicit parentId: Long, c: Option[Connection]=None): List[VirtualChallenge] =
    this.removeExpiredFromList(super.find(searchString, limit, offset, onlyEnabled, orderColumn, orderDirection))

  override def list(limit: Int, offset: Int, onlyEnabled: Boolean, searchString: String, orderColumn: String, orderDirection: String)
                   (implicit parentId: Long, c: Option[Connection]=None): List[VirtualChallenge] =
    this.removeExpiredFromList(super.list(limit, offset, onlyEnabled, searchString, orderColumn, orderDirection))

  private def removeExpiredFromList(superList:List[VirtualChallenge]) : List[VirtualChallenge] = {
    superList.flatMap(vc => {
      if (vc.isExpired) {
        this.delete(vc.id, User.superUser)
        None
      } else {
        Some(vc)
      }
    })
  }
  // --- END OF OVERRIDDEN FUNCTIONS TO FILTER OUT ANY EXPIRED VIRTUAL CHALLENGES
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.session

import java.net.URLDecoder

import org.maproulette.exception.InvalidException
import org.maproulette.utils.Utils
import play.api.libs.json._
import play.api.mvc.{AnyContent, AnyContentAsFormUrlEncoded, Request}
import org.joda.time.DateTime

/**
  * This holds the search parameters that are used to define task sets for retrieving random tasks
  * from the database
  *
  * TODO add spatial filters as well
  *
  * @author cuthbertm
  */
case class SearchLocation(left: Double, bottom: Double, right: Double, top: Double)

case class SearchChallengeParameters(
    challengeIds: Option[List[Long]] = None,
    challengeTags: Option[List[String]] = None,
    challengeTagConjunction: Option[Boolean] = None,
    challengeSearch: Option[String] = None,
    challengeEnabled: Option[Boolean] = None,
    challengeDifficulty: Option[Int] = None,
    challengeStatus: Option[List[Int]] = None,
    requiresLocal: Option[Int] = Some(SearchParameters.CHALLENGE_REQUIRES_LOCAL_EXCLUDE),
    archived: Boolean = false
)

case class SearchReviewParameters(
    mappers: Option[List[Long]] = None,
    reviewers: Option[List[Long]] = None,
    startDate: Option[String] = None,
    endDate: Option[String] = None,
    metaReviewers: Option[List[Long]] = None,
    metaReviewStatus: Option[List[Int]] = None
)

case class SearchTaskParameters(
    taskTags: Option[List[String]] = None,
    taskTagConjunction: Option[Boolean] = None,
    taskSearch: Option[String] = None,
    taskStatus: Option[List[Int]] = None,
    taskId: Option[Long] = None,
    taskReviewStatus: Option[List[Int]] = None,
    taskProperties: Option[Map[String, String]] = None,
    taskPropertySearchType: Option[String] = None,
    taskPropertySearch: Option[TaskPropertySearch] = None,
    taskPriorities: Option[List[Int]] = None,
    excludeTaskIds: Option[List[Long]] = None
)

case class SearchLeaderboardParameters(
    userFilter: Option[List[Long]] = None,
    projectFilter: Option[List[Long]] = None,
    challengeFilter: Option[List[Long]] = None,
    countryCodeFilter: Option[List[String]] = None,
    monthDuration: Option[Int] = None,
    start: Option[DateTime] = None,
    end: Option[DateTime] = None,
    onlyEnabled: Boolean = true
)

case class SearchParameters(
    projectIds: Option[List[Long]] = None,
    projectSearch: Option[String] = None,
    projectEnabled: Option[Boolean] = None,
    challengeParams: SearchChallengeParameters = SearchChallengeParameters(),
    taskParams: SearchTaskParameters = SearchTaskParameters(),
    reviewParams: SearchReviewParameters = SearchReviewParameters(),
    leaderboardParams: SearchLeaderboardParameters = SearchLeaderboardParameters(),
    priority: Option[Int] = None,
    location: Option[SearchLocation] = None,
    bounding: Option[SearchLocation] = None,
    boundingGeometries: Option[List[JsObject]] = None,
    fuzzySearch: Option[Int] = None,
    mapper: Option[String] = None,
    owner: Option[String] = None,
    reviewer: Option[String] = None,
    metaReviewer: Option[String] = None,
    invertFields: Option[List[String]] = None
) {
  def getProjectIds: Option[List[Long]] = projectIds match {
    case Some(v) => Some(v.filter(_ != -1))
    case None    => None
  }

  def getChallengeIds: Option[List[Long]] = challengeParams.challengeIds match {
    case Some(v) => Some(v.filter(_ != -1))
    case None    => None
  }

  def getPriority: Option[Int] = priority match {
    case Some(v) if v == -1 => None
    case _                  => priority
  }

  def getChallengeDifficulty: Option[Int] = challengeParams.challengeDifficulty match {
    case Some(v) if v == -1 => None
    case _                  => challengeParams.challengeDifficulty
  }

  def hasTaskTags: Boolean = taskParams.taskTags.getOrElse(List.empty).exists(tt => tt.nonEmpty)

  def hasChallengeTags: Boolean =
    challengeParams.challengeTags.getOrElse(List.empty).exists(ct => ct.nonEmpty)

  def enabledProject: Boolean = projectEnabled.getOrElse(true)

  def enabledChallenge: Boolean = challengeParams.challengeEnabled.getOrElse(true)
}

object SearchParameters {
  val TASK_PROP_SEARCH_TYPE_EQUALS       = "equals"
  val TASK_PROP_SEARCH_TYPE_NOT_EQUAL    = "not_equal"
  val TASK_PROP_SEARCH_TYPE_CONTAINS     = "contains"
  val TASK_PROP_SEARCH_TYPE_EXISTS       = "exists"
  val TASK_PROP_SEARCH_TYPE_MISSING      = "missing"
  val TASK_PROP_SEARCH_TYPE_LESS_THAN    = "less_than"
  val TASK_PROP_SEARCH_TYPE_GREATER_THAN = "greater_than"

  val TASK_PROP_VALUE_TYPE_STRING = "string"
  val TASK_PROP_VALUE_TYPE_NUMBER = "number"

  val TASK_PROP_OPERATION_TYPE_AND = "and"
  val TASK_PROP_OPERATION_TYPE_OR  = "or"

  val CHALLENGE_REQUIRES_LOCAL_EXCLUDE = 0
  val CHALLENGE_REQUIRES_LOCAL_INCLUDE = 1
  val CHALLENGE_REQUIRES_LOCAL_ONLY    = 2

  implicit val locationWrites = Json.writes[SearchLocation]
  implicit val locationReads  = Json.reads[SearchLocation]
  implicit val taskPropertySearchWrites: Writes[TaskPropertySearch] =
    Json.writes[TaskPropertySearch]
  implicit val taskPropertySearchReads: Reads[TaskPropertySearch] = Json.reads[TaskPropertySearch]

  implicit val challengeParamsWrites: Writes[SearchChallengeParameters] =
    Json.writes[SearchChallengeParameters]
  implicit val challengeParamsReads: Reads[SearchChallengeParameters] =
    Json.reads[SearchChallengeParameters]

  implicit val taskParamsWrites: Writes[SearchTaskParameters] =
    Json.writes[SearchTaskParameters]
  implicit val taskParamsReads: Reads[SearchTaskParameters] =
    Json.reads[SearchTaskParameters]

  implicit val reviewParamsWrites: Writes[SearchReviewParameters] =
    Json.writes[SearchReviewParameters]
  implicit val reviewParamsReads: Reads[SearchReviewParameters] =
    Json.reads[SearchReviewParameters]

  implicit val jodaDateWrite: Writes[DateTime] =
    JodaWrites.jodaDateWrites("yyyy-MM-dd'T'HH:mm:ss'Z")
  implicit val jodaDateReads: Reads[DateTime] = JodaReads.jodaDateReads("yyyy-MM-dd'T'HH:mm:ss'Z")

  implicit val leaderboardParamsWrites: Writes[SearchLeaderboardParameters] =
    Json.writes[SearchLeaderboardParameters]
  implicit val leaderboardParamsReads: Reads[SearchLeaderboardParameters] =
    Json.reads[SearchLeaderboardParameters]

  implicit val paramsWrites: Writes[SearchParameters] = Json.writes[SearchParameters]
  implicit val paramsReads: Reads[SearchParameters]   = Json.reads[SearchParameters]

  implicit object SearchParametersFormat extends Format[SearchParameters] {
    override def writes(o: SearchParameters): JsValue = {

      var original = Json.toJson(o)(Json.writes[SearchParameters])
      var updated  = moveUpChallengeParams(o, original)
      updated = moveUpTaskParams(o, updated)

      updated = updated.as[JsObject] - "challengeParams" - "taskParams"
      updated
    }

    private def moveUpChallengeParams(o: SearchParameters, original: JsValue): JsValue = {
      // Move challenge param fields up to top level
      var updated = o.challengeParams.challengeIds match {
        case Some(c) => Utils.insertIntoJson(original, "challengeIds", c, true)
        case None    => original
      }
      updated = o.challengeParams.challengeTags match {
        case Some(c) => Utils.insertIntoJson(updated, "challengeTags", c, true)
        case None    => updated
      }
      updated = o.challengeParams.challengeTagConjunction match {
        case Some(c) => Utils.insertIntoJson(updated, "challengeTagConjunction", c, true)
        case None    => updated
      }
      updated = o.challengeParams.challengeSearch match {
        case Some(r) => Utils.insertIntoJson(updated, "challengeSearch", r, true)
        case None    => updated
      }
      updated = o.challengeParams.challengeEnabled match {
        case Some(r) => Utils.insertIntoJson(updated, "challengeEnabled", r, true)
        case None    => updated
      }
      updated = o.challengeParams.challengeDifficulty match {
        case Some(r) => Utils.insertIntoJson(updated, "challengeDifficulty", r, true)
        case None    => updated
      }
      updated = o.challengeParams.challengeStatus match {
        case Some(r) => Utils.insertIntoJson(updated, "challengeStatus", r, true)
        case None    => updated
      }
      updated = Utils.insertIntoJson(
        updated,
        "requiresLocal",
        o.challengeParams.requiresLocal
          .getOrElse(SearchParameters.CHALLENGE_REQUIRES_LOCAL_EXCLUDE),
        true
      )
      updated
    }

    private def moveUpTaskParams(o: SearchParameters, original: JsValue): JsValue = {
      // Move task param fields up to top level

      var updated = original
      updated = o.taskParams.taskTags match {
        case Some(c) => Utils.insertIntoJson(updated, "taskTags", c, true)
        case None    => updated
      }
      updated = o.taskParams.taskTagConjunction match {
        case Some(c) => Utils.insertIntoJson(updated, "taskTagConjunction", c, true)
        case None    => updated
      }
      updated = o.taskParams.taskSearch match {
        case Some(c) => Utils.insertIntoJson(original, "taskSearch", c, true)
        case None    => original
      }
      updated = o.taskParams.taskStatus match {
        case Some(c) => Utils.insertIntoJson(updated, "taskStatus", c, true)
        case None    => updated
      }
      updated = o.taskParams.taskId match {
        case Some(c) => Utils.insertIntoJson(updated, "taskId", c, true)
        case None    => updated
      }
      updated = o.taskParams.taskReviewStatus match {
        case Some(c) => Utils.insertIntoJson(updated, "taskReviewStatus", c, true)
        case None    => updated
      }
      updated = o.taskParams.taskProperties match {
        case Some(c) => Utils.insertIntoJson(updated, "taskProperties", c, true)
        case None    => updated
      }
      updated = o.taskParams.taskPropertySearchType match {
        case Some(c) => Utils.insertIntoJson(updated, "taskPropertySearchType", c, true)
        case None    => updated
      }
      updated = o.taskParams.taskPropertySearch match {
        case Some(c) => Utils.insertIntoJson(updated, "taskPropertySearch", c, true)
        case None    => updated
      }
      updated = o.taskParams.taskPriorities match {
        case Some(c) => Utils.insertIntoJson(updated, "taskPriorities", c, true)
        case None    => updated
      }

      updated
    }

    override def reads(json: JsValue): JsResult[SearchParameters] = {
      implicit val challengeParamsReads: Reads[SearchChallengeParameters] =
        Json.reads[SearchChallengeParameters]
      implicit val taskParamsReads: Reads[SearchTaskParameters] =
        Json.reads[SearchTaskParameters]
      implicit val reviewParamsReads: Reads[SearchReviewParameters] =
        Json.reads[SearchReviewParameters]
      implicit val leaderboardParamsReads: Reads[SearchLeaderboardParameters] =
        Json.reads[SearchLeaderboardParameters]

      val challengeParams = constructChallengeParams(json)
      val taskParams      = constructTaskParams(json)

      var jsonWithAdditionalParams =
        Utils.insertIntoJson(json, "challengeParams", challengeParams, false)
      jsonWithAdditionalParams =
        Utils.insertIntoJson(jsonWithAdditionalParams, "taskParams", taskParams, false)
      jsonWithAdditionalParams = Utils.insertIntoJson(
        jsonWithAdditionalParams,
        "reviewParams",
        Map[String, JsValue](),
        false
      )
      jsonWithAdditionalParams = Utils.insertIntoJson(
        jsonWithAdditionalParams,
        "leaderboardParams",
        Map[String, JsValue]("onlyEnabled" -> JsBoolean(true)),
        false
      )

      Json.fromJson[SearchParameters](jsonWithAdditionalParams)(Json.reads[SearchParameters])
    }
  }

  private def constructChallengeParams(json: JsValue): Map[String, JsValue] = {
    var challengeParams = Map[String, JsValue]()
    var challengeFields = (new SearchChallengeParameters).getClass.getDeclaredFields

    challengeFields.foreach(field => {
      (json \ field.getName).toOption match {
        case Some(v) => challengeParams = challengeParams + (field.getName -> v)
        case None    => // do nothing
      }
    })

    challengeParams
  }

  private def constructTaskParams(json: JsValue): Map[String, JsValue] = {
    var taskParams = Map[String, JsValue]()
    var taskFields = (new SearchTaskParameters).getClass.getDeclaredFields

    taskFields.foreach(field => {
      (json \ field.getName).toOption match {
        case Some(v) => taskParams = taskParams + (field.getName -> v)
        case None    => // do nothing
      }
    })

    taskParams
  }

  /**
    * Retrieves the search cookie from the cookie list and creates a search parameter object
    * to send along with the request. It will also check the query string and if any parameters
    * are found it will override the values in the cookie.
    *
    * @param block   The block of code to be executed after the cookie has been retrieved
    * @param request The request that the cookie came in on
    * @tparam T The response type from the block of code
    * @return The response from the block of code
    */
  // format: off
  def withSearch[T](block: SearchParameters => T)(implicit request: Request[AnyContent]): T = {
    val params = request.cookies.get("search") match {
      case Some(c) => convert(c.value)
      case None => SearchParameters()
    }

    val projectIds = request.getQueryString("pid") match {
      case Some(q) => Utils.toLongList(q)
      case None => params.projectIds
    }

    val challengeIds = request.getQueryString("cid") match {
      case Some(q) => Utils.toLongList(q)
      case None => params.challengeParams.challengeIds
    }

    val archived = request.getQueryString("ca") match {
      case Some(q) => {
        if (q == "true") {
          true
        } else {
          false
        }
      }
      case None => false
    }

    var taskPropertySearch:Option[TaskPropertySearch] = None

    // If we are submitting the taskPropertySearch JSON as POST form data
    if (request.method == "POST") {
      request.body match {
        case AnyContentAsFormUrlEncoded(formData) =>
          val tpsData = formData("taskPropertySearch")
          if (tpsData.length > 0 && tpsData.head != "{}") {
            taskPropertySearch =
              Json.fromJson[TaskPropertySearch](Json.parse(tpsData.head)) match {
                case JsSuccess(tps, _) => {
                  Some(tps)
                }
                case e: JsError =>
                  throw new InvalidException(s"Unable to create TaskPropertySearch from JSON: ${JsError toJson e}")
              }
          }
        case _ => None
      }
    }
    // Otherwise if submitted as PUT data.
    else {
      taskPropertySearch = (request.body.asJson) match {
        case Some(v) =>
          (v \ "taskPropertySearch").toOption match {
            case Some(result) => {
              Json.fromJson[TaskPropertySearch](result) match {
                case JsSuccess(tps, _) => {
                  Some(tps)
                }
                case e: JsError =>
                  throw new InvalidException(s"Unable to create TaskPropertySearch from JSON: ${JsError toJson e}")
              }
            }
            case None => None
          }
        case None => None
      }
    }

    block(SearchParameters(
      //projectID
      projectIds,
      //projectSearch
      this.getStringParameter(request.getQueryString("ps"), params.projectSearch),
      //projectEnabled
      this.getBooleanParameter(request.getQueryString("pe"), params.projectEnabled),
      new SearchChallengeParameters(
        //challengeID
        challengeIds,
        //challengeTags
        request.getQueryString("ct") match {
          case Some(v) => Some(v.split(",").toList)
          case None => params.challengeParams.challengeTags
        },
        //challengeTagConjunction
        this.getBooleanParameter(request.getQueryString("ctc"), Some(params.challengeParams.challengeTagConjunction.getOrElse(false))),
        //challengeSearch
        this.getStringParameter(request.getQueryString("cs"), params.challengeParams.challengeSearch),
        //challengeEnabled
        this.getBooleanParameter(request.getQueryString("ce"), params.challengeParams.challengeEnabled),
        //challengeDifficulty
        this.getIntParameter(request.getQueryString("cd"), params.challengeParams.challengeDifficulty),
        //challengeStatus
        request.getQueryString("cStatus") match {
          case Some(v) => Utils.toIntList(v)
          case None => params.challengeParams.challengeStatus
        },
        //requiresLocal
        this.getIntParameter(request.getQueryString("cLocal"), Some(params.challengeParams.requiresLocal.getOrElse(SearchParameters.CHALLENGE_REQUIRES_LOCAL_EXCLUDE))),
        //includeArchived
        archived
      ),
      new SearchTaskParameters(
      //taskTags
        request.getQueryString("tt") match {
          case Some(v) => Some(v.split(",").toList)
          case None => params.taskParams.taskTags
        },
        //taskTagConjunction
        this.getBooleanParameter(request.getQueryString("ttc"), Some(params.taskParams.taskTagConjunction.getOrElse(false))),
        //taskSearch
        this.getStringParameter(request.getQueryString("ts"), params.taskParams.taskSearch),
        //taskStatus
        request.getQueryString("tStatus") match {
          case Some(v) => Utils.toIntList(v)
          case None => params.taskParams.taskStatus
        },
        //taskIds
        this.getLongParameter(request.getQueryString("tid"), params.taskParams.taskId),
        //taskReviewStatus
        request.getQueryString("trStatus") match {
          case Some(v) => Utils.toIntList(v)
          case None => params.taskParams.taskReviewStatus
        },
        //taskProperties (base 64 encoded key:value comma separated)
        request.getQueryString("tProps") match {
          case Some(v) => Utils.toMap(v)
          case None => params.taskParams.taskProperties
        },
        //taskPropertySearchType
        this.getStringParameter(request.getQueryString("tPropsSearchType"),
                                params.taskParams.taskPropertySearchType),
        //taskPropertySearch
        taskPropertySearch,
        //taskPriorities
        request.getQueryString("priorities") match {
          case Some(v) => Utils.toIntList(v)
          case None => params.taskParams.taskPriorities
        },

        request.getQueryString("tExcl") match {
          case Some(ids) => Utils.toLongList(ids)
          case None => params.taskParams.excludeTaskIds
        }
      ),
      // Search Review Parameters
      new SearchReviewParameters(
        request.getQueryString("users") match {
          case Some(r) => Utils.toLongList(r)
          case None => params.reviewParams.mappers
        },
        request.getQueryString("reviewers") match {
          case Some(r) => Utils.toLongList(r)
          case None => params.reviewParams.reviewers
        },
        this.getStringParameter(request.getQueryString("startDate"), params.reviewParams.startDate),
        this.getStringParameter(request.getQueryString("endDate"), params.reviewParams.endDate),
        request.getQueryString("metaReviewers") match {
          case Some(r) => Utils.toLongList(r)
          case None => params.reviewParams.metaReviewers
        },
        //metaReviewStatus
        request.getQueryString("mrStatus") match {
          case Some(v) => Utils.toIntList(v)
          case None => params.reviewParams.metaReviewStatus
        }
      ),
      // Search Leaderboard Parameters
      new SearchLeaderboardParameters(
        request.getQueryString("userIds") match {
          case Some(u) => Utils.toLongList(u)
          case None => params.leaderboardParams.userFilter
        },
        request.getQueryString("projectIds") match {
          case Some(p) => Utils.toLongList(p)
          case None => params.leaderboardParams.projectFilter
        },
        request.getQueryString("challengeIds") match {
          case Some(c) => Utils.toLongList(c)
          case None => params.leaderboardParams.challengeFilter
        },
        request.getQueryString("countryCodes") match {
          case Some(cc) => Utils.toStringList(cc)
          case None => params.leaderboardParams.countryCodeFilter
        },
        this.getIntParameter(request.getQueryString("monthDuration"), params.leaderboardParams.monthDuration),
        this.getDateParameter(request.getQueryString("start"), params.leaderboardParams.start),
        this.getDateParameter(request.getQueryString("end"), params.leaderboardParams.end),
        this.getBooleanParameter(request.getQueryString("onlyEnabled"), Some(params.leaderboardParams.onlyEnabled)).getOrElse(true)
      ),
      //taskPriority
      this.getIntParameter(request.getQueryString("tp"), params.priority),
      //taskBoundingBox for tasks found in bounding Box
      request.getQueryString("tbb") match {
        case Some(v) if v.nonEmpty =>
          v.split(",") match {
            case x if x.size == 4 => Some(SearchLocation(x(0).toDouble, x(1).toDouble, x(2).toDouble, x(3).toDouble))
            case _ => None
          }
        case _ => params.location
      },
      //boundingBox for Challenges bounds contained in bounding box
      request.getQueryString("bb") match {
        case Some(v) if v.nonEmpty =>
          v.split(",") match {
            case x if x.size == 4 => Some(SearchLocation(x(0).toDouble, x(1).toDouble, x(2).toDouble, x(3).toDouble))
            case _ => None
          }
        case _ => None
      },
      // boundingGeometries (not supported on URL)
      None,
      //FuzzySearch
      this.getIntParameter(request.getQueryString("fuzzy"), params.fuzzySearch),
      //CompletedBy
      this.getStringParameter(request.getQueryString("m"), params.mapper),
      //Owner
      this.getStringParameter(request.getQueryString("o"), params.owner),
      //Reviewer
      this.getStringParameter(request.getQueryString("r"), params.reviewer),
      //Meta-Reviewer
      this.getStringParameter(request.getQueryString("mr"), params.metaReviewer),
      // Fields to invert
      request.getQueryString("invf") match {
        case Some(q) => Utils.toStringList(q)
        case None => params.invertFields
      }
    ))
  }
  // format: off

  /**
    * Will attempt to convert the cookie to SearchParameters, if it fails it simply initializes an
    * empty SearchParameters
    *
    * @param value
    * @return
    */
  def convert(value: String): SearchParameters = {
    try {
      Utils.omitEmpty(Json.parse(URLDecoder.decode(value, "UTF-8")).as[JsObject], false, false).as[SearchParameters]
    } catch {
      case e: Exception =>
        SearchParameters()
    }
  }

  /**
    * If we don't want to use default taskStatus (0,3,6) but want to use all then
    * this method will copy the params and force all statuses (by creating an
    * empty list as opposed to None).
    * @see SearchParametersMixin.paramsTaskStatus for where the limiting is determined
    */
  def withDefaultAllTaskStatuses(params: SearchParameters): SearchParameters = {
    val taskStatuses = params.taskParams.taskStatus match {
      case Some(ts) => ts
      case None => List[Int]()
    }

    params.copy(
      taskParams = params.taskParams.copy(taskStatus = Some(taskStatuses))
    )
  }

  private def getBooleanParameter(value: Option[String], default: Option[Boolean]): Option[Boolean] = value match {
    case Some(v) => Some(v.toBoolean)
    case None => default
  }

  private def getIntParameter(value: Option[String], default: Option[Int]): Option[Int] = value match {
    case Some(v) => Some(v.toInt)
    case None => default
  }

  private def getStringParameter(value: Option[String], default: Option[String]): Option[String] = value match {
    case Some(v) => Some(v)
    case None => default
  }

  private def getLongParameter(value: Option[String], default: Option[Long]): Option[Long] = value match {
    case Some(v) => Some(v.toLong)
    case None => default
  }

  private def getDateParameter(value: Option[String], default: Option[DateTime]): Option[DateTime] = value match {
    case Some(v) => Utils.getDate(v)
    case None => default
  }
}

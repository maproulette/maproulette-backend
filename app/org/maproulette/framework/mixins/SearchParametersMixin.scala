/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.mixins

import org.maproulette.session.{SearchParameters, SearchLocation}
import org.maproulette.framework.psql.SQLUtils
import org.maproulette.framework.psql.filter._
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.{AND, OR}
import org.maproulette.framework.model.{TaskReview, Project, Challenge, Task}
import play.api.libs.json.JsDefined

trait SearchParametersMixin {

  /**
    * Sets up a where clause searching by given SearchParameters
    */
  def filterOnSearchParameters(
      params: SearchParameters
  )(implicit projectSearch: Boolean = true): Query = {
    var filterList = List(
      this.filterLocation(params),
      this.filterBounding(params),
      this.filterTaskStatus(params),
      this.filterTaskId(params),
      this.filterTaskFeatureId(params),
      this.filterProjectSearch(params),
      this.filterTaskReviewStatus(params),
      this.filterMetaReviewStatus(params),
      this.filterOwner(params),
      this.filterReviewer(params),
      this.filterMetaReviewer(params),
      this.filterMapper(params),
      this.filterTaskPriorities(params),
      this.filterTaskTags(params),
      this.filterPriority(params),
      this.filterChallengeDifficulty(params),
      this.filterChallengeStatus(params),
      this.filterChallengeRequiresLocal(params),
      this.filterBoundingGeometries(params),
      this.filterBundleId(params),
      // For efficiency can only query on task properties with a parent challenge id
      this.filterTaskProps(params),
      this.filterChallenges(params),
      this.filterChallengeTags(params),
      this.filterChallengeEnabled(params),
      this.filterChallengeArchived(params)
    )

    if (projectSearch) {
      filterList = this.filterProjects(params) :: this.filterProjectEnabled(params) :: filterList
    }

    Query(Filter(filterList))
  }

  def filterChallengeTags(params: SearchParameters): FilterGroup = {
    if (params.hasChallengeTags) {
      val tagList = params.challengeParams.challengeTags.get
        .map(t => {
          SQLUtils.testColumnName(t)
          s"'${t}'"
        })
        .mkString(",")

      val invert = params.invertFields.getOrElse(List()).contains("ct")
      FilterGroup(
        List(
          SubQueryFilter(
            Challenge.FIELD_ID,
            Query.simple(
              List(
                BaseParameter(
                  Task.FIELD_NAME,
                  tagList,
                  Operator.IN,
                  useValueDirectly = true,
                  table = Some("tags")
                )
              ),
              "SELECT challenge_id from tags_on_challenges tc INNER JOIN tags ON tags.id = tc.tag_id"
            ),
            invert,
            Operator.IN,
            Some("c")
          )
        )
      )
    } else {
      FilterGroup(List())
    }
  }

  /**
    * Filters by p.display_name with a like %projectSearch%
    * @param params with inverting on 'ps'
    */
  def filterProjectSearch(params: SearchParameters): FilterGroup = {
    FilterGroup(
      List(
        FilterParameter.conditional(
          Project.FIELD_DISPLAY_NAME,
          s"'${SQLUtils.search(params.projectSearch.getOrElse(""))}'",
          Operator.ILIKE,
          params.invertFields.getOrElse(List()).contains("ps"),
          true,
          params.projectSearch != None,
          Some("p")
        )
      )
    )
  }

  def filterProjects(params: SearchParameters, includeVirtual: Boolean = false): FilterGroup = {
    params.getProjectIds match {
      case Some(p) if p.nonEmpty => {
        val invert = params.invertFields.getOrElse(List()).contains("pid")
        if (!includeVirtual) {
          FilterGroup(
            List(
              BaseParameter(
                Project.FIELD_ID,
                p.mkString(","),
                Operator.IN,
                invert,
                true,
                Some("p")
              )
            )
          )
        } else {
          FilterGroup(
            List(
              BaseParameter(
                Project.FIELD_ID,
                p.mkString(","),
                Operator.IN,
                invert,
                true,
                Some("p")
              ),
              SubQueryFilter(
                Challenge.FIELD_ID,
                Query.simple(
                  List(
                    BaseParameter(
                      "project_id",
                      p.mkString(","),
                      Operator.IN,
                      useValueDirectly = true,
                      table = Some("")
                    )
                  ),
                  "SELECT challenge_id from virtual_project_challenges"
                ),
                invert,
                Operator.IN,
                Some("c")
              )
            ),
            if (invert) AND() else OR()
          )
        }
      }
      case _ =>
        params.projectSearch match {
          case Some(ps) if ps.nonEmpty =>
            params.fuzzySearch match {
              case Some(x) =>
                FilterGroup(
                  List(
                    FuzzySearchParameter(
                      Project.FIELD_DISPLAY_NAME,
                      s"'${ps.replace("'", "''")}'",
                      x,
                      table = Some("p")
                    )
                  )
                )
              case None =>
                FilterGroup(
                  List(
                    FilterParameter.conditional(
                      Project.FIELD_DISPLAY_NAME,
                      s"'${SQLUtils.search(params.projectSearch.getOrElse(""))}'",
                      Operator.ILIKE,
                      params.invertFields.getOrElse(List()).contains("ps"),
                      true,
                      params.projectSearch != None,
                      Some("p")
                    ),
                    CustomParameter(
                      s"(c.id IN " +
                        s"(SELECT vp2.challenge_id FROM virtual_project_challenges vp2 " +
                        s" INNER JOIN projects p2 ON p2.id = vp2.project_id WHERE " +
                        s" LOWER(p2.display_name) LIKE LOWER('${SQLUtils.search(ps)}') AND p2.enabled=true))"
                    )
                  ),
                  OR()
                )
            }
          case _ => FilterGroup(List())
        }
    }
  }

  /**
    * Filters by p.enabled. Will only include if params.projectEnabled value is true
    */
  def filterProjectEnabled(params: SearchParameters): FilterGroup = {
    FilterGroup(
      List(
        FilterParameter.conditional(
          Project.FIELD_ENABLED,
          params.projectEnabled.getOrElse(false),
          Operator.BOOL,
          useValueDirectly = true,
          includeOnlyIfTrue = params.projectEnabled.getOrElse(false),
          table = Some("p")
        )
      )
    )
  }

  /**
    * Filters by tasks.location with a @ MAKE_ENVELOPE etc.
    * @param params with inverting on 'tbb'
    */
  def filterLocation(params: SearchParameters): FilterGroup = {
    FilterGroup(
      List(
        this.locationSearch(
          Task.FIELD_LOCATION,
          params.location,
          Task.TABLE,
          params.invertFields.getOrElse(List()).contains("tbb")
        )
      )
    )
  }

  /**
    * Filters by c.bounding with a @ MAKE_ENVELOPE etc.
    * @param params with inverting on 'bb'
    */
  def filterBounding(params: SearchParameters): FilterGroup = {
    FilterGroup(
      List(
        this.locationSearch(
          "bounding",
          params.bounding,
          "c",
          params.invertFields.getOrElse(List()).contains("bb")
        )
      )
    )
  }

  /**
    * Filters by tasks.status
    * @param params with inverting on 'tStatus'
    * @param defaultStatus - used when no params.taskStatus is given, if you want to use all statuses
    *                        in this case pass in List()
    */
  def filterTaskStatus(
      params: SearchParameters,
      defaultStatuses: List[Int] = List(0, 3, 6)
  ): FilterGroup = {
    // Do not filter if taskStatus list has -1 or list is empty
    val dontFilter =
      params.taskParams.taskStatus.getOrElse(defaultStatuses).isEmpty ||
        params.taskParams.taskStatus.getOrElse(defaultStatuses).contains(-1)

    FilterGroup(
      List(
        FilterParameter.conditional(
          Task.FIELD_STATUS,
          // If taskStatus is not present then default taskStatus to 0,3,6
          params.taskParams.taskStatus.getOrElse(defaultStatuses).mkString(","),
          Operator.IN,
          params.invertFields.getOrElse(List()).contains("tStatus"),
          true,
          !dontFilter,
          Some(Task.TABLE)
        )
      )
    )
  }

  /**
    * Filters by tasks.id
    * @param params with inverting on 'tid'
    */
  def filterTaskId(params: SearchParameters): FilterGroup = {
    val invert = if (params.invertFields.getOrElse(List()).contains("tid")) "NOT " else ""

    params.taskParams.taskId match {
      case Some(tid) =>
        FilterGroup(
          List(
            CustomParameter(s"${invert}CAST(${Task.TABLE}.${Task.FIELD_ID} AS TEXT) LIKE '${tid}%'")
          )
        )
      case _ => FilterGroup(List())
    }
  }

  /**
    * Filters by tasks.name
    * @param params with inverting on 'fid'
    */
  def filterTaskFeatureId(params: SearchParameters): FilterGroup = {
    params.taskParams.taskFeatureId match {
      case Some(fid) =>
        FilterGroup(
          List(
            CustomParameter(
              s"LOWER(TRIM(${Task.TABLE}.${Task.FIELD_NAME}::TEXT)) LIKE LOWER('%${fid.trim}%')"
            )
          )
        )
      case None => FilterGroup(List())
    }
  }

  /**
    * Filters by bundle id
    * @param params with inverting on 'bid'
    */
  def filterBundleId(params: SearchParameters): FilterGroup = {
    params.taskParams.bundleId match {
      case Some(bid) =>
        FilterGroup(
          List(
            CustomParameter(s"${Task.TABLE}.${Task.FIELD_BUNDLE_ID} = $bid")
          )
        )
      case _ => FilterGroup(List())
    }
  }

  /**
    * Filters by tasks.priority
    * @param params with inverting on 'priorities'
    */
  def filterTaskPriorities(params: SearchParameters): FilterGroup = {
    FilterGroup(
      List(
        FilterParameter.conditional(
          Task.FIELD_PRIORITY,
          params.taskParams.taskPriorities.getOrElse(List()).mkString(","),
          Operator.IN,
          params.invertFields.getOrElse(List()).contains("priorities"),
          true,
          params.taskParams.taskPriorities != None &&
            !params.taskParams.taskPriorities.getOrElse(List()).isEmpty,
          Some(Task.TABLE)
        )
      )
    )
  }

  /**
    * Filters by tasks.priority
    * @param params with inverting on 'tp'
    */
  def filterPriority(params: SearchParameters): FilterGroup = {
    FilterGroup(
      List(
        FilterParameter.conditional(
          Task.FIELD_PRIORITY,
          params.priority.getOrElse("").toString,
          Operator.EQ,
          params.invertFields.getOrElse(List()).contains("tp"),
          true,
          params.priority != None &&
            (params.priority.get == 0 || params.priority.get == 1 || params.priority.get == 2),
          Some(Task.TABLE)
        )
      )
    )
  }

  /**
    * Filters by task tags
    * @param params with inverting on 'tt'
    */
  def filterTaskTags(params: SearchParameters): FilterGroup = {
    if (params.hasTaskTags) {
      val tagList = params.taskParams.taskTags.get
        .map(t => {
          SQLUtils.testColumnName(t)
          s"'${t.trim.toLowerCase()}'"
        })
        .mkString(",")

      val invert = params.invertFields.getOrElse(List()).contains("tt")
      FilterGroup(
        List(
          SubQueryFilter(
            Task.FIELD_ID,
            Query.simple(
              List(
                BaseParameter(
                  Task.FIELD_NAME,
                  tagList,
                  Operator.IN,
                  useValueDirectly = true,
                  table = Some("tags")
                )
              ),
              "SELECT task_id from tags_on_tasks tt INNER JOIN tags ON tags.id = tt.tag_id"
            ),
            invert,
            Operator.IN,
            Some(Task.TABLE)
          )
        )
      )
    } else {
      FilterGroup(List())
    }
  }

  /**
    * Filters by task review status
    * @param params with inverting on 'trStatus'
    */
  def filterTaskReviewStatus(params: SearchParameters): FilterGroup = {
    params.taskParams.taskReviewStatus match {
      case Some(statuses) if statuses.nonEmpty =>
        val invert = params.invertFields.getOrElse(List()).contains("trStatus")
        val query =
          FilterGroup(
            List(
              SubQueryFilter(
                Task.FIELD_ID,
                Query.simple(
                  List(
                    BaseParameter(
                      "task_id",
                      s"${Task.TABLE}.${Task.FIELD_ID}",
                      useValueDirectly = true,
                      table = Some(TaskReview.TABLE)
                    ),
                    BaseParameter(
                      TaskReview.FIELD_REVIEW_STATUS,
                      statuses.mkString(","),
                      Operator.IN,
                      useValueDirectly = true,
                      table = Some(TaskReview.TABLE)
                    )
                  ),
                  "SELECT task_id FROM task_review"
                ),
                invert,
                Operator.IN,
                Some(Task.TABLE)
              ),
              ConditionalFilterParameter(
                SubQueryFilter(
                  Task.FIELD_ID,
                  Query.simple(
                    List(
                      BaseParameter(
                        "task_id",
                        s"${Task.TABLE}.${Task.FIELD_ID}",
                        useValueDirectly = true,
                        table = Some(TaskReview.TABLE)
                      )
                    ),
                    "SELECT task_id FROM task_review task_review"
                  ),
                  !invert,
                  Operator.IN,
                  Some(Task.TABLE)
                ),
                statuses.contains(-1)
              )
            ),
            if (invert) AND() else OR()
          )
        query
      case Some(statuses) if statuses.isEmpty => FilterGroup(List())
      case _                                  => FilterGroup(List())
    }
  }

  /**
    * Filters by meta review status
    * @param params with inverting on 'mrStatus'
    */
  def filterMetaReviewStatus(params: SearchParameters): FilterGroup = {
    params.reviewParams.metaReviewStatus match {
      case Some(statuses) if statuses.nonEmpty =>
        val invert = params.invertFields.getOrElse(List()).contains("mrStatus")
        val query =
          FilterGroup(
            List(
              SubQueryFilter(
                Task.FIELD_ID,
                Query(
                  Filter(
                    List(
                      FilterGroup(
                        List(
                          BaseParameter(
                            "task_id",
                            s"${Task.TABLE}.${Task.FIELD_ID}",
                            useValueDirectly = true,
                            table = Some(TaskReview.TABLE)
                          )
                        )
                      ),
                      FilterGroup(
                        List(
                          BaseParameter(
                            TaskReview.FIELD_META_REVIEW_STATUS,
                            statuses.mkString(","),
                            Operator.IN,
                            negate = false,
                            useValueDirectly = true,
                            table = Some(TaskReview.TABLE)
                          ),
                          FilterParameter.conditional(
                            TaskReview.FIELD_META_REVIEW_STATUS,
                            None,
                            Operator.NULL,
                            negate = false,
                            useValueDirectly = true,
                            includeOnlyIfTrue = statuses.contains(Task.META_REVIEW_STATUS_NOT_SET),
                            table = Some(TaskReview.TABLE)
                          )
                        ),
                        OR()
                      )
                    )
                  ),
                  "SELECT task_id FROM task_review"
                ),
                negate = invert,
                Operator.IN,
                Some(Task.TABLE)
              ),
              ConditionalFilterParameter(
                SubQueryFilter(
                  Task.FIELD_ID,
                  Query.simple(
                    List(
                      BaseParameter(
                        "task_id",
                        s"${Task.TABLE}.${Task.FIELD_ID}",
                        useValueDirectly = true,
                        table = Some(TaskReview.TABLE)
                      )
                    ),
                    "SELECT task_id FROM task_review task_review"
                  ),
                  negate = !invert,
                  Operator.IN,
                  Some(Task.TABLE)
                ),
                statuses.contains(Task.REVIEW_STATUS_NOT_REQUESTED)
              )
            ),
            if (invert) AND() else OR()
          )
        query
      case Some(statuses) if statuses.isEmpty => FilterGroup(List())
      case _                                  => FilterGroup(List())
    }
  }

  /**
    * Filters by c.difficulty
    * @param params with inverting on 'cd'
    */
  def filterChallengeDifficulty(params: SearchParameters): FilterGroup = {
    FilterGroup(
      List(
        FilterParameter.conditional(
          "difficulty",
          params.challengeParams.challengeDifficulty.getOrElse("").toString,
          Operator.EQ,
          params.invertFields.getOrElse(List()).contains("cd"),
          true,
          params.challengeParams.challengeDifficulty != None,
          Some("c")
        )
      )
    )
  }

  /**
    * Filters by c.status
    * @param params with inverting on 'cStatus'
    */
  def filterChallengeStatus(params: SearchParameters): FilterGroup = {
    val searchList = params.challengeParams.challengeStatus.getOrElse(List())
    val invert     = params.invertFields.getOrElse(List()).contains("cStatus")

    FilterGroup(
      List(
        BaseParameter(
          Challenge.FIELD_STATUS,
          searchList.mkString(","),
          Operator.IN,
          invert,
          true,
          Some("c")
        ),
        FilterParameter.conditional(
          Challenge.FIELD_STATUS,
          None,
          Operator.NULL,
          invert,
          true,
          searchList.contains(-1),
          Some("c")
        )
      ),
      (if (invert) AND() else OR()),
      params.challengeParams.challengeStatus != None
    )
  }

  def filterChallenges(params: SearchParameters): FilterGroup = {
    params.getChallengeIds match {
      case Some(c) if c.nonEmpty =>
        FilterGroup(
          List(
            BaseParameter(
              Challenge.FIELD_ID,
              c.mkString(","),
              Operator.IN,
              params.invertFields.getOrElse(List()).contains("cid"),
              true,
              Some("c")
            )
          )
        )
      case _ =>
        params.challengeParams.challengeSearch match {
          case Some(cs) if cs.nonEmpty =>
            params.fuzzySearch match {
              case Some(x) =>
                FilterGroup(
                  List(
                    FuzzySearchParameter(
                      Challenge.FIELD_NAME,
                      cs,
                      x,
                      table = Some("c")
                    )
                  )
                )
              case None =>
                FilterGroup(
                  List(
                    BaseParameter(
                      Challenge.FIELD_NAME,
                      s"'${SQLUtils.search(cs)}'",
                      Operator.ILIKE,
                      useValueDirectly = true,
                      negate = params.invertFields.getOrElse(List()).contains("cs"),
                      table = Some("c")
                    )
                  )
                )
            }
          case _ => FilterGroup(List())
        }
    }
  }

  /**
    * Filters by c.enabled. Will only include if
    * challengeParams.challengeEnabled value is true
    */
  def filterChallengeEnabled(params: SearchParameters): FilterGroup = {
    FilterGroup(
      List(
        FilterParameter.conditional(
          Challenge.FIELD_ENABLED,
          params.challengeParams.challengeEnabled.getOrElse(false),
          Operator.BOOL,
          useValueDirectly = true,
          includeOnlyIfTrue = params.challengeParams.challengeEnabled.getOrElse(false),
          table = Some("c")
        )
      )
    )
  }

  /**
    * Filters by c.is_archived. Will only include if
    * challengeParams.archived value is true
    */
  def filterChallengeArchived(params: SearchParameters): FilterGroup = {
    if (params.challengeParams.archived.getOrElse("false") == "false") {
      FilterGroup(
        List(
          FilterParameter.conditional(
            Challenge.FIELD_ARCHIVED,
            value = "false",
            Operator.EQ,
            useValueDirectly = true,
            includeOnlyIfTrue = true,
            table = Some("c")
          )
        )
      )
    } else {
      FilterGroup(List())
    }
  }

  /**
    * Filters by c.requires_local
    */
  def filterChallengeRequiresLocal(params: SearchParameters): FilterGroup = {
    FilterGroup(
      List(
        params.challengeParams.challengeIds match {
          case Some(ids) if ids.nonEmpty => CustomParameter("")
          // do nothing, we don't want to restrict to requiresLocal if we have
          // specific challenge ids
          case _ =>
            params.challengeParams.requiresLocal match {
              case Some(SearchParameters.CHALLENGE_REQUIRES_LOCAL_EXCLUDE) =>
                BaseParameter(
                  "requires_local",
                  None,
                  Operator.BOOL,
                  true, // negate
                  table = Some("c")
                )
              case Some(SearchParameters.CHALLENGE_REQUIRES_LOCAL_ONLY) =>
                BaseParameter(
                  "requires_local",
                  None,
                  Operator.BOOL,
                  false, // don't negate
                  table = Some("c")
                )
              case _ => CustomParameter("")
            }
        }
      )
    )
  }

  /**
    * Filters on tasks.location @ GEOMETRY
    */
  def filterBoundingGeometries(params: SearchParameters): FilterGroup = {
    params.boundingGeometries match {
      case Some(bp) =>
        val allPolygons = new StringBuilder()
        bp.foreach(value =>
          value \ "bounding" match {
            case locationJSON: JsDefined => {
              val polygonLinestring = new StringBuilder()
              if (allPolygons.toString() != "")
                allPolygons.append(" OR ")

              (locationJSON \ "type").as[String] match {
                case "Polygon" => {
                  (locationJSON \ "coordinates")
                    .as[List[List[List[Double]]]]
                    .foreach(p =>
                      p.foreach(coordinates => {
                        if (polygonLinestring.toString() != "")
                          polygonLinestring.append(",")
                        polygonLinestring.append(s"${coordinates.head} ${coordinates(1)}")
                      })
                    )
                  allPolygons.append(
                    s"tasks.location @ ST_MakePolygon( ST_GeomFromText('LINESTRING($polygonLinestring)'))"
                  )
                }
                case "LineString" => {
                  (locationJSON \ "coordinates")
                    .as[List[List[Double]]]
                    .foreach(coordinates => {
                      if (polygonLinestring.toString() != "") {
                        polygonLinestring.append(",")
                      }
                      polygonLinestring.append(s"${coordinates.head} ${coordinates(1)}")
                    })
                  allPolygons.append(
                    s"tasks.location @ ST_GeomFromText('LINESTRING($polygonLinestring)')"
                  )
                }
                case "Point" => {
                  val coordinates = (locationJSON \ "coordinates").as[List[Double]]
                  allPolygons.append(
                    s"tasks.location @ ST_GeomFromText('POINT(${coordinates.head} ${coordinates(1)})')"
                  )
                }
                case _ => // do nothing
              }
            }
            case _ => // do nothing
          }
        )
        FilterGroup(List(CustomParameter("(" + allPolygons.toString + ")")))
      case _ => FilterGroup(List())
    }
  }

  /**
    * Filters on tasks features
    */
  def filterTaskProps(params: SearchParameters): FilterGroup = {
    params.getChallengeIds match {
      case Some(l) =>
        params.taskParams.taskPropertySearch match {
          case Some(tps) =>
            val query = new StringBuilder(s"""${Task.TABLE}.${Task.FIELD_ID} IN (
                | SELECT id FROM tasks,
                | jsonb_array_elements(geojson->'features') features
                | WHERE parent_id IN (${l.mkString(",")})
                | AND (${tps.toSQL}))""".stripMargin)
            FilterGroup(List(CustomParameter(query.toString())))
          case _ =>
            params.taskParams.taskProperties match {
              case Some(tp) =>
                val searchType = params.taskParams.taskPropertySearchType.getOrElse("equals")

                val query = new StringBuilder(s"""${Task.TABLE}.${Task.FIELD_ID} IN (
                    | SELECT id FROM tasks,
                    | jsonb_array_elements(geojson->'features') features
                    | WHERE parent_id IN (${l.mkString(",")})
                    | AND (true""".stripMargin)
                for ((k, v) <- tp) {
                  searchType match {
                    case SearchParameters.TASK_PROP_SEARCH_TYPE_EQUALS =>
                      query ++= s" AND features->'properties'->>'${k}' = '${v}' "
                    case SearchParameters.TASK_PROP_SEARCH_TYPE_NOT_EQUAL =>
                      query ++= s" AND features->'properties'->>'${k}' != '${v}' "
                    case SearchParameters.TASK_PROP_SEARCH_TYPE_CONTAINS =>
                      query ++= s" AND features->'properties'->>'${k}' LIKE '${SQLUtils.search(v)}' "
                    case SearchParameters.TASK_PROP_SEARCH_TYPE_EXISTS =>
                      query ++= s" AND features->'properties'->>'${k}' IS NOT NULL "
                    case SearchParameters.TASK_PROP_SEARCH_TYPE_MISSING =>
                      query ++= s" AND features->'properties'->>'${k}' IS NULL "
                    case _ => // should not happen
                  }
                }
                query ++= "))"
                FilterGroup(List(CustomParameter(query.toString())))
              case _ => FilterGroup(List())
            }
        }
      case None => FilterGroup(List())
    }
  }

  /**
    * Filters on tasks task_review.review_requested_by
    * @param params with inverting on 'o'
    */
  def filterOwner(params: SearchParameters): FilterGroup = {
    params.owner match {
      case Some(o) if o.nonEmpty =>
        this.buildReviewSubQuerySearch(params, TaskReview.FIELD_REVIEW_REQUESTED_BY, o, "o")
      case _ => FilterGroup(List())
    }
  }

  /**
    * Filters on tasks task_review.reviewed_by
    * @param params with inverting on 'r'
    */
  def filterReviewer(params: SearchParameters): FilterGroup = {
    params.reviewer match {
      case Some(r) if r.nonEmpty =>
        this.buildReviewSubQuerySearch(params, TaskReview.FIELD_REVIEWED_BY, r, "r")
      case _ => FilterGroup(List())
    }
  }

  /**
    * Filters on tasks task_review.meta_reviewed_by
    * @param params with inverting on 'r'
    */
  def filterMetaReviewer(params: SearchParameters): FilterGroup = {
    params.metaReviewer match {
      case Some(r) if r.nonEmpty =>
        this.buildReviewSubQuerySearch(params, TaskReview.FIELD_META_REVIEWED_BY, r, "mr")
      case _ => FilterGroup(List())
    }
  }

  /**
    * Filters on tasks.completedBy
    * @param params with inverting on 'm'
    */
  def filterMapper(params: SearchParameters): FilterGroup = {
    params.mapper match {
      case Some(m) if m.nonEmpty =>
        FilterGroup(
          List(
            SubQueryFilter(
              Task.FIELD_ID,
              Query.simple(
                List(
                  BaseParameter(
                    "t2.id",
                    s"${Task.TABLE}.${Task.FIELD_ID}",
                    useValueDirectly = true
                  ),
                  BaseParameter(
                    "u.name",
                    s"'${SQLUtils.search(m)}'",
                    Operator.ILIKE,
                    useValueDirectly = true
                  )
                ),
                "SELECT t2.id FROM tasks t2 INNER JOIN users u ON u.id = t2.completed_by"
              ),
              params.invertFields.getOrElse(List()).contains("m"),
              Operator.IN,
              Some(Task.TABLE)
            )
          )
        )
      case _ => FilterGroup(List())
    }
  }

  /**
    * Filters on task_review.review_requested_by.
    * Will always check that the field is not null
    *
    * @param params with inverting on 'mappers'
    */
  def filterReviewMappers(params: SearchParameters): FilterGroup = {
    FilterGroup(
      List(
        FilterParameter.conditional(
          TaskReview.FIELD_REVIEW_REQUESTED_BY,
          params.reviewParams.mappers.getOrElse(List()).mkString(","),
          Operator.IN,
          params.invertFields.getOrElse(List()).contains("mappers"),
          true,
          !params.reviewParams.mappers.getOrElse(List()).isEmpty,
          Some(TaskReview.TABLE)
        ),
        BaseParameter(
          TaskReview.FIELD_REVIEW_REQUESTED_BY,
          None,
          Operator.NULL,
          true,
          true,
          Some(TaskReview.TABLE)
        )
      )
    )
  }

  /**
    * Filters on task_review.reviewed_by.
    *
    * @param params with inverting on 'reviewers'
    */
  def filterReviewers(params: SearchParameters): FilterGroup = {
    FilterGroup(
      List(
        FilterParameter.conditional(
          TaskReview.FIELD_REVIEWED_BY,
          params.reviewParams.reviewers.getOrElse(List()).mkString(","),
          Operator.IN,
          params.invertFields.getOrElse(List()).contains("reviewers"),
          true,
          !params.reviewParams.reviewers.getOrElse(List()).isEmpty,
          Some(TaskReview.TABLE)
        )
      )
    )
  }

  /**
    * Filters on task_review.meta_reviewed_by.
    *
    * @param params with inverting on 'reviewers'
    */
  def filterMetaReviewers(params: SearchParameters): FilterGroup = {
    FilterGroup(
      List(
        FilterParameter.conditional(
          TaskReview.FIELD_META_REVIEWED_BY,
          params.reviewParams.metaReviewers.getOrElse(List()).mkString(","),
          Operator.IN,
          params.invertFields.getOrElse(List()).contains("metaReviewers"),
          true,
          !params.reviewParams.metaReviewers.getOrElse(List()).isEmpty,
          Some(TaskReview.TABLE)
        )
      )
    )
  }

  /**
    * Filters on task_review.reviewed_at is between startDate and endDate
    */
  def filterReviewDate(params: SearchParameters): FilterGroup = {
    val startDate = params.reviewParams.startDate.getOrElse(null)
    val endDate   = params.reviewParams.endDate.getOrElse(null)

    FilterGroup(
      List(
        FilterParameter.conditional(
          TaskReview.FIELD_REVIEWED_AT,
          "'" + startDate + " 00:00:00'",
          Operator.GTE,
          false,
          true,
          startDate != null &&
            startDate.matches("[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]"),
          Some(TaskReview.TABLE)
        ),
        FilterParameter.conditional(
          TaskReview.FIELD_REVIEWED_AT,
          "'" + endDate + " 23:59:59'",
          Operator.LTE,
          false,
          true,
          endDate != null &&
            endDate.matches("[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]"),
          Some(TaskReview.TABLE)
        )
      )
    )
  }

  /**
    * Filters on task.mappedOn
    */
  def filterMappedOnDate(params: SearchParameters): FilterGroup = {
    val mappedOnDate = params.taskParams.taskMappedOn.getOrElse(null)

    if (mappedOnDate == null) {
      return FilterGroup(List())
    }

    FilterGroup(
      List(
        FilterParameter.conditional(
          Task.FIELD_MAPPED_ON,
          "'" + mappedOnDate + " 00:00:00'",
          Operator.GTE,
          false,
          true,
          mappedOnDate != null &&
            mappedOnDate.matches("[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]"),
          Some(Task.TABLE)
        ),
        FilterParameter.conditional(
          Task.FIELD_MAPPED_ON,
          "'" + mappedOnDate + " 23:59:59'",
          Operator.LTE,
          false,
          true,
          mappedOnDate != null &&
            mappedOnDate.matches("[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]"),
          Some(Task.TABLE)
        )
      )
    )
  }

  /**
    * Private method to build a bounding box search
    */
  private def locationSearch(
      key: String,
      bounds: Option[SearchLocation],
      table: String,
      invert: Boolean = false
  ): Parameter[String] = {
    bounds match {
      case Some(sl) =>
        BaseParameter(
          key,
          s"ST_MakeEnvelope (${sl.left}, ${sl.bottom}, ${sl.right}, ${sl.top}, 4326)",
          Operator.AT,
          invert,
          true,
          Some(table)
        )
      case None => CustomParameter("")
    }
  }

  /**
    * Private method to help build a sub select on task_review and users.
    */
  private def buildReviewSubQuerySearch(
      params: SearchParameters,
      column: String,
      value: String,
      invertKey: String
  ): FilterGroup = {
    FilterGroup(
      List(
        SubQueryFilter(
          Task.FIELD_ID,
          Query.simple(
            List(
              BaseParameter(
                "tr.task_id",
                s"${Task.TABLE}.${Task.FIELD_ID}",
                useValueDirectly = true
              ),
              BaseParameter(
                "u.name",
                s"'${SQLUtils.search(value)}'",
                Operator.ILIKE,
                useValueDirectly = true
              )
            ),
            s"SELECT task_id FROM task_review tr INNER JOIN users u ON u.id = tr.${column}"
          ),
          params.invertFields.getOrElse(List()).contains(invertKey),
          Operator.IN,
          Some(Task.TABLE)
        )
      )
    )
  }
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.mixins

import org.scalatestplus.play.PlaySpec
import org.maproulette.framework.mixins.SearchParametersMixin
import org.maproulette.session._

/**
  * @author krotstan
  */
class SearchParametersMixinSpec() extends PlaySpec with SearchParametersMixin {
  implicit var challengeID: Long = -1

  def normalized(s: String): String = s.replaceAll("(?s)\\s+", " ").trim

  "filterChallengeTags" should {
    "match on challenge tags" in {
      val params =
        SearchParameters(challengeParams =
          SearchChallengeParameters(challengeTags = Some(List("my_tag", "tag2")))
        )
      this.filterChallengeTags(params).sql() mustEqual
        "c.id IN (SELECT challenge_id from tags_on_challenges tc " +
          "INNER JOIN tags ON tags.id = tc.tag_id WHERE tags.name IN ('my_tag','tag2'))"
    }

    "be empty" in {
      this.filterChallengeTags(SearchParameters()).sql() mustEqual ""
    }

    "be inverted" in {
      val params = SearchParameters(
        challengeParams = SearchChallengeParameters(challengeTags = Some(List("my_tag", "tag2"))),
        invertFields = Some(List("ct"))
      )
      this.filterChallengeTags(params).sql() mustEqual
        "NOT c.id IN (SELECT challenge_id from tags_on_challenges tc " +
          "INNER JOIN tags ON tags.id = tc.tag_id WHERE tags.name IN ('my_tag','tag2'))"
    }
  }

  "filterProjectSearch" should {
    "match on project name" in {
      val params = new SearchParameters(projectSearch = Some("my_project"))
      this.filterProjectSearch(params).sql() mustEqual "p.display_name ILIKE '%my_project%'"
    }

    "allow apostrophes in project name" in {
      val params = new SearchParameters(projectSearch = Some("my project's"))
      this.filterProjectSearch(params).sql() mustEqual "p.display_name ILIKE '%my project''s%'"
    }

    "be empty" in {
      this.filterProjectSearch(SearchParameters()).sql() mustEqual ""
    }

    "be inverted" in {
      val params =
        new SearchParameters(projectSearch = Some("my_project"), invertFields = Some(List("ps")))
      this.filterProjectSearch(params).sql() mustEqual "NOT p.display_name ILIKE '%my_project%'"
    }
  }

  "filterLocation" should {
    "match on location" in {
      val params = new SearchParameters(location = Some(SearchLocation(0.0, 0.0, 1.0, 1.0)))
      this.filterLocation(params).sql() mustEqual
        "tasks.location && ST_MakeEnvelope (0.0, 0.0, 1.0, 1.0, 4326)"
    }

    "be inverted" in {
      val params = new SearchParameters(
        location = Some(SearchLocation(0.0, 0.0, 1.0, 1.0)),
        invertFields = Some(List("tbb"))
      )
      this.filterLocation(params).sql() mustEqual
        "NOT tasks.location && ST_MakeEnvelope (0.0, 0.0, 1.0, 1.0, 4326)"
    }

    "be empty" in {
      this.filterLocation(SearchParameters()).sql() mustEqual ""
    }
  }

  "filterBounding" should {
    "match on location" in {
      val params = new SearchParameters(bounding = Some(SearchLocation(0.0, 0.0, 1.0, 1.0)))
      this.filterBounding(params).sql() mustEqual
        "c.bounding && ST_MakeEnvelope (0.0, 0.0, 1.0, 1.0, 4326)"
    }

    "be inverted" in {
      val params = new SearchParameters(
        bounding = Some(SearchLocation(0.0, 0.0, 1.0, 1.0)),
        invertFields = Some(List("bb"))
      )
      this.filterBounding(params).sql() mustEqual
        "NOT c.bounding && ST_MakeEnvelope (0.0, 0.0, 1.0, 1.0, 4326)"
    }

    "be empty" in {
      this.filterBounding(SearchParameters()).sql() mustEqual ""
    }
  }

  "filterTaskStatus" should {
    "match on task status" in {
      val params =
        SearchParameters(taskParams = SearchTaskParameters(taskStatus = Some(List(1, 2))))
      this.filterTaskStatus(params).sql() mustEqual "tasks.status IN (1,2)"
    }

    "use default open statuses if no params given" in {
      val params = SearchParameters()
      this.filterTaskStatus(params).sql() mustEqual "tasks.status IN (0,3,6)"
    }

    "be empty if given an empty default statuses" in {
      this.filterTaskStatus(SearchParameters(), List()).sql() mustEqual ""
    }

    "be empty if statuses include -1" in {
      val params = SearchParameters(taskParams = SearchTaskParameters(taskStatus = Some(List(-1))))
      this.filterTaskStatus(params).sql() mustEqual ""
    }

    "be inverted" in {
      val params = SearchParameters(
        taskParams = SearchTaskParameters(taskStatus = Some(List(1, 2))),
        invertFields = Some(List("tStatus"))
      )
      this.filterTaskStatus(params).sql() mustEqual "NOT tasks.status IN (1,2)"
    }
  }

  "filterTaskId" should {
    "match on task id" in {
      val params = SearchParameters(taskParams = SearchTaskParameters(taskId = Some(123)))
      this.filterTaskId(params).sql() mustEqual "CAST(tasks.id AS TEXT) LIKE '123%'"
    }

    "be empty" in {
      this.filterTaskId(SearchParameters()).sql() mustEqual ""
    }

    "be inverted" in {
      val params = SearchParameters(
        taskParams = SearchTaskParameters(taskId = Some(12345)),
        invertFields = Some(List("tid"))
      )
      this.filterTaskId(params).sql() mustEqual "NOT CAST(tasks.id AS TEXT) LIKE '12345%'"
    }
  }

  "filterTaskFeatureId" should {
    "match on task feature id" in {
      val params = SearchParameters(taskParams = SearchTaskParameters(taskFeatureId = Some("123")))
      this
        .filterTaskFeatureId(params)
        .sql() mustEqual s"LOWER(TRIM(tasks.name::TEXT)) LIKE LOWER('%123%')"
    }
  }

  "filterTaskPriorities" should {
    "match on task priority" in {
      val params =
        SearchParameters(taskParams = SearchTaskParameters(taskPriorities = Some(List(0, 1, 2))))
      this.filterTaskPriorities(params).sql() mustEqual "tasks.priority IN (0,1,2)"
    }

    "be empty" in {
      this.filterTaskPriorities(SearchParameters()).sql() mustEqual ""
    }

    "be empty when given empty list" in {
      val params =
        SearchParameters(taskParams = SearchTaskParameters(taskPriorities = Some(List())))
      this.filterTaskPriorities(params).sql() mustEqual ""
    }

    "be inverted" in {
      val params = SearchParameters(
        taskParams = SearchTaskParameters(taskPriorities = Some(List(0, 1, 2))),
        invertFields = Some(List("priorities"))
      )
      this.filterTaskPriorities(params).sql() mustEqual "NOT tasks.priority IN (0,1,2)"
    }
  }

  "filterPriority" should {
    "match on task priority" in {
      val params = SearchParameters(priority = Some(1))
      this.filterPriority(params).sql() mustEqual "tasks.priority = 1"
    }

    "be empty" in {
      this.filterPriority(SearchParameters()).sql() mustEqual ""
    }

    "be inverted" in {
      val params = SearchParameters(priority = Some(2), invertFields = Some(List("tp")))
      this.filterPriority(params).sql() mustEqual "NOT tasks.priority = 2"
    }

    "only filter if valid priority level (0,1,2)" in {
      val params = SearchParameters(priority = Some(3))
      this.filterPriority(params).sql() mustEqual ""
    }
  }

  "filterChallengeDifficulty" should {
    "match on challenge difficulty" in {
      val params =
        SearchParameters(challengeParams = SearchChallengeParameters(challengeDifficulty = Some(1)))
      this.filterChallengeDifficulty(params).sql() mustEqual "c.difficulty = 1"
    }

    "be empty" in {
      this.filterChallengeDifficulty(SearchParameters()).sql() mustEqual ""
    }

    "be inverted" in {
      val params = SearchParameters(
        challengeParams = SearchChallengeParameters(challengeDifficulty = Some(2)),
        invertFields = Some(List("cd"))
      )
      this.filterChallengeDifficulty(params).sql() mustEqual "NOT c.difficulty = 2"
    }
  }

  "filterTaskTags" should {
    "match on task tags" in {
      val params =
        SearchParameters(taskParams = SearchTaskParameters(taskTags = Some(List("my_tag", "tag2"))))
      this.filterTaskTags(params).sql() mustEqual
        "tasks.id IN (SELECT task_id from tags_on_tasks tt " +
          "INNER JOIN tags ON tags.id = tt.tag_id WHERE tags.name IN ('my_tag','tag2'))"
    }

    "be empty" in {
      this.filterTaskTags(SearchParameters()).sql() mustEqual ""
    }

    "be inverted" in {
      val params = SearchParameters(
        taskParams = SearchTaskParameters(taskTags = Some(List("my_tag", "tag2"))),
        invertFields = Some(List("tt"))
      )
      this.filterTaskTags(params).sql() mustEqual
        "NOT tasks.id IN (SELECT task_id from tags_on_tasks tt " +
          "INNER JOIN tags ON tags.id = tt.tag_id WHERE tags.name IN ('my_tag','tag2'))"
    }
  }

  "filterTaskReviewStatus" should {
    "match on task review status" in {
      val params =
        SearchParameters(taskParams = SearchTaskParameters(taskReviewStatus = Some(List(1, 2))))
      this.filterTaskReviewStatus(params).sql() mustEqual
        "(tasks.id IN (SELECT task_id FROM task_review " +
          "WHERE task_review.task_id = tasks.id AND task_review.review_status IN (1,2)))"
    }

    "include tasks without review status when params contains -1" in {
      val params =
        SearchParameters(taskParams = SearchTaskParameters(taskReviewStatus = Some(List(1, 2, -1))))
      this.filterTaskReviewStatus(params).sql() mustEqual
        "(tasks.id IN (SELECT task_id FROM task_review " +
          "WHERE task_review.task_id = tasks.id AND task_review.review_status IN (1,2,-1)) " +
          "OR NOT tasks.id IN (SELECT task_id FROM task_review task_review " +
          "WHERE task_review.task_id = tasks.id))"
    }

    "be empty" in {
      this.filterTaskReviewStatus(SearchParameters()).sql() mustEqual ""
    }

    "be inverted" in {
      val params = SearchParameters(
        taskParams = SearchTaskParameters(taskReviewStatus = Some(List(1, 2))),
        invertFields = Some(List("trStatus"))
      )
      this.filterTaskReviewStatus(params).sql() mustEqual
        "NOT tasks.id IN (SELECT task_id FROM task_review " +
          "WHERE task_review.task_id = tasks.id AND task_review.review_status IN (1,2))"
    }

    "invert when contains -1" in {
      val params = SearchParameters(
        taskParams = SearchTaskParameters(taskReviewStatus = Some(List(1, 2, -1))),
        invertFields = Some(List("trStatus"))
      )
      this.filterTaskReviewStatus(params).sql() mustEqual
        "NOT tasks.id IN (SELECT task_id FROM task_review " +
          "WHERE task_review.task_id = tasks.id AND task_review.review_status IN (1,2,-1)) " +
          "AND tasks.id IN (SELECT task_id FROM task_review task_review " +
          "WHERE task_review.task_id = tasks.id)"
    }
  }

  "filterMetaReviewStatus" should {
    "match on meta review status" in {
      val params =
        SearchParameters(reviewParams = SearchReviewParameters(metaReviewStatus = Some(List(1, 2))))
      this.filterMetaReviewStatus(params).sql() mustEqual
        "(tasks.id IN (SELECT task_id FROM task_review " +
          "WHERE (task_review.task_id = tasks.id) AND ((task_review.meta_review_status IN (1,2)))))"
    }

    "include tasks with any meta review status when params contains -1" in {
      val params =
        SearchParameters(reviewParams =
          SearchReviewParameters(metaReviewStatus = Some(List(1, 2, -1)))
        )
      this.filterMetaReviewStatus(params).sql() mustEqual
        "(tasks.id IN (SELECT task_id FROM task_review " +
          "WHERE (task_review.task_id = tasks.id) AND ((task_review.meta_review_status IN (1,2,-1)))) " +
          "OR NOT tasks.id IN (SELECT task_id FROM task_review task_review WHERE task_review.task_id = tasks.id))"
    }

    "include tasks without meta review status when params contains -2" in {
      val params =
        SearchParameters(reviewParams =
          SearchReviewParameters(metaReviewStatus = Some(List(1, 2, -2)))
        )
      this.filterMetaReviewStatus(params).sql() mustEqual
        "(tasks.id IN (SELECT task_id FROM task_review " +
          "WHERE (task_review.task_id = tasks.id) AND ((task_review.meta_review_status IN (1,2,-2) " +
          "OR task_review.meta_review_status IS NULL))))"
    }

    "be empty" in {
      this.filterMetaReviewStatus(SearchParameters()).sql() mustEqual ""
    }

    "be inverted" in {
      val params = SearchParameters(
        reviewParams = SearchReviewParameters(metaReviewStatus = Some(List(1, 2))),
        invertFields = Some(List("mrStatus"))
      )
      this.filterMetaReviewStatus(params).sql() mustEqual
        "NOT tasks.id IN (SELECT task_id FROM task_review " +
          "WHERE (task_review.task_id = tasks.id) AND ((task_review.meta_review_status IN (1,2))))"
    }

    "invert when contains -1" in {
      val params = SearchParameters(
        reviewParams = SearchReviewParameters(metaReviewStatus = Some(List(1, 2, -1))),
        invertFields = Some(List("mrStatus"))
      )
      this.filterMetaReviewStatus(params).sql() mustEqual
        "NOT tasks.id IN (SELECT task_id FROM task_review " +
          "WHERE (task_review.task_id = tasks.id) AND ((task_review.meta_review_status IN (1,2,-1)))) " +
          "AND tasks.id IN (SELECT task_id FROM task_review task_review " +
          "WHERE task_review.task_id = tasks.id)"
    }

    "invert when contains -2" in {
      val params = SearchParameters(
        reviewParams = SearchReviewParameters(metaReviewStatus = Some(List(1, 2, -2))),
        invertFields = Some(List("mrStatus"))
      )
      this.filterMetaReviewStatus(params).sql() mustEqual
        "NOT tasks.id IN (SELECT task_id FROM task_review " +
          "WHERE (task_review.task_id = tasks.id) AND ((task_review.meta_review_status IN (1,2,-2) " +
          "OR task_review.meta_review_status IS NULL)))"
    }
  }

  "filterChallengeStatus" should {
    "match on challenge status" in {
      val params = SearchParameters(challengeParams =
        SearchChallengeParameters(challengeStatus = Some(List(1, 2)))
      )
      this.filterChallengeStatus(params).sql() mustEqual
        s"""(c.status IN (1,2))"""
    }

    "include challenge with null status when params contains -1" in {
      val params = SearchParameters(challengeParams =
        SearchChallengeParameters(challengeStatus = Some(List(1, 2, -1)))
      )
      this.filterChallengeStatus(params).sql() mustEqual
        s"""(c.status IN (1,2,-1) OR c.status IS NULL)""".stripMargin
    }

    "be empty" in {
      this.filterChallengeStatus(SearchParameters()).sql() mustEqual ""
    }

    "be inverted" in {
      val params = SearchParameters(
        challengeParams = SearchChallengeParameters(challengeStatus = Some(List(1, 2))),
        invertFields = Some(List("cStatus"))
      )
      this.filterChallengeStatus(params).sql() mustEqual
        s"""NOT c.status IN (1,2)""".stripMargin
    }

    "invert when contains -1" in {
      val params = SearchParameters(
        challengeParams = SearchChallengeParameters(challengeStatus = Some(List(1, 2, -1))),
        invertFields = Some(List("cStatus"))
      )
      this.filterChallengeStatus(params).sql() mustEqual
        s"""NOT c.status IN (1,2,-1) AND c.status IS NOT NULL""".stripMargin
    }
  }

  "filterChallengeRequiresLocal" should {
    "match on challenge requires local" in {
      val params = SearchParameters(challengeParams = SearchChallengeParameters(requiresLocal =
        Some(SearchParameters.CHALLENGE_REQUIRES_LOCAL_EXCLUDE)
      )
      )
      this.filterChallengeRequiresLocal(params).sql() mustEqual "NOT c.requires_local"

      val params2 = SearchParameters(challengeParams = SearchChallengeParameters(requiresLocal =
        Some(SearchParameters.CHALLENGE_REQUIRES_LOCAL_ONLY)
      )
      )
      this.filterChallengeRequiresLocal(params2).sql() mustEqual "c.requires_local"

      val params3 = SearchParameters(challengeParams = SearchChallengeParameters(requiresLocal =
        Some(SearchParameters.CHALLENGE_REQUIRES_LOCAL_INCLUDE)
      )
      )
      this.filterChallengeRequiresLocal(params3).sql() mustEqual ""
    }

    "if given challenge ids then not filter" in {
      val params = SearchParameters(challengeParams = SearchChallengeParameters(
        requiresLocal = Some(SearchParameters.CHALLENGE_REQUIRES_LOCAL_EXCLUDE),
        challengeIds = Some(List(1, 2, 3))
      )
      )
      this.filterChallengeRequiresLocal(params).sql() mustEqual ""
    }
  }

  "filterBoundingGeometries" should {
    "be empty" in {
      this.filterBoundingGeometries(SearchParameters()).sql() mustEqual ""
    }
  }

  "filterTaskProps" should {
    "be empty" in {
      this.filterTaskProps(SearchParameters()).sql() mustEqual ""
    }

    "be empty if no challenge ids are given" in {
      val params = SearchParameters(taskParams = SearchTaskParameters(taskPropertySearch = Some(
        TaskPropertySearch(
          Some("x"),
          Some("1"),
          Some(SearchParameters.TASK_PROP_VALUE_TYPE_NUMBER),
          Some(SearchParameters.TASK_PROP_SEARCH_TYPE_EQUALS)
        )
      )
      )
      )
      this.filterTaskProps(params).sql() mustEqual ""
    }

    "match on task props when using params.taskPropertySearch" in {
      val params = SearchParameters(
        challengeParams = SearchChallengeParameters(challengeIds = Some(List(12345))),
        taskParams = SearchTaskParameters(taskPropertySearch = Some(
          TaskPropertySearch(
            Some("x"),
            Some("1"),
            Some(SearchParameters.TASK_PROP_VALUE_TYPE_NUMBER),
            Some(SearchParameters.TASK_PROP_SEARCH_TYPE_EQUALS)
          )
        )
        )
      )
      this.filterTaskProps(params).sql() mustEqual
        """tasks.id IN (
             | SELECT id FROM tasks,
             | jsonb_array_elements(geojson->'features') features
             | WHERE parent_id IN (12345)
             | AND ( CAST(features->'properties'->>'x' AS DOUBLE PRECISION)=1))""".stripMargin
    }

    "match on task props when using params.taskProperties" in {
      val params = SearchParameters(
        challengeParams = SearchChallengeParameters(challengeIds = Some(List(12345))),
        taskParams = SearchTaskParameters(taskProperties = Some(Map("x" -> "1")))
      )
      this.filterTaskProps(params).sql() mustEqual
        """tasks.id IN (
             | SELECT id FROM tasks,
             | jsonb_array_elements(geojson->'features') features
             | WHERE parent_id IN (12345)
             | AND (true AND features->'properties'->>'x' = '1' ))""".stripMargin
    }
  }

  "filterOwner" should {
    "be empty" in {
      this.filterOwner(SearchParameters()).sql() mustEqual ""
    }

    "match on review_requested_by" in {
      val params = SearchParameters(owner = Some("2223"))
      this.filterOwner(params).sql() mustEqual
        "tasks.id IN (SELECT task_id FROM task_review tr " +
          "INNER JOIN users u ON u.id = tr.review_requested_by " +
          "WHERE tr.task_id = tasks.id AND u.name ILIKE '%2223%')"
    }

    "be inverted" in {
      val params = SearchParameters(owner = Some("4123"), invertFields = Some(List("o")))
      this.filterOwner(params).sql() mustEqual
        "NOT tasks.id IN (SELECT task_id FROM task_review tr " +
          "INNER JOIN users u ON u.id = tr.review_requested_by " +
          "WHERE tr.task_id = tasks.id AND u.name ILIKE '%4123%')"
    }
  }

  "filterReviewer" should {
    "be empty" in {
      this.filterOwner(SearchParameters()).sql() mustEqual ""
    }

    "match on reviewer" in {
      val params = SearchParameters(reviewer = Some("1230"))
      this.filterReviewer(params).sql() mustEqual
        "tasks.id IN (SELECT task_id FROM task_review tr " +
          "INNER JOIN users u ON u.id = tr.reviewed_by " +
          "WHERE tr.task_id = tasks.id AND u.name ILIKE '%1230%')"
    }

    "be inverted" in {
      val params = SearchParameters(reviewer = Some("1"), invertFields = Some(List("r")))
      this.filterReviewer(params).sql() mustEqual
        "NOT tasks.id IN (SELECT task_id FROM task_review tr " +
          "INNER JOIN users u ON u.id = tr.reviewed_by " +
          "WHERE tr.task_id = tasks.id AND u.name ILIKE '%1%')"
    }
  }

  "filterMetaReviewer" should {
    "be empty" in {
      this.filterOwner(SearchParameters()).sql() mustEqual ""
    }

    "match on meta reviewer" in {
      val params = SearchParameters(metaReviewer = Some("9230"))
      this.filterMetaReviewer(params).sql() mustEqual
        "tasks.id IN (SELECT task_id FROM task_review tr " +
          "INNER JOIN users u ON u.id = tr.meta_reviewed_by " +
          "WHERE tr.task_id = tasks.id AND u.name ILIKE '%9230%')"
    }

    "be inverted" in {
      val params = SearchParameters(metaReviewer = Some("1"), invertFields = Some(List("mr")))
      this.filterMetaReviewer(params).sql() mustEqual
        "NOT tasks.id IN (SELECT task_id FROM task_review tr " +
          "INNER JOIN users u ON u.id = tr.meta_reviewed_by " +
          "WHERE tr.task_id = tasks.id AND u.name ILIKE '%1%')"
    }
  }

  "filterMapper" should {
    "be empty" in {
      this.filterMapper(SearchParameters()).sql() mustEqual ""
    }

    "match on mapper" in {
      val params = SearchParameters(mapper = Some("900123"))
      this.filterMapper(params).sql() mustEqual
        "tasks.id IN (SELECT t2.id FROM tasks t2 " +
          "INNER JOIN users u ON u.id = t2.completed_by " +
          "WHERE t2.id = tasks.id AND u.name ILIKE '%900123%')"
    }

    "be inverted" in {
      val params = SearchParameters(mapper = Some("123000"), invertFields = Some(List("m")))
      this.filterMapper(params).sql() mustEqual
        "NOT tasks.id IN (SELECT t2.id FROM tasks t2 " +
          "INNER JOIN users u ON u.id = t2.completed_by " +
          "WHERE t2.id = tasks.id AND u.name ILIKE '%123000%')"
    }
  }

  "filterReviewMappers" should {
    "match on mappers" in {
      val params =
        SearchParameters(reviewParams = SearchReviewParameters(mappers = Some(List(1, 2, 3))))
      this.filterReviewMappers(params).sql() mustEqual
        "task_review.review_requested_by IN (1,2,3) AND task_review.review_requested_by IS NOT NULL"
    }

    "not match on no mappers" in {
      val params = SearchParameters(reviewParams = SearchReviewParameters(mappers = Some(List())))
      this.filterReviewMappers(params).sql() mustEqual
        "task_review.review_requested_by IS NOT NULL"
    }

    "make sure the field is not null" in {
      this.filterReviewMappers(SearchParameters()).sql() mustEqual
        "task_review.review_requested_by IS NOT NULL"
    }

    "can be inverted" in {
      val params = SearchParameters(
        reviewParams = SearchReviewParameters(mappers = Some(List(1, 2, 3))),
        invertFields = Some(List("mappers"))
      )
      this.filterReviewMappers(params).sql() mustEqual
        "NOT task_review.review_requested_by IN (1,2,3) AND task_review.review_requested_by IS NOT NULL"
    }
  }

  "filterReviewers" should {
    "match on reviewers" in {
      val params =
        SearchParameters(reviewParams = SearchReviewParameters(reviewers = Some(List(1, 2, 3))))
      this.filterReviewers(params).sql() mustEqual "task_review.reviewed_by IN (1,2,3)"
    }

    "be empty" in {
      this.filterReviewers(SearchParameters()).sql() mustEqual ""
    }

    "can be inverted" in {
      val params = SearchParameters(
        reviewParams = SearchReviewParameters(reviewers = Some(List(1, 2, 3))),
        invertFields = Some(List("reviewers"))
      )
      this.filterReviewers(params).sql() mustEqual "NOT task_review.reviewed_by IN (1,2,3)"
    }
  }

  "filterMetaReviewers" should {
    "match on meta reviewers" in {
      val params =
        SearchParameters(reviewParams = SearchReviewParameters(metaReviewers = Some(List(1, 2, 3))))
      this.filterMetaReviewers(params).sql() mustEqual "task_review.meta_reviewed_by IN (1,2,3)"
    }

    "be empty" in {
      this.filterMetaReviewers(SearchParameters()).sql() mustEqual ""
    }

    "can be inverted" in {
      val params = SearchParameters(
        reviewParams = SearchReviewParameters(metaReviewers = Some(List(1, 2, 3))),
        invertFields = Some(List("metaReviewers"))
      )
      this.filterMetaReviewers(params).sql() mustEqual "NOT task_review.meta_reviewed_by IN (1,2,3)"
    }
  }

  "filterReviewDate" should {
    "match on reviewDate" in {
      val params =
        SearchParameters(reviewParams =
          SearchReviewParameters(startDate = Some("2020-05-27"), endDate = Some("2020-05-28"))
        )
      this.filterReviewDate(params).sql() mustEqual
        "task_review.reviewed_at >= '2020-05-27 00:00:00' AND task_review.reviewed_at <= '2020-05-28 23:59:59'"
    }

    "be empty" in {
      this.filterReviewDate(SearchParameters()).sql() mustEqual ""
    }

    "include only start date" in {
      val params =
        SearchParameters(reviewParams = SearchReviewParameters(startDate = Some("2020-05-27")))
      this.filterReviewDate(params).sql() mustEqual
        "task_review.reviewed_at >= '2020-05-27 00:00:00'"
    }

    "include only end date" in {
      val params =
        SearchParameters(reviewParams = SearchReviewParameters(endDate = Some("2020-01-01")))
      this.filterReviewDate(params).sql() mustEqual
        "task_review.reviewed_at <= '2020-01-01 23:59:59'"
    }

    "include only valid dates" in {
      val params =
        SearchParameters(reviewParams = SearchReviewParameters(startDate = Some("x123x")))
      this.filterReviewDate(params).sql() mustEqual ""
    }
  }

  "filterProjectEnabled" should {
    "match on projectEnabled" in {
      val params = SearchParameters(projectEnabled = Some(true))
      this.filterProjectEnabled(params).sql() mustEqual "p.enabled"
    }

    "be empty if false" in {
      val params = SearchParameters(projectEnabled = Some(false))
      this.filterProjectEnabled(params).sql() mustEqual ""
      this.filterProjectEnabled(new SearchParameters()).sql() mustEqual ""
    }
  }

  "filterChallengeEnabled" should {
    "match on challengeEnabled" in {
      val params =
        SearchParameters(challengeParams = SearchChallengeParameters(challengeEnabled = Some(true)))
      this.filterChallengeEnabled(params).sql() mustEqual "c.enabled"
    }

    "be empty if false" in {
      val params = SearchParameters(challengeParams =
        SearchChallengeParameters(challengeEnabled = Some(false))
      )
      this.filterProjectEnabled(params).sql() mustEqual ""
      this.filterProjectEnabled(new SearchParameters()).sql() mustEqual ""
    }
  }

  "filterProjects" should {
    "search by project ids" in {
      val params = SearchParameters(projectIds = Some(List(123, 456)))
      this.filterProjects(params).sql() mustEqual "p.id IN (123,456)"
    }

    "invert search project Ids" in {
      val params =
        SearchParameters(projectIds = Some(List(123, 456)), invertFields = Some(List("pid")))
      this.filterProjects(params).sql() mustEqual "NOT p.id IN (123,456)"
    }

    "search by project ids including virutal" in {
      val params = SearchParameters(projectIds = Some(List(123, 456)))
      this.filterProjects(params, true).sql() mustEqual
        "(p.id IN (123,456) OR c.id IN (SELECT challenge_id from virtual_project_challenges " +
          "WHERE project_id IN (123,456)))"
    }

    "invert search project Ids including virtual" in {
      val params =
        SearchParameters(projectIds = Some(List(123, 456)), invertFields = Some(List("pid")))
      this.filterProjects(params, true).sql() mustEqual
        "NOT p.id IN (123,456) AND NOT c.id IN (SELECT challenge_id " +
          "from virtual_project_challenges WHERE project_id IN (123,456))"
    }

    "does fuzzy search" in {
      val params     = SearchParameters(projectSearch = Some("abc"), fuzzySearch = Some(2))
      val filter     = this.filterProjects(params)
      val parameters = filter.parameters()
      normalized(filter.sql().replaceAll(parameters.head.name, "abc")) mustEqual
        "(p.display_name <> '' AND " +
          "(LEVENSHTEIN(LOWER(p.display_name), LOWER({abc})) < 2 OR " +
          "METAPHONE(LOWER(p.display_name), 4) = METAPHONE(LOWER({abc}), 4) OR " +
          "SOUNDEX(LOWER(p.display_name)) = SOUNDEX(LOWER({abc}))) )"
    }

    "does search on display name and virtual project names" in {
      val params = SearchParameters(projectSearch = Some("abc"))
      this.filterProjects(params).sql() mustEqual
        "(p.display_name ILIKE '%abc%' OR " +
          "(c.id IN (SELECT vp2.challenge_id FROM virtual_project_challenges vp2  " +
          "INNER JOIN projects p2 ON p2.id = vp2.project_id WHERE  LOWER(p2.display_name) " +
          "LIKE LOWER('%abc%') AND p2.enabled=true)))"
    }

    "can be empty" in {
      this.filterProjects(new SearchParameters()).sql() mustEqual ""
    }
  }

  "filterChallenges" should {
    "search by challenge ids" in {
      val params = SearchParameters(challengeParams =
        SearchChallengeParameters(challengeIds = Some(List(123, 456)))
      )
      this.filterChallenges(params).sql() mustEqual "c.id IN (123,456)"
    }

    "invert search challenge ids" in {
      val params = SearchParameters(
        challengeParams = SearchChallengeParameters(challengeIds = Some(List(123, 456))),
        invertFields = Some(List("cid"))
      )
      this.filterChallenges(params).sql() mustEqual "NOT c.id IN (123,456)"
    }

    "does fuzzy search" in {
      val params = SearchParameters(
        challengeParams = SearchChallengeParameters(challengeSearch = Some("testC")),
        fuzzySearch = Some(2)
      )
      val filter     = this.filterChallenges(params)
      val parameters = filter.parameters()
      normalized(filter.sql().replaceAll(parameters.head.name, "testC")) mustEqual
        "(c.name <> '' AND (LEVENSHTEIN(LOWER(c.name), LOWER({testC})) < 2 OR " +
          "METAPHONE(LOWER(c.name), 4) = METAPHONE(LOWER({testC}), 4) OR " +
          "SOUNDEX(LOWER(c.name)) = SOUNDEX(LOWER({testC}))) )"
    }

    "does search on name" in {
      val params = SearchParameters(challengeParams =
        SearchChallengeParameters(challengeSearch = Some("testC"))
      )
      this.filterChallenges(params).sql() mustEqual "c.name ILIKE '%testC%'"
    }

    "can be empty" in {
      this.filterChallenges(new SearchParameters()).sql() mustEqual ""
    }
  }

}

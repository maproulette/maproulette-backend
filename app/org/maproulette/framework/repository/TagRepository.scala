/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection

import anorm.SqlParser.get
import anorm.{BatchSql, NamedParameter, RowParser, SQL, ~}
import javax.inject.Inject
import org.maproulette.exception.UniqueViolationException
import org.maproulette.framework.model.{Tag, TaskTag}
import org.maproulette.framework.psql.filter.{BaseParameter, Filter, FilterGroup, Operator}
import org.maproulette.framework.psql.{OR, Query}
import play.api.db.Database

/**
  * @author mcuthbert
  */
class TagRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = Tag.TABLE

  /**
    * Retrieves a specific tag
    *
    * @param id The id for the tag
    * @param c  Implicit provided optional connection
    * @return An optional tag
    */
  def retrieve(id: Long)(implicit c: Option[Connection] = None): Option[Tag] = {
    this.query(Query.simple(List(BaseParameter(Tag.FIELD_ID, id)))).headOption
  }

  /**
    * Create a new Tag object
    *
    * @param tag The tag to create
    * @param c   An implicit connection
    * @return The newly created Tag
    */
  def create(tag: Tag)(implicit c: Option[Connection] = None): Tag = {
    this.withMRTransaction { implicit c =>
      SQL(
        """INSERT INTO tags (name, description, tag_type)
          |VALUES ({name}, {description}, {tagType})
          |ON CONFLICT(LOWER(name), tag_type) DO NOTHING RETURNING *""".stripMargin
      ).on(
          Symbol("name")        -> tag.name.toLowerCase,
          Symbol("description") -> tag.description,
          Symbol("tagType")     -> tag.tagType
        )
        .as(TagRepository.parser.*)
        .headOption match {
        case Some(t) => t
        case None =>
          throw new UniqueViolationException(
            s"Tag with name ${tag.name} already exists in the database"
          )
      }
    }
  }

  /**
    * Updates a tag, only the name and description fields can be updated
    *
    * @param tag The tag to update
    * @param c   an implicit connection
    * @return The updated Tag object
    */
  def update(tag: Tag)(implicit c: Option[Connection] = None): Option[Tag] = {
    this.withMRTransaction { implicit c =>
      SQL("UPDATE tags SET name = {name}, description = {description} WHERE id = {id} RETURNING *")
        .on(
          Symbol("name")        -> tag.name.toLowerCase,
          Symbol("description") -> tag.description,
          Symbol("id")          -> tag.id
        )
        .as(TagRepository.parser.*)
        .headOption
    }
  }

  def delete(id: Long)(implicit c: Option[Connection] = None): Boolean = {
    this.withMRTransaction { implicit c =>
      SQL("DELETE FROM tags WHERE id = {id}").on(Symbol("id") -> id).execute()
    }
  }

  def listByChallenges(id: List[Long]): Map[Long, List[Tag]] = {
    this.withMRConnection { implicit c =>
      val challengeTagParser: RowParser[(Long, Tag)] = {
        TagRepository.parser ~
          get[Long]("tags_on_challenges.challenge_id") map {
          case tag ~ challengeId => (challengeId, tag)
        }
      }

      Query
        .simple(
          List(
            BaseParameter(
              Tag.FIELD_CHALLENGE_ID,
              id,
              Operator.IN,
              table = Some(Tag.TABLE_TAGS_ON_CHALLENGES)
            )
          ),
          "SELECT * FROM tags INNER JOIN tags_on_challenges ON tags.id = tags_on_challenges.tag_id"
        )
        .build()
        .as(challengeTagParser.*)
        .groupBy(_._1)
        .map {
          case (k, v) => (k, v.map(_._2))
        }
    }
  }

  def updateTagList(tags: List[Tag])(implicit c: Option[Connection] = None): List[Tag] = {
    if (tags.nonEmpty) {
      this.withMRTransaction { implicit c =>
        val sqlQuery =
          s"""WITH upsert AS (UPDATE tags SET description = {description}
                              WHERE id = {id} OR (name = {name} AND tag_type = {tagType}) RETURNING *)
                              INSERT INTO tags (name, description, tag_type) SELECT {name}, {description}, {tagType}
                              WHERE NOT EXISTS (SELECT * FROM upsert)"""
        val parameters = tags.map(tag => {
          val descriptionString = tag.description match {
            case Some(d) => d
            case None    => ""
          }
          Seq[NamedParameter](
            "name"        -> tag.name.toLowerCase,
            "description" -> descriptionString,
            "id"          -> tag.id,
            "tagType"     -> tag.tagType
          )
        })
        BatchSql(sqlQuery, parameters.head, parameters.tail: _*).execute()
      }

      val tagFilterGroups = tags.map(tag =>
        FilterGroup(
          List(
            BaseParameter(Tag.FIELD_NAME, tag.name.toLowerCase.replaceAll("'", "")),
            BaseParameter(Tag.FIELD_TAG_TYPE, tag.tagType)
          )
        )
      )
      this.query(Query(Filter(tagFilterGroups, OR())))
    } else {
      List.empty
    }
  }

  /**
    * Query function that allows a user to build their own query against the Tags table
    *
    * @param query The query to execute
    * @param c     An implicit connection
    * @return A list of returned tags
    */
  def query(query: Query)(implicit c: Option[Connection] = None): List[Tag] = {
    this.withMRConnection { implicit c =>
      query.build(s"SELECT * FROM tags").as(TagRepository.parser.*)
    }
  }

  /**
    * Query function that allows a user to build their own query against the Tags table
    * and return a mapping between task ids and tags.
    *
    * @param query The query to execute
    * @param c     An implicit connection
    * @return A map of taskId -> List of Tag
    */
  def queryTaskTags(query: Query)(implicit c: Option[Connection] = None): Map[Long, List[Tag]] = {
    this.withMRConnection { implicit c =>
      val taskTags    = query.build(s"SELECT * FROM tags").as(TagRepository.taskTagParser.*)
      val taskTagsMap = scala.collection.mutable.Map[Long, List[Tag]]().withDefaultValue(null)

      taskTags.foreach(t => {
        if (taskTagsMap(t.taskId) == null) {
          taskTagsMap(t.taskId) = List()
        }
        taskTagsMap(t.taskId) = t.tag :: taskTagsMap(t.taskId)
      })

      taskTagsMap.toMap
    }
  }

  /**
    * Toggles the active status of a tag
    *
    * @param tagId The id of the tag to toggle
    * @param active The new active status to set
    * @param c An implicit connection
    * @return The updated Tag
    */
  def toggleStatus(tagId: Long, active: Boolean)(
      implicit c: Option[Connection] = None
  ): Option[Tag] = {
    this.withMRTransaction { implicit c =>
      SQL("UPDATE tags SET active = {active} WHERE id = {id} RETURNING *")
        .on(
          Symbol("active") -> active,
          Symbol("id")     -> tagId
        )
        .as(TagRepository.parser.*)
        .headOption
    }
  }
}

object TagRepository {
  val parser: RowParser[Tag] = {
    get[Long]("tags.id") ~
      get[String]("tags.name") ~
      get[Option[String]]("tags.description") ~
      get[String]("tags.tag_type") ~
      get[Boolean]("tags.active") map {
      case id ~ name ~ description ~ tagType ~ active =>
        new Tag(id, name.toLowerCase, description, tagType = tagType, active = active)
    }
  }

  val taskTagParser: RowParser[TaskTag] = {
    get[Long]("task_id") ~
      get[Long]("tags.id") ~
      get[String]("tags.name") ~
      get[Option[String]]("tags.description") ~
      get[String]("tags.tag_type") ~
      get[Boolean]("tags.active") map {
      case taskId ~ id ~ name ~ description ~ tagType ~ active => {
        new TaskTag(
          taskId,
          new Tag(id, name.toLowerCase, description, tagType = tagType, active = active)
        )
      }
    }
  }
}

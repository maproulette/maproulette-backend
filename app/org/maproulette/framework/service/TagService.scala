/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.Inject
import org.maproulette.exception.InvalidException
import org.maproulette.framework.model.{Tag, User}
import org.maproulette.framework.psql.filter.{BaseParameter, FilterParameter, Operator}
import org.maproulette.framework.psql.{Order, Paging, Query, SQLUtils}
import org.maproulette.framework.repository.TagRepository
import org.maproulette.permissions.Permission
import org.maproulette.utils.Writers
import play.api.libs.json.JsValue

/**
  * @author mcuthbert
  */
class TagService @Inject() (
    repository: TagRepository,
    permission: Permission
) extends ServiceMixin[Tag]
    with Writers {

  def delete(id: Long, user: User): Boolean = {
    this.retrieve(id).foreach { deletedItem =>
      this.permission.hasObjectAdminAccess(deletedItem, user)
      this.repository.delete(id)
    }
    true
  }

  /**
    * Inserts a new tag object into the database
    *
    * @param tag The tag object to insert into the database
    * @param user the user executing the create request
    * @return The object that was inserted into the database. This will include the newly created id
    */
  def create(tag: Tag, user: User): Tag = {
    if (tag.name.isEmpty) {
      // do not allow empty tags
      throw new InvalidException(s"Tags cannot be empty strings")
    }
    this.permission.hasObjectWriteAccess(tag, user)
    this.repository.create(tag)
  }

  def update(id: Long, tag: JsValue, user: User): Option[Tag] = {
    this
      .retrieve(id)
      .flatMap { implicit existing =>
        this.permission.hasObjectWriteAccess(existing, user)
        val name = (tag \ "name").asOpt[String].getOrElse(existing.name)
        if (name.isEmpty) {
          // do not allow empty tags
          throw new InvalidException(s"Tags cannot be empty strings")
        }
        val description =
          (tag \ "description").asOpt[String].getOrElse(existing.description.getOrElse(""))
        this.repository.update(Tag(id, name, Some(description)))
      }
  }

  /**
    * Retrieves an object of that type
    *
    * @param id The identifier for the object
    * @return An optional object, None if not found
    */
  override def retrieve(id: Long): Option[Tag] = this.repository.retrieve(id)

  /**
    * Finds tags based on the provided prefix and if supplied the tag_type's
    *
    * @param searchString The search string for the tag
    * @param tagType The tag type, which defaults to "challenges
    * @param paging paging for requests
    * @param order Order, the ordering of the returned tags
    * @return The list of tags that match the search criteria
    */
  def find(
      searchString: String,
      tagTypes: List[String] = List("challenges"),
      paging: Paging = Paging(),
      order: Order = Order(),
      usePrefix: Boolean = false
  ): List[Tag] = {
    this.query(
      Query.simple(
        List(
          BaseParameter(Tag.FIELD_NAME, SQLUtils.search(searchString, usePrefix), Operator.ILIKE),
          FilterParameter.conditional(
            Tag.FIELD_TAG_TYPE,
            tagTypes.mkString(","),
            Operator.IN,
            includeOnlyIfTrue = tagTypes.nonEmpty
          )
        ),
        paging = paging,
        order = order
      )
    )
  }

  /**
    * Retrieve a tag based on it's name
    *
    * @param name The name of the tag
    * @param parentId The parent identifier of the tag, set to -1 if you don't want to filter by it's parent
    * @return The tag, None if not found
    */
  def retrieveByName(name: String, tagType: String, parentId: Long = -1): Option[Tag] = {
    this
      .query(
        Query.simple(
          List(
            BaseParameter(Tag.FIELD_NAME, name.toLowerCase),
            BaseParameter(Tag.FIELD_TAG_TYPE, tagType),
            FilterParameter
              .conditional(Tag.FIELD_PARENT_ID, parentId, includeOnlyIfTrue = parentId != -1)
          )
        )
      )
      .headOption
  }

  /**
    * Get all the tags for a specific task
    *
    * @param id The id fo the task
    * @return List of tags for the task
    */
  def listByTask(id: Long): List[Tag] = {
    this.query(
      Query.simple(
        List(BaseParameter(Tag.FIELD_TASK_ID, id, table = Some(Tag.TABLE_TAGS_ON_TASKS))),
        "SELECT * FROM tags INNER JOIN tags_on_tasks ON tags.id = tags_on_tasks.tag_id"
      )
    )
  }

  /**
    * Get all the tags for a specific task
    *
    * @param id The id fo the task
    * @return List of tags for the task
    */
  def listByTasks(taskIds: List[Long]): Map[Long, List[Tag]] = {
    this.repository.queryTaskTags(
      Query.simple(
        List(
          BaseParameter(
            Tag.FIELD_TASK_ID,
            taskIds,
            operator = Operator.IN,
            table = Some(Tag.TABLE_TAGS_ON_TASKS)
          )
        ),
        """SELECT *, task_id FROM tags
           INNER JOIN tags_on_tasks ON tags.id = tags_on_tasks.tag_id"""
      )
    )
  }

  /**
    * Resolves a list of tags by (name, tag type), creating any that do not
    * exist yet. Existing tags are never modified, so any user may call this
    * when tagging an item they can edit. Tags without a name are ignored.
    *
    * @param tags A list of tag objects to look up or create
    * @param user The user making the request
    * @return The matching tags, including the ids of any newly created ones
    */
  def updateTagList(tags: List[Tag], user: User): List[Tag] = {
    val named = tags.filter(_.name.nonEmpty)
    if (named.nonEmpty) {
      this.repository.updateTagList(named)
    } else {
      List.empty
    }
  }

  def listByChallenge(id: Long): List[Tag] =
    this.listByChallenges(List(id)).getOrElse(id, List.empty)

  def listByChallenges(ids: List[Long]): Map[Long, List[Tag]] =
    this.repository.listByChallenges(ids)

  /**
    * Retrieve a list of Tags based on the provided list of names
    *
    * @param names The names to be retrieved
    * @return List of objects, empty list if none found
    */
  def retrieveListByName(names: List[String], parentId: Long = -1): List[Tag] = {
    if (names.isEmpty) {
      List.empty
    } else {
      this.query(
        Query.simple(
          List(
            BaseParameter(Tag.FIELD_NAME, names.map(_.toLowerCase), Operator.IN),
            FilterParameter
              .conditional(Tag.FIELD_PARENT_ID, parentId, includeOnlyIfTrue = parentId != -1)
          )
        )
      )
    }
  }

  /**
    * Retrieves all the objects based on the search criteria
    *
    * @param query The query to match against to retrieve the objects
    * @return The list of objects
    */
  override def query(query: Query): List[Tag] = this.repository.query(query)

  /**
    * Toggles a tag's active status. Only super users can perform this action.
    *
    * @param tagId The id of the tag to toggle
    * @param user The user making the request (must be super user)
    * @return The updated tag
    */
  def toggleTagStatus(tagId: Long, user: User): Option[Tag] = {
    if (!permission.isSuperUser(user)) {
      throw new IllegalAccessException("Only super users can toggle tag status")
    }

    this.retrieve(tagId) match {
      case Some(tag) => this.repository.toggleStatus(tagId, !tag.active)
      case None      => None
    }
  }
}

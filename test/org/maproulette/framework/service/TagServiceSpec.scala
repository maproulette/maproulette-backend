/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import org.maproulette.framework.model.{Tag, User}
import org.maproulette.framework.psql.Order
import org.maproulette.framework.util.{FrameworkHelper, KeywordTag}
import play.api.Application
import play.api.libs.json.Json

/**
  * @author mcuthbert
  */
class TagServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val service: TagService                       = this.serviceManager.tag
  override implicit val projectTestName: String = "TagServiceSpecProject"

  "TagService" should {
    "delete a tag" taggedAs KeywordTag in {
      val createdTag = this.service.create(Tag(-1, "DeleteServiceTagTest"), User.superUser)
      this.service.delete(createdTag.id, User.superUser)
      val retrievedTag = this.service.retrieve(createdTag.id)
      retrievedTag mustEqual None
    }

    "update a tag" taggedAs KeywordTag in {
      val createdTag = this.service.create(Tag(-1, "UpdateServiceTagTest"), User.superUser)
      this.service.update(
        createdTag.id,
        Json.obj(
          "name"        -> "TAG_UPDATE_NAME",
          "description" -> "TAG_UPDATE_DESCRIPTION",
          "tagType"     -> "tasks"
        ),
        User.superUser
      )
      // only descriptions can be updated on tags
      val retrievedTag = this.service.retrieve(createdTag.id)
      retrievedTag.get.id mustEqual createdTag.id
      retrievedTag.get.name mustEqual "tag_update_name"
      retrievedTag.get.description.get mustEqual "TAG_UPDATE_DESCRIPTION"
      retrievedTag.get.tagType mustEqual "challenges"
    }

    "Create/retrieve a tag" taggedAs KeywordTag in {
      val createdTag   = this.service.create(Tag(-1, "CreateServiceTagTest"), User.superUser)
      val retrievedTag = this.service.retrieve(createdTag.id)
      retrievedTag.get.id mustEqual createdTag.id
      retrievedTag.get.name mustEqual createdTag.name
      retrievedTag.get.description mustEqual createdTag.description
      retrievedTag.get.tagType mustEqual createdTag.tagType
    }

    "find a tag" taggedAs KeywordTag in {
      val tagList = (0 to 9).map(index => Tag(-1, s"FindTagTest$index")).toList
      this.service.updateTagList(tagList, User.superUser)
      val foundTags =
        this.service.find("FindTag", usePrefix = true, order = Order > ("name", Order.ASC))
      foundTags.size mustEqual 10
      foundTags.zipWithIndex.map { case (tag, index) => tag.name mustEqual s"findtagtest$index" }

      val noTags = this.service.find("TagTest", usePrefix = true)
      noTags.size mustEqual 0

      val foundTags2 = this.service.find("FindTag", order = Order > ("name", Order.ASC))
      foundTags2.size mustEqual 10
      foundTags.zipWithIndex.map { case (tag, index) => tag.name mustEqual s"findtagtest$index" }
    }

    "retrieve a tag by name" taggedAs KeywordTag in {
      val createdTag   = this.service.create(Tag(-1, "RetrieveServiceTagTest"), User.superUser)
      val retrievedTag = this.service.retrieveByName("RetrieveServiceTagTest", "challenges")
      retrievedTag.get.id mustEqual createdTag.id
      retrievedTag.get.name mustEqual createdTag.name
      retrievedTag.get.description mustEqual createdTag.description
      retrievedTag.get.tagType mustEqual createdTag.tagType
    }

    "list tags by task" taggedAs KeywordTag in {
      val tagList     = (1 to 4).map(index => Tag(-1, s"TaskTag$index")).toList
      val createdTags = this.service.updateTagList(tagList, User.superUser)
      this.taskDAL.updateItemTags(
        this.defaultTask.id,
        createdTags.map(_.id),
        this.defaultUser,
        true
      )
      val tags = this.service.listByTask(this.defaultTask.id)
      tags.size mustEqual 4
      tags.foreach(tag => tag.name.startsWith("tasktag") mustEqual true)
    }

    "list all tags by a list of challenges" taggedAs KeywordTag in {
      val tagList     = (1 to 4).map(index => Tag(-1, s"ChallengeTag$index")).toList
      val createdTags = this.service.updateTagList(tagList, User.superUser)
      this.challengeDAL.updateItemTags(
        this.defaultChallenge.id,
        createdTags.map(_.id),
        this.defaultUser,
        true
      )
      val tagMap = this.service.listByChallenges(List(this.defaultChallenge.id))
      tagMap.size mustEqual 1
      val retrievedTags = tagMap.get(this.defaultChallenge.id).head
      retrievedTags.size mustEqual 4
      retrievedTags.foreach(tag => tag.name.startsWith("challengetag") mustEqual true)
    }

    "list all tags by a list of tasks" taggedAs KeywordTag in {
      val tagList     = (1 to 4).map(index => Tag(-1, s"TaskTag$index")).toList
      val createdTags = this.service.updateTagList(tagList, User.superUser)
      this.taskDAL.updateItemTags(
        this.defaultTask.id,
        createdTags.map(_.id),
        this.defaultUser,
        true
      )
      val tagMap = this.service.listByTasks(List(this.defaultTask.id))
      tagMap(this.defaultTask.id).size mustEqual 4
      tagMap(this.defaultTask.id).foreach(tag => tag.name.startsWith("tasktag") mustEqual true)
    }

    "only let super users create, update or delete tags" taggedAs KeywordTag in {
      val user = this.defaultUser
      an[IllegalAccessException] should be thrownBy this.service
        .create(Tag(-1, "NonSuperCreateTagTest"), user)
      val tag = this.service.create(Tag(-1, "NonSuperModifyTagTest"), User.superUser)
      an[IllegalAccessException] should be thrownBy this.service
        .update(tag.id, Json.obj("name" -> "renamed"), user)
      an[IllegalAccessException] should be thrownBy this.service.delete(tag.id, user)
      this.service.retrieve(tag.id).get.name mustEqual "nonsupermodifytagtest"
    }

    "not modify existing tags when resolving a tag list" taggedAs KeywordTag in {
      val existing = this.service.create(
        Tag(-1, "ExistingListTagTest", Some("original description")),
        User.superUser
      )
      val resolved = this.service.updateTagList(
        List(
          Tag(-1, "ExistingListTagTest", Some("overwritten")),
          Tag(existing.id, "", Some("overwritten by id")),
          Tag(-1, "NewListTagTest")
        ),
        this.defaultUser
      )
      resolved.map(_.name).toSet mustEqual Set("existinglisttagtest", "newlisttagtest")
      resolved.find(_.name == "existinglisttagtest").get.id mustEqual existing.id
      this.service.retrieve(existing.id).get.description mustEqual Some("original description")
    }

    "resolve tags whose names contain apostrophes" taggedAs KeywordTag in {
      this.service.create(Tag(-1, "obrien"), User.superUser)
      val resolved = this.service.updateTagList(List(Tag(-1, "O'Brien")), this.defaultUser)
      resolved.map(_.name) mustEqual List("o'brien")
    }
  }

  "Retagging a task" should {
    "be refused for a user with no involvement in the task" taggedAs KeywordTag in {
      val task = this.taskDAL.insert(this.getTestTask("TagAccessStranger"), User.superUser)
      an[IllegalAccessException] should be thrownBy this.taskDAL.requireTagAccess(task.id, stranger)
    }

    "be allowed for the user holding the task's lock" taggedAs KeywordTag in {
      val task = this.taskDAL.insert(this.getTestTask("TagAccessLockHolder"), User.superUser)
      this.taskDAL.lockItem(stranger, task)
      try {
        this.taskDAL.requireTagAccess(task.id, stranger)
      } finally {
        this.taskDAL.unlockItem(stranger, task)
      }
    }

    "be allowed for a user with write access to the challenge" taggedAs KeywordTag in {
      val task = this.taskDAL.insert(this.getTestTask("TagAccessWriter"), User.superUser)
      this.taskDAL.requireTagAccess(task.id, this.defaultUser)
    }
  }

  private lazy val stranger: User = this.serviceManager.user.create(
    this.getTestUser(51234580, "TagAccessStranger"),
    User.superUser
  )
}

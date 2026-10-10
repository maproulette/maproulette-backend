/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import org.maproulette.framework.model.Tag
import org.maproulette.framework.psql.filter.{BaseParameter, Operator}
import org.maproulette.framework.psql.{Order, Query}
import org.maproulette.framework.util.{FrameworkHelper, KeywordRepoTag}
import play.api.Application

/**
  * @author mcuthbert
  */
class TagRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  val repository: TagRepository =
    this.application.injector.instanceOf(classOf[TagRepository])
  override implicit val projectTestName: String = "TagRepositorySpecProject"

  "TagRepository" should {
    "create/retrieve a tag" taggedAs KeywordRepoTag in {
      val createdTag   = this.repository.create(Tag(-1, "retrieveTagTest"))
      val retrievedTag = this.repository.retrieve(createdTag.id)
      retrievedTag.get.id mustEqual createdTag.id
      retrievedTag.get.name mustEqual createdTag.name
    }

    "update a tag" taggedAs KeywordRepoTag in {
      val createdTag = this.repository.create(Tag(-1, "updateTagTest"))
      this.repository
        .update(createdTag.copy(name = "NAME_UPDATE", description = Some("UPDATE_DESCRIPTION")))
      val retrievedTag = this.repository.retrieve(createdTag.id)
      retrievedTag.get.id mustEqual createdTag.id
      retrievedTag.get.name mustEqual "name_update"
      retrievedTag.get.description mustEqual Some("UPDATE_DESCRIPTION")
      // update the desciption now
      this.repository.update(createdTag.copy(name = "NAME_UPDATE", description = None))
      val retrievedTag2 = this.repository.retrieve(createdTag.id)
      retrievedTag2.get.description mustEqual None
    }

    "delete a tag" taggedAs KeywordRepoTag in {
      val createdTag = this.repository.create(Tag(-1, "deleteTagTest"))
      repository.delete(createdTag.id)
      this.repository.retrieve(createdTag.id) mustEqual None
    }

    "list all the tags in a challenge" taggedAs KeywordRepoTag in {
      val tagList     = (1 to 4).map(index => Tag(-1, s"ChallengeTag$index")).toList
      val createdTags = this.repository.updateTagList(tagList)
      this.challengeDAL.updateItemTags(
        this.defaultChallenge.id,
        createdTags.map(_.id),
        this.defaultUser,
        true
      )
      val tagMap = this.repository.listByChallenges(List(this.defaultChallenge.id))
      tagMap.size mustEqual 1
      val retrievedTags = tagMap.get(this.defaultChallenge.id).head
      retrievedTags.size mustEqual 4
      retrievedTags.foreach(tag => tag.name.startsWith("challengetag") mustEqual true)
    }

    "create missing tags from a list without modifying existing ones" taggedAs KeywordRepoTag in {
      val tagList     = (0 to 9).map(index => Tag(-1, s"tag$index", Some(s"description$index"))).toList
      val updatedTags = this.repository.updateTagList(tagList)
      val tags = this.repository
        .query(
          Query.simple(
            List(BaseParameter(Tag.FIELD_ID, updatedTags.map(_.id), Operator.IN)),
            order = Order > (Tag.FIELD_NAME, Order.ASC)
          )
        )
      tags.size mustEqual 10
      tags.zipWithIndex.foreach {
        case (tag, index) =>
          tag.name mustEqual s"tag$index"
          tag.description.get mustEqual s"description$index"
      }
      val resolved = this.repository.updateTagList(
        List(
          tags.head.copy(description = Some("descriptionUpdate0")),
          tags(1).copy(name = "tagUpdate1", description = Some("descriptionUpdate1"))
        )
      )
      resolved.size mustEqual 2
      val existing = resolved.find(_.name == "tag0").get
      (existing.id, existing.description) mustEqual (tags.head.id, tags.head.description)
      val created = resolved.find(_.name == "tagupdate1").get
      created.id must not equal tags(1).id
      created.description mustEqual Some("descriptionUpdate1")

      val unchanged = this.repository.query(
        Query.simple(
          List(BaseParameter(Tag.FIELD_ID, List(tags.head.id, tags(1).id), Operator.IN)),
          order = Order > (Tag.FIELD_NAME, Order.ASC)
        )
      )
      unchanged.map(t => (t.name, t.description)) mustEqual List(
        ("tag0", Some("description0")),
        ("tag1", Some("description1"))
      )
    }
  }
}

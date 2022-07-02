/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.util.UUID

import org.maproulette.framework.model.{User, UserMetrics, UserSettings, CustomBasemap}
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.{BaseParameter, Operator}
import org.maproulette.framework.service.UserService
import org.maproulette.framework.util.{FrameworkHelper, UserRepoTag}
import play.api.Application
import play.api.libs.oauth.RequestToken

/**
  * @author mcuthbert
  */
class UserRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  val userRepository: UserRepository = this.application.injector.instanceOf(classOf[UserRepository])
  val userService: UserService       = this.serviceManager.user

  "UserRepository" should {
    "upsert user" taggedAs UserRepoTag in {
      val insertedUser  = this.insertBaseUser(1, "name1")
      val retrievedUser = this.repositoryGet(insertedUser.id)
      retrievedUser.get mustEqual insertedUser
    }

    "update user" taggedAs UserRepoTag in {
      val insertedUser  = this.insertBaseUser(2, "name2")
      val updatedApiKey = UUID.randomUUID().toString
      val updateUser = insertedUser.copy(
        osmProfile = insertedUser.osmProfile.copy(
          displayName = "name3",
          avatarURL = "UPDATE_avatarURL",
          requestToken = RequestToken("UPDATED_TOKEN", "UPDATED_SECRET")
        ),
        apiKey = Some(updatedApiKey),
        settings = UserSettings(
          Some(1),
          Some(2),
          Some("id"),
          Some("en-US"),
          Some("email_address"),
          Some(true),
          Some(false),
          Some(5),
          Some(true),
          None,
          None,
          None
        )
      )
      this.userRepository.update(updateUser, "POINT (14.0 22.0)")
      val updatedUser = this.repositoryGet(insertedUser.id).get
      updatedUser.osmProfile.displayName mustEqual updateUser.osmProfile.displayName
      updatedUser.osmProfile.avatarURL mustEqual updateUser.osmProfile.avatarURL
      updatedUser.osmProfile.requestToken mustEqual updateUser.osmProfile.requestToken
      // API Key should not be allowed to updated here
      updatedUser.apiKey mustEqual insertedUser.apiKey
      updatedUser.settings mustEqual updateUser.settings
    }

    "update user's customBasemaps" taggedAs UserRepoTag in {
      val insertedUser  = this.insertBaseUser(30, "name30")
      val updatedApiKey = UUID.randomUUID().toString
      val updateUser = insertedUser.copy(
        osmProfile = insertedUser.osmProfile.copy(
          displayName = "name31",
          avatarURL = "UPDATE_avatarURL",
          requestToken = RequestToken("UPDATED_TOKEN", "UPDATED_SECRET")
        ),
        apiKey = Some(updatedApiKey),
        settings = UserSettings(
          Some(1),
          Some(2),
          Some("id"),
          Some("en-US"),
          Some("email_address2"),
          Some(true),
          Some(false),
          Some(5),
          Some(true),
          None,
          None,
          Some(
            List(
              CustomBasemap(
                name = "my_custom_basemap",
                url = "http://maproulette.org/this/is/a/url"
              )
            )
          )
        )
      )
      this.userRepository.update(updateUser, "POINT (14.0 22.0)")
      val updatedUser = this.repositoryGet(insertedUser.id).get
      updatedUser.osmProfile.displayName mustEqual updateUser.osmProfile.displayName
      updatedUser.settings.customBasemaps.get.length mustEqual 1
      updatedUser.settings.customBasemaps.get.head.name mustEqual "my_custom_basemap"
      updatedUser.settings.customBasemaps.get.head.url mustEqual "http://maproulette.org/this/is/a/url"

      // Change basemaps
      val basemaps = List(
        CustomBasemap(
          id = updatedUser.settings.customBasemaps.get.head.id,
          name = "updated_custom_basemap",
          url = "http://updated/url",
          overlay = true
        ),
        CustomBasemap(name = "new_basemap", url = "new_url")
      )

      val user2 = updatedUser.copy(
        settings = updatedUser.settings.copy(customBasemaps = Some(basemaps))
      )
      this.userRepository.update(user2, "POINT (14.0 22.0)")
      val updatedUser2 = this.repositoryGet(user2.id).get
      updatedUser2.settings.customBasemaps.get.length mustEqual 2

      val updatedBasemaps = updatedUser2.settings.customBasemaps.getOrElse(List())
      val first           = updatedBasemaps.head
      first.id mustEqual basemaps.head.id
      first.name mustEqual "updated_custom_basemap"
      first.url mustEqual "http://updated/url"
      first.overlay mustEqual true

      val second = updatedBasemaps(1)
      second.id must not be -1
      second.name mustEqual "new_basemap"
      second.url mustEqual "new_url"

      // Remove basemap by not including in list
      val LessBasemaps = List(
        CustomBasemap(
          id = first.id,
          name = "updated_custom_basemap",
          url = "http://updated/url",
          overlay = true
        )
      )
      val user3 = updatedUser2.copy(
        settings = updatedUser2.settings.copy(customBasemaps = Some(LessBasemaps))
      )
      this.userRepository.update(user3, "POINT (14.0 22.0)")
      val updatedUser3 = this.repositoryGet(user3.id).get
      updatedUser3.settings.customBasemaps.get.length mustEqual 1

      // Not passing customBasemaps preserves existing ones.
      val user4 = updatedUser3.copy(
        settings = updatedUser.settings.copy(customBasemaps = None)
      )
      this.userRepository.update(user4, "POINT (14.0 22.0)")
      val updatedUser4 = this.repositoryGet(user4.id).get
      updatedUser4.settings.customBasemaps.get.length mustEqual 1

      //Passing an empty list of customBasemaps deletes all
      val user5 = updatedUser4.copy(
        settings = updatedUser4.settings.copy(customBasemaps = Some(List()))
      )
      this.userRepository.update(user5, "POINT (14.0 22.0)")
      val updatedUser5 = this.repositoryGet(user5.id).get
      updatedUser5.settings.customBasemaps mustEqual None
    }

    "update API key" taggedAs UserRepoTag in {
      val insertedUser =
        this.userRepository.upsert(this.getTestUser(3, "APITest"), "TestAPIKey", "POINT (20 40)")
      this.userRepository.updateAPIKey(insertedUser.id, "NEW_updated_key")
      val retrievedUser = this.repositoryGet(insertedUser.id)
      retrievedUser.get.apiKey.get mustEqual "NEW_updated_key"
    }

    "delete user" taggedAs UserRepoTag in {
      val insertedUser = this.userRepository
        .upsert(this.getTestUser(4, "DeleteTest"), "TestAPIKey", "POINT (20 40)")
      this.userRepository.delete(insertedUser.id)
      val retrievedUser = this.repositoryGet(insertedUser.id)
      retrievedUser.isEmpty mustEqual true
    }

    "delete user by OSMID" taggedAs UserRepoTag in {
      val insertedUser = this.userRepository
        .upsert(this.getTestUser(5, "DeleteByOidTest"), "TestAPIKey", "POINT (20 40)")
      this.userRepository.deleteByOSMID(5)
      val retrievedUser = this.repositoryGet(insertedUser.id)
      retrievedUser.isEmpty mustEqual true
    }

    "update user score" taggedAs UserRepoTag in {
      val insertedUser = this.userRepository
        .upsert(this.getTestUser(61, "UpdateUserO"), "TestAPIKey", "POINT (20 40)")
      val updatedUser = this.userRepository.updateUserScore(
        insertedUser.id,
        List(
          BaseParameter(
            UserMetrics.FIELD_SCORE,
            s"=(${UserMetrics.FIELD_SCORE}+1000)",
            Operator.CUSTOM
          ),
          BaseParameter(
            UserMetrics.FIELD_TOTAL_REJECTED,
            s"=(${UserMetrics.FIELD_TOTAL_REJECTED}+1)",
            Operator.CUSTOM
          )
        )
      )
      this.userRepository.deleteByOSMID(61)
    }

    "add user achievements" taggedAs UserRepoTag in {
      val insertedUser = this.userRepository
        .upsert(this.getTestUser(62, "AddAchievementsTest"), "TestAPIKey", "POINT (20 40)")

      // Brand-new users have no achievements
      insertedUser.achievements.getOrElse(List.empty).length mustEqual 0

      this.userRepository.addAchievements(insertedUser.id, List(1, 2, 3))
      this.repositoryGet(insertedUser.id).get.achievements.getOrElse(List.empty).length mustEqual 3

      // Make sure dup achievements don't get added
      this.userRepository.addAchievements(insertedUser.id, List(3, 4, 5))
      this.repositoryGet(insertedUser.id).get.achievements.getOrElse(List.empty).length mustEqual 5
      this.userRepository.deleteByOSMID(62)
    }
  }

  override implicit val projectTestName: String = "UserRepositorySpecProject"

  private def repositoryGet(id: Long): Option[User] = {
    this.userRepository
      .query(
        Query.simple(
          List(BaseParameter(User.FIELD_ID, id))
        )
      )
      .headOption
  }

  private def insertBaseUser(osmId: Long, osmName: String): User =
    this.userService.create(this.getTestUser(osmId, osmName), User.superUser)
}

/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.permissions

import java.sql.Connection

import com.google.inject.Singleton
import javax.inject.{Inject, Provider}
import org.maproulette.Config
import org.maproulette.cache.CacheObject
import org.maproulette.data._
import org.maproulette.exception.NotFoundException
import org.maproulette.framework.model._
import org.maproulette.framework.service.ServiceManager
import org.maproulette.models._
import org.maproulette.models.dal.DALManager

/**
  * @author cuthbertm
  */
@Singleton
class Permission @Inject() (
    dalManager: Provider[DALManager],
    serviceManager: ServiceManager,
    config: Config
) {

  /**
    * Checks read access based purely on the id and item type, will throw a NotFoundException if
    * no object of the given type with the given id can be found. Delegates permission check to
    * object based hasReadAccess function
    *
    * @param itemType The type of item that user is checked read access against
    * @param user     The user checking whether they have access or not
    * @param id       The id of the object
    */
  def hasReadAccess(
      itemType: ItemType,
      user: User
  )(implicit id: Long, c: Option[Connection] = None): Unit = {
    this.getItem(itemType) match {
      case Some(obj) => this.hasObjectReadAccess(obj, user)
      case _ =>
        throw new NotFoundException(
          s"No ${Actions.getTypeName(itemType.typeId).getOrElse("Unknown")} found using id [$id] to check read access"
        )
    }
  }

  def hasWriteAccess(itemType: ItemType, user: User, role: Int = Grant.ROLE_WRITE_ACCESS)(
      implicit id: Long,
      c: Option[Connection] = None
  ): Unit =
    if (!this.isSuperUser(user)) {
      itemType match {
        case UserType() =>
          // we use read access here, simply because read and write access on a user is the same in that
          // you are required to the super user or owner to access the User object. It is much stricter
          // than other objects in the system
          this.hasReadAccess(itemType, user)
        case GrantType() =>
          throw new IllegalAccessException("Only super users can write to grant objects")
        case _ =>
          this.getItem(itemType)(id, c) match {
            case Some(obj) =>
              this.hasObjectWriteAccess(obj.asInstanceOf[CacheObject[Long]], user, role)
            case _ =>
              throw new NotFoundException(
                s"No ${Actions.getTypeName(itemType.typeId).getOrElse("Unknown")} found using id [$id] to check write access"
              )
          }
      }
    }

  /**
    * Uses the hasWriteAccess function, but switches to check for users granted admin role instead of write role
    *
    * @param obj  The object that we are checking to see if they have access to it
    * @param user The user making the request
    * @param c    An implicit database connection
    */
  def hasObjectAdminAccess(obj: Any, user: User)(
      implicit c: Option[Connection] = None
  ): Unit =
    this.hasObjectWriteAccess(obj, user, Grant.ROLE_ADMIN)

  /**
    * Checks whether a user has write permission to the given object
    *
    * @param obj  The object in question
    * @param user The user requesting write access
    */
  def hasObjectWriteAccess(
      obj: Any,
      user: User,
      role: Int = Grant.ROLE_WRITE_ACCESS
  )(implicit c: Option[Connection] = None): Unit =
    if (!this.isSuperUser(user)) {
      obj match {
        case u: User => this.hasObjectReadAccess(u, user)
        case group: Group =>
          if (group.groupType == Group.GROUP_TYPE_TEAM) {
            if (!this.serviceManager.team.isUserTeamAdmin(group, user, User.superUser)) {
              throw new IllegalAccessException("Must be an admin of team to write to it")
            }
          } else {
            this.hasObjectReadAccess(group, user)
          }
        case p: Project =>
          this.hasProjectAccess(Some(p), user, role)
        case c: Challenge =>
          this.hasProjectAccess(
            dalManager
              .get()
              .challenge
              .retrieveRootObject(Right(c), user),
            user,
            role
          )
        case vc: VirtualChallenge =>
          if (vc.ownerId != user.osmProfile.id) {
            throw new IllegalAccessException(
              s"Only super users or the owner of the Virtual Challenge can write to it."
            )
          }
        case t: Task =>
          this.hasProjectAccess(
            dalManager.get().task.retrieveRootObject(Right(t), user),
            user,
            role
          )
        case tag: Tag =>
        //this.hasReadAccess(TagType(), user)(tag.id)
        case g: Grant =>
          throw new IllegalAccessException(
            s"Only super users can write to grant objects"
          )
        case b: Bundle =>
          if (b.owner != user.osmProfile.id) {
            throw new IllegalAccessException(
              s"Only super users or the owner of the bundle can write to it."
            )
          }
        case _ =>
          throw new IllegalAccessException(s"Unknown object type provided ${obj.toString}")
      }
    }

  /**
    * Checks to see if the object has read access to the object in request. Will throw an
    * IllegalAccessException if it does not have access. Read access is available on all objects
    * except User objects, users can only read their own user objects or all user objects if they are
    * a super user.
    *
    * @param obj  The object you are checking to see if the user has read access on the object
    * @param user The user requesting the access.
    */
  def hasObjectReadAccess(obj: Any, user: User)(
      implicit c: Option[Connection] = None
  ): Unit = if (!this.isSuperUser(user)) {
    obj match {
      case u: User if u.id != user.id & u.osmProfile.id != user.osmProfile.id =>
        throw new IllegalAccessException(
          s"User does not have read access to requested user object [${u.id}]"
        )
      case g: Grant =>
        if (g.grantee.granteeType != UserType() || g.grantee.granteeId != user.id) {
          throw new IllegalAccessException(
            s"User does not have read access to requested grant object [${g.id}]"
          )
        }
      case _ => // don't do anything, they have access
    }
  }

  def hasProjectTypeAccess(
      user: User,
      role: Int = Grant.ROLE_ADMIN
  )(implicit id: Long, c: Option[Connection] = None): Unit =
    this.hasProjectAccess(this.serviceManager.project.retrieve(id), user, role)

  def hasProjectAccess(project: Option[Project], user: User, role: Int = Grant.ROLE_ADMIN)(
      implicit c: Option[Connection] = None
  ): Unit = if (!this.isSuperUser(user)) {
    project match {
      case Some(p) =>
        // Make sure we're dealing with the latest user data
        this.serviceManager.user.retrieve(user.id) match {
          case Some(u) =>
            if (!u.grantsForProject(p.id).exists(_.role <= role)) {
              throw new IllegalAccessException(
                s"User [${user.id}] does not have required access to this project [${p.id}]"
              )
            }
          case None => throw new NotFoundException("No user found to check for access")
        }

      case None =>
        throw new NotFoundException(s"No project found to check access for object")
    }
  }

  /**
    * Uses the hasWriteAccess function, but switches to check for user granted admin role instead of write role
    *
    * @param itemType The type of object that the user want's access too
    * @param user     The user making the request
    * @param id       The id of the object
    * @param c        An implicit database connection
    */
  def hasAdminAccess(
      itemType: ItemType,
      user: User
  )(implicit id: Long, c: Option[Connection] = None): Unit =
    this.hasWriteAccess(itemType, user, Grant.ROLE_ADMIN)

  /**
    * Checks that a user is a super user
    *
    * @param user
    */
  def hasSuperAccess(user: User): Unit = if (!this.isSuperUser(user)) {
    throw new IllegalAccessException(s"Only super users can perform this action.")
  }

  /**
    * Determines if the given user has been configured as a superuser
    */
  def isSuperUser(user: User): Boolean = {
    // Check if the user is the system super user (id=-999)
    if (user.id == User.DEFAULT_SUPER_USER_ID) true
    else
      config.superAccounts match {
        case List("*") if config.isDevMode => true
        case _                             => serviceManager.user.isSuperUser(user.id)
      }
  }

  private def getItem(
      itemType: ItemType
  )(implicit id: Long, c: Option[Connection] = None): Option[_] = {
    try {
      dalManager.get().getManager(itemType).retrieveById
    } catch {
      case _: NotFoundException =>
        // if the dal manager doesn't have it then maybe the ServiceManager does
        serviceManager.getService(itemType).retrieve(id)
      case e: Throwable => throw e
    }
  }
}

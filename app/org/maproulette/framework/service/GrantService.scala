/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service


import javax.inject.{Inject, Singleton}
import org.maproulette.Config
import org.maproulette.exception.InvalidException
import org.maproulette.framework.model.{Grant, Grantee, GrantTarget, User}
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.{Parameter, BaseParameter, Operator}
import org.maproulette.framework.repository.{GrantRepository}
import org.maproulette.data._
import org.maproulette.permissions.Permission

/**
  * @author nrotstan
  */
@Singleton
class GrantService @Inject() (
    repository: GrantRepository,
    config: Config,
    permission: Permission
) extends ServiceMixin[Grant] {

  /**
    * Using this function would always fail, as it requires super user access
    *
    * @param query The query to match against to retrieve the objects
    * @return The list of objects
    */
  override def query(query: Query): List[Grant] = this.query(query, User.guestUser)

  /**
    * Using this function would always fail, as it requires super user access
    *
    * @param id The id of the Grant to retrieve
    * @return The Grant or None
    */
  override def retrieve(id: Long): Option[Grant] = this.retrieve(id, User.guestUser)

  /**
    * Retrieves all the Grants based on the search criteria
    *
    * @param query The query to match against to retrieve the Grants
    * @param user The user making the request
    */
  def query(query: Query, user: User): List[Grant] = {
    this.hasAccess(user)
    this.repository.query(query)
  }

  /**
    * Retrieve a Grant by id
    *
    * @param id The id of the Grant to retrieve
    * @param user The user making the request
    */
  def retrieve(id: Long, user: User): Option[Grant] =
    this
      .query(
        Query.simple(List(BaseParameter(Grant.FIELD_ID, id))),
        user
      )
      .headOption

  /**
    * Retrieve all grants belonging to the given Grantee
    *
    * @param grantee the Grantees for which grants are desired
    * @param user the user making the request
    */
  def retrieveGrantsTo(grantee: Grantee, user: User): List[Grant] =
    this.retrieveMatchingGrants(grantee = Some(List(grantee)), user = user)

  /**
    * Retrieve all grants belonging to any of the given grantees, all of which
    * must be of the same item type
    *
    * @param granteeType the item type of all grantees
    * @param granteeIds the ids of the grantees for which grants are desired
    * @param user the user making the request
    */
  def retrieveGrantsTo(granteeType: ItemType, granteeIds: List[Long], user: User): List[Grant] = {
    val grantees = granteeIds.map(Grantee(granteeType, _))
    this.retrieveMatchingGrants(grantee = Some(grantees), user = user)
  }

  /**
    * Retrieve all grants on the given target object
    *
    * @param target the GrantTarget on which grants are desired
    * @param user the user making the request
    */
  def retrieveGrantsOn(target: GrantTarget, user: User): List[Grant] = {
    this.retrieveMatchingGrants(target = Some(target), user = user)
  }

  /**
    * Retrieve all grants matching the given criteria
    *
    * @param grantee optional Grantees to filter by (must all be of same type)
    * @param role optional role to filter by
    * @param target optional GrantTarget to filter by
    * @param user the user making the request
    */
  def retrieveMatchingGrants(
      grantee: Option[List[Grantee]] = None,
      role: Option[Int] = None,
      target: Option[GrantTarget] = None,
      user: User
  ): List[Grant] =
    this.query(Query.simple(this.grantFilterParameters(grantee, role, target)), user)

  /**
    * Create a new Grant
    *
    * @param grant The grant to create
    * @param user The user making the request
    */
  def createGrant(grant: Grant, user: User): Option[Grant] = {
    this.hasAccess(user)

    // Generate a copy with a valid name if missing
    this.repository.create(
      grant.name match {
        case ""   => grant.copy(name = grant.description())
        case name => grant
      }
    )
  }

  /**
    * Delete a grant
    *
    * @param grant The grant to be deleted
    * @param user  The user executing the request
    */
  def deleteGrant(grant: Grant, user: User): Boolean = {
    this.hasAccess(user)
    this.repository.delete(grant.id)
  }

  /**
    * Delete all grants assigned to the given Grantee
    *
    * @param grantee the Grantee for which assigned grants are to be deleted
    * @param user the user making the request
    */
  def deleteGrantsTo(grantee: Grantee, user: User): Boolean =
    this.deleteMatchingGrants(grantee = Some(grantee), user = user)

  /**
    * Delete all grants on the given grant target
    *
    * @param target the GrantTarget for which all grants are to be deleted
    * @param user the user making the request
    */
  def deleteGrantsOn(target: GrantTarget, user: User): Boolean =
    this.deleteMatchingGrants(target = Some(target), user = user)

  /**
    * Delete all grants matching the given criteria. Either grantee or target
    * (or both) must be specified or an exception will be thrown.
    *
    * @param grantee optional Grantees to filter by (must all be of same type)
    * @param role optional role to filter by
    * @param target optional GrantTarget to filter by
    * @param user the user making the request
    */
  def deleteMatchingGrants(
      grantee: Option[Grantee] = None,
      role: Option[Int] = None,
      target: Option[GrantTarget] = None,
      user: User
  ): Boolean = {
    this.hasAccess(user)

    // Either grantee or target must be defined (or both)
    if (!grantee.isDefined && !target.isDefined) {
      throw new InvalidException(
        "Grant deletion too broad: a grantee or grant target must be specified"
      )
    }

    // Convert single grantee to List expected by grantFilterParameters
    val granteeList = grantee match {
      case Some(g) => Some(List(g))
      case None    => None
    }

    this.repository.deleteGrants(
      Query.simple(this.grantFilterParameters(granteeList, role, target))
    )
  }

  /**
    * Generates List of query Parameters setup to match the given filter
    * arguments
    *
    * @param granteeFilter optional Grantees to filter by (must all be of same type)
    * @param roleFilter optional role to filter by
    * @param targetFilter optional GrantTarget to filter by
    */
  private def grantFilterParameters(
      granteeFilter: Option[List[Grantee]] = None,
      roleFilter: Option[Int] = None,
      targetFilter: Option[GrantTarget] = None
  ): List[Parameter[_]] =
    List(
      granteeFilter match {
        case Some(grantee) =>
          Some(BaseParameter(Grant.FIELD_GRANTEE_TYPE, grantee.head.granteeType.typeId))
        case None => None
      },
      granteeFilter match {
        case Some(grantee) =>
          Some(BaseParameter(Grant.FIELD_GRANTEE_ID, grantee.map(_.granteeId), Operator.IN))
        case None => None
      },
      roleFilter match {
        case Some(role) => Some(BaseParameter(Grant.FIELD_ROLE, role))
        case None       => None
      },
      targetFilter match {
        case Some(target) =>
          Some(BaseParameter(Grant.FIELD_OBJECT_TYPE, target.objectType.typeId))
        case None => None
      },
      targetFilter match {
        case Some(target) =>
          Some(BaseParameter(Grant.FIELD_OBJECT_ID, target.objectId))
        case None => None
      }
    ).flatten

  /**
    * Access for user functions are limited to super users
    *
    * @param user A super user
    */
  private def hasAccess(user: User): Unit = {
    if (!permission.isSuperUser(user)) {
      throw new IllegalAccessException("Only super users have access to grant objects.")
    }
  }
}

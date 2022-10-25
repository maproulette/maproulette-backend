/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.provider.osm

import java.sql.Connection

import javax.inject.{Inject, Singleton}
import org.maproulette.Config
import org.maproulette.exception.ChangeConflictException
import org.maproulette.framework.model.User
import org.maproulette.framework.psql.TransactionManager
import org.maproulette.models.dal.DALManager
import org.maproulette.provider.osm.objects._
import play.api.db.Database
import play.api.libs.oauth.{OAuthCalculator, RequestToken}
import play.api.libs.ws.{WSClient, WSResponse}
import play.shaded.oauth.oauth.signpost.exception.OAuthNotAuthorizedException

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}
import scala.xml.{Elem, NodeSeq}

/**
  * @author mcuthbert
  */
@Singleton
class ChangesetProvider @Inject() (
    ws: WSClient,
    nodeService: NodeProvider,
    wayService: WayProvider,
    relationService: RelationProvider,
    config: Config,
    dalManager: DALManager,
    val db: Database
) extends TransactionManager {

  import scala.concurrent.ExecutionContext.Implicits.global

  def getVersionedChange(tagChanges: List[TagChange]): Future[List[VersionedObject]] = {
    val p = Promise[List[VersionedObject]]()
    this.conflateTagChanges(tagChanges) onComplete {
      case Success(res) => p success res.map(_._1)
      case Failure(f)   => p failure f
    }
    p.future
  }

  /**
    * Test the series of tag changes that you want to commit. The response will be just the diffs
    * and what would be changed.
    *
    * @param tagChanges A list of TagChange objects to test changes with OSM objects
    * @return
    */
  def testTagChange(tagChanges: List[TagChange]): Future[List[TagChangeResult]] = {
    val p = Promise[List[TagChangeResult]]()
    this.conflateTagChanges(tagChanges) onComplete {
      case Success(res) => p success res.map(_._2)
      case Failure(f)   => p failure f
    }
    p.future
  }

  /**
    * Submit a set of changes to the OSM servers
    *
    * @param changes       The changes to be submitted to OSM
    * @param changeSetComment The changeset comment to be associated with the change
    * @return future that will return the OSMChange that was submitted to the OSM servers
    */
  def submitOsmChange(
      changes: OSMChange,
      changeSetComment: String,
      accessToken: RequestToken,
      taskId: Option[Long] = None
  )(implicit c: Option[Connection] = None): Future[Elem] = {
    val p = Promise[Elem]()
    // create the new changeset
    this.createChangeset(changeSetComment, accessToken) onComplete {
      case Success(changesetId) =>
        // retrieve the current version of the osm feature
        // conflate the current tags with provided tags
        this.getOsmChange(changes, Some(changesetId)) onComplete {
          case Success(res) =>
            // submit the conflated changes
            val url = s"${config.getOSMServer}/api/0.6/changeset/$changesetId/upload"
            ws.url(url)
              .sign(OAuthCalculator(config.getOSMOauth.consumerKey, accessToken))
              .post(res) onComplete {
              case Success(uploadResult) =>
                uploadResult.status match {
                  case ChangesetProvider.STATUS_OK => {
                    taskId match {
                      case Some(tid) =>
                        implicit val id: Long = tid
                        dalManager.task.retrieveById(id) match {
                          case Some(task) =>
                            dalManager.task.mergeUpdate(
                              task.copy(changesetId = Some(changesetId)),
                              User.superUser
                            )
                          case _ => // do nothing
                        }
                      case _ => // do nothing
                    }
                    p success res
                  }
                  case ChangesetProvider.STATUS_CONFLICT =>
                    p failure new ChangeConflictException(
                      s"Conflict found in upload: ${uploadResult.body}. $res"
                    )
                  case x =>
                    p failure new Exception(
                      s"${url} failed with status code $x (${uploadResult.statusText}"
                    )
                }
                this.closeChangeset(changesetId, accessToken)
              case Failure(f) =>
                this.closeChangeset(changesetId, accessToken)
                p failure f
            }
          case Failure(f) => p failure f
        }
      case Failure(f) => p failure f
    }
    p.future
  }

  /**
    * Returns a osmChange XML object for geometry changes
    *
    * @param change The geometry changes to process
    * @param changeSetId The id of the changeset
    * @return The osmChange XML
    */
  def getOsmChange(change: OSMChange, changeSetId: Option[Int]): Future[Elem] = {
    val changePromise = Promise[Elem]()
    val osmCreates = change.creates match {
      case Some(creates) =>
        <create>
          {for (create <- creates) yield create.toCreateElement(changeSetId.getOrElse(-1))}
        </create>
      case None => NodeSeq.Empty
    }

    val updatesPromise = Promise[NodeSeq]()
    val osmUpdates = change.updates match {
      case Some(updates) =>
        // Only support tag changes for now
        val tagChanges = updates.map(update => {
          TagChange(
            update.osmId,
            update.osmType,
            update.tags.updates,
            update.tags.deletes,
            update.version
          )
        })

        this.conflateTagChanges(tagChanges) onComplete {
          case Success(res) =>
            updatesPromise success
              <modify>
              {for (entity <- res) yield entity._1.toChangeElement(changeSetId.getOrElse(-1))}
            </modify>
          case Failure(f) => updatesPromise failure f
        }
      case None => updatesPromise success NodeSeq.Empty
    }

    updatesPromise.future onComplete {
      case Success(osmUpdates) =>
        changePromise success
          <osmChange version="0.6" generator="MapRoulette">
          {osmCreates}
          {osmUpdates}
        </osmChange>
      case Failure(f) => changePromise failure f
    }

    changePromise.future
  }

  private def conflateTagChanges(
      tagChanges: List[TagChange]
  ): Future[List[(VersionedObject, TagChangeResult)]] = {
    val grouping  = tagChanges.groupBy(_.osmType)
    val nodes     = grouping.getOrElse(OSMType.NODE, List.empty)
    val ways      = grouping.getOrElse(OSMType.WAY, List.empty)
    val relations = grouping.getOrElse(OSMType.RELATION, List.empty)

    val results = for {
      nodes     <- nodeService.get(nodes.map(_.osmId))
      ways      <- wayService.get(ways.map(_.osmId))
      relations <- relationService.get(relations.map(_.osmId))
    } yield (nodes, ways, relations)

    val p = Promise[List[(VersionedObject, TagChangeResult)]]()
    results onComplete {
      case Success(r) =>
        val changes = (r._1 ++ r._2 ++ r._3).flatMap(feature => {
          // there is only allowed one tagChange object per feature object, so we just grab the first one
          val change = tagChanges.filter(_.osmId == feature.id).head
          // make sure the change matches the feature, if not we need to skip this one
          if (change.osmId != feature.id || change.osmType != feature.getOSMType) {
            // TODO probably should log something here
            None
          } else {
            val deletedTags = feature.tags.filter(k => change.deletes.contains(k._1))
            val addedTags   = mutable.Map[String, String]()
            val updatedTags = mutable.Map[String, (String, String)]()
            val mutableTags: mutable.Map[String, String] =
              mutable.Map(feature.tags.filter(k => !change.deletes.contains(k._1)).toSeq: _*)
            change.updates.foreach(up => {
              if (!mutableTags.contains(up._1)) {
                addedTags.put(up._1, up._2)
              } else {
                updatedTags.put(up._1, (mutableTags(up._1), up._2))
              }
              mutableTags.put(up._1, up._2)
            })
            val tagChangeResult = TagChangeResult(
              feature.id,
              feature.getOSMType,
              addedTags.toMap,
              updatedTags.toMap,
              deletedTags
            )
            feature match {
              case x: VersionedNode     => Some(x.copy(tags = mutableTags.toMap), tagChangeResult)
              case x: VersionedWay      => Some(x.copy(tags = mutableTags.toMap), tagChangeResult)
              case x: VersionedRelation => Some(x.copy(tags = mutableTags.toMap), tagChangeResult)
            }
          }
        })
        p success changes
      case Failure(f) => p failure f
    }
    p.future
  }

  /**
    * Creates a new changeset for the user.
    *
    * @param comment     The comment to associate with the changeset
    * @param accessToken The access token for the user
    * @return
    */
  def createChangeset(comment: String, accessToken: RequestToken): Future[Int] = {
    // Changeset XML Element
    val newChangeSet =
      <osm>
        <changeset>
          <tag k="created_by" v="MapRoulette"/>
          <tag k="comment" v={comment}/>
        </changeset>
      </osm>
    implicit val url = s"${config.getOSMServer}/api/0.6/changeset/create"
    implicit val req = ws
      .url(url)
      .sign(OAuthCalculator(config.getOSMOauth.consumerKey, accessToken))
      .put(newChangeSet)
    this.checkResult[Int] { response =>
      response.body.toInt
    }
  }

  private def checkResult[T](
      block: WSResponse => T
  )(implicit req: Future[WSResponse], url: String): Future[T] = {
    val p = Promise[T]()
    req onComplete {
      case Success(res) =>
        res.status match {
          case ChangesetProvider.STATUS_OK => p success block(res)
          case ChangesetProvider.STATUS_UNAUTHORIZED =>
            p failure new OAuthNotAuthorizedException(
              s"User not authorized to submit tag changes on this server. $res"
            )
          case x => p failure new Exception(s"${url} failed with status code $x (${res.statusText}")
        }
      case Failure(f) => p failure f
    }
    p.future
  }

  /**
    * Close a currently open changeset. All changes within that changes will be applied in a single transaction.
    *
    * @param changesetId The id of the changeset that you applying all your changesets in
    * @param accessToken The access token for the user
    * @return true if succeeded, if failed will respond with exception failure
    */
  def closeChangeset(changesetId: Int, accessToken: RequestToken): Future[Boolean] = {
    implicit val url = s"${config.getOSMServer}/api/0.6/changeset/$changesetId/close"
    implicit val req =
      ws.url(url).sign(OAuthCalculator(config.getOSMOauth.consumerKey, accessToken)).put("")
    this.checkResult[Boolean] { response =>
      true
    }
  }
}

object ChangesetProvider {
  // Conflation Responses
  // 0 - Unable to conflate changes for unknown reason
  // 1 - Requested Tag change is the same as the current version
  // 2 - Change for requested Tag is potentially invalid based on wiki specs

  private val STATUS_OK           = 200
  private val STATUS_CONFLICT     = 409
  private val STATUS_UNAUTHORIZED = 401
}

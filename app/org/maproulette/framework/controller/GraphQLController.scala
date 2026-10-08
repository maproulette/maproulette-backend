/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.controller

import java.util.concurrent.TimeUnit

import javax.inject.{Inject, Singleton}
import org.maproulette.exception.InvalidException
import org.maproulette.framework.graphql.{GraphQL, UserContext}
import org.maproulette.framework.model.User
import org.maproulette.framework.service.ServiceManager
import org.maproulette.session.SessionManager
import org.slf4j.LoggerFactory
import play.api.libs.json._
import play.api.mvc._
import sangria.ast.Document
import sangria.execution._
import sangria.marshalling.playJson._
import sangria.parser.QueryParser

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
  * @author mcuthbert
  */
@Singleton
class GraphQLController @Inject() (
    graphQL: GraphQL,
    sessionManager: SessionManager,
    components: ControllerComponents,
    services: ServiceManager,
    implicit val executionContext: ExecutionContext
) extends AbstractController(components) {
  val exceptionHandler = ExceptionHandler {
    case (_, e: UserFacingError) =>
      logger.warn(e.getMessage)
      HandledException(e.getMessage)
    case (_, e: IllegalAccessException) =>
      logger.warn(e.getMessage)
      HandledException(e.getMessage)
    case (_, e: Throwable) =>
      logger.error(e.getMessage, e)
      HandledException("An unexpected error occurred")
  }
  private val logger = LoggerFactory.getLogger(classOf[GraphQLController])

  def graphiql = Action(Ok(views.html.graphiql()))

  def graphqlBody: Action[JsValue] = Action.async(parse.json) { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      val extract: JsValue => (String, Option[String], Option[JsObject]) = query =>
        (
          (query \ "query").as[String],
          (query \ "operationName").asOpt[String],
          (query \ "variables").toOption.flatMap {
            case JsString(vars) => Some(parseVariables(vars))
            case obj: JsObject  => Some(obj)
            case _              => None
          }
        )

      val maybeQuery: Try[(String, Option[String], Option[JsObject])] = Try {
        request.body match {
          case arrayBody @ JsArray(_)   => extract(arrayBody.value(0))
          case objectBody @ JsObject(_) => extract(objectBody)
          case otherType =>
            throw new Error {
              s"/graphql endpoint does not support request body of type [${otherType.getClass.getSimpleName}"
            }
        }
      }

      maybeQuery match {
        case Success((query, operationName, variables)) =>
          Await.result(
            this.executeQuery(query, user.getOrElse(User.guestUser), variables, operationName),
            Duration.create(1, TimeUnit.MINUTES)
          )
        case Failure(error) => throw new InvalidException(error.getMessage)
      }
    }

  }

  def executeQuery(
      query: String,
      user: User,
      variables: Option[JsObject] = None,
      operation: Option[String] = None
  ): Future[Result] = QueryParser.parse(query) match {
    case Success(queryAst: Document) =>
      Executor
        .execute(
          schema = graphQL.schema,
          deferredResolver = graphQL.resolver,
          queryAst = queryAst,
          variables = variables.getOrElse(Json.obj()),
          userContext = UserContext(sessionManager, user, services),
          exceptionHandler = exceptionHandler
        )
        .map(Ok(_))
        .recover {
          case error: QueryAnalysisError => BadRequest(error.resolveError)
          case error: ErrorWithResolver  => InternalServerError(error.resolveError)
        }
    case Failure(ex) => Future(BadRequest(s"${ex.getMessage}"))
  }

  def parseVariables(variables: String): JsObject =
    if (variables.trim.isEmpty || variables.trim == "null") Json.obj()
    else Json.parse(variables).as[JsObject]
}

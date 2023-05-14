/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.exception

import org.slf4j.LoggerFactory
import play.api.libs.json.{JsString, Json}
import play.api.mvc.Result
import play.api.mvc.Results._
import play.shaded.oauth.oauth.signpost.exception.OAuthNotAuthorizedException

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

/**
  * Function wrappers that wrap our code blocks in try catches.
  *
  * @author cuthbertm
  */
object MPExceptionUtil {
  private val logger = LoggerFactory.getLogger(this.getClass)

  /**
    * Used for Actions, wraps the code block and if InvalidException found will send a BadRequest,
    * all other exceptions sent back as InternalServerError
    *
    * @param block The block of code to be executed expecting a Result back
    * @return Result
    */
  def internalExceptionCatcher(block: () => Result): Result = {
    try {
      block()
    } catch {
      case e: InvalidException =>
        logger.error(e.getMessage, e)
        BadRequest(Json.toJson(StatusMessage("KO", JsString(e.getMessage))))
      case e: IllegalAccessException =>
        logger.error(e.getMessage)
        Forbidden(Json.toJson(StatusMessage("Forbidden", JsString(e.getMessage))))
      case e: NotFoundException =>
        logger.error(e.getMessage, e)
        NotFound(Json.toJson(StatusMessage("NotFound", JsString(e.getMessage))))
      case e: Exception =>
        logger.error(e.getMessage, e)
        InternalServerError(Json.toJson(StatusMessage("KO", JsString(e.getMessage))))
    }
  }

  /**
    * Used for async Actions, so the expected result from the block of code is a Future,
    * on success will simply pass the result on, on failure will throw either a BadRequest,
    * Unauthorized or InternalServerError
    *
    * @param block The block of code to be executed expecting a Future[Result] back
    * @return Future[Result]
    */
  def internalAsyncExceptionCatcher(block: () => Future[Result]): Future[Result] = {
    val p = Promise[Result]()
    Try(block()) match {
      case Success(f) =>
        f onComplete {
          case Success(result) => p success result
          case Failure(e)      => p success manageException(e)
        }
      case Failure(e) => p success manageException(e)
    }
    p.future
  }

  def manageException(e: Throwable): Result = {
    e match {
      case e: InvalidException =>
        logger.error(e.getMessage, e)
        BadRequest(Json.toJson(StatusMessage("KO", JsString(e.getMessage))))
      case e: OAuthNotAuthorizedException =>
        // When a user is not authorized, log it at debug level to avoid simple DoS with logging
        logger.debug(e.getMessage)
        Unauthorized(Json.toJson(StatusMessage("NotAuthorized", JsString(e.getMessage)))).withNewSession
      case e: IllegalAccessException =>
        logger.error(e.getMessage, e)
        Forbidden(Json.toJson(StatusMessage("Forbidden", JsString(e.getMessage))))
      case e: NotFoundException =>
        logger.error(e.getMessage, e)
        NotFound(Json.toJson(StatusMessage("NotFound", JsString(e.getMessage))))
      case e: ChangeConflictException =>
        logger.error(e.getMessage, e)
        Conflict(Json.toJson(StatusMessage("Conflict", JsString(e.getMessage))))
      case e: Throwable =>
        logger.error(e.getMessage, e)
        InternalServerError(Json.toJson(StatusMessage("KO", JsString(e.getMessage))))
    }
  }
}

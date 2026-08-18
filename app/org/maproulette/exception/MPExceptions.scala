/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.exception

import org.maproulette.models.Lock
import sangria.execution.UserFacingError

/**
  * Simple exception class extending exception, to handle invalid API calls. This allows us to pattern
  * match on the exception and if InvalidException is found we return a BadRequest instead of
  * an InternalServerError
  *
  * @param message The message to send with the exception
  */
class InvalidException(message: String) extends Exception(message) with UserFacingError

/**
  * NotFoundException should be throw whenever we try to retrieve an object based on the object id
  * and find nothing
  *
  * @param message The message to send with the exception
  */
class NotFoundException(message: String) extends Exception(message) with UserFacingError

/**
  * Exception for handling any exceptions related to locking of MapRoulette objects
  *
  * @param message The message to send with the exception
  */
class LockedException(message: String) extends Exception(message) with UserFacingError

/**
  * Thrown when a user attempts to lock a task while already holding a lock on a
  * different task. Carries the user's existing lock so callers can surface enough
  * detail (which task/bundle is currently held) for a client to offer a
  * confirm-and-switch flow, retrying with force=true.
  *
  * @param message The message to send with the exception
  * @param conflictingLock The lock the user currently holds elsewhere
  */
class LockConflictException(message: String, val conflictingLock: Lock)
    extends Exception(message)
    with UserFacingError

/**
  * Exception for handling the unique violations when trying to insert objects into the database
  *
  * @param message The message to send with the exception
  */
class UniqueViolationException(message: String) extends Exception(message) with UserFacingError

/**
  * Exception for handling any conflicts found during changeset conflation
  *
  * @param message The message to send with the exception
  */
class ChangeConflictException(message: String) extends Exception(message) with UserFacingError

/**
  * Exception for wrapping all other errors
  *
  * @param message The message to send with the exception
  */
class InternalServerException(message: String) extends Exception(message) with UserFacingError

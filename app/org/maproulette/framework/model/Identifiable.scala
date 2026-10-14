/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

/**
  * Represents models that have an id
  *
  * @author nrotstan
  */
trait Identifiable {
  val id: Long
}

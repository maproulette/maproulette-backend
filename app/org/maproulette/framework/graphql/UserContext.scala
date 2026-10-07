/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.graphql

import org.maproulette.framework.model.User
import org.maproulette.framework.service.ServiceManager
import org.maproulette.session.SessionManager

/**
  * @author mcuthbert
  */
case class UserContext(sessionManager: SessionManager, user: User, services: ServiceManager)

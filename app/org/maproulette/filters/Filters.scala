/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.filters

import javax.inject.Inject
import play.api.http.DefaultHttpFilters
import play.filters.cors.CORSFilter
import play.filters.gzip.GzipFilter

/**
  * @author cuthbertm
  */
class Filters @Inject() (corsFilter: CORSFilter, gzipFilter: GzipFilter)
    extends DefaultHttpFilters(corsFilter, gzipFilter)

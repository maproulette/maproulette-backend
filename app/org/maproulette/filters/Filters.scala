/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.filters

import javax.inject.Inject
import play.api.http.DefaultHttpFilters
import play.api.http.EnabledFilters
import play.filters.cors.CORSFilter
import play.filters.gzip.GzipFilter

/**
 * See https://www.playframework.com/documentation/2.8.x/ScalaHttpFilters#Using-filters
  * @author cuthbertm
  */
class Filters @Inject() (defaultFilters: EnabledFilters, corsFilter: CORSFilter, gzipFilter: GzipFilter)
    extends DefaultHttpFilters(defaultFilters.filters :+ corsFilter :+ gzipFilter: _*)

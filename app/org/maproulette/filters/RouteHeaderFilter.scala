package org.maproulette.filters

import javax.inject.Inject
import akka.stream.Materializer
import play.api.mvc.Filter
import play.api.mvc.RequestHeader
import play.api.mvc.Result
import play.api.routing.Router

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/**
  * Adds an X-Route response header containing the matched route pattern, for example
  * `/api/v2/task/:id/tags`. Reverse proxies can read this header to record per-route
  * metrics such as request counts, error rates and latencies.
  */
class RouteHeaderFilter @Inject() (
    implicit val mat: Materializer,
    implicit val ec: ExecutionContext
) extends Filter {
  private val dynamicSegment = """\$([A-Za-z_][A-Za-z0-9_]*)<[^>]*>""".r

  def apply(
      nextFilter: RequestHeader => Future[Result]
  )(requestHeader: RequestHeader): Future[Result] = {
    val route = requestHeader.attrs
      .get(Router.Attrs.HandlerDef)
      .map(hd => dynamicSegment.replaceAllIn(hd.path, ":$1"))
      .getOrElse("unmatched")

    nextFilter(requestHeader).map(_.withHeaders("X-Route" -> route))
  }
}

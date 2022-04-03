package org.maproulette.filters

import javax.inject.Inject
import akka.stream.Materializer
import org.maproulette.filters.HttpLoggingFilter.logger
import org.slf4j.LoggerFactory
import play.api.mvc.Result
import play.api.mvc.RequestHeader
import play.api.mvc.Filter
import play.api.routing.HandlerDef
import play.api.routing.Router

import scala.concurrent.Future
import scala.concurrent.ExecutionContext

/**
 * Filter to provide an http request log at the service side. The HttpLoggingFilter is enabled by default within
 * application.conf but will create no output until logback is set to the debug or trace levels.
 */
class HttpLoggingFilter @Inject() (implicit val mat: Materializer, ec: ExecutionContext)
    extends Filter {
  def apply(
      nextFilter: RequestHeader => Future[Result]
  )(requestHeader: RequestHeader): Future[Result] = {
    val startTime = System.currentTimeMillis

    nextFilter(requestHeader).map { result =>
      val handlerDef: HandlerDef = requestHeader.attrs(Router.Attrs.HandlerDef)
      val action                 = handlerDef.controller + "." + handlerDef.method
      val endTime                = System.currentTimeMillis
      val requestTime            = endTime - startTime

      logger.debug(
        "id={} {}: '{}' took {}ms and returned {}",
        requestHeader.id,
        action,
        requestHeader.toString(),
        requestTime,
        result.header.status
      )

      logger.trace(
        "id={} Request Headers: {}",
        requestHeader.id,
        requestHeader.headers.headers
          .map({ case (k, v) => s"${k}=${v}" })
          .mkString("  ;; ")
      )

      result
    }
  }
}

object HttpLoggingFilter {
  private val logger = LoggerFactory.getLogger(getClass.getName)
}

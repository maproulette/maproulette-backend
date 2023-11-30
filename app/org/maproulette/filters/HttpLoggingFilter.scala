package org.maproulette.filters

import javax.inject.Inject
import akka.stream.Materializer
import org.slf4j.{LoggerFactory, MarkerFactory}
import play.api.mvc.Result
import play.api.mvc.RequestHeader
import play.api.mvc.Filter
import play.api.routing.Router

import scala.concurrent.Future
import scala.concurrent.ExecutionContext

/**
  * Filter to provide an http request log at the service side.
  */
class HttpLoggingFilter @Inject() (
    implicit val mat: Materializer,
    implicit val ec: ExecutionContext
) extends Filter {
  private val logger       = LoggerFactory.getLogger(getClass.getName)
  private val accessLogger = LoggerFactory.getLogger("AccessLogger")

  def apply(
      nextFilter: RequestHeader => Future[Result]
  )(requestHeader: RequestHeader): Future[Result] = {
    val startTime = System.currentTimeMillis
    // Create a uuid and associate it with a Marker
    val uuid   = java.util.UUID.randomUUID.toString
    val marker = MarkerFactory.getMarker(uuid)

    nextFilter(requestHeader).map { result =>
      val endTime          = System.currentTimeMillis
      val requestTotalTime = endTime - startTime
      val handlerDef       = requestHeader.attrs.get(Router.Attrs.HandlerDef)
      val action = handlerDef match {
        case Some(hd) => hd.controller + "." + hd.method
        case None     => "unknown"
      }

      accessLogger.info(
        marker,
        "Request '{}' [{}] {}ms - Response {}",
        requestHeader.toString(),
        action,
        requestTotalTime,
        result.header.status
      )

      if (logger.isTraceEnabled()) {
        logger.trace(
          marker,
          "id={} Request Headers: {}",
          requestHeader.id,
          requestHeader.headers.headers
            .map({ case (k, v) => s"${k}=${v}" })
            .mkString("  ;; ")
        )
      }

      result.withHeaders("maproulette-request-id" -> uuid)
    }
  }
}

package org.maproulette.filters

import javax.inject.Inject
import akka.stream.Materializer
import org.slf4j.LoggerFactory
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
    val uuid      = java.util.UUID.randomUUID.toString

    nextFilter(requestHeader).map { result =>
      val endTime          = System.currentTimeMillis
      val requestTotalTime = endTime - startTime
      val handlerDef       = requestHeader.attrs.get(Router.Attrs.HandlerDef)
      val action = handlerDef match {
        case Some(hd) => hd.controller + "." + hd.method
        case None     => "unknown"
      }

      accessLogger.info(
        "Request {} '{}' [{}] {}ms - Response {}",
        uuid,
        requestHeader.toString(),
        action,
        requestTotalTime,
        result.header.status
      )

      if (logger.isTraceEnabled()) {
        logger.trace(
          "Request {} Headers: {}",
          uuid,
          requestHeader.headers.headers
            .map({ case (k, v) => s"${k}=${v}" })
            .mkString("  ;; ")
        )
      }

      result.withHeaders("maproulette-request-id" -> uuid)
    }
  }
}

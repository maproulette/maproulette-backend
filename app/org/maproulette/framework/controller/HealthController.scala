/*
 * Copyright (C) 2026 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.controller

import akka.actor.ActorSystem
import akka.pattern.after
import anorm._
import com.zaxxer.hikari.HikariDataSource
import play.api.db.Database
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, TimeoutException}
import scala.util.control.NonFatal

/**
  * Health, liveness, and readiness probes for monitoring and orchestration.
  *
  * - liveness:   is the process alive? or is it stuck in a way that restarting it might fix?
  * - readiness:  should this process recieve traffic right now? (is it ready to serve requests)
  * - health:     is the process healthy, and if not, what is wrong with it?
  *
  * liveness and readiness signals are meant for use by an orchestrator or load balancer;
  * health is meant for monitoring and alerting when the application is down.
  */
@Singleton
class HealthController @Inject() (
    db: Database,
    system: ActorSystem,
    components: ControllerComponents
) extends AbstractController(components) {

  private implicit val ec: ExecutionContext = components.executionContext

  // How long readiness/health checks should wait for DB response. This should
  // be set pretty low (definitely lower than the orchestrator's healthcheck
  // timeout, so that if the DB is down the healthcheck will start failing
  // promptly instead of just resulting in long timeouts).
  private val ProbeTimeout: FiniteDuration = 5.seconds

  def liveness: Action[AnyContent] = Action {
    Ok(Json.obj("status" -> "pass"))
  }

  def readiness: Action[AnyContent] = Action.async {
    probeDatabase()
      .map(_ => Ok(Json.obj("status" -> "pass")))
      .recover { case NonFatal(_) => ServiceUnavailable(Json.obj("status" -> "fail")) }
  }

  def health: Action[AnyContent] = Action.async {
    probeDatabase()
      .map(ms => buildHealth(healthy = true, Json.obj("status" -> "pass", "milliseconds" -> ms)))
      .recover {
        case NonFatal(e) =>
          buildHealth(healthy = false, Json.obj("status" -> "fail", "output" -> e.getMessage))
      }
  }

  /**
    * Runs SELECT 1 against the default pool and returns the round-trip time in
    * milliseconds, failing the future if it does not complete within ProbeTimeout.
    * The timeout does not cancel the underlying JDBC call (which unwinds on its own
    * once HikariCP gives up); it only bounds how long the caller waits.
    */
  private def probeDatabase(): Future[Long] = {
    val start = System.nanoTime()
    val query = Future {
      db.withConnection { implicit c =>
        SQL("SELECT 1").as(SqlParser.scalar[Int].single)
      }
    }
    val timeout = after(ProbeTimeout, system.scheduler) {
      Future.failed(new TimeoutException(s"database probe exceeded ${ProbeTimeout.toMillis}ms"))
    }
    Future
      .firstCompletedOf(Seq(query, timeout))
      .map(_ => (System.nanoTime() - start) / 1000000)
  }

  private def buildHealth(healthy: Boolean, dbCheck: JsObject): Result = {
    val body = Json.obj(
      "status" -> (if (healthy) "pass" else "fail"),
      "checks" -> Json.obj(
        "database:responseTime" -> dbCheck,
        "database:connections"  -> poolStats()
      )
    )

    if (healthy) Ok(body) else ServiceUnavailable(body)
  }

  /**
    * Returns HikariCP pool utilization stats. These are application level
    * stats so they are available even if the DB is unreachable (but if multiple
    * backend replicas are running behind a load balancer, the stats only
    * describe the one that happens to serve your request)
    */
  private def poolStats(): JsValue = {
    db.dataSource match {
      case h: HikariDataSource if h.getHikariPoolMXBean != null =>
        val mx = h.getHikariPoolMXBean
        Json.obj(
          "status"   -> "pass",
          "active"   -> mx.getActiveConnections,
          "idle"     -> mx.getIdleConnections,
          "awaiting" -> mx.getThreadsAwaitingConnection,
          "total"    -> mx.getTotalConnections
        )
      case _ => Json.obj()
    }
  }
}

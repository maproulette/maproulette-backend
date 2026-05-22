/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.jobs

import java.sql.{Connection, DriverManager}
import javax.inject.{Inject, Provider, Singleton}

import org.maproulette.Config
import org.maproulette.framework.service.ServiceManager
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import play.api.Configuration
import play.api.inject.ApplicationLifecycle

import scala.concurrent.Future

/**
  * Background worker that keeps the pre-computed `tile_cells` pyramid fresh.
  *
  * The task / challenge triggers (evolution 107) emit a `tile_dirty` NOTIFY
  * whenever they enqueue dirty leaf cells. This component holds one dedicated
  * database connection, `LISTEN`s on that channel, and drains the dirty-cell
  * queue as soon as a notification arrives — so map tiles refresh within a
  * second of a mutation without polling the database on a fixed schedule.
  *
  * The blocking `getNotifications` call also returns on a timeout, which
  * doubles as a safety sweep: even if a NOTIFY is missed (e.g. emitted while
  * the listener was reconnecting) the queue is still drained every
  * [[SweepIntervalMs]]. The loop catches every Throwable and reconnects, so the
  * worker thread cannot die silently.
  *
  * The `LISTEN` connection is opened directly via `DriverManager` rather than
  * taken from Play's pool. A pooled connection is proxied/wrapped (the pool,
  * plus the SQL-logging layer when `db.logSql=true`), and that wrapper does not
  * `unwrap` to `org.postgresql.PGConnection` — which `getNotifications`
  * requires. A pooled connection also survives `close()`, so it could never be
  * truly released. A raw connection sidesteps both problems and does not tie up
  * a pool slot. Drains still use the pool (short transactions, pool's job).
  */
@Singleton
class TileDirtyListener @Inject() (
    configuration: Configuration,
    config: Config,
    serviceManager: Provider[ServiceManager],
    lifecycle: ApplicationLifecycle
) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  /** getNotifications timeout; doubles as the safety-sweep interval. */
  private val SweepIntervalMs = 30000

  /** Leaf cells recomputed per drain transaction. */
  private val DrainBatch = 512

  /** Hard cap on batches per wake-up, so a single drain cannot run unbounded. */
  private val MaxBatchesPerWake = 200

  /** Delay before reconnecting after a connection error. */
  private val ReconnectDelayMs = 5000

  private val jdbcUrl  = configuration.get[String]("db.default.url")
  private val jdbcUser = configuration.getOptional[String]("db.default.username").getOrElse("")
  private val jdbcPass = configuration.getOptional[String]("db.default.password").getOrElse("")

  @volatile private var running                = false
  @volatile private var listenConn: Connection = _
  private var worker: Thread                   = _

  // Gated only on the explicit enable flag. Note this deliberately does NOT
  // check bootstrap mode: `maproulette.bootstrap=true` is a normal dev setting,
  // not a signal to skip background tile work. Tests disable it via
  // `osm.tile.listener.enabled=false` in test.conf.
  if (config.tileListenerEnabled) {
    running = true
    worker = new Thread(() => runLoop(), "tile-dirty-listener")
    worker.setDaemon(true)
    worker.start()
    logger.info("Tile dirty-cell listener started")
  } else {
    logger.info("Tile dirty-cell listener disabled")
  }

  lifecycle.addStopHook { () =>
    running = false
    val c = listenConn
    if (c != null) {
      try c.close()
      catch { case _: Throwable => }
    }
    if (worker != null) worker.interrupt()
    Future.successful(())
  }

  private def runLoop(): Unit = {
    while (running) {
      try {
        val conn = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPass)
        conn.setAutoCommit(true)
        listenConn = conn
        try {
          val st = conn.createStatement()
          try st.execute("LISTEN tile_dirty")
          finally st.close()
          val pg = conn.unwrap(classOf[PGConnection])

          drain() // clear anything queued before we started listening
          while (running && !conn.isClosed) {
            // Blocks until a notification arrives or the timeout elapses;
            // either way the queue is drained.
            pg.getNotifications(SweepIntervalMs)
            drain()
          }
        } finally {
          listenConn = null
          try conn.close()
          catch { case _: Throwable => }
        }
      } catch {
        case _: InterruptedException => // shutting down
        case t: Throwable =>
          if (running) {
            logger.warn(s"Tile dirty-cell listener error; reconnecting: ${t.getMessage}")
            try Thread.sleep(ReconnectDelayMs)
            catch { case _: InterruptedException => }
          }
      }
    }
    logger.info("Tile dirty-cell listener stopped")
  }

  /** Drain the dirty-cell queue in bounded batches until it is empty. */
  private def drain(): Unit = {
    try {
      val service = serviceManager.get().tileAggregate
      var total   = 0
      var batches = 0
      var n       = service.rebuildDirtyCells(DrainBatch)
      total += n
      while (n >= DrainBatch && batches < MaxBatchesPerWake) {
        n = service.rebuildDirtyCells(DrainBatch)
        total += n
        batches += 1
      }
      if (total > 0) logger.info(s"Tile listener rebuilt $total dirty leaf cells")
    } catch {
      case t: Throwable => logger.warn(s"Tile dirty-cell drain failed: ${t.getMessage}")
    }
  }
}

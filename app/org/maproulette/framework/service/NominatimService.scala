/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.slf4j.LoggerFactory
import play.api.libs.json._
import play.api.libs.ws.WSClient
import scala.concurrent.{ExecutionContext, Await}
import scala.concurrent.duration._
import scala.collection.concurrent.TrieMap

/**
  * Service for interacting with the Nominatim API to fetch location polygon data.
  *
  * Polygons are resolved by stable OSM identifiers (osm_type + osm_id), not by
  * Nominatim's internal place_id. Nominatim's public instance does not guarantee
  * place_id stability across endpoints — the same place_id returned by /search
  * can resolve to an unrelated OSM object via /details.php (observed in
  * practice: a city search returning a place_id that /details.php resolves to
  * a small waterway). Using /lookup with osm_ids avoids that ambiguity.
  */
@Singleton
class NominatimService @Inject() (wsClient: WSClient)(implicit ec: ExecutionContext) {

  private val logger             = LoggerFactory.getLogger(this.getClass)
  private val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org"
  private val REQUEST_TIMEOUT    = 10.seconds

  // Cache keyed by (osm_type, osm_id). Only successful lookups are cached;
  // failures (rate limit, timeout, transient errors) must be retried on the
  // next request — caching None would make a brief Nominatim outage permanent
  // for the life of the JVM.
  private val geometryCache = TrieMap.empty[(String, Long), String]

  /**
    * Fetches polygon geometry from Nominatim for a given OSM object.
    *
    * @param osmType Single-letter OSM type ("N", "W", or "R", case-insensitive)
    * @param osmId   OSM object id
    * @return Option containing the polygon geometry as WKT string, or None if
    *         the lookup failed or the geometry can't be expressed as a polygon
    */
  def getPolygonByOsmId(osmType: String, osmId: Long): Option[String] = {
    val normalizedType = normalizeOsmType(osmType)
    normalizedType match {
      case Some(t) =>
        val key = (t, osmId)
        geometryCache.get(key) match {
          case Some(cached) => Some(cached)
          case None =>
            val result = fetchFromNominatim(t, osmId)
            result.foreach(wkt => geometryCache.put(key, wkt))
            result
        }
      case None => None
    }
  }

  /** Accepts "N"/"W"/"R" or "node"/"way"/"relation" (any case). */
  private def normalizeOsmType(osmType: String): Option[String] = {
    Option(osmType).map(_.trim.toLowerCase) match {
      case Some("n") | Some("node")     => Some("N")
      case Some("w") | Some("way")      => Some("W")
      case Some("r") | Some("relation") => Some("R")
      case _                            => None
    }
  }

  /**
    * Calls Nominatim's /lookup endpoint with the given OSM identifier to
    * retrieve the polygon GeoJSON, then converts to WKT.
    */
  private def fetchFromNominatim(osmTypePrefix: String, osmId: Long): Option[String] = {
    try {
      val lookupResponse = Await.result(
        wsClient
          .url(s"$NOMINATIM_BASE_URL/lookup")
          .withRequestTimeout(REQUEST_TIMEOUT)
          .addQueryStringParameters(
            "osm_ids"         -> s"$osmTypePrefix$osmId",
            "format"          -> "json",
            "polygon_geojson" -> "1"
          )
          .addHttpHeaders("User-Agent" -> "MapRoulette/1.0")
          .get(),
        REQUEST_TIMEOUT + 1.second
      )

      if (lookupResponse.status != 200) {
        logger.warn(
          s"Nominatim /lookup for $osmTypePrefix$osmId returned ${lookupResponse.status}: " +
            lookupResponse.body.take(200)
        )
        return None
      }

      val lookupJson = lookupResponse.json.as[JsArray]
      if (lookupJson.value.isEmpty) {
        logger.warn(s"Nominatim /lookup for $osmTypePrefix$osmId returned no results")
        return None
      }

      val place = lookupJson.value.head
      (place \ "geojson").asOpt[JsObject] match {
        case Some(geometry) =>
          val wkt = convertGeoJSONToWKT(geometry)
          if (wkt.isEmpty) {
            logger.warn(
              s"Nominatim /lookup for $osmTypePrefix$osmId returned geometry type " +
                s"${(geometry \ "type").asOpt[String].getOrElse("unknown")} which cannot be " +
                "converted to a polygon WKT"
            )
          }
          wkt
        case None =>
          logger.warn(s"Nominatim /lookup for $osmTypePrefix$osmId returned no geojson")
          None
      }
    } catch {
      case e: java.util.concurrent.TimeoutException =>
        logger.warn(s"Nominatim /lookup for $osmTypePrefix$osmId timed out", e)
        None
      case e: Exception =>
        logger.warn(s"Nominatim /lookup for $osmTypePrefix$osmId failed: ${e.getMessage}", e)
        None
    }
  }

  /**
    * Converts a GeoJSON geometry object to WKT (Well-Known Text) format for PostGIS.
    * Handles Polygon, MultiPolygon, and Point geometry types.
    */
  private def convertGeoJSONToWKT(geometry: JsObject): Option[String] = {
    try {
      val geometryType = (geometry \ "type").asOpt[String]
      val coordinates  = (geometry \ "coordinates").asOpt[JsArray]

      (geometryType, coordinates) match {
        case (Some("Polygon"), Some(coords)) if coords.value.nonEmpty =>
          Some(polygonToWKT(coords))
        case (Some("MultiPolygon"), Some(coords)) if coords.value.nonEmpty =>
          Some(multiPolygonToWKT(coords))
        case (Some("Point"), Some(coords)) if coords.value.size >= 2 =>
          Some(pointToWKT(coords))
        case _ =>
          None
      }
    } catch {
      case _: Exception =>
        None
    }
  }

  /**
    * Converts a GeoJSON Polygon to WKT format
    */
  private def polygonToWKT(coordinates: JsArray): String = {
    val rings = coordinates.value
      .map { ring =>
        val points = ring
          .as[JsArray]
          .value
          .map { point =>
            val coords = point.as[JsArray]
            s"${coords(0).as[Double]} ${coords(1).as[Double]}"
          }
          .mkString(", ")
        s"($points)"
      }
      .mkString(", ")
    s"POLYGON($rings)"
  }

  /**
    * Converts a GeoJSON MultiPolygon to WKT format
    */
  private def multiPolygonToWKT(coordinates: JsArray): String = {
    val polygons = coordinates.value
      .map { polygon =>
        val rings = polygon
          .as[JsArray]
          .value
          .map { ring =>
            val points = ring
              .as[JsArray]
              .value
              .map { point =>
                val coords = point.as[JsArray]
                s"${coords(0).as[Double]} ${coords(1).as[Double]}"
              }
              .mkString(", ")
            s"($points)"
          }
          .mkString(", ")
        s"($rings)"
      }
      .mkString(", ")
    s"MULTIPOLYGON($polygons)"
  }

  /**
    * Converts a GeoJSON Point to WKT format
    */
  private def pointToWKT(coordinates: JsArray): String = {
    val coords = coordinates.value
    s"POINT(${coords(0).as[Double]} ${coords(1).as[Double]})"
  }

  /**
    * Clears the geometry cache
    */
  def clearCache(): Unit = {
    geometryCache.clear()
  }

  /**
    * Gets the current cache size
    */
  def getCacheSize: Int = {
    geometryCache.size
  }
}

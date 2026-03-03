/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import play.api.libs.json._
import play.api.libs.ws.WSClient
import scala.concurrent.{ExecutionContext, Await}
import scala.concurrent.duration._
import scala.collection.concurrent.TrieMap

/**
  * Service for interacting with the Nominatim API to fetch location polygon data
  *
  * @author maproulette
  */
@Singleton
class NominatimService @Inject() (wsClient: WSClient)(implicit ec: ExecutionContext) {

  private val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org"
  private val REQUEST_TIMEOUT    = 10.seconds

  // Cache to store WKT geometries by place_id (thread-safe)
  private val geometryCache = TrieMap.empty[Long, Option[String]]

  /**
    * Fetches polygon geometry from Nominatim using a place_id
    *
    * @param placeId The Nominatim place_id (location_id)
    * @return Option containing the polygon geometry as WKT string, or None if not found or error
    */
  def getPolygonByPlaceId(placeId: Long): Option[String] = {
    // Check cache first
    geometryCache.get(placeId) match {
      case Some(cachedResult) => cachedResult
      case None               =>
        // Not in cache, fetch from Nominatim
        val result = fetchFromNominatim(placeId)
        // Store in cache (even if None, to avoid repeated failed requests)
        geometryCache.put(placeId, result)
        result
    }
  }

  /**
    * Fetches polygon geometry from Nominatim API using a two-step lookup (not cached).
    *
    * Step 1: Call /details to get the osm_type and osm_id for the place_id
    * Step 2: Call /lookup with the osm_ids to get the actual polygon geometry
    *
    * This two-step approach is necessary because the /details endpoint's polygon_geojson
    * can return incorrect geometry (e.g. a building instead of the searched region),
    * while /lookup reliably returns the correct polygon for the OSM object.
    *
    * @param placeId The Nominatim place_id
    * @return Option containing the WKT polygon string
    */
  private def fetchFromNominatim(placeId: Long): Option[String] = {
    try {
      // Step 1: Get osm_type and osm_id from /details
      val detailsResponse = Await.result(
        wsClient
          .url(s"$NOMINATIM_BASE_URL/details.php")
          .withRequestTimeout(REQUEST_TIMEOUT)
          .addQueryStringParameters(
            "place_id"       -> placeId.toString,
            "format"         -> "json",
            "addressdetails" -> "0"
          )
          .addHttpHeaders("User-Agent" -> "MapRoulette/1.0")
          .get(),
        REQUEST_TIMEOUT + 1.second
      )

      if (detailsResponse.status != 200) return None

      val detailsJson = detailsResponse.json
      val osmType     = (detailsJson \ "osm_type").asOpt[String]
      val osmId       = (detailsJson \ "osm_id").asOpt[Long]

      (osmType, osmId) match {
        case (Some(ot), Some(oid)) =>
          // Build OSM ID prefix: N for node, W for way, R for relation
          val prefix = ot match {
            case "node"     => "N"
            case "way"      => "W"
            case "relation" => "R"
            case _          => return None
          }

          // Step 2: Get polygon geometry from /lookup
          val lookupResponse = Await.result(
            wsClient
              .url(s"$NOMINATIM_BASE_URL/lookup")
              .withRequestTimeout(REQUEST_TIMEOUT)
              .addQueryStringParameters(
                "osm_ids"         -> s"$prefix$oid",
                "format"          -> "json",
                "polygon_geojson" -> "1"
              )
              .addHttpHeaders("User-Agent" -> "MapRoulette/1.0")
              .get(),
            REQUEST_TIMEOUT + 1.second
          )

          if (lookupResponse.status != 200) return None

          val lookupJson = lookupResponse.json.as[JsArray]
          if (lookupJson.value.isEmpty) return None

          val place = lookupJson.value.head
          (place \ "geojson").asOpt[JsObject] match {
            case Some(geometry) => convertGeoJSONToWKT(geometry)
            case None           => None
          }

        case _ => None
      }
    } catch {
      case _: java.util.concurrent.TimeoutException => None
      case _: Exception                             => None
    }
  }

  /**
    * Converts a GeoJSON geometry object to WKT (Well-Known Text) format for PostGIS.
    * Handles Polygon, MultiPolygon, Point, and LineString geometry types.
    *
    * @param geometry The GeoJSON geometry object
    * @return Option containing the WKT string, or None if conversion fails or type unsupported
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
        case (Some("LineString"), Some(coords)) if coords.value.size >= 2 =>
          None
        case (Some("GeometryCollection"), _) =>
          None
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
    * Gets polygon geometry from cache or fetches from Nominatim
    * This is a synchronous wrapper for easier integration with existing code
    *
    * @param placeId The Nominatim place_id
    * @return Option containing the WKT polygon string
    */
  def getLocationPolygon(placeId: Long): Option[String] = {
    getPolygonByPlaceId(placeId)
  }

  /**
    * Clears the geometry cache
    * Useful for testing or if cache needs to be refreshed
    */
  def clearCache(): Unit = {
    geometryCache.clear()
  }

  /**
    * Gets the current cache size
    *
    * @return Number of cached geometries
    */
  def getCacheSize: Int = {
    geometryCache.size
  }
}

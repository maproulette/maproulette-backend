/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import play.api.libs.json._
import play.api.libs.ws.WSClient
import scala.concurrent.{ExecutionContext, Future, Await}
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
    * Fetches polygon geometry from Nominatim API (not cached)
    *
    * @param placeId The Nominatim place_id
    * @return Option containing the WKT polygon string
    */
  private def fetchFromNominatim(placeId: Long): Option[String] = {
    try {
      val url = s"$NOMINATIM_BASE_URL/details.php"

      val futureResponse = wsClient
        .url(url)
        .addQueryStringParameters(
          "place_id"        -> placeId.toString,
          "format"          -> "json",
          "polygon_geojson" -> "1",
          "addressdetails"  -> "0"
        )
        .addHttpHeaders(
          "User-Agent" -> "MapRoulette/1.0"
        )
        .get()

      val response = Await.result(futureResponse, REQUEST_TIMEOUT)

      if (response.status == 200) {
        val json = response.json

        // Extract the geometry from the response
        (json \ "geometry").asOpt[JsObject] match {
          case Some(geometry) =>
            // Convert the GeoJSON geometry to a WKT string for PostGIS
            convertGeoJSONToWKT(geometry)
          case None =>
            None
        }
      } else {
        None
      }
    } catch {
      case e: Exception =>
        None
    }
  }

  /**
    * Converts a GeoJSON geometry object to WKT (Well-Known Text) format for PostGIS
    *
    * @param geometry The GeoJSON geometry object
    * @return Option containing the WKT string, or None if conversion fails
    */
  private def convertGeoJSONToWKT(geometry: JsObject): Option[String] = {
    val geometryType = (geometry \ "type").asOpt[String]
    val coordinates  = (geometry \ "coordinates").asOpt[JsArray]

    (geometryType, coordinates) match {
      case (Some("Polygon"), Some(coords)) =>
        Some(polygonToWKT(coords))
      case (Some("MultiPolygon"), Some(coords)) =>
        Some(multiPolygonToWKT(coords))
      case (Some("Point"), Some(coords)) =>
        Some(pointToWKT(coords))
      case _ =>
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

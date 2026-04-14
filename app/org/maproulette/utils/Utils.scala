/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.utils

import org.apache.commons.lang3.{StringEscapeUtils, StringUtils}
import org.joda.time.DateTime
import org.maproulette.exception.NotFoundException
import org.maproulette.framework.model.{User, Task}
import org.maproulette.models.Lock
import play.api.libs.json._
import play.api.mvc.Result
import play.api.mvc.Results._

import scala.reflect.ClassTag
import scala.reflect.runtime.universe._
import scala.util.{Failure, Random, Success, Try}

/**
  * Some random utliity helper functions
  *
  * @author cuthbertm
  */
object Utils extends DefaultWrites {
  val UTC_TIMEZONE = "+00:00"

  def randomStringFromCharList(
      length: Int,
      chars: Seq[Char] = ('a' to 'z') ++ ('A' to 'Z')
  ): String =
    (1 to length).foldLeft("")((previous, _) => {
      val randomNum = Random.nextInt(chars.length)
      previous + chars(randomNum)
    })

  def getProperties(value: JsValue, key: String): JsValue = {
    (value \ key).asOpt[JsObject] match {
      case Some(JsObject(p)) =>
        val idMap = (value \ "id").toOption match {
          case Some(idValue) => p + ("osmid" -> idValue)
          case None          => p
        }
        val updatedMap = idMap.map { kv =>
          if (kv._1 == "maproulette") {
            val fix = kv._2 match {
              case sf: JsValue => Json.stringify(sf)
              case _           => ""
            }
            kv._1 -> fix
          } else {
            try {
              val strValue = kv._2 match {
                case JsNull      => ""
                case v: JsNumber => v.toString
                case v: JsArray  => v.as[Seq[String]].mkString(",")
                case v           => v.as[String]
              }
              kv._1 -> strValue
            } catch {
              // if we can't convert it into a string, then just use the toString method and hope we get something sensible
              // other option could be just to ignore it.
              case e: Throwable =>
                kv._1 -> kv._2.toString
            }
          }
        }.toMap
        Json.toJson(updatedMap).as[JsObject]
      case _ => Json.obj()
    }
  }

  /**
    * Unescapes stringified JSON (where the JSON data was surrounded by quotes
    * and the internal quotes escaped). Removes those surrounding quotes and
    * unescapes internal quotes, making it suitable for parsing. This is
    * probably safe to call even on most non-stringified strings that are
    * intended for JSON parsing
    */
  def unescapeStringifiedJSON(j: String): String = {
    StringUtils.removeStart(StringUtils.removeEnd(j, "\""), "\"").replaceAll("\\\\\"", "\"")
  }

  def tryOptional[T](func: () => T): Option[T] = {
    Try(func.apply()) match {
      case Success(value)     => Some(value)
      case Failure(exception) => None
    }
  }

  /**
    * https://stackoverflow.com/questions/15488639/how-to-write-readst-and-writest-in-scala-enumeration-play-framework-2-1
    */
  def enumReads[E <: Enumeration](enum: E): Reads[E#Value] = new Reads[E#Value] {
    def reads(json: JsValue): JsResult[E#Value] = json match {
      case JsString(s) => {
        try {
          JsSuccess(enum.withName(s))
        } catch {
          case _: NoSuchElementException =>
            JsError(
              s"Enumeration expected of type: '${enum.getClass}', but it does not appear to contain the value: '$s'"
            )
        }
      }
      case _ => JsError("String value expected")
    }
  }

  /**
    * https://stackoverflow.com/questions/15488639/how-to-write-readst-and-writest-in-scala-enumeration-play-framework-2-1
    */
  def enumWrites[E <: Enumeration]: Writes[E#Value] = new Writes[E#Value] {
    def writes(v: E#Value): JsValue = JsString(v.toString)
  }

  /**
    * Checks to see if a string is a number
    *
    * @param x The string to check
    * @return true is string is a number
    */
  def isDigit(x: String): Boolean = x forall Character.isDigit

  /**
    * Wraps a JSON message inside of a BadRequest response
    *
    * @param message The message to place inside the json
    * @return 400 BadRequest
    */
  def badRequest(message: String): Result = {
    BadRequest(Json.obj("status" -> "KO", "message" -> message))
  }

  /**
    * Quick method that will include a -1 id. -1 is the long value that is used for an object
    * that has not been inserted into the database yet. Will not add if id is already there
    *
    * @param json The json that you want to add the id into
    * @return
    */
  def insertJsonID(json: JsValue, overwrite: Boolean = false): JsValue =
    insertIntoJson(json, "id", -1L)(LongWrites)

  def insertIntoJson[Value](json: JsValue, key: String, value: Value, overwrite: Boolean = false)(
      implicit writes: Writes[Value]
  ): JsValue = {
    if (overwrite) {
      json.as[JsObject] + (key -> Json.toJson(value))
    } else {
      (json \ key).toOption match {
        case Some(_) => json
        case None    => json.as[JsObject] + (key -> Json.toJson(value))
      }
    }
  }

  def getCaseClassVariableNames[T: TypeTag]: List[MethodSymbol] = {
    typeOf[T].members.collect {
      case m: MethodSymbol if m.isCaseAccessor => m
    }.toList
  }

  def getCaseClassValueMappings[T: ClassTag](
      obj: T
  )(implicit tag: TypeTag[T]): Map[MethodSymbol, Any] = {
    implicit val mirror = scala.reflect.runtime.currentMirror
    val im              = mirror.reflect(obj)
    typeOf[T].members.collect {
      case m: MethodSymbol if m.isCaseAccessor => m -> im.reflectMethod(m).apply()
    }.toMap
  }

  def omitEmpty(
      original: JsObject,
      strings: Boolean = true,
      arrays: Boolean = true,
      objects: Boolean = true
  ): JsObject = {
    original.value.foldLeft(original) {
      case (obj, (key, JsString(st))) if strings & st.isEmpty => obj - key
      case (obj, (key, JsArray(arr))) if arrays & arr.isEmpty => obj - key
      case (obj, (key, JsObject(o))) if objects & o.isEmpty   => obj - key
      case (obj, (_, _))                                      => obj
    }
  }

  def split(value: String, splitCharacter: String = ","): List[String] = value match {
    case "" => List[String]()
    case _  => value.split(splitCharacter).toList
  }

  def optionStringToOptionInt(value: Option[String]): Option[Int] = value match {
    case Some(v) => Some(v.toInt)
    case None    => None
  }

  def optionStringToOptionLong(value: Option[String]): Option[Long] = value match {
    case Some(v) => Some(v.toLong)
    case None    => None
  }

  def optionStringToOptionBoolean(value: Option[String]): Option[Boolean] = value match {
    case Some(v) => Some(v.toBoolean)
    case None    => None
  }

  def optionStringToOptionStringList(value: Option[String]): Option[List[String]] = value match {
    case Some(v) if v.nonEmpty => Some(v.split(",").toList)
    case None                  => None
  }

  def toStringList(stringList: String): Option[List[String]] =
    if (stringList.isEmpty) {
      None
    } else {
      Some(stringList.split(",").toList)
    }

  def toLongList(stringList: String): Option[List[Long]] =
    if (stringList.isEmpty) {
      None
    } else {
      Some(stringList.split(",").toList.map(_.toLong))
    }

  def toIntList(stringList: String): Option[List[Int]] =
    if (stringList.isEmpty) {
      None
    } else {
      Some(stringList.split(",").toList.map(_.toInt))
    }

  def toDoubleList(stringList: String): Option[List[Double]] =
    if (stringList.isEmpty) {
      None
    } else {
      Some(stringList.split(",").toList.map(_.toDouble))
    }

  def toMap(stringMap: String): Option[Map[String, String]] =
    if (stringMap.isEmpty) {
      None
    } else {
      val resultMap = scala.collection.mutable.Map[String, String]()
      stringMap.split(",").foreach { r =>
        {
          val pair = r.split(":")
          resultMap += new String(java.util.Base64.getDecoder.decode(pair(0))) ->
            new String(java.util.Base64.getDecoder.decode(pair(1)))
        }
      }
      Some(resultMap.toMap)
    }

  def getDate(date: String): Option[DateTime] =
    if (StringUtils.isEmpty(date)) {
      None
    } else {
      Some(DateTime.parse(date))
    }

  def negativeToOption(value: Long): Option[Long] = value match {
    case v if v < 0 => None
    case v          => Some(v)
  }

  def getResponseJSONNoLock(
      task: Option[Task],
      userFunc: (User, Long, Int) => List[User]
  ): JsValue = task match {
    case Some(t) => getResponseJSON(Some(t, Lock.emptyLock), userFunc)
    case None    => getResponseJSON(None, userFunc)
  }

  /**
    * Builds the response JSON for mapping based on a Task
    *
    * @param task The optional task to check
    * @return If None supplied as Task parameter then will throw NotFoundException
    */
  def getResponseJSON(
      task: Option[(Task, Lock)],
      userFunc: (User, Long, Int) => List[User]
  ): JsValue = task match {
    case Some(t) =>
      val currentStatus = t._1.status.getOrElse(Task.STATUS_CREATED)
      val locked = t._2.lockedTime match {
        case Some(_) => true
        case None    => false
      }
      val userString = userFunc(null, t._1.id, 1).headOption match {
        case Some(user) =>
          s"""
             |   "last_modified_user_osm_id":${user.osmProfile.id},
             |   "last_modified_user_id":${user.id},
             |   "last_modified_user":"${user.osmProfile.displayName}",
           """.stripMargin
        case None => ""
      }
      Json.parse(s"""
           |{
           |   "id":${t._1.id},
           |   "parentId":${t._1.parent},
           |   "name":"${t._1.name}",
           |   "instruction":"${StringEscapeUtils.escapeJson(t._1.instruction.getOrElse(""))}",
           |   "statusName":"${Task
                      .getStatusName(currentStatus)
                      .getOrElse(Task.STATUS_CREATED_NAME)}",
           |   "status":$currentStatus, $userString
           |   "geometry":${t._1.geometries},
           |   "locked":$locked,
           |   "created":"${t._1.created}",
           |   "modified":"${t._1.modified}"
           |}
            """.stripMargin)
    case None => throw new NotFoundException(s"Could not find task")
  }

  class jsonWrites(key: String) extends Writes[String] {
    override def writes(value: String): JsValue = Json.parse(value)
  }

  class jsonReads(key: String) extends Reads[String] {
    override def reads(value: JsValue): JsResult[String] = JsSuccess(value.toString())
  }
}

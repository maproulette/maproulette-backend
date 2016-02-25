package org.maproulette.data

import anorm._
import anorm.SqlParser._
import org.maproulette.data.dal.{ChallengeDAL, TagDAL}
import play.api.Play.current
import play.api.db.DB
import play.api.libs.json._

/**
  * @author cuthbertm
  */
case class Task(override val id:Long,
                override val name: String,
                override val identifier:Option[String]=None,
                parent: Long,
                instruction: String,
                location: JsValue,
                status:Option[Int]=None) extends BaseObject[Long] {
  lazy val tags:List[Tag] = DB.withConnection { implicit c =>
    SQL"""SELECT t.id, t.name, t.description FROM tags as t
        INNER JOIN tags_on_tasks as tt ON t.id = tt.tag_id
        WHERE tt.task_id = $id""".as(TagDAL.parser *)
  }

  def getParent = ChallengeDAL.retrieveById(parent)
}

object Task {
  implicit val taskReads: Reads[Task] = Json.reads[Task]
  implicit val taskWrites: Writes[Task] = Json.writes[Task]

  val STATUS_CREATED = 0
  val STATUS_FIXED = 1
  val STATUS_FALSE_POSITIVE = 2
  val STATUS_SKIPPED = 3
  val STATUS_DELETED = 4

  def isValidStatusProgression(current:Int, toSet:Int) : Boolean = {
    if (current == toSet) {
      true
    } else {
      current match {
        case STATUS_CREATED => toSet > STATUS_CREATED || toSet <= STATUS_DELETED
        case STATUS_FIXED => false
        case STATUS_FALSE_POSITIVE => toSet == STATUS_FIXED
        case STATUS_SKIPPED => toSet == STATUS_FIXED || toSet == STATUS_FALSE_POSITIVE || toSet == STATUS_DELETED
        case STATUS_DELETED => toSet == STATUS_CREATED
      }
    }
  }
}

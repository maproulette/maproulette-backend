package org.maproulette.permissions

import java.util.UUID

import org.maproulette.exception.InvalidException
import org.maproulette.framework.model.{Challenge, Project, User}
import org.maproulette.framework.util.{FrameworkHelper, TaskTag}
import play.api.Application
import play.api.libs.json.Json

class TaskUpdateAccessSpec(implicit val application: Application) extends FrameworkHelper {
  private var attacker: User               = null
  private var attackerProject: Project     = null
  private var attackerChallenge: Challenge = null

  "Updating a task" should {
    "be refused for a user with write on another challenge who names it as parent" taggedAs TaskTag in {
      val victim = this.defaultTask
      an[IllegalAccessException] should be thrownBy this.taskDAL.update(
        Json.obj("parentId" -> attackerChallenge.id, "name" -> "hijacked"),
        fresh(attacker)
      )(victim.id)
      this.taskDAL.retrieveById(victim.id).get.name mustEqual victim.name
    }

    "be refused for a user with no access to the task's challenge" taggedAs TaskTag in {
      val victim = this.defaultTask
      an[IllegalAccessException] should be thrownBy this.taskDAL.update(
        Json.obj("name" -> "hijacked"),
        fresh(attacker)
      )(victim.id)
      this.taskDAL.retrieveById(victim.id).get.name mustEqual victim.name
    }

    "refuse to merge an existing task under a different parent" taggedAs TaskTag in {
      val victim = this.defaultTask
      an[InvalidException] should be thrownBy this.taskDAL.mergeUpdate(
        victim.copy(parent = attackerChallenge.id, name = "hijacked"),
        fresh(attacker)
      )(victim.id)
      this.taskDAL.retrieveById(victim.id).get.name mustEqual victim.name
    }

    "still allow a user to update a task in their own challenge" taggedAs TaskTag in {
      val own = this.taskDAL.insert(
        this.getTestTask(UUID.randomUUID().toString, attackerChallenge.id),
        User.superUser
      )
      this.taskDAL.update(
        Json.obj("parentId" -> attackerChallenge.id, "name" -> "renamed"),
        fresh(attacker)
      )(own.id)
      this.taskDAL.retrieveById(own.id).get.name mustEqual "renamed"
    }
  }

  private def fresh(user: User): User = this.serviceManager.user.retrieve(user.id).get

  override implicit val projectTestName: String = "TaskUpdateAccessSpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    attacker = this.serviceManager.user.create(
      this.getTestUser(51234569, "TaskUpdateAttacker"),
      User.superUser
    )
    attackerProject = this.serviceManager.project.create(
      Project(-1, attacker.osmProfile.id, "TaskUpdateAccessSpec_attacker"),
      attacker
    )
    attackerChallenge = this.challengeDAL.insert(
      this.getTestChallenge("TaskUpdateAccessSpec_attacker", attackerProject.id),
      User.superUser
    )
  }

  override protected def afterAll(): Unit = {
    this.serviceManager.project.delete(attackerProject.id, User.superUser, true)
    super.afterAll()
  }
}

package org.maproulette.permissions

import scala.collection.mutable.ListBuffer

import org.maproulette.exception.InvalidException
import org.maproulette.framework.model.{
  Challenge,
  ChallengeExtra,
  Grant,
  Group,
  MemberObject,
  Project,
  TeamMember,
  TeamRole,
  User
}
import org.maproulette.framework.util.{FrameworkHelper, TeamTag}
import play.api.Application
import play.api.libs.json.{JsNull, Json}

class OwnerTeamAccessSpec(implicit val application: Application) extends FrameworkHelper {
  private var writer: User               = null
  private var writerTeam: Group          = null
  private var adminTeam: Group           = null
  private var manager: User              = null
  private var managedTeam: Group         = null
  private var challengeWriter: User      = null
  private var challengeWriterTeam: Group = null
  private val created                    = ListBuffer[Project]()

  "Changing a project's owning team" should {
    "be refused for a write user handing it to their own team" taggedAs TeamTag in {
      val project = writableProject("assign")
      an[IllegalAccessException] should be thrownBy this.serviceManager.project
        .update(project.id, Json.obj("ownerTeamId" -> writerTeam.id), fresh(writer))
      this.serviceManager.project.retrieve(project.id).get.ownerTeamId mustEqual None
    }

    "be refused for a write user clearing the current owning team" taggedAs TeamTag in {
      val project = writableProject("clear", ownerTeamId = Some(adminTeam.id))
      an[IllegalAccessException] should be thrownBy this.serviceManager.project
        .update(project.id, Json.obj("ownerTeamId" -> JsNull), fresh(writer))
      this.serviceManager.project.retrieve(project.id).get.ownerTeamId mustEqual Some(adminTeam.id)
    }

    "be allowed for an admin handing it to a team they manage" taggedAs TeamTag in {
      val project = writableProject("admin_assign")
      this.serviceManager.project
        .update(project.id, Json.obj("ownerTeamId" -> adminTeam.id), this.defaultUser)
        .get
        .ownerTeamId mustEqual Some(adminTeam.id)
    }

    "be refused for an admin handing it to a team they do not manage" taggedAs TeamTag in {
      val project = writableProject("admin_foreign")
      an[InvalidException] should be thrownBy this.serviceManager.project
        .update(project.id, Json.obj("ownerTeamId" -> writerTeam.id), this.defaultUser)
    }

    "be refused for an admin taking it from a team they do not manage" taggedAs TeamTag in {
      val project = writableProject("admin_take", ownerTeamId = Some(writerTeam.id))
      an[InvalidException] should be thrownBy this.serviceManager.project
        .update(project.id, Json.obj("ownerTeamId" -> JsNull), this.defaultUser)
      this.serviceManager.project.retrieve(project.id).get.ownerTeamId mustEqual Some(writerTeam.id)
    }

    "be refused for a manager of the owning team clearing it" taggedAs TeamTag in {
      // A manager gets write through the team, and clearing ownership takes admin away
      // from the team's owners and admins
      val project = writableProject("manager_clear", ownerTeamId = Some(managedTeam.id))
      an[IllegalAccessException] should be thrownBy this.serviceManager.project
        .update(project.id, Json.obj("ownerTeamId" -> JsNull), fresh(manager))
      this.serviceManager.project.retrieve(project.id).get.ownerTeamId mustEqual Some(
        managedTeam.id
      )
    }

    "reject a non-numeric team id" taggedAs TeamTag in {
      val project = writableProject("non_numeric")
      an[InvalidException] should be thrownBy this.serviceManager.project
        .update(project.id, Json.obj("ownerTeamId" -> "abc"), this.defaultUser)
    }
  }

  "Updating a project as a write user" should {
    "not allow changing its owner" taggedAs TeamTag in {
      val project = writableProject("owner")
      an[IllegalAccessException] should be thrownBy this.serviceManager.project
        .update(project.id, Json.obj("ownerId" -> writer.osmProfile.id), fresh(writer))
    }

    "still allow ordinary edits" taggedAs TeamTag in {
      val project = writableProject("edit")
      this.serviceManager.project
        .update(project.id, Json.obj("description" -> "edited"), fresh(writer))
        .get
        .description mustEqual Some("edited")
    }
  }

  "Creating a project" should {
    "be refused under a team the creator does not manage" taggedAs TeamTag in {
      an[InvalidException] should be thrownBy this.serviceManager.project.create(
        Project(
          -1,
          writer.osmProfile.id,
          "OwnerTeamAccessSpec_create_foreign",
          ownerTeamId = Some(adminTeam.id)
        ),
        fresh(writer)
      )
    }
  }

  "Changing a challenge's owning team" should {
    "be refused for a write user handing it to their own team" taggedAs TeamTag in {
      val project = writableProject("challenge")
      val challenge = this.challengeDAL.insert(
        this.getTestChallenge("OwnerTeamAccessSpec_challenge", project.id),
        User.superUser
      )
      an[IllegalAccessException] should be thrownBy this.challengeDAL
        .update(Json.obj("ownerTeamId" -> writerTeam.id), fresh(writer))(challenge.id)
      this.challengeDAL.retrieveById(challenge.id).get.extra.ownerTeamId mustEqual None
    }

    "be refused for a user with write granted only on the challenge" taggedAs TeamTag in {
      val project = writableProject("challenge_grant")
      val challenge = this.challengeDAL.insert(
        this.getTestChallenge("OwnerTeamAccessSpec_challenge_grant", project.id),
        User.superUser
      )
      this.serviceManager.challenge.addUserToChallenge(
        challenge.id,
        challengeWriter.id,
        Grant.ROLE_WRITE_ACCESS,
        User.superUser
      )
      an[IllegalAccessException] should be thrownBy this.challengeDAL
        .update(Json.obj("ownerTeamId" -> challengeWriterTeam.id), fresh(challengeWriter))(
          challenge.id
        )
      this.challengeDAL.retrieveById(challenge.id).get.extra.ownerTeamId mustEqual None
    }

    "be refused for a manager of the owning team clearing it" taggedAs TeamTag in {
      val project = writableProject("challenge_manager_clear")
      val challenge = this.challengeDAL.insert(
        this
          .getTestChallenge("OwnerTeamAccessSpec_challenge_manager_clear", project.id)
          .copy(extra = ChallengeExtra(ownerTeamId = Some(managedTeam.id))),
        User.superUser
      )
      an[IllegalAccessException] should be thrownBy this.challengeDAL
        .update(Json.obj("ownerTeamId" -> JsNull), fresh(manager))(challenge.id)
      this.challengeDAL.retrieveById(challenge.id).get.extra.ownerTeamId mustEqual Some(
        managedTeam.id
      )
    }
  }

  "Changing a challenge's owning team as an admin" should {
    "be refused for a team they do not manage" taggedAs TeamTag in {
      val challenge = writableChallenge("challenge_admin_foreign")
      an[InvalidException] should be thrownBy this.challengeDAL
        .update(Json.obj("ownerTeamId" -> writerTeam.id), this.defaultUser)(challenge.id)
      this.challengeDAL.retrieveById(challenge.id).get.extra.ownerTeamId mustEqual None
    }

    "be allowed for a team they manage" taggedAs TeamTag in {
      val challenge = writableChallenge("challenge_admin_assign")
      this.challengeDAL
        .update(Json.obj("ownerTeamId" -> adminTeam.id), this.defaultUser)(challenge.id)
        .get
        .extra
        .ownerTeamId mustEqual Some(adminTeam.id)
    }

    "reject a non-numeric team id" taggedAs TeamTag in {
      val challenge = writableChallenge("challenge_non_numeric")
      an[InvalidException] should be thrownBy this.challengeDAL
        .update(Json.obj("ownerTeamId" -> "abc"), this.defaultUser)(challenge.id)
    }
  }

  "Updating a challenge as a write user" should {
    "not allow moving it to another project" taggedAs TeamTag in {
      val challenge = writableChallenge("challenge_move")
      val other     = writableProject("challenge_move_target")
      an[InvalidException] should be thrownBy this.challengeDAL
        .update(Json.obj("parentId" -> other.id), fresh(writer))(challenge.id)
      this.challengeDAL
        .retrieveById(challenge.id)
        .get
        .general
        .parent mustEqual challenge.general.parent
    }

    "not allow changing its owner" taggedAs TeamTag in {
      val challenge = writableChallenge("challenge_owner")
      an[IllegalAccessException] should be thrownBy this.challengeDAL
        .update(Json.obj("ownerId" -> writer.osmProfile.id), fresh(writer))(challenge.id)
      this.challengeDAL
        .retrieveById(challenge.id)
        .get
        .general
        .owner mustEqual challenge.general.owner
    }

    "not allow featuring it" taggedAs TeamTag in {
      val challenge = writableChallenge("challenge_featured")
      an[IllegalAccessException] should be thrownBy this.challengeDAL
        .update(Json.obj("featured" -> true), fresh(writer))(challenge.id)
      this.challengeDAL.retrieveById(challenge.id).get.general.featured mustEqual false
    }

    "still allow ordinary edits that resend unchanged fields" taggedAs TeamTag in {
      val challenge = writableChallenge("challenge_edit")
      this.challengeDAL
        .update(
          Json.obj(
            "description" -> "edited",
            "featured"    -> false,
            "parentId"    -> challenge.general.parent,
            "ownerId"     -> challenge.general.owner
          ),
          fresh(writer)
        )(challenge.id)
        .get
        .description mustEqual Some("edited")
    }
  }

  /**
    * A challenge in a fresh writable project (see writableProject), created by the superuser.
    */
  private def writableChallenge(label: String): Challenge = {
    val project = writableProject(label)
    this.challengeDAL.insert(
      this.getTestChallenge(s"OwnerTeamAccessSpec_$label", project.id),
      User.superUser
    )
  }

  /**
    * A project administered by the default user, on which the writer holds write access.
    * Created as the superuser so it can start out owned by any team.
    */
  private def writableProject(label: String, ownerTeamId: Option[Long] = None): Project = {
    val project = this.serviceManager.project.create(
      Project(
        -1,
        this.defaultUser.osmProfile.id,
        s"OwnerTeamAccessSpec_$label",
        ownerTeamId = ownerTeamId
      ),
      User.superUser
    )
    created += project
    this.serviceManager.user
      .addUserToProject(writer.osmProfile.id, project.id, Grant.ROLE_WRITE_ACCESS, User.superUser)
    project
  }

  private def ownedTeam(label: String, owner: User): Group =
    this.serviceManager.team
      .create(this.getTestTeam(s"OwnerTeamAccessSpec_$label"), MemberObject.user(owner.id), owner)
      .get

  private def fresh(user: User): User = this.serviceManager.user.retrieve(user.id).get

  override implicit val projectTestName: String = "OwnerTeamAccessSpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    writer = this.serviceManager.user.create(
      this.getTestUser(51234570, "OwnerTeamAccessWriter"),
      User.superUser
    )
    writerTeam = ownedTeam("writer", writer)
    adminTeam = ownedTeam("admin", this.defaultUser)
    manager = this.serviceManager.user.create(
      this.getTestUser(51234571, "OwnerTeamAccessManager"),
      User.superUser
    )
    managedTeam = ownedTeam("managed", this.defaultUser)
    this.serviceManager.team.addTeamMember(
      managedTeam,
      MemberObject.user(manager.id),
      TeamRole.MANAGER,
      TeamMember.STATUS_MEMBER,
      this.defaultUser
    )
    challengeWriter = this.serviceManager.user.create(
      this.getTestUser(51234572, "OwnerTeamAccessChallengeWriter"),
      User.superUser
    )
    challengeWriterTeam = ownedTeam("challenge_writer", challengeWriter)
  }

  override protected def afterAll(): Unit = {
    created.foreach(p => this.serviceManager.project.delete(p.id, User.superUser, true))
    super.afterAll()
  }
}

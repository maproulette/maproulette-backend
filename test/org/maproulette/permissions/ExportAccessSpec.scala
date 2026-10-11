package org.maproulette.permissions

import org.maproulette.exception.InvalidException
import org.maproulette.framework.model.{
  Challenge,
  Grant,
  Group,
  MemberObject,
  Project,
  TeamMember,
  TeamRole,
  User
}
import org.maproulette.framework.util.{FrameworkHelper, TeamTag}
import org.maproulette.session.{
  SearchChallengeParameters,
  SearchParameters,
  SearchTaskParameters,
  TaskPropertySearch
}
import play.api.Application
import play.api.libs.json.{JsObject, Json}

class ExportAccessSpec(implicit val application: Application) extends FrameworkHelper {
  private var reader: User              = null
  private var stranger: User            = null
  private var teamMember: User          = null
  private var otherProject: Project     = null
  private var otherChallenge: Challenge = null

  "canExport" should {
    "allow the project's admin" taggedAs TeamTag in {
      this.permission.canExport(this.defaultChallenge, this.defaultUser) mustEqual true
    }

    "allow a user with only read access to the project" taggedAs TeamTag in {
      this.permission.canExport(this.defaultChallenge, fresh(reader)) mustEqual true
    }

    "allow a superuser" taggedAs TeamTag in {
      this.permission.canExport(this.defaultChallenge, User.superUser) mustEqual true
    }

    "refuse a user with no role on the project" taggedAs TeamTag in {
      this.permission.canExport(this.defaultChallenge, fresh(stranger)) mustEqual false
    }

    "refuse a plain member of the team that owns the project" taggedAs TeamTag in {
      this.permission.canExport(otherChallenge, fresh(teamMember)) mustEqual false
    }
  }

  "hasExportScopeAccess" should {
    "allow a reader to export a project and its challenges" taggedAs TeamTag in {
      this.permission.hasExportScopeAccess(
        scope(
          projectIds = List(this.defaultProject.id),
          challengeIds = List(this.defaultChallenge.id)
        ),
        fresh(reader)
      )
    }

    "refuse a user with no role on the project" taggedAs TeamTag in {
      an[IllegalAccessException] should be thrownBy this.permission
        .hasExportScopeAccess(scope(projectIds = List(this.defaultProject.id)), fresh(stranger))
    }

    "refuse a challenge outside the projects the user may export" taggedAs TeamTag in {
      an[IllegalAccessException] should be thrownBy this.permission.hasExportScopeAccess(
        scope(projectIds = List(this.defaultProject.id), challengeIds = List(otherChallenge.id)),
        fresh(reader)
      )
    }

    "require a project or challenge" taggedAs TeamTag in {
      an[InvalidException] should be thrownBy this.permission
        .hasExportScopeAccess(SearchParameters(), fresh(reader))
    }

    "refuse an inverted project or challenge filter" taggedAs TeamTag in {
      List("pid", "cid").foreach { field =>
        an[InvalidException] should be thrownBy this.permission.hasExportScopeAccess(
          scope(
            projectIds = List(this.defaultProject.id),
            challengeIds = List(this.defaultChallenge.id)
          ).copy(invertFields = Some(List(field))),
          fresh(reader)
        )
      }
    }

    "refuse a virtual project, even to its owner" taggedAs TeamTag in {
      val virtual = this.serviceManager.project.create(
        Project(
          -1,
          this.defaultUser.osmProfile.id,
          "ExportAccessSpec_virtual",
          isVirtual = Some(true)
        ),
        this.defaultUser
      )
      an[IllegalAccessException] should be thrownBy this.permission
        .hasExportScopeAccess(scope(projectIds = List(virtual.id)), this.defaultUser)
    }

    "allow a superuser any scope, including none" taggedAs TeamTag in {
      this.permission.hasExportScopeAccess(SearchParameters(), User.superUser)
    }
  }

  "The challenge GeoJSON" should {
    val userFields =
      List("mr_mapper", "mr_mappedOn", "mr_reviewer", "mr_reviewedAt", "mr_reviewTimeSeconds")

    "omit who mapped and reviewed each task unless asked for them" taggedAs TeamTag in {
      properties(this.challengeDAL.getChallengeGeometry(this.defaultChallenge.id)).foreach {
        props =>
          userFields.foreach(field => props.keys must not contain field)
      }
      properties(
        this.challengeDAL.getChallengeGeometry(this.defaultChallenge.id, includeUserFields = true)
      ).foreach(props => userFields.foreach(field => props.keys must contain(field)))
    }

    "ignore reviewer and owner name filters unless user fields are included" taggedAs TeamTag in {
      val all = properties(this.challengeDAL.getChallengeGeometry(this.defaultChallenge.id))
      all must not be empty
      List(SearchParameters(reviewer = Some("nobody")), SearchParameters(owner = Some("nobody")))
        .foreach { params =>
          properties(
            this.challengeDAL.getChallengeGeometry(this.defaultChallenge.id, params = Some(params))
          ).length mustEqual all.length
          properties(
            this.challengeDAL.getChallengeGeometry(
              this.defaultChallenge.id,
              params = Some(params),
              includeUserFields = true
            )
          ) mustBe empty
        }
    }
  }

  "Filtering the challenge GeoJSON by task property" should {
    "bind the property search" taggedAs TeamTag in {
      def search(value: String) =
        SearchParameters(taskParams = SearchTaskParameters(taskPropertySearch = Some(
          TaskPropertySearch(
            Some("osm_id"),
            Some(value),
            Some(SearchParameters.TASK_PROP_VALUE_TYPE_STRING),
            Some(SearchParameters.TASK_PROP_SEARCH_TYPE_EQUALS)
          )
        )
        )
        )
      val all = properties(this.challengeDAL.getChallengeGeometry(this.defaultChallenge.id))
      properties(
        this.challengeDAL.getChallengeGeometry(
          this.defaultChallenge.id,
          params = Some(search("OSM_W_378169283_000000_000"))
        )
      ).length mustEqual all.length
      properties(
        this.challengeDAL.getChallengeGeometry(
          this.defaultChallenge.id,
          params = Some(search("x' OR '1'='1"))
        )
      ) mustBe empty
    }
  }

  "Filtering the task summary export" should {
    "bind the user and task property filters" taggedAs TeamTag in {
      val challengeIds = List(this.defaultChallenge.id)
      val scoped       = scope(challengeIds = challengeIds)
      val all          = this.taskDAL.retrieveTaskSummaries(challengeIds, -1, 0, scoped)._1
      all must not be empty
      List(
        scoped.copy(mapper = Some("nobody")),
        scoped.copy(reviewer = Some("nobody")),
        scoped.copy(owner = Some("nobody")),
        scoped.copy(taskParams = SearchTaskParameters(taskPropertySearch = Some(
          TaskPropertySearch(
            Some("osm_id"),
            Some("x' OR '1'='1"),
            Some(SearchParameters.TASK_PROP_VALUE_TYPE_STRING),
            Some(SearchParameters.TASK_PROP_SEARCH_TYPE_EQUALS)
          )
        )
        )
        )
      ).foreach { params =>
        this.taskDAL.retrieveTaskSummaries(challengeIds, -1, 0, params)._1 mustBe empty
      }
    }
  }

  private def properties(geojson: String): List[JsObject] =
    (Json.parse(geojson) \ "features").asOpt[List[JsObject]].getOrElse(List.empty).map { f =>
      (f \ "properties").as[JsObject]
    }

  private def scope(
      projectIds: List[Long] = List.empty,
      challengeIds: List[Long] = List.empty
  ): SearchParameters =
    SearchParameters(
      projectIds = Some(projectIds),
      challengeParams = SearchChallengeParameters(challengeIds = Some(challengeIds))
    )

  private def fresh(user: User): User = this.serviceManager.user.retrieve(user.id).get

  override implicit val projectTestName: String = "ExportAccessSpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    reader = this.serviceManager.user.create(
      this.getTestUser(51234580, "ExportAccessReader"),
      User.superUser
    )
    this.serviceManager.user.addUserToProject(
      reader.osmProfile.id,
      this.defaultProject.id,
      Grant.ROLE_READ_ONLY,
      User.superUser
    )
    stranger = this.serviceManager.user.create(
      this.getTestUser(51234581, "ExportAccessStranger"),
      User.superUser
    )

    // a second project owned by a team the reader is not on, with a
    // member who should not get access
    val team: Group = this.serviceManager.team
      .create(
        this.getTestTeam("ExportAccessSpec_team"),
        MemberObject.user(this.defaultUser.id),
        this.defaultUser
      )
      .get
    teamMember = this.serviceManager.user.create(
      this.getTestUser(51234582, "ExportAccessTeamMember"),
      User.superUser
    )
    this.serviceManager.team.addTeamMember(
      team,
      MemberObject.user(teamMember.id),
      TeamRole.MEMBER,
      TeamMember.STATUS_MEMBER,
      this.defaultUser
    )
    otherProject = this.serviceManager.project.create(
      Project(
        -1,
        this.defaultUser.osmProfile.id,
        "ExportAccessSpec_other",
        ownerTeamId = Some(team.id)
      ),
      User.superUser
    )
    otherChallenge = this.challengeDAL.insert(
      this.getTestChallenge("ExportAccessSpec_other_challenge", otherProject.id),
      User.superUser
    )
  }
}

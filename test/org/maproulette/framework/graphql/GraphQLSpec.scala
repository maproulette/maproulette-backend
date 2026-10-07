package org.maproulette.framework.graphql

import org.maproulette.data.ActionManager
import org.maproulette.framework.graphql.schemas._
import org.maproulette.framework.service._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec

class GraphQLSpec extends PlaySpec with MockitoSugar {
  private val schema = new GraphQL(
    new ProjectSchema(mock[ProjectService]),
    new ChallengeSchema(mock[ChallengeService]),
    new CommentSchema(mock[CommentService]),
    new GrantSchema(mock[GrantService]),
    new UserSchema(mock[UserService]),
    new TagSchema(mock[TagService]),
    new TeamSchema(mock[TeamService]),
    new ActionItemSchema(mock[ActionManager])
  ).schema

  "the GraphQL schema" should {
    "offer no way to authenticate with, look up by, or generate an API key" in {
      val roots = schema.query.fieldsByName.keySet ++ schema.mutation.get.fieldsByName.keySet

      roots must contain noneOf ("auth", "retrieveByApiKey", "updateAPIKey")
    }
  }
}

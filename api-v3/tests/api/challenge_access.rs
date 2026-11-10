//! These tests cover how a user's role on a challenge comes from v2's grants and teams.
use maproulette::db::authz::challenge_access;
use maproulette::model::{ChallengeAccess, ChallengeId, ProjectId, Role, UserId};

use crate::utils::{Grantee, Object, TeamId, TestApp};

// This is what the UI calls the team roles that v2 stores as 2 and 3 respectively
const TEAM_MANAGER: Role = Role::Write;
const TEAM_MEMBER: Role = Role::Read;

struct Fixture {
    app: TestApp,
    user: UserId,
    project: ProjectId,
    challenge: ChallengeId,
}

impl Fixture {
    /// A user with no roles, and a challenge in a project, both owned by someone else.
    async fn new() -> Fixture {
        let app = TestApp::spawn().await;
        let user = app.user().await.id;
        let project = app.project().await;
        let challenge = app.challenge(project).await;
        Fixture {
            app,
            user,
            project,
            challenge,
        }
    }

    async fn access(&self, challenge: ChallengeId) -> ChallengeAccess {
        challenge_access(&self.app.db, self.user, challenge)
            .await
            .unwrap()
    }

    async fn role(&self) -> Option<Role> {
        match self.access(self.challenge).await {
            ChallengeAccess::Role(role) => role,
            ChallengeAccess::NotFound => panic!("challenge not found"),
        }
    }

    async fn grant(&self, grantee: Grantee, role: Role, object: Object) {
        self.app.grant(grantee, role, object).await;
    }

    /// A new team with the user in it at `role`.
    async fn join_team(&self, role: Role) -> TeamId {
        let team = self.app.team().await;
        self.app.add_member(team, self.user).await;
        self.grant(Grantee::User(self.user), role, Object::Team(team))
            .await;
        team
    }

    async fn set_challenge_owner_team(&self, team: TeamId) {
        sqlx::query!(
            "UPDATE challenges SET owner_team_id = $1 WHERE id = $2",
            team.0,
            self.challenge.0
        )
        .execute(&self.app.db)
        .await
        .unwrap();
    }

    async fn set_project_owner_team(&self, team: TeamId) {
        sqlx::query!(
            "UPDATE projects SET owner_team_id = $1 WHERE id = $2",
            team.0,
            self.project.0
        )
        .execute(&self.app.db)
        .await
        .unwrap();
    }
}

#[tokio::test]
async fn no_grants_is_no_role() {
    let f = Fixture::new().await;
    assert_eq!(f.role().await, None);
}

#[tokio::test]
async fn superuser_has_every_challenge() {
    let f = Fixture::new().await;
    f.app.make_superuser(f.user).await;
    assert_eq!(f.role().await, Some(Role::SuperUser));
}

#[tokio::test]
async fn own_challenge_grant() {
    let f = Fixture::new().await;
    f.grant(
        Grantee::User(f.user),
        Role::Write,
        Object::Challenge(f.challenge),
    )
    .await;
    assert_eq!(f.role().await, Some(Role::Write));
}

#[tokio::test]
async fn project_grant_is_inherited() {
    let f = Fixture::new().await;
    f.grant(
        Grantee::User(f.user),
        Role::Admin,
        Object::Project(f.project),
    )
    .await;
    assert_eq!(f.role().await, Some(Role::Admin));
}

#[tokio::test]
async fn challenge_role_wins_over_stronger_project_role() {
    let f = Fixture::new().await;
    f.grant(
        Grantee::User(f.user),
        Role::Read,
        Object::Challenge(f.challenge),
    )
    .await;
    f.grant(
        Grantee::User(f.user),
        Role::Admin,
        Object::Project(f.project),
    )
    .await;
    assert_eq!(f.role().await, Some(Role::Read));
}

#[tokio::test]
async fn challenge_team_role_wins_over_stronger_project_role() {
    let f = Fixture::new().await;
    let team = f.join_team(TEAM_MANAGER).await;
    f.set_challenge_owner_team(team).await;
    f.grant(
        Grantee::User(f.user),
        Role::Admin,
        Object::Project(f.project),
    )
    .await;
    assert_eq!(f.role().await, Some(Role::Write));
}

#[tokio::test]
async fn strongest_challenge_level_role_wins() {
    let f = Fixture::new().await;
    f.grant(
        Grantee::User(f.user),
        Role::Read,
        Object::Challenge(f.challenge),
    )
    .await;
    let team = f.join_team(TEAM_MANAGER).await;
    f.set_challenge_owner_team(team).await;
    assert_eq!(f.role().await, Some(Role::Write));
}

#[tokio::test]
async fn challenge_owner_team_gives_the_users_team_role() {
    let f = Fixture::new().await;
    let team = f.join_team(Role::Owner).await;
    f.set_challenge_owner_team(team).await;
    assert_eq!(f.role().await, Some(Role::Owner));
}

#[tokio::test]
async fn role_in_team_grant_is_ignored() {
    // TODO: this matches v2, but is it correct? seems like maybe a bug
    let f = Fixture::new().await;
    let team = f.join_team(TEAM_MANAGER).await;
    f.grant(
        Grantee::Team(team),
        Role::Read,
        Object::Challenge(f.challenge),
    )
    .await;
    assert_eq!(f.role().await, Some(Role::Write));
}

#[tokio::test]
async fn plain_team_member_gets_nothing() {
    let f = Fixture::new().await;
    let team = f.join_team(TEAM_MEMBER).await;
    f.set_challenge_owner_team(team).await;
    assert_eq!(f.role().await, None);
}

#[tokio::test]
async fn plain_team_membership_does_not_block_project_inheritance() {
    let f = Fixture::new().await;
    let team = f.join_team(TEAM_MEMBER).await;
    f.set_challenge_owner_team(team).await;
    f.grant(
        Grantee::User(f.user),
        Role::Admin,
        Object::Project(f.project),
    )
    .await;
    assert_eq!(f.role().await, Some(Role::Admin));
}

#[tokio::test]
async fn invited_team_member_gets_nothing() {
    let f = Fixture::new().await;
    let team = f.app.team().await;
    f.app.invite(team, f.user).await;
    f.grant(Grantee::User(f.user), Role::Owner, Object::Team(team))
        .await;
    f.set_challenge_owner_team(team).await;
    assert_eq!(f.role().await, None);
}

#[tokio::test]
async fn team_member_with_null_status_gets_nothing() {
    let f = Fixture::new().await;
    let team = f.join_team(Role::Owner).await;
    let clear = "UPDATE group_members SET status = NULL WHERE group_id = $1";
    f.app.exec(clear, team.0.into()).await;
    f.set_challenge_owner_team(team).await;
    assert_eq!(f.role().await, None);
}

#[tokio::test]
async fn project_team_grant_is_inherited() {
    let f = Fixture::new().await;
    let team = f.join_team(TEAM_MANAGER).await;
    f.grant(Grantee::Team(team), Role::Admin, Object::Project(f.project))
        .await;
    assert_eq!(f.role().await, Some(Role::Write));
}

#[tokio::test]
async fn project_owner_team_is_inherited() {
    let f = Fixture::new().await;
    let team = f.join_team(Role::Owner).await;
    f.set_project_owner_team(team).await;
    assert_eq!(f.role().await, Some(Role::Owner));
}

#[tokio::test]
async fn strongest_of_several_teams_wins() {
    let f = Fixture::new().await;
    let managed = f.join_team(TEAM_MANAGER).await;
    let owned = f.join_team(Role::Owner).await;
    f.set_challenge_owner_team(managed).await;
    f.grant(
        Grantee::Team(owned),
        Role::Read,
        Object::Challenge(f.challenge),
    )
    .await;
    assert_eq!(f.role().await, Some(Role::Owner));
}

#[tokio::test]
async fn grants_on_other_objects_do_not_count() {
    let f = Fixture::new().await;
    let other_challenge = Object::Challenge(ChallengeId(f.challenge.0 + 1));
    f.grant(Grantee::User(f.user), Role::Admin, other_challenge)
        .await;
    let other_project = Object::Project(ProjectId(f.project.0 + 1));
    f.grant(Grantee::User(f.user), Role::Admin, other_project)
        .await;
    let someone_else = Grantee::User(UserId(f.user.0 + 1));
    f.grant(someone_else, Role::Admin, Object::Challenge(f.challenge))
        .await;
    // Superusers have a grant on project 0, but an admin grant there means nothing.
    f.grant(
        Grantee::User(f.user),
        Role::Admin,
        Object::Project(ProjectId(0)),
    )
    .await;
    assert_eq!(f.role().await, None);
}

#[tokio::test]
async fn team_role_needs_membership_of_that_team() {
    let f = Fixture::new().await;
    let (a, b) = (f.app.team().await, f.app.team().await);
    f.app.add_member(b, f.user).await;
    f.grant(Grantee::User(f.user), Role::Owner, Object::Team(a))
        .await;
    f.set_challenge_owner_team(a).await;
    assert_eq!(f.role().await, None);
}

#[tokio::test]
async fn another_users_membership_does_not_count() {
    let f = Fixture::new().await;
    let other = f.app.user().await.id;
    let team = f.app.team().await;
    f.app.add_member(team, other).await;
    f.grant(Grantee::User(f.user), Role::Owner, Object::Team(team))
        .await;
    f.set_challenge_owner_team(team).await;
    assert_eq!(f.role().await, None);
}

#[tokio::test]
async fn grant_to_team_with_users_id_does_not_count() {
    // The user isn't in the team that happens to have the same id.
    let f = Fixture::new().await;
    let team = f.app.team_with_id(f.user.0).await;
    f.grant(
        Grantee::Team(team),
        Role::Admin,
        Object::Challenge(f.challenge),
    )
    .await;
    assert_eq!(f.role().await, None);
}

#[tokio::test]
async fn grant_to_user_with_teams_id_does_not_count() {
    // The user manages a team whose id is also some other user's id.
    let f = Fixture::new().await;
    let team = f.app.team_with_id(f.user.0 + 100).await;
    f.app.add_member(team, f.user).await;
    f.grant(Grantee::User(f.user), TEAM_MANAGER, Object::Team(team))
        .await;
    let other_user = Grantee::User(UserId(team.0));
    f.grant(other_user, Role::Admin, Object::Challenge(f.challenge))
        .await;
    assert_eq!(f.role().await, None);
}

#[tokio::test]
async fn grant_on_a_project_with_a_teams_id_is_not_a_team_role() {
    // Only a grant on the team itself gives the user a role in it.
    let f = Fixture::new().await;
    let team = f.app.team_with_id(f.project.0 + 100).await;
    f.app.add_member(team, f.user).await;
    let project = Object::Project(ProjectId(team.0));
    f.grant(Grantee::User(f.user), Role::Admin, project).await;
    f.set_challenge_owner_team(team).await;
    assert_eq!(f.role().await, None);
}

#[tokio::test]
async fn team_membership_of_a_team_with_the_users_id_does_not_count() {
    // group_members rows can name a team as the member, and that team's id can be the user's id.
    let f = Fixture::new().await;
    let team = f.app.team().await;
    sqlx::query!(
        "INSERT INTO group_members (group_id, member_type, member_id, status) \
         VALUES ($1, 6, $2, 0)",
        team.0,
        f.user.0,
    )
    .execute(&f.app.db)
    .await
    .unwrap();
    f.grant(Grantee::User(f.user), Role::Owner, Object::Team(team))
        .await;
    f.set_challenge_owner_team(team).await;
    assert_eq!(f.role().await, None);
}

#[tokio::test]
async fn missing_challenge_is_not_found_even_for_superusers() {
    let f = Fixture::new().await;
    f.app.make_superuser(f.user).await;
    let missing = ChallengeId(f.challenge.0 + 1);
    assert_eq!(f.access(missing).await, ChallengeAccess::NotFound);
}

#[tokio::test]
async fn deleted_challenge_is_not_found() {
    let f = Fixture::new().await;
    f.grant(
        Grantee::User(f.user),
        Role::Admin,
        Object::Challenge(f.challenge),
    )
    .await;
    let delete = "UPDATE challenges SET deleted = true WHERE id = $1";
    f.app.exec(delete, f.challenge.0.into()).await;
    assert_eq!(f.access(f.challenge).await, ChallengeAccess::NotFound);
}

#[tokio::test]
async fn challenge_in_deleted_project_is_not_found() {
    let f = Fixture::new().await;
    f.grant(
        Grantee::User(f.user),
        Role::Admin,
        Object::Challenge(f.challenge),
    )
    .await;
    let delete = "UPDATE projects SET deleted = true WHERE id = $1";
    f.app.exec(delete, f.project.0.into()).await;
    assert_eq!(f.access(f.challenge).await, ChallengeAccess::NotFound);
}

#[tokio::test]
async fn deleted_challenge_is_not_found_even_for_superusers() {
    let f = Fixture::new().await;
    f.app.make_superuser(f.user).await;
    let delete = "UPDATE challenges SET deleted = true WHERE id = $1";
    f.app.exec(delete, f.challenge.0.into()).await;
    assert_eq!(f.access(f.challenge).await, ChallengeAccess::NotFound);
}

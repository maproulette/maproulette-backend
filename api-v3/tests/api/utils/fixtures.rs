use std::sync::atomic::{AtomicI32, Ordering};

use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use maproulette::auth::token::Token;
use maproulette::model::{ChallengeId, CredentialId, OsmUserId, ProjectId, Role, UserId};

use super::{SECRET, TestApp, play};

#[derive(Clone, Copy, Debug)]
pub struct TeamId(pub i32);

/// Who a grant is for (v2's `grantee_type` and `grantee_id`).
pub enum Grantee {
    User(UserId),
    Team(TeamId),
}

/// What a grant is on (v2's `object_type` and `object_id`).
pub enum Object {
    Project(ProjectId),
    Challenge(ChallengeId),
    Team(TeamId),
}

pub struct User {
    pub id: UserId,
    pub osm_id: OsmUserId,
    pub name: String,
    oauth_token: String,
}

impl User {
    /// A v2 session cookie for this user, as if they had logged in at `login_ms`.
    pub fn session_cookie(&self, login_ms: i64) -> String {
        play::cookie(self.session_data(login_ms), SECRET)
    }

    /// The session data that v2 stores in the cookie when this user logs in at `login_ms`.
    pub fn session_data(&self, login_ms: i64) -> Value {
        let hash: String = Sha256::digest(self.oauth_token.as_bytes())
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        json!({
            "tokenHash": hash,
            "userId": self.id.to_string(),
            "osmId": self.osm_id.to_string(),
            "userTick": login_ms.to_string(),
        })
    }
}

impl TestApp {
    pub async fn user(&self) -> User {
        static NEXT_OSM_ID: AtomicI32 = AtomicI32::new(1_000_000);
        let osm_id = NEXT_OSM_ID.fetch_add(1, Ordering::Relaxed);
        let name = format!("mapper{osm_id}");
        let oauth_token = Token::generate().as_str().to_owned();
        let id = sqlx::query_scalar!(
            "INSERT INTO users (osm_id, osm_created, name, oauth_token, oauth_secret) \
             VALUES ($1, now(), $2, $3, '') RETURNING id",
            osm_id,
            name,
            oauth_token,
        )
        .fetch_one(&self.db)
        .await
        .unwrap();
        User {
            id: UserId(id),
            osm_id: OsmUserId(osm_id),
            name,
            oauth_token,
        }
    }

    /// Inserts a credential that expires in a day.
    pub async fn credential(
        &self,
        user: &User,
        kind: &str,
        scopes: &[&str],
    ) -> (Token, CredentialId) {
        let token = Token::generate();
        let scopes: Vec<String> = scopes.iter().map(|s| s.to_string()).collect();
        let id = sqlx::query_scalar!(
            "INSERT INTO credentials (user_id, kind, token_hash, hint, name, scopes, expires_at) \
             VALUES ($1, $2, $3, $4, 'test', $5, now() + interval '1 day') RETURNING id",
            user.id.0,
            kind,
            &token.hash()[..],
            token.hint(),
            &scopes,
        )
        .fetch_one(&self.db)
        .await
        .unwrap();
        (token, CredentialId(id))
    }

    /// Inserts a project, owned by a new user. (`owner_id` doesn't give anyone a role.)
    pub async fn project(&self) -> ProjectId {
        let owner = self.user().await;
        let id = sqlx::query_scalar!(
            "INSERT INTO projects (name, owner_id) VALUES (gen_random_uuid()::text, $1) \
             RETURNING id",
            owner.osm_id.0,
        )
        .fetch_one(&self.db)
        .await
        .unwrap();
        ProjectId(id)
    }

    /// Inserts a challenge in the project, owned by a new user.
    pub async fn challenge(&self, project: ProjectId) -> ChallengeId {
        let owner = self.user().await;
        let id = sqlx::query_scalar!(
            "INSERT INTO challenges (name, parent_id, owner_id) VALUES ('c', $1, $2) RETURNING id",
            project.0,
            owner.osm_id.0,
        )
        .fetch_one(&self.db)
        .await
        .unwrap();
        ChallengeId(id)
    }

    /// Inserts a team (a `groups` row with v2's team `group_type`).
    pub async fn team(&self) -> TeamId {
        let id = sqlx::query_scalar!(
            "INSERT INTO groups (name, group_type) VALUES (gen_random_uuid()::text, 1) RETURNING id"
        )
        .fetch_one(&self.db)
        .await
        .unwrap();
        TeamId(id)
    }

    /// Inserts a team with the given id, for tests where ids of different kinds coincide.
    pub async fn team_with_id(&self, id: i32) -> TeamId {
        sqlx::query!(
            "INSERT INTO groups (id, name, group_type) VALUES ($1, gen_random_uuid()::text, 1)",
            id
        )
        .execute(&self.db)
        .await
        .unwrap();
        TeamId(id)
    }

    /// Adds the user to the team. In v2, a member's role in the team is a separate grant.
    pub async fn add_member(&self, team: TeamId, user: UserId) {
        self.insert_member(team, user, 0).await;
    }

    /// Invites the user to the team. They aren't a member until they accept.
    pub async fn invite(&self, team: TeamId, user: UserId) {
        self.insert_member(team, user, 1).await;
    }

    async fn insert_member(&self, team: TeamId, user: UserId, status: i32) {
        sqlx::query!(
            "INSERT INTO group_members (group_id, member_type, member_id, status) \
             VALUES ($1, 5, $2, $3)",
            team.0,
            user.0,
            status,
        )
        .execute(&self.db)
        .await
        .unwrap();
    }

    pub async fn grant(&self, grantee: Grantee, role: Role, object: Object) {
        let (grantee_type, grantee_id) = match grantee {
            Grantee::User(id) => (5, id.0),
            Grantee::Team(id) => (6, id.0),
        };
        let (object_type, object_id) = match object {
            Object::Project(id) => (0, id.0),
            Object::Challenge(id) => (1, id.0),
            Object::Team(id) => (6, id.0),
        };
        self.insert_grant(
            grantee_type,
            grantee_id,
            v2_role(role),
            object_type,
            object_id,
        )
        .await;
    }

    pub async fn make_superuser(&self, user: UserId) {
        self.insert_grant(5, user.0, v2_role(Role::SuperUser), 0, 0)
            .await;
    }

    async fn insert_grant(
        &self,
        grantee_type: i32,
        grantee_id: i32,
        role: i32,
        object_type: i32,
        object_id: i32,
    ) {
        sqlx::query!(
            "INSERT INTO grants (name, grantee_type, grantee_id, role, object_type, object_id) \
             VALUES ('test', $1, $2, $3, $4, $5)",
            grantee_type,
            grantee_id,
            role,
            object_type,
            object_id,
        )
        .execute(&self.db)
        .await
        .unwrap();
    }

    /// HACK: runs a statement that takes one id, for changing a fixture after creating it
    pub async fn exec(&self, sql: &'static str, id: i64) {
        sqlx::query(sql).bind(id).execute(&self.db).await.unwrap();
    }
}

fn v2_role(role: Role) -> i32 {
    match role {
        Role::SuperUser => -1,
        Role::Owner => 0,
        Role::Admin => 1,
        Role::Write => 2,
        Role::Read => 3,
    }
}

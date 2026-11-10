use std::sync::atomic::{AtomicI32, Ordering};

use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use maproulette::auth::token::Token;
use maproulette::model::{CredentialId, OsmUserId, UserId};

use super::{SECRET, TestApp, play};

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

    /// HACK: runs a statement that takes one id, for changing a fixture after creating it
    pub async fn exec(&self, sql: &'static str, id: i64) {
        sqlx::query(sql).bind(id).execute(&self.db).await.unwrap();
    }
}

use sqlx::PgExecutor;

use crate::model::{OsmUserId, UserId};

pub struct Profile {
    pub id: UserId,
    pub osm_id: OsmUserId,
    pub name: String,
}

pub async fn profile(db: impl PgExecutor<'_>, id: UserId) -> Result<Profile, sqlx::Error> {
    let row = sqlx::query!("SELECT id, osm_id, name FROM users WHERE id = $1", id.0)
        .fetch_one(db)
        .await?;
    Ok(Profile {
        id: UserId(row.id),
        osm_id: OsmUserId(row.osm_id),
        name: row.name,
    })
}

/// The user's current OSM access token. v2 sessions are bound to it (logging in
/// again replaces it, which ends the user's other sessions)
pub async fn oauth_token(
    db: impl PgExecutor<'_>,
    id: UserId,
) -> Result<Option<String>, sqlx::Error> {
    sqlx::query_scalar!("SELECT oauth_token FROM users WHERE id = $1", id.0)
        .fetch_optional(db)
        .await
}

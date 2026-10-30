use sqlx::PgExecutor;

use crate::model::{CredentialId, UserId};

/// A credential from the database, which we look up by its token hash. The
/// fields returned here are what we need to know to determine whether the
/// credential is valid, and if not, why not.
pub struct Credential {
    pub id: CredentialId,
    pub user_id: UserId,
    pub kind: String,
    pub scopes: Vec<String>,
    pub revoked: bool,
    pub expired: bool,
    pub idle: bool,
    pub stale: bool,
}

pub async fn find(
    db: impl PgExecutor<'_>,
    token_hash: &[u8],
) -> Result<Option<Credential>, sqlx::Error> {
    sqlx::query!(
        r#"
        SELECT id, user_id, kind, scopes,
               revoked_at IS NOT NULL AS "revoked!",
               coalesce(expires_at <= now(), false) AS "expired!",
               kind = 'pat' AND last_used_at <= now() - interval '90 days' AS "idle!",
               last_used_at < now() - interval '5 minutes' AS "stale!"
        FROM credentials
        WHERE token_hash = $1
        "#,
        token_hash,
    )
    .map(|r| Credential {
        id: CredentialId(r.id),
        user_id: UserId(r.user_id),
        kind: r.kind,
        scopes: r.scopes,
        revoked: r.revoked,
        expired: r.expired,
        idle: r.idle,
        stale: r.stale,
    })
    .fetch_optional(db)
    .await
}

/// Records that a credential was just used.
pub async fn touch(db: impl PgExecutor<'_>, id: CredentialId) -> Result<(), sqlx::Error> {
    sqlx::query!(
        "UPDATE credentials SET last_used_at = now() \
         WHERE id = $1 AND last_used_at < now() - interval '5 minutes'",
        id.0
    )
    .execute(db)
    .await?;
    Ok(())
}

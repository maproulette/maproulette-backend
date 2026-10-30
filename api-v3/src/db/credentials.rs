use sqlx::PgExecutor;

use crate::model::{CredentialId, NewPat, Pat, Scope, UserId};

/// A credential from the database, which we look up by its token hash. The
/// fields returned here are what we need to know to determine whether the
/// credential is valid, and if not, why not.
pub struct Credential {
    pub id: CredentialId,
    pub user_id: UserId,
    pub kind: String,
    pub scopes: Vec<Scope>,
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
        scopes: scopes(&r.scopes),
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

/// The user's active personal access tokens, newest first.
pub async fn list_pats(db: impl PgExecutor<'_>, user_id: UserId) -> Result<Vec<Pat>, sqlx::Error> {
    sqlx::query!(
        r#"
        SELECT id, name AS "name!", scopes, created_at, expires_at, last_used_at
        FROM credentials
        WHERE user_id = $1 AND kind = 'pat' AND revoked_at IS NULL
          AND (expires_at IS NULL OR expires_at > now())
          AND last_used_at > now() - interval '90 days'
        ORDER BY created_at DESC, id DESC
        "#,
        user_id.0,
    )
    .map(|r| Pat {
        id: CredentialId(r.id),
        name: r.name,
        scopes: scopes(&r.scopes),
        created_at: r.created_at,
        expires_at: r.expires_at,
        last_used_at: r.last_used_at,
    })
    .fetch_all(db)
    .await
}

/// Stores and returns a new personal access token, unless the user already has
/// `limit` active ones, in which case it returns None.
pub async fn create_pat(
    db: impl PgExecutor<'_>,
    user_id: UserId,
    token_hash: &[u8],
    // For operators, to identify a token in logs and the database. Never shown to users.
    hint: &str,
    pat: &NewPat,
    limit: i64,
) -> Result<Option<Pat>, sqlx::Error> {
    let scopes: Vec<String> = pat.scopes.iter().map(|s| s.as_str().to_owned()).collect();
    sqlx::query!(
        r#"
        INSERT INTO credentials (user_id, kind, token_hash, hint, name, scopes, expires_at)
        SELECT $1, 'pat', $2, $3, $4, $5, $6
        WHERE (
            SELECT count(*) FROM credentials
            WHERE user_id = $1 AND kind = 'pat' AND revoked_at IS NULL
              AND (expires_at IS NULL OR expires_at > now())
              AND last_used_at > now() - interval '90 days'
        ) < $7
        RETURNING id, created_at, last_used_at
        "#,
        user_id.0,
        token_hash,
        hint,
        pat.name,
        &scopes,
        pat.expires_at,
        limit,
    )
    .map(|r| Pat {
        id: CredentialId(r.id),
        name: pat.name.clone(),
        scopes: pat.scopes.clone(),
        created_at: r.created_at,
        expires_at: pat.expires_at,
        last_used_at: r.last_used_at,
    })
    .fetch_optional(db)
    .await
}

/// Revokes one of the user's personal access tokens. Returns false if the user
/// has no such token, or it's already revoked.
pub async fn revoke_pat(
    db: impl PgExecutor<'_>,
    user_id: UserId,
    id: CredentialId,
) -> Result<bool, sqlx::Error> {
    let revoked = sqlx::query!(
        "UPDATE credentials SET revoked_at = now() \
         WHERE id = $1 AND user_id = $2 AND kind = 'pat' AND revoked_at IS NULL",
        id.0,
        user_id.0,
    )
    .execute(db)
    .await?
    .rows_affected();
    Ok(revoked == 1)
}

fn scopes(stored: &[String]) -> Vec<Scope> {
    stored.iter().filter_map(|s| Scope::parse(s)).collect()
}

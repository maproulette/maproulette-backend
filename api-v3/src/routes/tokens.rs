//! The user's personal access tokens: `/me/tokens`.

use axum::Json;
use axum::extract::State;
use axum::http::StatusCode;
use serde::{Deserialize, Serialize};
use time::OffsetDateTime;
use utoipa::ToSchema;

use super::extract::{JsonBody, Path};
use crate::AppState;
use crate::auth::{Principal, token};
use crate::authz;
use crate::db;
use crate::error::{Error, FieldError, Problem};
use crate::model::{CredentialId, NewPat, Pat, Scope};

/// How many active tokens a user may have.
const MAX_TOKENS: i64 = 20;

const MAX_NAME_CHARS: usize = 100;

/// A personal access token. The token itself is shown only when it's created.
#[derive(Serialize, ToSchema)]
pub struct Token {
    id: CredentialId,
    /// What the token is for, as the user described it when creating it.
    name: String,
    /// Scopes the token has access to.
    scopes: Vec<Scope>,
    #[serde(with = "time::serde::rfc3339")]
    created_at: OffsetDateTime,
    /// When the token stops working, or null if it doesn't expire.
    #[serde(with = "time::serde::rfc3339::option")]
    #[schema(required = true)]
    expires_at: Option<OffsetDateTime>,
    /// When the token was last used, to within a few minutes. Starts at `created_at`. A token
    /// that goes unused for 90 days stops working, even if it doesn't expire.
    #[serde(with = "time::serde::rfc3339")]
    last_used_at: OffsetDateTime,
}

impl From<Pat> for Token {
    fn from(pat: Pat) -> Token {
        Token {
            id: pat.id,
            name: pat.name,
            scopes: pat.scopes,
            created_at: pat.created_at,
            expires_at: pat.expires_at,
            last_used_at: pat.last_used_at,
        }
    }
}

/// A newly created token, including the token itself.
#[derive(Serialize, ToSchema)]
pub struct CreatedToken {
    /// The token itself. The only time it's ever shown is in this response;
    /// the database stores only the hash of the token.
    token: String,
    /// Other details about the token (name, scopes, expiration date, etc)
    #[serde(flatten)]
    details: Token,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub struct NewToken {
    name: String,
    scopes: Vec<Scope>,
    #[serde(with = "time::serde::rfc3339::option")]
    #[schema(required = true)]
    expires_at: Option<OffsetDateTime>,
}

impl NewToken {
    fn parse(self, now: OffsetDateTime) -> Result<NewPat, Error> {
        let mut errors = Vec::new();
        let name = self.name.trim();
        if name.is_empty() {
            errors.push(FieldError::pointer("/name", "must not be empty"));
        } else if name.chars().count() > MAX_NAME_CHARS {
            errors.push(FieldError::pointer(
                "/name",
                format!("must be at most {MAX_NAME_CHARS} characters"),
            ));
        }
        if self.scopes.is_empty() {
            errors.push(FieldError::pointer(
                "/scopes",
                "must list at least one scope",
            ));
        }
        for (i, scope) in self.scopes.iter().enumerate() {
            if self.scopes[..i].contains(scope) {
                errors.push(FieldError::pointer(
                    format!("/scopes/{i}"),
                    "is listed twice",
                ));
            }
        }
        if self.expires_at.is_some_and(|t| t <= now) {
            errors.push(FieldError::pointer("/expires_at", "must be in the future"));
        }
        if !errors.is_empty() {
            return Err(Error::Validation(errors));
        }
        Ok(NewPat {
            name: name.to_owned(),
            scopes: self.scopes,
            expires_at: self.expires_at,
        })
    }
}

/// Lists the user's active tokens
#[utoipa::path(
    get,
    path = "/me/tokens",
    tag = "tokens",
    security(("session" = [])),
    responses(
        (status = 200, description = "The user's active tokens", body = Vec<Token>),
        (status = 401, description = "Not logged in", body = Problem, content_type = "application/problem+json"),
        (status = 403, description = "Authenticated with a token rather than a session", body = Problem, content_type = "application/problem+json"),
    ),
)]
pub async fn list(
    State(state): State<AppState>,
    principal: Principal,
) -> Result<Json<Vec<Token>>, Error> {
    authz::manage_tokens(&principal)?;
    let pats = db::credentials::list_pats(&state.db, principal.user_id).await?;
    Ok(Json(pats.into_iter().map(Token::from).collect()))
}

/// Creates a token. The response is the only time the token itself is shown.
#[utoipa::path(
    post,
    path = "/me/tokens",
    tag = "tokens",
    security(("session" = [])),
    request_body = NewToken,
    responses(
        (status = 201, description = "The new token", body = CreatedToken),
        (status = 400, description = "The body isn't JSON", body = Problem, content_type = "application/problem+json"),
        (status = 401, description = "Not logged in", body = Problem, content_type = "application/problem+json"),
        (status = 403, description = "Authenticated with a token rather than a session, or from an untrusted origin", body = Problem, content_type = "application/problem+json"),
        (status = 409, description = "The user already has the maximum number of active tokens", body = Problem, content_type = "application/problem+json"),
        (status = 415, description = "The body isn't `application/json`", body = Problem, content_type = "application/problem+json"),
        (status = 422, description = "The body is invalid", body = Problem, content_type = "application/problem+json"),
    ),
)]
pub async fn create(
    State(state): State<AppState>,
    principal: Principal,
    JsonBody(body): JsonBody<NewToken>,
) -> Result<(StatusCode, Json<CreatedToken>), Error> {
    authz::manage_tokens(&principal)?;
    let pat = body.parse(OffsetDateTime::now_utc())?;
    let secret = token::Token::generate();
    let pat = db::credentials::create_pat(
        &state.db,
        principal.user_id,
        &secret.hash(),
        secret.hint(),
        &pat,
        MAX_TOKENS,
    )
    .await?
    .ok_or_else(|| {
        Error::Conflict(format!(
            "You already have {MAX_TOKENS} active tokens. Revoke one before creating another."
        ))
    })?;
    tracing::info!(credential_id = %pat.id, "created personal access token");
    Ok((
        StatusCode::CREATED,
        Json(CreatedToken {
            token: secret.as_str().to_owned(),
            details: pat.into(),
        }),
    ))
}

/// Revokes a token
#[utoipa::path(
    delete,
    path = "/me/tokens/{id}",
    tag = "tokens",
    security(("session" = [])),
    params(("id" = CredentialId, Path, description = "The token's id")),
    responses(
        (status = 204, description = "Revoked"),
        (status = 401, description = "Not logged in", body = Problem, content_type = "application/problem+json"),
        (status = 403, description = "Authenticated with a token rather than a session, or from an untrusted origin", body = Problem, content_type = "application/problem+json"),
        (status = 404, description = "The user has no such token, or it's already revoked", body = Problem, content_type = "application/problem+json"),
    ),
)]
pub async fn revoke(
    State(state): State<AppState>,
    principal: Principal,
    Path(id): Path<CredentialId>,
) -> Result<StatusCode, Error> {
    authz::manage_tokens(&principal)?;
    if !db::credentials::revoke_pat(&state.db, principal.user_id, id).await? {
        return Err(Error::NotFound);
    }
    tracing::info!(credential_id = %id, "revoked personal access token");
    Ok(StatusCode::NO_CONTENT)
}

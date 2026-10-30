pub mod play;
pub mod token;

use axum::extract::{FromRequestParts, OptionalFromRequestParts, Request, State};
use axum::http::request::Parts;
use axum::http::{HeaderMap, HeaderValue, Method, header};
use axum::middleware::Next;
use axum::response::Response;
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

use crate::AppState;
use crate::db;
use crate::error::Error;
use crate::model::{CredentialId, Scope, UserId};
use token::Token;

#[derive(Clone, Debug)]
pub struct Principal {
    pub user_id: UserId,
    pub via: Via,
}

#[derive(Clone, Debug)]
pub enum Via {
    Session,
    Token {
        credential_id: CredentialId,
        kind: TokenKind,
        scopes: Vec<Scope>,
    },
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum TokenKind {
    Pat,
    Editor,
}

impl Principal {
    /// Fails unless the principal may act with `scope`. A session acts with the user's full role,
    /// so it has every scope. A token has the scopes it was created with.
    pub fn require_scope(&self, scope: Scope) -> Result<(), Error> {
        match &self.via {
            Via::Session => Ok(()),
            Via::Token { scopes, .. } if scopes.contains(&scope) => Ok(()),
            Via::Token { .. } => Err(Error::InsufficientScope(scope.as_str())),
        }
    }

    /// Prepares a value for the `X-Auth` response header, which indicates how the
    /// request was authenticated so the reverse proxy can log it.
    fn x_auth(&self) -> String {
        match &self.via {
            Via::Session => format!("session:user/{}", self.user_id),
            Via::Token {
                credential_id,
                kind: TokenKind::Pat,
                ..
            } => format!("pat:{credential_id}"),
            Via::Token {
                credential_id,
                kind: TokenKind::Editor,
                ..
            } => format!("editor:{credential_id}"),
        }
    }
}

/// The resolved principal, which `resolve` attaches to every request.
#[derive(Clone)]
struct Auth(Option<Principal>);

/// Middleware that resolves the request's principal, records it in the trace
/// span, and sets `X-Auth` on the response.
pub async fn resolve(
    State(state): State<AppState>,
    mut req: Request,
    next: Next,
) -> Result<Response, Error> {
    let principal = principal(&state, req.headers()).await?;
    if let Some(Principal {
        via: Via::Session, ..
    }) = &principal
    {
        check_origin(&state.allowed_origins, req.method(), req.headers())?;
    }
    let x_auth = principal.as_ref().map(|p| {
        let x_auth = p.x_auth();
        tracing::Span::current().record("auth", &x_auth);
        HeaderValue::try_from(x_auth).expect("X-Auth is ASCII")
    });
    req.extensions_mut().insert(Auth(principal));
    let mut res = next.run(req).await;
    if let Some(x_auth) = x_auth {
        res.headers_mut().insert("x-auth", x_auth);
    }
    Ok(res)
}

/// Defends session-authenticated requests against cross-site request forgery (CSRF).
fn check_origin(
    allowed: &[HeaderValue],
    method: &Method,
    headers: &HeaderMap,
) -> Result<(), Error> {
    if method.is_safe() {
        return Ok(());
    }
    match headers.get(header::ORIGIN) {
        Some(origin) if allowed.contains(origin) => Ok(()),
        _ => Err(Error::Forbidden(
            "A request that uses the session cookie to change something must come from a \
             MapRoulette frontend. Scripts should use a personal access token.",
        )),
    }
}

async fn principal(state: &AppState, headers: &HeaderMap) -> Result<Option<Principal>, Error> {
    let mut authorization = headers.get_all(header::AUTHORIZATION).iter();
    match (authorization.next(), authorization.next()) {
        (None, _) => session(state, headers).await,
        (Some(value), None) => {
            let token = bearer(value).ok_or(Error::InvalidToken)?;
            token_principal(state, &token).await.map(Some)
        }
        (Some(_), Some(_)) => Err(Error::InvalidToken),
    }
}

/// The token in an `Authorization: Bearer <token>` header, if it is well formed.
fn bearer(value: &HeaderValue) -> Option<Token> {
    let (scheme, token) = value.to_str().ok()?.split_once(' ')?;
    if !scheme.eq_ignore_ascii_case("bearer") {
        return None;
    }
    Token::parse(token.trim())
}

async fn token_principal(state: &AppState, token: &Token) -> Result<Principal, Error> {
    let credential = db::credentials::find(&state.db, &token.hash())
        .await?
        .ok_or(Error::InvalidToken)?;
    let kind = match credential.kind.as_str() {
        "pat" => TokenKind::Pat,
        "editor" => TokenKind::Editor,
        _ => return reject(credential.id, "not a bearer credential"),
    };
    for (rejected, reason) in [
        (credential.revoked, "revoked"),
        (credential.expired, "expired"),
        (credential.idle, "idle"),
    ] {
        if rejected {
            return reject(credential.id, reason);
        }
    }
    if credential.stale {
        // Update the credential's last_used_at time in the background
        let db = state.db.clone();
        tokio::spawn(async move {
            if let Err(e) = db::credentials::touch(&db, credential.id).await {
                tracing::warn!(credential_id = %credential.id, error = %e, "updating last_used_at failed");
            }
        });
    }
    Ok(Principal {
        user_id: credential.user_id,
        via: Via::Token {
            credential_id: credential.id,
            kind,
            scopes: credential.scopes,
        },
    })
}

fn reject(credential_id: CredentialId, reason: &str) -> Result<Principal, Error> {
    tracing::info!(%credential_id, reason, "rejected token");
    Err(Error::InvalidToken)
}

/// The principal for v2's Play session cookie, if the request has a valid one.
/// The session is valid only while its token hash matches the user's current
/// OSM token.
async fn session(state: &AppState, headers: &HeaderMap) -> Result<Option<Principal>, Error> {
    let Some(session) =
        cookie(headers, play::COOKIE).and_then(|c| play::decode(c, &state.session_secret))
    else {
        return Ok(None);
    };
    let Some(oauth_token) = db::users::oauth_token(&state.db, session.user_id).await? else {
        return Ok(None);
    };
    let current = hex(&Sha256::digest(oauth_token.as_bytes()));
    if !bool::from(current.as_bytes().ct_eq(session.token_hash.as_bytes())) {
        return Ok(None);
    }
    Ok(Some(Principal {
        user_id: session.user_id,
        via: Via::Session,
    }))
}

fn cookie<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(';'))
        .find_map(|pair| {
            let (k, v) = pair.trim().split_once('=')?;
            (k == name).then_some(v)
        })
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

impl<S: Send + Sync> FromRequestParts<S> for Principal {
    type Rejection = Error;

    async fn from_request_parts(parts: &mut Parts, state: &S) -> Result<Self, Error> {
        <Principal as OptionalFromRequestParts<S>>::from_request_parts(parts, state)
            .await?
            .ok_or(Error::Unauthenticated)
    }
}

impl<S: Send + Sync> OptionalFromRequestParts<S> for Principal {
    type Rejection = Error;

    async fn from_request_parts(parts: &mut Parts, _: &S) -> Result<Option<Self>, Error> {
        let Auth(principal) = parts
            .extensions
            .get::<Auth>()
            .expect("the resolve middleware runs on every route");
        Ok(principal.clone())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_writes_need_a_trusted_origin() {
        let allowed = [HeaderValue::from_static("https://maproulette.org")];
        let check = |method: Method, origin: Option<&'static str>| {
            let mut headers = HeaderMap::new();
            if let Some(origin) = origin {
                headers.insert(header::ORIGIN, HeaderValue::from_static(origin));
            }
            check_origin(&allowed, &method, &headers).is_ok()
        };
        for method in [Method::POST, Method::PUT, Method::PATCH, Method::DELETE] {
            assert!(
                check(method.clone(), Some("https://maproulette.org")),
                "{method}"
            );
            for origin in [
                None,
                Some("null"),
                Some("https://evil.example"),
                Some("http://maproulette.org"),
                Some("https://maproulette.org.evil.example"),
                Some("https://beta.maproulette.org"),
            ] {
                assert!(!check(method.clone(), origin), "{method} from {origin:?}");
            }
        }
        for method in [Method::GET, Method::HEAD, Method::OPTIONS] {
            assert!(check(method.clone(), None), "{method}");
            assert!(
                check(method.clone(), Some("https://evil.example")),
                "{method}"
            );
        }
    }
}

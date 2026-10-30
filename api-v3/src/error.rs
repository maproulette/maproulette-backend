use std::sync::Arc;

use axum::body::Body;
use axum::extract::Request;
use axum::http::{HeaderValue, StatusCode, header};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use serde::Serialize;
use utoipa::ToSchema;

/// The error type returned by route handler functions.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    /// The route requires a logged-in user and the request has no credential.
    #[error("authentication required")]
    Unauthenticated,
    /// The request's bearer token is malformed, unknown, expired or revoked.
    #[error("invalid token")]
    InvalidToken,
    /// The request's token lacks the scope that the action needs.
    #[error("token lacks the {0} scope")]
    InsufficientScope(&'static str),
    /// The caller can see the resource but may not do this to it.
    #[error("forbidden")]
    Forbidden,
    /// No such resource, or the caller may not know that it exists.
    #[error("not found")]
    NotFound,
    #[error("method not allowed")]
    MethodNotAllowed,
    /// The request can't be parsed at all, for the reason given.
    #[error("bad request: {0}")]
    BadRequest(String),
    /// The request conflicts with the resource's current state, for the reason given.
    #[error("conflict: {0}")]
    Conflict(String),
    #[error("request body too large")]
    PayloadTooLarge,
    /// The body's media type or encoding isn't one the route accepts, for the reason given.
    #[error("unsupported media type: {0}")]
    UnsupportedMediaType(String),
    /// The request parsed, but these parts of it are invalid.
    #[error("invalid request")]
    Validation(Vec<FieldError>),
    /// A condition that a retry can be expected to resolve: a lock timeout, a deadlock, the
    /// database being unreachable, the request timing out.
    #[error("service unavailable")]
    Unavailable(#[source] anyhow::Error),
    /// Anything else (clients get only a generic response).
    #[error("unexpected error")]
    Unexpected(#[source] anyhow::Error),
}

#[derive(Clone, Debug, Serialize, ToSchema)]
pub struct FieldError {
    /// An RFC 6901 JSON Pointer into the request body.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schema(nullable = false)]
    pub pointer: Option<String>,
    /// A query parameter.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schema(nullable = false)]
    pub parameter: Option<String>,
    pub message: String,
}

impl FieldError {
    /// A problem with the request body whose location is described only in the message.
    pub fn body(message: impl Into<String>) -> FieldError {
        FieldError {
            pointer: None,
            parameter: None,
            message: message.into(),
        }
    }

    pub fn pointer(pointer: impl Into<String>, message: impl Into<String>) -> FieldError {
        FieldError {
            pointer: Some(pointer.into()),
            parameter: None,
            message: message.into(),
        }
    }

    pub fn parameter(parameter: impl Into<String>, message: impl Into<String>) -> FieldError {
        FieldError {
            pointer: None,
            parameter: Some(parameter.into()),
            message: message.into(),
        }
    }
}

/// An RFC 9457 problem document (`application/problem+json`).
#[derive(Serialize, ToSchema)]
pub struct Problem {
    /// Always `about:blank`: `title` is the standard phrase for `status`.
    #[serde(rename = "type")]
    pub kind: &'static str,
    pub title: &'static str,
    pub status: u16,
    /// A human-readable explanation of this error.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schema(nullable = false)]
    pub detail: Option<String>,
    /// For 422 responses: every invalid part of the request.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub errors: Vec<FieldError>,
    /// The request's `x-request-id`, which uniquely identifies it.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schema(nullable = false)]
    pub request_id: Option<String>,
}

/// Seconds a client should wait before retrying after a 503.
const RETRY_AFTER: &str = "5";

impl Error {
    fn status(&self) -> StatusCode {
        match self {
            Error::Unauthenticated | Error::InvalidToken => StatusCode::UNAUTHORIZED,
            Error::InsufficientScope(_) | Error::Forbidden => StatusCode::FORBIDDEN,
            Error::NotFound => StatusCode::NOT_FOUND,
            Error::MethodNotAllowed => StatusCode::METHOD_NOT_ALLOWED,
            Error::BadRequest(_) => StatusCode::BAD_REQUEST,
            Error::Conflict(_) => StatusCode::CONFLICT,
            Error::PayloadTooLarge => StatusCode::PAYLOAD_TOO_LARGE,
            Error::UnsupportedMediaType(_) => StatusCode::UNSUPPORTED_MEDIA_TYPE,
            Error::Validation(_) => StatusCode::UNPROCESSABLE_ENTITY,
            Error::Unavailable(_) => StatusCode::SERVICE_UNAVAILABLE,
            Error::Unexpected(_) => StatusCode::INTERNAL_SERVER_ERROR,
        }
    }

    /// The `WWW-Authenticate` challenge, per RFC 6750
    fn challenge(&self) -> Option<String> {
        match self {
            Error::Unauthenticated => Some("Bearer".into()),
            Error::InvalidToken => Some(r#"Bearer error="invalid_token""#.into()),
            Error::InsufficientScope(scope) => Some(format!(
                r#"Bearer error="insufficient_scope", scope="{scope}""#
            )),
            _ => None,
        }
    }

    fn detail(&self) -> Option<String> {
        match self {
            Error::InsufficientScope(scope) => Some(format!("The token lacks the {scope} scope")),
            Error::BadRequest(detail)
            | Error::Conflict(detail)
            | Error::UnsupportedMediaType(detail) => Some(detail.clone()),
            _ => None,
        }
    }

    fn problem(&self, request_id: Option<String>) -> Problem {
        let status = self.status();
        Problem {
            kind: "about:blank",
            title: status.canonical_reason().unwrap_or("Error"),
            status: status.as_u16(),
            detail: self.detail(),
            errors: match self {
                Error::Validation(errors) => errors.clone(),
                _ => Vec::new(),
            },
            request_id,
        }
    }
}

impl From<sqlx::Error> for Error {
    fn from(e: sqlx::Error) -> Error {
        if is_transient(&e) {
            Error::Unavailable(e.into())
        } else {
            Error::Unexpected(e.into())
        }
    }
}

/// Database errors that a retry can be expected to resolve.
fn is_transient(e: &sqlx::Error) -> bool {
    match e {
        sqlx::Error::Io(_) | sqlx::Error::PoolTimedOut | sqlx::Error::PoolClosed => true,
        sqlx::Error::Database(db) => matches!(
            db.code().as_deref(),
            // lock_not_available, deadlock_detected, serialization_failure
            Some("55P03" | "40P01" | "40001")
        ),
        _ => false,
    }
}

/// Carries an error from `into_response` to the `report` middleware.
#[derive(Clone)]
struct Report(Arc<Error>);

impl IntoResponse for Error {
    fn into_response(self) -> Response {
        let mut res = self.status().into_response();
        let headers = res.headers_mut();
        if let Some(challenge) = self.challenge() {
            headers.insert(
                header::WWW_AUTHENTICATE,
                HeaderValue::try_from(challenge).expect("challenges are ASCII"),
            );
        }
        if matches!(self, Error::Unavailable(_)) {
            headers.insert(header::RETRY_AFTER, HeaderValue::from_static(RETRY_AFTER));
        }
        res.extensions_mut().insert(Report(Arc::new(self)));
        res
    }
}

/// Middleware that turns an `Error` response into a problem document and logs
/// server errors with their causes.
pub async fn report(req: Request, next: Next) -> Response {
    let request_id = req
        .headers()
        .get("x-request-id")
        .and_then(|v| v.to_str().ok())
        .map(String::from);
    let mut res = next.run(req).await;
    let Some(Report(error)) = res.extensions_mut().remove::<Report>() else {
        return res;
    };
    if res.status().is_server_error() {
        tracing::error!(error = %causes(&*error), "request failed");
    }
    let body = serde_json::to_vec(&error.problem(request_id)).expect("problems serialize");
    *res.body_mut() = Body::from(body);
    res.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/problem+json"),
    );
    res
}

/// An error and its chain of sources, as `outer: inner: innermost`.
fn causes(e: &dyn std::error::Error) -> String {
    let mut s = e.to_string();
    let mut source = e.source();
    while let Some(e) = source {
        s.push_str(": ");
        s.push_str(&e.to_string());
        source = e.source();
    }
    s
}

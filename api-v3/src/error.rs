use axum::http::{HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use serde::Serialize;
use utoipa::ToSchema;

/// The error type returned by route handler functions.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("not found")]
    NotFound,
    #[error("method not allowed")]
    MethodNotAllowed,
    #[error("request timed out")]
    Timeout,
    #[error(transparent)]
    Database(#[from] sqlx::Error),
}

/// An RFC 9457 problem document (`application/problem+json`).
#[derive(Serialize, ToSchema)]
pub struct Problem {
    /// Always `about:blank`: `title` is the standard phrase for `status`.
    #[serde(rename = "type")]
    pub kind: &'static str,
    pub title: &'static str,
    pub status: u16,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schema(nullable = false)]
    pub detail: Option<String>,
}

/// Seconds a client should wait before retrying after a 503.
const RETRY_AFTER: &str = "5";

impl Error {
    fn status(&self) -> StatusCode {
        match self {
            Error::NotFound => StatusCode::NOT_FOUND,
            Error::MethodNotAllowed => StatusCode::METHOD_NOT_ALLOWED,
            Error::Timeout => StatusCode::SERVICE_UNAVAILABLE,
            Error::Database(e) if is_transient(e) => StatusCode::SERVICE_UNAVAILABLE,
            Error::Database(_) => StatusCode::INTERNAL_SERVER_ERROR,
        }
    }
}

/// Database errors that a retry can be expected to resolve. The server returns
/// 503 Service Unavailable for these, with a Retry-After header, and 500
/// Internal Server Error for others.
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

impl IntoResponse for Error {
    fn into_response(self) -> Response {
        let status = self.status();
        if status.is_server_error() {
            tracing::error!(error = %self, "request failed");
        }
        let problem = Problem {
            kind: "about:blank",
            title: status.canonical_reason().unwrap_or("Error"),
            status: status.as_u16(),
            detail: None,
        };
        let mut res = (status, axum::Json(problem)).into_response();
        let headers = res.headers_mut();
        headers.insert(
            header::CONTENT_TYPE,
            HeaderValue::from_static("application/problem+json"),
        );
        if status == StatusCode::SERVICE_UNAVAILABLE {
            headers.insert(header::RETRY_AFTER, HeaderValue::from_static(RETRY_AFTER));
        }
        res
    }
}

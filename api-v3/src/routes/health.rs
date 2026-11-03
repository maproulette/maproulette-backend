//! Probes for orchestration and monitoring:
//!
//! - live: is the process alive, or is it stuck in a way that a restart might fix?
//! - ready: should this process receive traffic right now?
//! - health: is the process healthy, and if not, what is wrong with it?

use std::time::{Duration, Instant};

use axum::Json;
use axum::extract::State;
use axum::http::{StatusCode, header};
use axum::response::{IntoResponse, Response};
use serde::Serialize;
use sqlx::PgPool;
use utoipa::ToSchema;

use crate::AppState;

/// How long a probe waits for the database. Should be lower than the pool's
/// acquire timeout and lower than typical load balancer probe timeouts.
const PROBE_TIMEOUT: Duration = Duration::from_secs(2);

#[derive(Serialize, ToSchema, Clone, Copy)]
#[serde(rename_all = "snake_case")]
pub enum Status {
    Pass,
    Fail,
}

impl Status {
    fn of(ok: bool) -> Status {
        if ok { Status::Pass } else { Status::Fail }
    }

    fn code(self) -> StatusCode {
        match self {
            Status::Pass => StatusCode::OK,
            Status::Fail => StatusCode::SERVICE_UNAVAILABLE,
        }
    }
}

#[derive(Serialize, ToSchema)]
pub struct Probe {
    status: Status,
}

#[derive(Serialize, ToSchema)]
pub struct Health {
    status: Status,
    checks: Checks,
}

#[derive(Serialize, ToSchema)]
pub struct Checks {
    database: DatabaseCheck,
    pool: PoolCheck,
}

#[derive(Serialize, ToSchema)]
pub struct DatabaseCheck {
    status: Status,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schema(nullable = false)]
    error: Option<DatabaseError>,
    response_time_ms: u64,
}

#[derive(Serialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum DatabaseError {
    /// The probe query didn't finish within 2 seconds.
    Timeout,
    /// The probe query failed.
    Unreachable,
}

/// This process's connection pool. Available even when the database is down.
#[derive(Serialize, ToSchema)]
pub struct PoolCheck {
    /// Open connections.
    size: u32,
    /// Open connections that are not in use.
    idle: usize,
    /// Maximum connection count.
    max: u32,
}

/// Reports that the process is up. Does not require the database to be reachable.
#[utoipa::path(
    get,
    path = "/health/live",
    tag = "service",
    responses((status = 200, description = "The process is up", body = Probe)),
)]
pub async fn live() -> Response {
    let status = Status::Pass;
    respond(status, Probe { status })
}

/// Reports whether the process can serve requests, which needs the database.
#[utoipa::path(
    get,
    path = "/health/ready",
    tag = "service",
    responses(
        (status = 200, description = "Ready for traffic", body = Probe),
        (status = 503, description = "The database is unreachable", body = Probe),
    ),
)]
pub async fn ready(State(state): State<AppState>) -> Response {
    let status = Status::of(probe(&state.db).await.1.is_ok());
    respond(status, Probe { status })
}

/// Reports the state of the process and its database connection, for monitoring.
#[utoipa::path(
    get,
    path = "/health",
    tag = "service",
    responses(
        (status = 200, description = "Healthy", body = Health),
        (status = 503, description = "Unhealthy; see 'checks' for details", body = Health),
    ),
)]
pub async fn health(State(state): State<AppState>) -> Response {
    let (elapsed, result) = probe(&state.db).await;
    let status = Status::of(result.is_ok());
    let checks = Checks {
        database: DatabaseCheck {
            status,
            error: result.err(),
            response_time_ms: elapsed.as_millis() as u64,
        },
        pool: PoolCheck {
            size: state.db.size(),
            idle: state.db.num_idle(),
            max: state.db.options().get_max_connections(),
        },
    };
    respond(status, Health { status, checks })
}

/// Runs `SELECT 1`, returning how long it took and whether it succeeded.
async fn probe(db: &PgPool) -> (Duration, Result<(), DatabaseError>) {
    let start = Instant::now();
    let query = sqlx::query("SELECT 1").execute(db);
    let result = match tokio::time::timeout(PROBE_TIMEOUT, query).await {
        Ok(Ok(_)) => Ok(()),
        Ok(Err(_)) => Err(DatabaseError::Unreachable),
        Err(_) => Err(DatabaseError::Timeout),
    };
    (start.elapsed(), result)
}

/// Probes must never be cached.
fn respond(status: Status, body: impl Serialize) -> Response {
    (
        status.code(),
        [(header::CACHE_CONTROL, "no-store")],
        Json(body),
    )
        .into_response()
}

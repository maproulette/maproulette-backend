use axum::Json;
use axum::extract::State;
use serde::Serialize;
use utoipa::ToSchema;

use crate::AppState;
use crate::error::{Error, Problem};

#[derive(Serialize, ToSchema)]
pub struct Health {
    status: &'static str,
}

/// Reports whether the service is up and can reach the database.
#[utoipa::path(
    get,
    path = "/health",
    tag = "service",
    responses(
        (status = 200, description = "The service is healthy", body = Health),
        (
            status = 503,
            description = "The database is unreachable",
            body = Problem,
            content_type = "application/problem+json",
        ),
    ),
)]
pub async fn health(State(state): State<AppState>) -> Result<Json<Health>, Error> {
    sqlx::query("SELECT 1").execute(&state.db).await?;
    Ok(Json(Health { status: "ok" }))
}

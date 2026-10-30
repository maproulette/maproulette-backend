use axum::Json;
use axum::extract::State;
use serde::Serialize;
use utoipa::ToSchema;

use crate::AppState;
use crate::auth::Principal;
use crate::db;
use crate::error::{Error, Problem};
use crate::model::{OsmUserId, UserId};

#[derive(Serialize, ToSchema)]
pub struct Me {
    /// MapRoulette user id.
    id: UserId,
    /// OpenStreetMap user id.
    osm_id: OsmUserId,
    /// OpenStreetMap display name.
    name: String,
}

/// Returns the authenticated user. Lets a script check that its token works.
#[utoipa::path(
    get,
    path = "/me",
    tag = "users",
    security(("session" = []), ("bearer" = [])),
    responses(
        (status = 200, description = "The authenticated user", body = Me),
        (
            status = 401,
            description = "No credential, or an invalid token",
            body = Problem,
            content_type = "application/problem+json",
        ),
    ),
)]
pub async fn me(State(state): State<AppState>, principal: Principal) -> Result<Json<Me>, Error> {
    let profile = db::users::profile(&state.db, principal.user_id).await?;
    Ok(Json(Me {
        id: profile.id,
        osm_id: profile.osm_id,
        name: profile.name,
    }))
}

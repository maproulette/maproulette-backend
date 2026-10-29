mod health;

use std::time::Duration;

use axum::Router;
use axum::body::Bytes;
use axum::extract::{MatchedPath, Request};
use axum::http::{HeaderValue, header};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use tower_http::request_id::{MakeRequestUuid, PropagateRequestIdLayer, SetRequestIdLayer};
use tower_http::trace::{DefaultOnResponse, TraceLayer};
use tracing::Level;
use utoipa::OpenApi;
use utoipa_axum::router::OpenApiRouter;
use utoipa_axum::routes;

use crate::AppState;
use crate::error::Error;

const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(OpenApi)]
#[openapi(
    info(title = "MapRoulette API", version = "3"),
    servers((url = "/api/v3")),
)]
struct ApiDoc;

/// Every documented route. Paths are relative to `/api/v3`.
fn api() -> OpenApiRouter<AppState> {
    OpenApiRouter::with_openapi(ApiDoc::openapi()).routes(routes!(health::health))
}

/// The OpenAPI document for the routes in `api()`.
pub fn openapi() -> utoipa::openapi::OpenApi {
    api().split_for_parts().1
}

/// The whole service: every route under `/api/v3`, plus `/api/v3/openapi.json`, with the
/// middleware that applies to all of them.
pub fn router(state: AppState) -> Router {
    let (api, spec) = api().split_for_parts();
    let spec = Bytes::from(serde_json::to_vec(&spec).expect("OpenAPI document serializes"));
    let api = api
        .route(
            "/openapi.json",
            get(move || async move { ([(header::CONTENT_TYPE, "application/json")], spec) }),
        )
        .method_not_allowed_fallback(|| async { Error::MethodNotAllowed });

    // Layers run bottom to top on the way in.
    Router::new()
        .nest("/api/v3", api)
        .fallback(|| async { Error::NotFound })
        .layer(middleware::from_fn(timeout))
        .layer(middleware::from_fn(route_header))
        .layer(PropagateRequestIdLayer::x_request_id())
        .layer(
            TraceLayer::new_for_http()
                .make_span_with(|req: &Request| {
                    let id = req
                        .headers()
                        .get("x-request-id")
                        .and_then(|v| v.to_str().ok())
                        .unwrap_or_default();
                    tracing::info_span!(
                        "request",
                        method = %req.method(),
                        uri = %req.uri(),
                        route = route(req),
                        request_id = id,
                    )
                })
                .on_response(DefaultOnResponse::new().level(Level::INFO))
                // `Error` logs server errors itself.
                .on_failure(()),
        )
        .layer(SetRequestIdLayer::x_request_id(MakeRequestUuid))
        .with_state(state)
}

/// Fails the request with a 503 if the handler takes longer than `REQUEST_TIMEOUT`.
async fn timeout(req: Request, next: Next) -> Response {
    match tokio::time::timeout(REQUEST_TIMEOUT, next.run(req)).await {
        Ok(res) => res,
        Err(_) => Error::Timeout.into_response(),
    }
}

/// Sets `X-Route` to the request's route. Caddy logs it for per-route metrics.
async fn route_header(req: Request, next: Next) -> Response {
    let route = HeaderValue::try_from(route(&req)).expect("route patterns are ASCII");
    let mut res = next.run(req).await;
    res.headers_mut().insert("x-route", route);
    res
}

/// The matched route pattern, with parameters written `:id` (`/api/v3/tasks/:id`), or
/// `unmatched`.
fn route(req: &Request) -> String {
    req.extensions()
        .get::<MatchedPath>()
        .map(|p| p.as_str().replace('{', ":").replace('}', ""))
        .unwrap_or_else(|| "unmatched".into())
}

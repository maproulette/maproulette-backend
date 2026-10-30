// The extractors and `Patch` have no users until the token and challenge endpoints land.
#[cfg_attr(not(test), expect(dead_code))]
mod extract;
mod health;
mod me;
#[cfg_attr(not(test), expect(dead_code))]
mod patch;

use std::sync::Arc;
use std::time::Duration;

use axum::Router;
use axum::body::Bytes;
use axum::extract::{MatchedPath, Request};
use axum::http::{HeaderName, HeaderValue, Method, header};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use tower_http::cors::{AllowCredentials, AllowOrigin, CorsLayer};
use tower_http::request_id::{MakeRequestUuid, PropagateRequestIdLayer, SetRequestIdLayer};
use tower_http::trace::{DefaultOnResponse, TraceLayer};
use tracing::Level;
use utoipa::openapi::security::{ApiKey, ApiKeyValue, HttpAuthScheme, HttpBuilder, SecurityScheme};
use utoipa::{Modify, OpenApi};
use utoipa_axum::router::OpenApiRouter;
use utoipa_axum::routes;

use crate::AppState;
use crate::auth::{self, play};
use crate::error::{self, Error};

const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(OpenApi)]
#[openapi(
    info(title = "MapRoulette API", version = "3"),
    servers((url = "/api/v3")),
    modifiers(&SecuritySchemes),
)]
struct ApiDoc;

struct SecuritySchemes;

impl Modify for SecuritySchemes {
    fn modify(&self, openapi: &mut utoipa::openapi::OpenApi) {
        let components = openapi.components.get_or_insert_with(Default::default);
        components.add_security_scheme(
            "bearer",
            SecurityScheme::Http(
                HttpBuilder::new()
                    .scheme(HttpAuthScheme::Bearer)
                    .description(Some("A personal access token (`mrt_...`)"))
                    .build(),
            ),
        );
        components.add_security_scheme(
            "session",
            SecurityScheme::ApiKey(ApiKey::Cookie(ApiKeyValue::with_description(
                play::COOKIE,
                "The browser session from logging in to maproulette.org",
            ))),
        );
    }
}

/// Every documented route. Paths are relative to `/api/v3`.
fn api() -> OpenApiRouter<AppState> {
    OpenApiRouter::with_openapi(ApiDoc::openapi())
        .merge(probes())
        .merge(authenticated())
}

/// Routes that never look at credentials, so they keep working when the database is down.
fn probes() -> OpenApiRouter<AppState> {
    OpenApiRouter::new()
        .routes(routes!(health::live))
        .routes(routes!(health::ready))
        .routes(routes!(health::health))
}

/// Routes behind `auth::resolve`.
fn authenticated() -> OpenApiRouter<AppState> {
    OpenApiRouter::new().routes(routes!(me::me))
}

/// The OpenAPI document for the routes in `api()`.
pub fn openapi() -> utoipa::openapi::OpenApi {
    api().split_for_parts().1
}

/// The whole service: every route under `/api/v3`, plus `/api/v3/openapi.json`, with the
/// middleware that applies to all of them.
pub fn router(state: AppState) -> Router {
    let cors = cors(state.allowed_origins.clone());
    let spec = Bytes::from(serde_json::to_vec(&openapi()).expect("OpenAPI document serializes"));
    let (probes, _) = probes().split_for_parts();
    let (authenticated, _) = authenticated().split_for_parts();
    let api = probes
        .route(
            "/openapi.json",
            get(move || async move { ([(header::CONTENT_TYPE, "application/json")], spec) }),
        )
        .merge(authenticated.layer(middleware::from_fn_with_state(state.clone(), auth::resolve)))
        .method_not_allowed_fallback(|| async { Error::MethodNotAllowed });

    // Layers run bottom to top on the way in.
    Router::new()
        .nest("/api/v3", api)
        .fallback(|| async { Error::NotFound })
        .layer(middleware::from_fn(timeout))
        .layer(middleware::from_fn(error::report))
        .layer(cors)
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
                        auth = tracing::field::Empty,
                    )
                })
                .on_response(DefaultOnResponse::new().level(Level::INFO))
                // `Error` logs server errors itself.
                .on_failure(()),
        )
        .layer(SetRequestIdLayer::x_request_id(MakeRequestUuid))
        .with_state(state)
}

/// Lets web pages on other origins call the API. Any origin may make requests
/// without credentials, but only our own frontend can use cookies to make
/// requests or read their responses.
fn cors(allowed_origins: Arc<[HeaderValue]>) -> CorsLayer {
    CorsLayer::new()
        .allow_origin(AllowOrigin::mirror_request())
        .allow_credentials(AllowCredentials::predicate(move |origin, _| {
            allowed_origins.contains(origin)
        }))
        .allow_methods([
            Method::GET,
            Method::HEAD,
            Method::POST,
            Method::PUT,
            Method::PATCH,
            Method::DELETE,
        ])
        .allow_headers([
            header::AUTHORIZATION,
            header::CONTENT_TYPE,
            header::CONTENT_ENCODING,
        ])
        .expose_headers([
            HeaderName::from_static("x-request-id"),
            header::LOCATION,
            header::RETRY_AFTER,
            header::WWW_AUTHENTICATE,
        ])
        .max_age(Duration::from_secs(60 * 60))
}

/// Fails the request with a 503 if the handler takes longer than `REQUEST_TIMEOUT`.
async fn timeout(req: Request, next: Next) -> Response {
    match tokio::time::timeout(REQUEST_TIMEOUT, next.run(req)).await {
        Ok(res) => res,
        Err(_) => Error::Unavailable(anyhow::anyhow!(
            "request took longer than {REQUEST_TIMEOUT:?}"
        ))
        .into_response(),
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

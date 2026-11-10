use axum::http::{Method, StatusCode};
use serde_json::json;

use crate::utils::{TestApp, send};

#[tokio::test]
async fn unknown_path_is_a_problem_document() {
    let res = TestApp::without_db().await.get("/api/v3/nope").await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    assert_eq!(
        res.json(),
        json!({
            "type": "about:blank",
            "title": "Not Found",
            "status": 404,
            "request_id": res.header("x-request-id"),
        })
    );
    assert_eq!(res.header("x-route"), "unmatched");
}

#[tokio::test]
async fn wrong_method_is_a_problem_document() {
    let app = TestApp::without_db().await;
    let res = send(app.request(Method::POST, "/api/v3/health")).await;
    assert_eq!(res.status, StatusCode::METHOD_NOT_ALLOWED);
    assert_eq!(res.header("x-route"), "/api/v3/health");
}

#[tokio::test]
async fn client_request_id_is_kept() {
    let app = TestApp::without_db().await;
    let res = send(
        app.request(Method::GET, "/api/v3/nope")
            .header("x-request-id", "client-chosen"),
    )
    .await;
    assert_eq!(res.header("x-request-id"), "client-chosen");
    assert_eq!(res.json()["request_id"], "client-chosen");
}

#[tokio::test]
async fn serves_openapi_document() {
    let res = TestApp::without_db()
        .await
        .get("/api/v3/openapi.json")
        .await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.header("x-route"), "/api/v3/openapi.json");
    assert_eq!(
        res.json(),
        serde_json::to_value(maproulette::openapi()).unwrap()
    );
}

use axum::http::StatusCode;
use serde_json::json;

use crate::utils::TestApp;

#[tokio::test]
async fn live_passes_without_database() {
    let res = TestApp::without_db().await.get("/api/v3/health/live").await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.json(), json!({"status": "pass"}));
    assert_eq!(res.header("cache-control"), "no-store");
}

#[tokio::test]
async fn ready_passes() {
    let res = TestApp::spawn().await.get("/api/v3/health/ready").await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.json(), json!({"status": "pass"}));
}

#[tokio::test]
async fn ready_fails_without_database() {
    let res = TestApp::without_db()
        .await
        .get("/api/v3/health/ready")
        .await;
    assert_eq!(res.status, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(res.json(), json!({"status": "fail"}));
}

#[tokio::test]
async fn health_passes() {
    let res = TestApp::spawn().await.get("/api/v3/health").await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.header("cache-control"), "no-store");
    let body = res.json();
    assert_eq!(body["status"], "pass");
    assert_eq!(body["checks"]["database"]["status"], "pass");
    assert!(body["checks"]["database"].get("error").is_none());
    assert!(body["checks"]["database"]["response_time_ms"].is_u64());
    assert_eq!(body["checks"]["pool"]["max"], 10);
}

#[tokio::test]
async fn health_reports_unreachable_database() {
    let res = TestApp::without_db().await.get("/api/v3/health").await;
    assert_eq!(res.status, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(res.header("cache-control"), "no-store");
    let body = res.json();
    assert_eq!(body["status"], "fail");
    assert_eq!(body["checks"]["database"]["status"], "fail");
    // sqlx keeps retrying a refused connection, so the probe times out before it gets an error.
    assert_eq!(body["checks"]["database"]["error"], "timeout");
    assert_eq!(
        body["checks"]["pool"],
        json!({"size": 0, "idle": 0, "max": 10})
    );
}

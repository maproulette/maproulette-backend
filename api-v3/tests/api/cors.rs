use axum::http::{Method, StatusCode};

use crate::utils::{ORIGIN, TestApp, TestResponse, send};

async fn preflight(app: &TestApp, origin: &str) -> TestResponse {
    send(
        app.request(Method::OPTIONS, "/api/v3/me")
            .header("origin", origin)
            .header("access-control-request-method", "POST")
            .header("access-control-request-headers", "content-type"),
    )
    .await
}

#[tokio::test]
async fn frontends_may_make_credentialed_requests() {
    let app = TestApp::without_db().await;
    let res = preflight(&app, ORIGIN).await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.header("access-control-allow-origin"), ORIGIN);
    assert_eq!(res.header("access-control-allow-credentials"), "true");
    assert!(res.header("access-control-allow-methods").contains("POST"));
    assert!(
        res.header("access-control-allow-headers")
            .contains("content-type")
    );
}

#[tokio::test]
async fn other_origins_may_make_requests_without_credentials() {
    let app = TestApp::without_db().await;
    let res = preflight(&app, "https://tool.example").await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(
        res.header("access-control-allow-origin"),
        "https://tool.example"
    );
    assert!(
        res.headers
            .get("access-control-allow-credentials")
            .is_none()
    );

    let res = send(
        app.request(Method::GET, "/api/v3/health/live")
            .header("origin", "https://tool.example"),
    )
    .await;
    assert_eq!(
        res.header("access-control-allow-origin"),
        "https://tool.example"
    );
    assert!(
        res.headers
            .get("access-control-allow-credentials")
            .is_none()
    );
    assert!(
        res.header("access-control-expose-headers")
            .contains("x-request-id")
    );
    assert!(res.header("vary").contains("origin"));
}

#[tokio::test]
async fn error_responses_are_readable_cross_origin() {
    let app = TestApp::without_db().await;
    let res = send(
        app.request(Method::GET, "/api/v3/nope")
            .header("origin", ORIGIN),
    )
    .await;
    assert_eq!(res.status, StatusCode::NOT_FOUND);
    assert_eq!(res.header("access-control-allow-origin"), ORIGIN);
    assert_eq!(res.header("access-control-allow-credentials"), "true");
}

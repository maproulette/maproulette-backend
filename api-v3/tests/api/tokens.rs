use axum::http::{Method, StatusCode, header};
use serde_json::{Value, json};

use crate::utils::play::now_ms;
use crate::utils::{ORIGIN, TestApp, TestResponse, send};

struct Session<'a> {
    app: &'a TestApp,
    cookie: String,
}

impl Session<'_> {
    async fn new(app: &TestApp) -> Session<'_> {
        let user = app.user().await;
        let cookie = format!("PLAY_SESSION={}", user.session_cookie(now_ms()));
        Session { app, cookie }
    }

    fn request(&self, method: Method, path: &str) -> reqwest::RequestBuilder {
        self.app
            .request(method, path)
            .header(header::COOKIE, &self.cookie)
            .header(header::ORIGIN, ORIGIN)
    }

    async fn create(&self, body: Value) -> TestResponse {
        send(self.request(Method::POST, "/api/v3/me/tokens").json(&body)).await
    }

    /// Creates a token, failing the test if that doesn't work, and returns the response body.
    async fn create_ok(&self, name: &str) -> Value {
        let res = self.create(new_token(name)).await;
        assert_eq!(res.status, StatusCode::CREATED, "{:?}", res.json());
        res.json()
    }

    async fn list(&self) -> Vec<Value> {
        let res = send(self.request(Method::GET, "/api/v3/me/tokens")).await;
        assert_eq!(res.status, StatusCode::OK);
        res.json().as_array().unwrap().clone()
    }

    async fn revoke(&self, id: &Value) -> TestResponse {
        send(self.request(Method::DELETE, &format!("/api/v3/me/tokens/{id}"))).await
    }
}

fn new_token(name: &str) -> Value {
    json!({"name": name, "scopes": ["challenges:write"], "expires_at": null})
}

async fn me_with_token(app: &TestApp, token: &str) -> TestResponse {
    send(app.request(Method::GET, "/api/v3/me").bearer_auth(token)).await
}

/// Returns the `pointer` of each error in a 422 response.
fn pointers(res: &TestResponse) -> Vec<String> {
    assert_eq!(res.status, StatusCode::UNPROCESSABLE_ENTITY);
    res.json()["errors"]
        .as_array()
        .unwrap()
        .iter()
        .map(|e| e["pointer"].as_str().unwrap_or("(none)").to_owned())
        .collect()
}

#[tokio::test]
async fn create_use_list_and_revoke() {
    let app = TestApp::spawn().await;
    let s = Session::new(&app).await;
    let created = s.create_ok("rebuild pipeline").await;
    let token = created["token"].as_str().unwrap();
    assert!(token.starts_with("mrt_"));
    // hint is for server operators to identify tokens in logs; users don't see it.
    assert!(created.get("hint").is_none());
    assert_eq!(created["name"], "rebuild pipeline");
    assert_eq!(created["scopes"], json!(["challenges:write"]));
    assert_eq!(created["expires_at"], Value::Null);
    assert_eq!(created["last_used_at"], created["created_at"]);

    let res = me_with_token(&app, token).await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.header("x-auth"), format!("pat:{}", created["id"]));

    let mut listed = created.clone();
    listed.as_object_mut().unwrap().remove("token");
    assert_eq!(s.list().await, [listed]);

    assert_eq!(
        s.revoke(&created["id"]).await.status,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        me_with_token(&app, token).await.status,
        StatusCode::UNAUTHORIZED
    );
    assert!(s.list().await.is_empty());
    assert_eq!(s.revoke(&created["id"]).await.status, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn list_is_newest_first() {
    let app = TestApp::spawn().await;
    let s = Session::new(&app).await;
    let first = s.create_ok("first").await;
    let second = s.create_ok("second").await;
    let ids: Vec<_> = s.list().await.iter().map(|t| t["id"].clone()).collect();
    assert_eq!(ids, [second["id"].clone(), first["id"].clone()]);
}

#[tokio::test]
async fn expiry_is_kept_and_name_is_trimmed() {
    let app = TestApp::spawn().await;
    let s = Session::new(&app).await;
    let res = s
        .create(json!({
            "name": "  nightly  ",
            "scopes": ["challenges:write"],
            "expires_at": "2099-01-02T03:04:05Z",
        }))
        .await;
    assert_eq!(res.status, StatusCode::CREATED);
    assert_eq!(res.json()["name"], "nightly");
    assert_eq!(res.json()["expires_at"], "2099-01-02T03:04:05Z");
}

#[tokio::test]
async fn invalid_bodies_list_every_problem() {
    let app = TestApp::spawn().await;
    let s = Session::new(&app).await;
    let res = s
        .create(json!({"name": " ", "scopes": [], "expires_at": "2000-01-01T00:00:00Z"}))
        .await;
    assert_eq!(pointers(&res), ["/name", "/scopes", "/expires_at"]);

    let res = s
        .create(json!({
            "name": "x".repeat(101),
            "scopes": ["challenges:write", "challenges:write"],
            "expires_at": null,
        }))
        .await;
    assert_eq!(pointers(&res), ["/name", "/scopes/1"]);

    assert_eq!(
        s.create(new_token(&"x".repeat(100))).await.status,
        StatusCode::CREATED
    );
}

#[tokio::test]
async fn misshapen_bodies_are_rejected() {
    let app = TestApp::spawn().await;
    let s = Session::new(&app).await;
    for body in [
        json!({"name": "x", "scopes": ["tasks:map"], "expires_at": null}),
        json!({"name": "x", "scopes": ["challenges:write"], "expires_at": null, "extra": 1}),
        json!({"name": "x", "scopes": ["challenges:write"]}),
        json!({"scopes": ["challenges:write"], "expires_at": null}),
        json!({"name": "x", "scopes": ["challenges:write"], "expires_at": "tomorrow"}),
    ] {
        let res = s.create(body.clone()).await;
        assert_eq!(pointers(&res), ["(none)"], "{body}");
    }
    let req = s.request(Method::POST, "/api/v3/me/tokens");
    let res = send(req.header(header::CONTENT_TYPE, "text/plain").body("{}")).await;
    assert_eq!(res.status, StatusCode::UNSUPPORTED_MEDIA_TYPE);
    let req = s.request(Method::POST, "/api/v3/me/tokens");
    let res = send(
        req.header(header::CONTENT_TYPE, "application/json")
            .body("{"),
    )
    .await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST);
    assert!(s.list().await.is_empty());
}

#[tokio::test]
async fn tokens_cannot_manage_tokens() {
    let app = TestApp::spawn().await;
    let s = Session::new(&app).await;
    let created = s.create_ok("x").await;
    let token = created["token"].as_str().unwrap();
    let id = &created["id"];
    for req in [
        app.request(Method::GET, "/api/v3/me/tokens"),
        app.request(Method::POST, "/api/v3/me/tokens")
            .json(&new_token("y")),
        app.request(Method::DELETE, &format!("/api/v3/me/tokens/{id}")),
    ] {
        let res = send(req.bearer_auth(token)).await;
        assert_eq!(res.status, StatusCode::FORBIDDEN);
    }
    assert_eq!(s.list().await.len(), 1);
}

#[tokio::test]
async fn anonymous_requests_are_unauthorized() {
    let app = TestApp::spawn().await;
    for (method, path) in [
        (Method::GET, "/api/v3/me/tokens"),
        (Method::POST, "/api/v3/me/tokens"),
        (Method::DELETE, "/api/v3/me/tokens/1"),
    ] {
        let res = send(app.request(method, path).json(&new_token("x"))).await;
        assert_eq!(res.status, StatusCode::UNAUTHORIZED, "{path}");
    }
}

#[tokio::test]
async fn session_writes_need_a_trusted_origin() {
    let app = TestApp::spawn().await;
    let s = Session::new(&app).await;
    let created = s.create_ok("x").await;
    let path = format!("/api/v3/me/tokens/{}", created["id"]);
    for origin in [None, Some("https://evil.example"), Some("null")] {
        for req in [
            app.request(Method::POST, "/api/v3/me/tokens")
                .json(&new_token("y")),
            app.request(Method::DELETE, &path),
        ] {
            let mut req = req.header(header::COOKIE, &s.cookie);
            if let Some(origin) = origin {
                req = req.header(header::ORIGIN, origin);
            }
            let res = send(req).await;
            assert_eq!(res.status, StatusCode::FORBIDDEN, "{origin:?}");
            let detail = res.json()["detail"].as_str().unwrap().to_owned();
            assert!(detail.contains("personal access token"), "{detail}");
        }
    }

    // Reads don't change anything, so they don't need an Origin.
    let res = send(
        app.request(Method::GET, "/api/v3/me/tokens")
            .header(header::COOKIE, &s.cookie),
    )
    .await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(s.list().await.len(), 1);
}

#[tokio::test]
async fn other_users_tokens_are_invisible() {
    let app = TestApp::spawn().await;
    let alice = Session::new(&app).await;
    let bob = Session::new(&app).await;
    let token = alice.create_ok("alice's").await;
    assert!(bob.list().await.is_empty());
    assert_eq!(bob.revoke(&token["id"]).await.status, StatusCode::NOT_FOUND);
    let res = me_with_token(&app, token["token"].as_str().unwrap()).await;
    assert_eq!(res.status, StatusCode::OK);
}

#[tokio::test]
async fn at_most_twenty_active_tokens() {
    let app = TestApp::spawn().await;
    let s = Session::new(&app).await;

    let mut tokens = Vec::new();
    for i in 0..20 {
        tokens.push(s.create_ok(&format!("token {i}")).await);
    }

    let res = s.create(new_token("one too many")).await;
    assert_eq!(res.status, StatusCode::CONFLICT);
    assert_eq!(s.list().await.len(), 20);

    // Revoked, expired and idle tokens don't count towards the limit
    assert_eq!(
        s.revoke(&tokens[0]["id"]).await.status,
        StatusCode::NO_CONTENT
    );

    let expire = "UPDATE credentials SET expires_at = now() - interval '1 second' WHERE id = $1";
    app.exec(expire, tokens[1]["id"].as_i64().unwrap()).await;
    let idle = "UPDATE credentials SET last_used_at = now() - interval '91 days' WHERE id = $1";
    app.exec(idle, tokens[2]["id"].as_i64().unwrap()).await;
    assert_eq!(s.list().await.len(), 17);
    for i in 0..3 {
        s.create_ok(&format!("replacement {i}")).await;
    }
    assert_eq!(
        s.create(new_token("one too many")).await.status,
        StatusCode::CONFLICT
    );
}

use std::time::Duration;

use axum::http::{Method, StatusCode, header};
use serde_json::json;

use crate::utils::play::{self, now_ms};
use crate::utils::{SECRET, TestApp, TestResponse, send};

const DAY_MS: i64 = 24 * 60 * 60 * 1000;

async fn me(app: &TestApp, headers: &[(header::HeaderName, &str)]) -> TestResponse {
    let mut req = app.request(Method::GET, "/api/v3/me");
    for (name, value) in headers {
        req = req.header(name, *value);
    }
    send(req).await
}

fn bearer(token: &str) -> String {
    format!("Bearer {token}")
}

fn session(cookie: &str) -> String {
    format!("PLAY_SESSION={cookie}")
}

fn assert_invalid_token(res: &TestResponse) {
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    assert_eq!(
        res.header("www-authenticate"),
        r#"Bearer error="invalid_token""#
    );
    assert!(res.headers.get("x-auth").is_none());
}

fn assert_anonymous(res: &TestResponse) {
    assert_eq!(res.status, StatusCode::UNAUTHORIZED);
    assert_eq!(res.header("www-authenticate"), "Bearer");
    assert!(res.headers.get("x-auth").is_none());
}

#[tokio::test]
async fn no_credential_is_anonymous() {
    let app = TestApp::spawn().await;
    assert_anonymous(&me(&app, &[]).await);
}

#[tokio::test]
async fn pat_authenticates() {
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let (token, id) = app.credential(&user, "pat", &["challenges:write"]).await;
    let res = me(&app, &[(header::AUTHORIZATION, &bearer(token.as_str()))]).await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.header("x-auth"), format!("pat:{id}"));
    assert_eq!(
        res.json(),
        json!({"id": user.id, "osm_id": user.osm_id, "name": user.name})
    );
}

#[tokio::test]
async fn editor_token_authenticates() {
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let (token, id) = app.credential(&user, "editor", &["tasks:map"]).await;
    let res = me(&app, &[(header::AUTHORIZATION, &bearer(token.as_str()))]).await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.header("x-auth"), format!("editor:{id}"));
}

#[tokio::test]
async fn bearer_scheme_is_case_insensitive() {
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let (token, _) = app.credential(&user, "pat", &["challenges:write"]).await;
    let value = format!("bearer {}", token.as_str());
    let res = me(&app, &[(header::AUTHORIZATION, &value)]).await;
    assert_eq!(res.status, StatusCode::OK);
}

#[tokio::test]
async fn bearer_allows_extra_spaces_but_not_tabs() {
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let (token, _) = app.credential(&user, "pat", &["challenges:write"]).await;
    for value in [
        format!("Bearer  {}", token.as_str()),
        format!("Bearer {} ", token.as_str()),
    ] {
        let res = me(&app, &[(header::AUTHORIZATION, &value)]).await;
        assert_eq!(res.status, StatusCode::OK, "{value:?}");
    }
    let tab = format!("Bearer\t{}", token.as_str());
    assert_invalid_token(&me(&app, &[(header::AUTHORIZATION, &tab)]).await);
}

#[tokio::test]
async fn malformed_authorization_is_rejected() {
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let (token, _) = app.credential(&user, "pat", &["challenges:write"]).await;
    let mut typo = token.as_str().to_owned();
    typo.replace_range(10..11, if &typo[10..11] == "a" { "b" } else { "a" });
    for value in [
        "Bearer".to_owned(),
        "Bearer ".to_owned(),
        format!("Basic {}", token.as_str()),
        format!("Token {}", token.as_str()),
        bearer(&typo),
        bearer("mrt_not_a_token"),
    ] {
        let res = me(&app, &[(header::AUTHORIZATION, &value)]).await;
        assert_invalid_token(&res);
    }
}

#[tokio::test]
async fn two_authorization_headers_are_rejected() {
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let (token, _) = app.credential(&user, "pat", &["challenges:write"]).await;
    let value = bearer(token.as_str());
    let headers = [
        (header::AUTHORIZATION, value.as_str()),
        (header::AUTHORIZATION, value.as_str()),
    ];
    assert_invalid_token(&me(&app, &headers).await);
}

#[tokio::test]
async fn unusable_credentials_are_rejected() {
    let app = TestApp::spawn().await;
    let user = app.user().await;
    for sql in [
        "UPDATE credentials SET revoked_at = now() WHERE id = $1",
        "UPDATE credentials SET expires_at = now() - interval '1 second' WHERE id = $1",
        // A PAT that never expires still stops working if it isn't used for 90 days.
        "UPDATE credentials SET expires_at = NULL, last_used_at = now() - interval '91 days' \
         WHERE id = $1",
        "DELETE FROM credentials WHERE id = $1",
    ] {
        let (token, id) = app.credential(&user, "pat", &["challenges:write"]).await;
        app.exec(sql, id.0).await;
        let res = me(&app, &[(header::AUTHORIZATION, &bearer(token.as_str()))]).await;
        assert_invalid_token(&res);
    }
}

#[tokio::test]
async fn idle_rule_applies_only_to_pats() {
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let (token, id) = app.credential(&user, "editor", &["tasks:map"]).await;
    let idle = "UPDATE credentials SET last_used_at = now() - interval '91 days' WHERE id = $1";
    app.exec(idle, id.0).await;
    let res = me(&app, &[(header::AUTHORIZATION, &bearer(token.as_str()))]).await;
    assert_eq!(res.status, StatusCode::OK);
}

#[tokio::test]
async fn session_token_is_not_a_bearer_token() {
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let (token, _) = app.credential(&user, "session", &[]).await;
    let res = me(&app, &[(header::AUTHORIZATION, &bearer(token.as_str()))]).await;
    assert_invalid_token(&res);
}

#[tokio::test]
async fn last_used_is_updated_only_when_stale() {
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let (token, id) = app.credential(&user, "pat", &["challenges:write"]).await;
    let auth = bearer(token.as_str());
    let seconds_since_use = async || -> i64 {
        sqlx::query_scalar!(
            r#"SELECT extract(epoch FROM now() - last_used_at)::bigint AS "seconds!"
               FROM credentials WHERE id = $1"#,
            id.0
        )
        .fetch_one(&app.db)
        .await
        .unwrap()
    };

    app.exec(
        "UPDATE credentials SET last_used_at = now() - interval '1 minute' WHERE id = $1",
        id.0,
    )
    .await;
    me(&app, &[(header::AUTHORIZATION, &auth)]).await;

    tokio::time::sleep(Duration::from_millis(200)).await;
    assert!((60..120).contains(&seconds_since_use().await));

    app.exec(
        "UPDATE credentials SET last_used_at = now() - interval '1 hour' WHERE id = $1",
        id.0,
    )
    .await;
    me(&app, &[(header::AUTHORIZATION, &auth)]).await;
    for _ in 0..50 {
        if seconds_since_use().await < 60 {
            return;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    panic!("last_used_at was not updated");
}

#[tokio::test]
async fn session_authenticates() {
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let cookie = format!(
        "other=1; PLAY_SESSION={}; another=2",
        user.session_cookie(now_ms())
    );
    let res = me(&app, &[(header::COOKIE, &cookie)]).await;
    assert_eq!(res.status, StatusCode::OK);
    assert_eq!(res.header("x-auth"), format!("session:user/{}", user.id));
    assert_eq!(res.json()["id"], json!(user.id));
}

#[tokio::test]
async fn session_cookie_can_be_in_any_cookie_header() {
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let cookie = session(&user.session_cookie(now_ms()));
    let headers = [
        (header::COOKIE, "other=1"),
        (header::COOKIE, cookie.as_str()),
    ];
    assert_eq!(me(&app, &headers).await.status, StatusCode::OK);
}

#[tokio::test]
async fn first_session_cookie_wins() {
    // Play reads the first cookie with a given name, so v3 does too.
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let valid = user.session_cookie(now_ms());
    let first_valid = format!("PLAY_SESSION={valid}; PLAY_SESSION=garbage");
    let res = me(&app, &[(header::COOKIE, &first_valid)]).await;
    assert_eq!(res.status, StatusCode::OK);
    let first_invalid = format!("PLAY_SESSION=garbage; PLAY_SESSION={valid}");
    assert_anonymous(&me(&app, &[(header::COOKIE, &first_invalid)]).await);
}

#[tokio::test]
async fn invalid_bearer_does_not_fall_back_to_session() {
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let cookie = session(&user.session_cookie(now_ms()));
    let headers = [
        (header::COOKIE, cookie.as_str()),
        (header::AUTHORIZATION, "Bearer nope"),
    ];
    assert_invalid_token(&me(&app, &headers).await);
}

#[tokio::test]
async fn session_lasts_thirty_days_from_login() {
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let fresh = session(&user.session_cookie(now_ms() - 29 * DAY_MS));
    let res = me(&app, &[(header::COOKIE, &fresh)]).await;
    assert_eq!(res.status, StatusCode::OK);
    let stale = session(&user.session_cookie(now_ms() - 31 * DAY_MS));
    assert_anonymous(&me(&app, &[(header::COOKIE, &stale)]).await);
}

#[tokio::test]
async fn session_from_the_future_is_accepted() {
    // v2 only checks that the login isn't too old. Only someone with the secret
    // can make a cookie with a login time in the future, so this is probably okay.
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let cookie = session(&user.session_cookie(now_ms() + DAY_MS));
    assert_eq!(
        me(&app, &[(header::COOKIE, &cookie)]).await.status,
        StatusCode::OK
    );
}

#[tokio::test]
async fn session_ends_when_user_logs_in_again() {
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let cookie = session(&user.session_cookie(now_ms()));
    let relogin = "UPDATE users SET oauth_token = 'replaced' WHERE id = $1";
    app.exec(relogin, user.id.0.into()).await;
    assert_anonymous(&me(&app, &[(header::COOKIE, &cookie)]).await);
}

#[tokio::test]
async fn invalid_sessions_are_anonymous() {
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let data = user.session_data(now_ms());
    let valid = play::cookie(data.clone(), SECRET);
    let res = me(&app, &[(header::COOKIE, &session(&valid))]).await;
    assert_eq!(res.status, StatusCode::OK, "the unmodified cookie works");

    let (signed, _) = valid.rsplit_once('.').unwrap();
    let mut earlier_login = data.clone();
    earlier_login["tokenHash"] = json!("0".repeat(64));
    let now = now_ms() / 1000;
    for cookie in [
        format!("{signed}.{}", "A".repeat(43)),
        play::cookie(data.clone(), "another-secret-at-least-thirty-two-bytes"),
        play::cookie(earlier_login, SECRET),
        // What v2 stores while a login is in progress.
        play::cookie(json!({"state": "x", "redirectUri": "/"}), SECRET),
        // Play allows five minutes of clock skew, so these are just past that.
        play::cookie_with_claims(json!({"data": data, "nbf": now + 600}), SECRET),
        play::cookie_with_claims(json!({"data": data, "nbf": now, "exp": now - 600}), SECRET),
        "garbage".to_owned(),
    ] {
        assert_anonymous(&me(&app, &[(header::COOKIE, &session(&cookie))]).await);
    }
}

#[tokio::test]
async fn session_time_claims_allow_clock_skew() {
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let data = user.session_data(now_ms());
    let now = now_ms() / 1000;
    for cookie in [
        play::cookie_with_claims(json!({"data": data, "nbf": now + 60}), SECRET),
        play::cookie_with_claims(json!({"data": data, "nbf": now, "exp": now - 60}), SECRET),
    ] {
        let res = me(&app, &[(header::COOKIE, &session(&cookie))]).await;
        assert_eq!(res.status, StatusCode::OK);
    }
}

#[tokio::test]
async fn session_rejects_other_algorithms() {
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let claims = json!({"data": user.session_data(now_ms()), "nbf": now_ms() / 1000});
    for alg in ["none", "HS512", "hs256"] {
        let cookie = play::signed_jwt(json!({"alg": alg}), claims.clone(), SECRET);
        assert_anonymous(&me(&app, &[(header::COOKIE, &session(&cookie))]).await);
    }
    let cookie = play::cookie_with_claims(claims, SECRET);
    let (unsigned, _) = cookie.rsplit_once('.').unwrap();
    let res = me(&app, &[(header::COOKIE, &session(&format!("{unsigned}.")))]).await;
    assert_anonymous(&res);
}

#[tokio::test]
async fn session_with_bad_data_is_anonymous() {
    let app = TestApp::spawn().await;
    let user = app.user().await;
    let data = user.session_data(now_ms());
    let mut unknown_user = data.clone();
    unknown_user["userId"] = json!((user.id.0 + 1000).to_string());
    let mut bad_tick = data.clone();
    bad_tick["userTick"] = json!("yesterday");
    let mut numeric_id = data.clone();
    numeric_id["userId"] = json!(user.id);
    for data in [unknown_user, bad_tick, numeric_id] {
        let cookie = play::cookie(data, SECRET);
        assert_anonymous(&me(&app, &[(header::COOKIE, &session(&cookie))]).await);
    }
}

#[tokio::test]
async fn probes_ignore_credentials() {
    let app = TestApp::without_db().await;
    let req = app
        .request(Method::GET, "/api/v3/health/live")
        .header(header::AUTHORIZATION, "Bearer nope");
    let res = send(req).await;
    assert_eq!(res.status, StatusCode::OK);
    assert!(res.headers.get("x-auth").is_none());
}

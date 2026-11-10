//! Tests for the `PUT /challenges/:id/tasks` endpoint

use std::io::Write;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use axum::http::{Method, StatusCode};
use flate2::Compression;
use flate2::write::GzEncoder;
use futures_util::StreamExt;
use serde_json::{Value, json};
use sqlx::{Connection, PgConnection};

use maproulette::auth::token::Token;
use maproulette::config::{Config, UploadLimits};
use maproulette::model::{ChallengeId, Role};

use crate::utils::play::now_ms;
use crate::utils::{Grantee, ORIGIN, Object, TestApp, TestResponse, User, send};

// v2's task statuses.
const CREATED: i32 = 0;
const FIXED: i32 = 1;
const FALSE_POSITIVE: i32 = 2;
const SKIPPED: i32 = 3;
const TOO_HARD: i32 = 6;
const DISABLED: i32 = 9;

// v2's challenge statuses.
const BUILDING: i32 = 1;
const READY: i32 = 3;
const FINISHED: i32 = 5;

struct Fixture {
    app: TestApp,
    user: User,
    token: Token,
    challenge: ChallengeId,
}

impl Fixture {
    /// Create a challenge and a user who can write to it with a token.
    async fn new() -> Fixture {
        Fixture::with(|_| {}).await
    }

    async fn with(configure: impl FnOnce(&mut Config)) -> Fixture {
        let app = TestApp::spawn_with(configure).await;
        let user = app.user().await;
        let project = app.project().await;
        let challenge = app.challenge(project).await;
        app.grant(
            Grantee::User(user.id),
            Role::Admin,
            Object::Project(project),
        )
        .await;
        let (token, _) = app.credential(&user, "pat", &["challenges:write"]).await;
        Fixture {
            app,
            user,
            token,
            challenge,
        }
    }

    async fn with_limits(limits: UploadLimits) -> Fixture {
        Fixture::with(|config| config.uploads = limits).await
    }

    /// An unauthenticated upload to the challenge.
    fn request(&self, content_type: &str) -> reqwest::RequestBuilder {
        self.app
            .request(
                Method::PUT,
                &format!("/api/v3/challenges/{}/tasks", self.challenge),
            )
            .header("content-type", content_type)
    }

    async fn put(&self, body: impl Into<reqwest::Body>) -> TestResponse {
        let req = self.request("application/x-ndjson");
        send(req.bearer_auth(self.token.as_str()).body(body)).await
    }

    async fn upload(&self, features: &[Value]) -> TestResponse {
        self.put(ndjson(features)).await
    }

    /// Uploads some tasks, failing the test if the upload doesn't succeed.
    /// On success, returns the success response.
    async fn ok(&self, features: &[Value]) -> Value {
        let res = self.upload(features).await;
        assert_eq!(res.status, StatusCode::OK, "{}", res.json());
        res.json()
    }

    /// Inserts a task directly into the database, at POINT(0 0) and with no instruction.
    async fn task(&self, name: &str, status: i32, geojson: Value) -> i32 {
        sqlx::query_scalar!(
            "INSERT INTO tasks (name, parent_id, instruction, status, geojson, geom, location) \
             VALUES ($1, $2, '', $3, $4, \
                     ST_SetSRID(ST_MakePoint(0, 0), 4326), ST_SetSRID(ST_MakePoint(0, 0), 4326)) \
             RETURNING id",
            name,
            self.challenge.0,
            status,
            geojson,
        )
        .fetch_one(&self.app.db)
        .await
        .unwrap()
    }

    /// Fetch the challenge's tasks as (name, status), sorted by name.
    async fn statuses(&self) -> Vec<(String, i32)> {
        sqlx::query!(
            "SELECT name, status FROM tasks WHERE parent_id = $1 ORDER BY name",
            self.challenge.0
        )
        .map(|r| (r.name, r.status))
        .fetch_all(&self.app.db)
        .await
        .unwrap()
    }

    async fn challenge_row(&self) -> ChallengeRow {
        sqlx::query_as!(
            ChallengeRow,
            r#"
            SELECT status AS "status!", status_message, overpass_ql, remote_geo_json,
                   last_task_refresh IS NOT NULL AS "refreshed!",
                   data_origin_date IS NOT NULL AS "dated!",
                   ST_AsText(bounding) AS bounding, completion_metrics
            FROM challenges WHERE id = $1
            "#,
            self.challenge.0
        )
        .fetch_one(&self.app.db)
        .await
        .unwrap()
    }

    /// Open another connection to the test's database
    async fn connection(&self) -> PgConnection {
        PgConnection::connect_with(&self.app.db.connect_options())
            .await
            .unwrap()
    }

    /// Waits until some connection to the test's database is waiting for a lock.
    async fn lock_wait(&self) {
        for _ in 0..1000 {
            let waiting = sqlx::query_scalar!(
                r#"
                SELECT EXISTS (
                    SELECT FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
                    WHERE NOT l.granted AND a.datname = current_database()
                ) AS "waiting!"
                "#
            )
            .fetch_one(&self.app.db)
            .await
            .unwrap();
            if waiting {
                return;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        panic!("nothing started waiting for a lock");
    }
}

#[derive(Debug)]
struct ChallengeRow {
    status: i32,
    status_message: Option<String>,
    overpass_ql: Option<String>,
    remote_geo_json: Option<String>,
    refreshed: bool,
    dated: bool,
    bounding: Option<String>,
    completion_metrics: Value,
}

/// The `errors` of a 422 response.
fn errors(res: &TestResponse) -> Vec<Value> {
    assert_eq!(
        res.status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{}",
        res.json()
    );
    res.json()["errors"].as_array().unwrap().clone()
}

/// The line each error is on.
fn lines(errors: &[Value]) -> Vec<u64> {
    errors.iter().map(|e| e["line"].as_u64().unwrap()).collect()
}

fn ndjson(features: &[Value]) -> String {
    features.iter().map(|f| format!("{f}\n")).collect()
}

fn point(lon: f64, lat: f64) -> Value {
    json!({"type": "Point", "coordinates": [lon, lat]})
}

fn feature(id: &str) -> Value {
    json!({"type": "Feature", "id": id, "geometry": point(-122.3, 47.6), "properties": {"name": id}})
}

/// The `tasks.geojson` that v3 stores for an uploaded feature.
fn stored(feature: Value) -> Value {
    json!({"type": "FeatureCollection", "features": [feature]})
}

fn counts(
    created: u64,
    updated: u64,
    unchanged: u64,
    reopened: u64,
    closed: u64,
    open: u64,
) -> Value {
    json!({
        "created": created, "updated": updated, "unchanged": unchanged,
        "reopened": reopened, "closed": closed, "open_tasks": open,
    })
}

fn gzip(data: &[u8]) -> Vec<u8> {
    let mut gz = GzEncoder::new(Vec::new(), Compression::default());
    gz.write_all(data).unwrap();
    gz.finish().unwrap()
}

#[tokio::test]
async fn creates_tasks_in_v2s_shape() {
    let f = Fixture::new().await;
    // Tasks go to the database in COPY's text format, which escapes these characters.
    let escapes = json!({
        "type": "Feature", "id": "back\\slash é",
        "geometry": point(-122.3, 47.6),
        "properties": {"s": "quote \" backslash \\ newline \n tab \t return \r"},
    });
    let line = json!({
        "type": "Feature", "id": "line",
        "geometry": {"type": "LineString", "coordinates": [[0, 0, 5], [1, 1, 6]]},
        "properties": {},
    });
    assert_eq!(
        f.ok(&[escapes.clone(), line.clone()]).await,
        counts(2, 0, 0, 0, 0, 2)
    );

    let rows = sqlx::query!(
        r#"
        SELECT name, geojson, ST_AsText(geom) AS "geom!", ST_AsText(location) AS "location!"
        FROM tasks WHERE parent_id = $1 ORDER BY name
        "#,
        f.challenge.0
    )
    .map(|r| (r.name, r.geojson, r.geom, r.location))
    .fetch_all(&f.app.db)
    .await
    .unwrap();
    let point = "POINT(-122.3 47.6)";
    // geom drops Z, but the stored GeoJSON keeps it.
    #[rustfmt::skip]
    let expected = [
        ("back\\slash é".into(), stored(escapes), point.into(), point.into()),
        ("line".into(), stored(line), "LINESTRING(0 0,1 1)".into(), "POINT(0.5 0.5)".into()),
    ];
    assert_eq!(rows, expected);
}

#[tokio::test]
async fn updates_the_challenge() {
    let f = Fixture::new().await;
    // Uploading converts a remote GeoJSON challenge, and repairs if it was stuck building.
    sqlx::query!(
        "UPDATE challenges SET overpass_ql = '', remote_geo_json = 'https://example.com/t.json', \
         status = $2, status_message = 'stuck' WHERE id = $1",
        f.challenge.0,
        BUILDING,
    )
    .execute(&f.app.db)
    .await
    .unwrap();
    f.ok(&[feature("a"), feature("b")]).await;
    let c = f.challenge_row().await;
    assert_eq!((c.status, c.status_message), (READY, None));
    assert_eq!((c.overpass_ql, c.remote_geo_json), (None, None));
    assert!(c.refreshed && c.dated);
    assert!(c.bounding.unwrap().starts_with("POLYGON(("));
    assert_eq!(c.completion_metrics["total"], 2);

    assert_eq!(f.ok(&[]).await, counts(0, 0, 0, 0, 2, 0));
    assert_eq!(f.challenge_row().await.status, FINISHED);
    f.ok(&[feature("a")]).await;
    assert_eq!(f.challenge_row().await.status, READY);
}

#[tokio::test]
async fn matches_uploaded_tasks_to_existing_ones() {
    let f = Fixture::new().await;
    // cases are (stored name, status, is unchanged, is in the upload, status afterwards)
    let cases = [
        ("open-same", CREATED, true, true, CREATED),
        ("open-changed", SKIPPED, false, true, SKIPPED),
        ("done-changed", FALSE_POSITIVE, false, true, FALSE_POSITIVE),
        ("closed-same", DISABLED, true, true, CREATED),
        ("open-missing", TOO_HARD, true, false, DISABLED),
        ("done-missing", FIXED, true, false, FIXED),
        ("closed-missing", DISABLED, true, false, DISABLED),
        // Keys match ignoring case, and the task takes the uploaded case.
        ("Case", CREATED, true, true, CREATED),
        // v3 tasks have no instruction, so a task with one has changed.
        ("instructed", CREATED, true, true, CREATED),
    ];
    let mut upload = vec![feature("new")];
    for (name, status, same, uploaded, _) in cases {
        let key = name.to_lowercase();
        let geojson = match same {
            true => stored(feature(&key)),
            false => stored(json!({})),
        };
        f.task(name, status, geojson).await;
        if uploaded {
            upload.push(feature(&key));
        }
    }
    let add_instruction =
        "UPDATE tasks SET instruction = 'old' WHERE parent_id = $1 AND name = 'instructed'";
    f.app.exec(add_instruction, f.challenge.0.into()).await;
    assert_eq!(f.ok(&upload).await, counts(1, 4, 1, 1, 1, 6));
    let mut expected: Vec<(String, i32)> = cases
        .iter()
        .map(|&(name, _, _, _, after)| (name.to_lowercase(), after))
        .chain([("new".into(), CREATED)])
        .collect();
    expected.sort();
    assert_eq!(f.statuses().await, expected);

    let tasks = sqlx::query!(
        r#"SELECT name, geojson, ST_AsText(geom) AS "geom!"
           FROM tasks WHERE parent_id = $1 AND name NOT LIKE '%missing'"#,
        f.challenge.0
    )
    .fetch_all(&f.app.db)
    .await
    .unwrap();
    for t in tasks {
        assert_eq!(t.geojson, stored(feature(&t.name)), "{}", t.name);
        // Tasks inserted by `Fixture::task` are at POINT(0 0) until they're rewritten.
        if t.name.contains("changed") {
            assert_eq!(t.geom, "POINT(-122.3 47.6)", "{}", t.name);
        }
    }
}

#[tokio::test]
async fn refuses_overpass_challenges() {
    let f = Fixture::new().await;
    let set_query = "UPDATE challenges SET overpass_ql = '[out:json];node;out;' WHERE id = $1";
    f.app.exec(set_query, f.challenge.0.into()).await;
    let res = f.upload(&[feature("a")]).await;
    assert_eq!(res.status, StatusCode::CONFLICT, "{}", res.json());
    assert!(f.statuses().await.is_empty());
}

#[tokio::test]
async fn reports_invalid_features_by_line_and_writes_nothing() {
    let f = Fixture::new().await;
    f.ok(&[feature("a")]).await;
    let body = format!(
        "{}\n\n{}\nnot json\n{}\n",
        feature("b"),
        json!({"type": "Feature", "id": "c", "geometry": null}),
        json!({"type": "Feature", "id": 5, "geometry": point(0.0, 0.0), "extra": 1}),
    );
    assert_eq!(lines(&errors(&f.put(body).await)), [3, 4, 5]);
    assert_eq!(f.statuses().await, [("a".into(), CREATED)]);
}

#[tokio::test]
async fn reports_tasks_the_database_finds_invalid_and_writes_nothing() {
    let f = Fixture::new().await;
    f.ok(&[feature("a")]).await;
    let mut bowtie = feature("bowtie");
    bowtie["geometry"] =
        json!({"type": "Polygon", "coordinates": [[[0, 0], [1, 1], [1, 0], [0, 1], [0, 0]]]});
    let res = f
        .upload(&[feature("way/1"), feature("WAY/1"), bowtie])
        .await;
    let at: Vec<Value> = errors(&res)
        .iter()
        .map(|e| json!([e["line"], e["key"], e["pointer"]]))
        .collect();
    let expected = [
        json!([2, "WAY/1", "/id"]),
        json!([3, "bowtie", "/geometry"]),
    ];
    assert_eq!(at, expected);
    assert_eq!(f.statuses().await, [("a".into(), CREATED)]);
}

#[tokio::test]
async fn reads_the_rest_of_a_rejected_body() {
    // A proxy in front of the service can only relay the error once the whole body is read.
    let f = Fixture::new().await;
    // The first chunk is rejected, so the service answers while most of the body is unsent.
    let chunks: Vec<String> = std::iter::once("{}\n".repeat(150))
        .chain(std::iter::repeat_n(
            ndjson(&[feature("a")]).repeat(1000),
            50,
        ))
        .collect();
    let total = chunks.len();
    let sent = Arc::new(AtomicUsize::new(0));
    let stream = futures_util::stream::iter(chunks).map({
        let sent = sent.clone();
        move |chunk| {
            sent.fetch_add(1, Ordering::Relaxed);
            Ok::<_, std::io::Error>(chunk)
        }
    });
    let res = f.put(reqwest::Body::wrap_stream(stream)).await;
    assert_eq!(errors(&res).len(), 100, "at most 100 errors are listed");
    for _ in 0..500 {
        if sent.load(Ordering::Relaxed) == total {
            return;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    panic!(
        "only {} of {total} chunks were read",
        sent.load(Ordering::Relaxed)
    );
}

#[tokio::test]
async fn checks_the_content_type_and_encoding() {
    let f = Fixture::new().await;
    let body = ndjson(&[feature("a")]).into_bytes();
    let bom = [b"\xef\xbb\xbf".as_slice(), &body].concat();
    let (ndjson, gz) = ("application/x-ndjson", Some("gzip"));
    #[rustfmt::skip]
    let cases = [
        ("application/json", None, body.clone(), StatusCode::UNSUPPORTED_MEDIA_TYPE),
        (ndjson, Some("br"), body.clone(), StatusCode::UNSUPPORTED_MEDIA_TYPE),
        (ndjson, gz, b"not gzip".to_vec(), StatusCode::BAD_REQUEST),
        (ndjson, gz, gzip(&body), StatusCode::OK),
        ("application/x-ndjson; charset=utf-8", None, body.clone(), StatusCode::OK),
        ("application/jsonl", None, body.clone(), StatusCode::OK),
        (ndjson, None, bom, StatusCode::OK),
    ];
    for (content_type, encoding, body, status) in cases {
        let mut req = f.request(content_type).bearer_auth(f.token.as_str());
        if let Some(encoding) = encoding {
            req = req.header("content-encoding", encoding);
        }
        let res = send(req.body(body)).await;
        assert_eq!(
            res.status,
            status,
            "{content_type} {encoding:?}: {}",
            res.json()
        );
    }
}

#[tokio::test]
async fn enforces_upload_limits() {
    let f = Fixture::with_limits(UploadLimits {
        max_features: 2,
        max_line_bytes: 300,
        max_bytes: 2000,
        ..UploadLimits::default()
    })
    .await;
    let res = f.upload(&[feature("a"), feature("b"), feature("c")]).await;
    assert_eq!(res.status, StatusCode::PAYLOAD_TOO_LARGE);

    let mut long = feature("long");
    long["properties"]["pad"] = json!("x".repeat(300));
    let found = errors(&f.upload(&[feature("a"), long]).await);
    assert_eq!(lines(&found), [2]);

    // The size limit applies after decompression.
    let blank_lines = format!("{}\n", " ".repeat(250)).repeat(10);
    let req = f
        .request("application/x-ndjson")
        .bearer_auth(f.token.as_str())
        .header("content-encoding", "gzip");
    let res = send(req.body(gzip(blank_lines.as_bytes()))).await;
    assert_eq!(res.status, StatusCode::PAYLOAD_TOO_LARGE);
}

#[tokio::test]
async fn accepts_a_body_larger_than_axums_default_limit() {
    let f = Fixture::new().await;
    let features: Vec<Value> = (0..50)
        .map(|i| {
            let mut f = feature(&i.to_string());
            f["properties"]["pad"] = json!("x".repeat(60_000));
            f
        })
        .collect();
    assert!(ndjson(&features).len() > 2 * 1024 * 1024);
    assert_eq!(f.ok(&features).await, counts(50, 0, 0, 0, 0, 50));
}

#[tokio::test]
async fn times_out_a_stalled_body() {
    let f = Fixture::with_limits(UploadLimits {
        idle_timeout: Duration::from_millis(300),
        ..UploadLimits::default()
    })
    .await;
    let first = ndjson(&[feature("a")]);
    let stream = futures_util::stream::once(async { Ok::<_, std::io::Error>(first) })
        .chain(futures_util::stream::pending());
    let res = f.put(reqwest::Body::wrap_stream(stream)).await;
    assert_eq!(res.status, StatusCode::REQUEST_TIMEOUT);
}

#[tokio::test]
async fn authenticates_and_authorizes() {
    // The policy and role lookup have their own tests. This checks that the route uses them.
    let f = Fixture::new().await;
    let body = ndjson(&[feature("a")]);
    let req = || f.request("application/x-ndjson").body(body.clone());

    assert_eq!(send(req()).await.status, StatusCode::UNAUTHORIZED);

    let (token, _) = f.app.credential(&f.user, "pat", &["tasks:map"]).await;
    let res = send(req().bearer_auth(token.as_str())).await;
    assert_eq!(res.status, StatusCode::FORBIDDEN);
    assert_eq!(
        res.header("www-authenticate"),
        r#"Bearer error="insufficient_scope", scope="challenges:write""#
    );

    let missing = f
        .app
        .request(Method::PUT, "/api/v3/challenges/999999/tasks")
        .bearer_auth(f.token.as_str())
        .header("content-type", "application/x-ndjson");
    assert_eq!(
        send(missing.body(body.clone())).await.status,
        StatusCode::NOT_FOUND
    );
    assert!(f.statuses().await.is_empty());

    let cookie = format!("PLAY_SESSION={}", f.user.session_cookie(now_ms()));
    let res = send(req().header("cookie", cookie).header("origin", ORIGIN)).await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.json());
    assert_eq!(res.header("x-auth"), format!("session:user/{}", f.user.id));
}

#[tokio::test]
async fn asks_for_a_retry_after_waiting_too_long_for_a_lock() {
    let f = Fixture::with(|config| config.database_lock_timeout = Duration::from_millis(200)).await;
    // Uploads to a challenge take this advisory lock; by taking it manually
    // we are simulating a concurrent uplaod
    let mut other = f.connection().await;
    sqlx::query!("SELECT pg_advisory_lock(1, $1)", f.challenge.0)
        .execute(&mut other)
        .await
        .unwrap();
    let res = f.upload(&[feature("a")]).await;
    assert_eq!(
        res.status,
        StatusCode::SERVICE_UNAVAILABLE,
        "{}",
        res.json()
    );
    assert_eq!(res.header("retry-after"), "5");
    assert!(f.statuses().await.is_empty());
}

#[tokio::test]
async fn waits_for_a_mapper_holding_a_task() {
    let f = Fixture::new().await;
    f.ok(&[feature("a"), feature("b")]).await;
    // A mapper's status change locks the task row, then the challenge row (via trigger).
    // This should prevent the upload from runnning until the task update commits.
    let mut mapper = f.connection().await;
    let mut tx = mapper.begin().await.unwrap();
    sqlx::query!(
        "UPDATE tasks SET status = $2 WHERE parent_id = $1 AND name = 'b'",
        f.challenge.0,
        FIXED,
    )
    .execute(&mut *tx)
    .await
    .unwrap();
    let req = f.request("application/x-ndjson");
    let upload = tokio::spawn(send(
        req.bearer_auth(f.token.as_str())
            .body(ndjson(&[feature("a")])),
    ));
    f.lock_wait().await;
    tx.commit().await.unwrap();
    let res = upload.await.unwrap();
    // b was done by the time the upload could lock it, so it isn't closed.
    assert_eq!(res.json(), counts(0, 0, 1, 0, 0, 1));
    assert_eq!(
        f.statuses().await,
        [("a".into(), CREATED), ("b".into(), FIXED)]
    );
}

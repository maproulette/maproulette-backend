mod database;
mod fixtures;
pub mod play;

use axum::body::Bytes;
use axum::http::{HeaderMap, Method, StatusCode};
use serde_json::Value;
use sqlx::PgPool;
use tokio::net::TcpListener;

use maproulette::config::{Config, UploadLimits};
use maproulette::db;

pub const SECRET: &str = "test-secret-at-least-thirty-two-bytes-long";

pub const ORIGIN: &str = "https://maproulette.test";

pub struct TestApp {
    base_url: String,
    client: reqwest::Client,
    pub db: PgPool,
}

impl TestApp {
    /// Starts the service with a new database of its own. Needs `TEST_DATABASE_URL`.
    pub async fn spawn() -> TestApp {
        TestApp::start(database::create().await).await
    }

    /// Starts the service with a database URL that refuses connections.
    pub async fn without_db() -> TestApp {
        TestApp::start("postgres://nobody@127.0.0.1:1/nothing".into()).await
    }

    async fn start(database_url: String) -> TestApp {
        let config = Config {
            database_url,
            database_max_connections: 10,
            listen_addr: "127.0.0.1:0".parse().unwrap(),
            session_secret: SECRET.into(),
            allowed_origins: vec![ORIGIN.parse().unwrap()],
            uploads: UploadLimits::default(),
        };
        let listener = TcpListener::bind(config.listen_addr).await.unwrap();
        let base_url = format!("http://{}", listener.local_addr().unwrap());
        let app = maproulette::app(&config).unwrap();
        tokio::spawn(maproulette::serve(listener, app, std::future::pending()));
        TestApp {
            base_url,
            client: reqwest::Client::builder().no_proxy().build().unwrap(),
            db: db::connect(&config.database_url, 2).unwrap(),
        }
    }

    /// Builds a request to `path` (which starts with `/api/v3`), to pass to `send`.
    pub fn request(&self, method: Method, path: &str) -> reqwest::RequestBuilder {
        self.client
            .request(method, format!("{}{path}", self.base_url))
    }

    pub async fn get(&self, path: &str) -> TestResponse {
        send(self.request(Method::GET, path)).await
    }
}

pub struct TestResponse {
    pub status: StatusCode,
    pub headers: HeaderMap,
    pub body: Bytes,
}

impl TestResponse {
    pub fn json(&self) -> Value {
        serde_json::from_slice(&self.body).unwrap()
    }

    pub fn header(&self, name: &str) -> &str {
        self.headers
            .get(name)
            .unwrap_or_else(|| panic!("no {name} header"))
            .to_str()
            .unwrap()
    }
}

/// Sends a request and reads the response. Panics if the response breaks a rule
/// that every endpoint should follow (so tests all get those checks for free).
pub async fn send(req: reqwest::RequestBuilder) -> TestResponse {
    let res = req.send().await.unwrap();
    let res = TestResponse {
        status: res.status(),
        headers: res.headers().clone(),
        body: res.bytes().await.unwrap(),
    };
    check(&res);
    res
}

fn check(res: &TestResponse) {
    let request_id = res.header("x-request-id");
    assert!(!request_id.is_empty(), "empty x-request-id");
    let route = res.header("x-route");
    // the health probe endpoints are special, and return their own JSON response shapes
    // for HTTP errors (not problem JSON like the rest of the application)
    const PROBES: [&str; 3] = [
        "/api/v3/health",
        "/api/v3/health/live",
        "/api/v3/health/ready",
    ];
    let probe_failure = PROBES.contains(&route) && res.status == StatusCode::SERVICE_UNAVAILABLE;
    if (res.status.is_client_error() || res.status.is_server_error()) && !probe_failure {
        assert_eq!(
            res.header("content-type"),
            "application/problem+json",
            "{} from {route} isn't a problem document",
            res.status
        );
        let problem = res.json();
        assert_eq!(problem["status"], res.status.as_u16(), "{problem}");
        assert_eq!(problem["request_id"], request_id, "{problem}");
        assert!(problem["title"].is_string(), "{problem}");
    }
}

use std::env;
use std::net::SocketAddr;
use std::time::Duration;

use anyhow::Context;
use axum::http::HeaderValue;

pub struct Config {
    pub database_url: String,
    pub database_max_connections: u32,
    /// How long a statement waits for a lock before failing (and the request gets a 503).
    pub database_lock_timeout: Duration,
    pub listen_addr: SocketAddr,
    /// The key that signs v2's session cookies (Play's `play.http.secret.key`).
    pub session_secret: String,
    /// MapRoulette's own frontends, as origins (`https://maproulette.org`). Only these may make
    /// credentialed cross-origin requests, or change state with the session cookie.
    pub allowed_origins: Vec<HeaderValue>,
    pub uploads: UploadLimits,
}

/// Limits on task uploads (`PUT /challenges/:id/tasks`). Maybe these should be configurable
/// via env vars, but they aren't right now (however test code does lower them).
#[derive(Clone, Debug)]
pub struct UploadLimits {
    /// Maximum request body size in bytes, after decompression.
    pub max_bytes: u64,
    /// Maximum number of features in the body, and consequently tasks in the challenge.
    pub max_features: u64,
    /// Maximum length of one line (one GeoJSON feature) in bytes.
    pub max_line_bytes: usize,
    /// How long the client may go without sending anything.
    pub idle_timeout: Duration,
    /// How long the client may take to send the whole body.
    pub receive_timeout: Duration,
    /// How many bodies this process receives at once. Further uploads get a 503.
    pub receiving: usize,
    /// How many uploads this process applies to the database at once. Further uploads wait.
    pub applying: usize,
}

impl Default for UploadLimits {
    fn default() -> UploadLimits {
        UploadLimits {
            max_bytes: 100 * 1024 * 1024,
            max_features: 100_000,
            max_line_bytes: 1024 * 1024,
            idle_timeout: Duration::from_secs(30),
            receive_timeout: Duration::from_secs(10 * 60),
            receiving: 8,
            applying: 2,
        }
    }
}

impl Config {
    pub fn from_env() -> anyhow::Result<Self> {
        let database_url = env::var("DATABASE_URL").context("DATABASE_URL must be set")?;
        let database_max_connections = env::var("DATABASE_MAX_CONNECTIONS")
            .unwrap_or_else(|_| "10".into())
            .parse()
            .context("DATABASE_MAX_CONNECTIONS must be a positive integer")?;
        let database_lock_timeout = env::var("DATABASE_LOCK_TIMEOUT_MS")
            .unwrap_or_else(|_| "5000".into())
            .parse()
            .map(Duration::from_millis)
            .context("DATABASE_LOCK_TIMEOUT_MS must be a non-negative integer")?;
        let listen_addr = env::var("LISTEN_ADDR")
            .unwrap_or_else(|_| "127.0.0.1:9001".into())
            .parse()
            .context("LISTEN_ADDR must be a socket address, like 127.0.0.1:9001")?;
        let session_secret =
            env::var("APPLICATION_SECRET").context("APPLICATION_SECRET must be set")?;
        anyhow::ensure!(
            session_secret.len() >= 32,
            "APPLICATION_SECRET must be at least 32 bytes"
        );
        let allowed_origins = env::var("ALLOWED_ORIGINS")
            .context("ALLOWED_ORIGINS must be set")?
            .split(',')
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(origin)
            .collect::<anyhow::Result<_>>()?;
        Ok(Config {
            database_url,
            database_max_connections,
            database_lock_timeout,
            listen_addr,
            session_secret,
            allowed_origins,
            uploads: UploadLimits::default(),
        })
    }
}

fn origin(s: &str) -> anyhow::Result<HeaderValue> {
    let host = s
        .strip_prefix("https://")
        .or_else(|| s.strip_prefix("http://"))
        .filter(|host| !host.is_empty() && !host.contains('/'));
    anyhow::ensure!(
        host.is_some(),
        "ALLOWED_ORIGINS entries must be origins like https://maproulette.org, not {s:?}"
    );
    Ok(HeaderValue::try_from(s)?)
}

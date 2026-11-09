use std::env;
use std::net::SocketAddr;

use anyhow::Context;
use axum::http::HeaderValue;

pub struct Config {
    pub database_url: String,
    pub database_max_connections: u32,
    pub listen_addr: SocketAddr,
    /// The key that signs v2's session cookies (Play's `play.http.secret.key`).
    pub session_secret: String,
    /// MapRoulette's own frontends, as origins (`https://maproulette.org`). Only these may make
    /// credentialed cross-origin requests, or change state with the session cookie.
    pub allowed_origins: Vec<HeaderValue>,
}

impl Config {
    pub fn from_env() -> anyhow::Result<Self> {
        let database_url = env::var("DATABASE_URL").context("DATABASE_URL must be set")?;
        let database_max_connections = env::var("DATABASE_MAX_CONNECTIONS")
            .unwrap_or_else(|_| "10".into())
            .parse()
            .context("DATABASE_MAX_CONNECTIONS must be a positive integer")?;
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
            listen_addr,
            session_secret,
            allowed_origins,
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

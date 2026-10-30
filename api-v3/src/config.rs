use std::env;
use std::net::SocketAddr;

use anyhow::Context;

pub struct Config {
    pub database_url: String,
    pub database_max_connections: u32,
    pub listen_addr: SocketAddr,
    /// The key that signs v2's session cookies (Play's `play.http.secret.key`).
    pub session_secret: String,
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
        Ok(Config {
            database_url,
            database_max_connections,
            listen_addr,
            session_secret,
        })
    }
}

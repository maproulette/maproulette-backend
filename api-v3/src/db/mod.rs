pub mod authz;
pub mod credentials;
pub mod users;

use std::str::FromStr;
use std::time::Duration;

use sqlx::PgPool;
use sqlx::postgres::{PgConnectOptions, PgPoolOptions};

/// A pool that opens connections as they're needed, configured by `options`.
pub fn connect(url: &str, max_connections: u32) -> Result<PgPool, sqlx::Error> {
    Ok(PgPoolOptions::new()
        .max_connections(max_connections)
        .acquire_timeout(Duration::from_secs(5))
        .connect_lazy_with(options(url)?))
}

/// Connection options: the URL, plus the timeouts every session gets.
pub fn options(url: &str) -> Result<PgConnectOptions, sqlx::Error> {
    Ok(PgConnectOptions::from_str(url)?
        .application_name("maproulette-backend")
        .options([
            ("statement_timeout", "25s"),
            ("lock_timeout", "5s"),
            ("idle_in_transaction_session_timeout", "60s"),
        ]))
}

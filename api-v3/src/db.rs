use std::str::FromStr;
use std::time::Duration;

use sqlx::PgPool;
use sqlx::postgres::{PgConnectOptions, PgPoolOptions};

pub async fn connect(url: &str, max_connections: u32) -> Result<PgPool, sqlx::Error> {
    PgPoolOptions::new()
        .max_connections(max_connections)
        .acquire_timeout(Duration::from_secs(5))
        .connect_with(options(url)?)
        .await
}

pub fn options(url: &str) -> Result<PgConnectOptions, sqlx::Error> {
    Ok(PgConnectOptions::from_str(url)?
        .application_name("maproulette-backend")
        .options([
            ("statement_timeout", "25s"),
            ("lock_timeout", "5s"),
            ("idle_in_transaction_session_timeout", "60s"),
        ]))
}

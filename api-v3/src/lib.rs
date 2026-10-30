pub mod auth;
pub mod authz;
pub mod config;
pub mod db;
pub mod error;
pub mod model;
mod routes;

pub use routes::{openapi, router};

use std::sync::Arc;
use std::time::Duration;

use axum::Router;
use tokio::net::TcpListener;
use tokio::sync::Notify;

use config::Config;

#[derive(Clone)]
pub struct AppState {
    pub db: sqlx::PgPool,
    /// The key that signs v2's session cookies.
    pub session_secret: Arc<[u8]>,
}

/// How long in-flight requests get to finish after shutdown begins.
const DRAIN_TIMEOUT: Duration = Duration::from_secs(5);

pub fn app(config: &Config) -> Result<Router, sqlx::Error> {
    let db = db::connect(&config.database_url, config.database_max_connections)?;
    Ok(router(AppState {
        db,
        session_secret: config.session_secret.as_bytes().into(),
    }))
}

pub async fn serve(
    listener: TcpListener,
    app: Router,
    shutdown: impl Future<Output = ()> + Send + 'static,
) -> anyhow::Result<()> {
    let stopping = Arc::new(Notify::new());
    let server = axum::serve(listener, app).with_graceful_shutdown({
        let stopping = stopping.clone();
        async move {
            shutdown.await;
            stopping.notify_one();
        }
    });
    tokio::select! {
        res = server.into_future() => res?,
        _ = async {
            stopping.notified().await;
            tokio::time::sleep(DRAIN_TIMEOUT).await;
        } => anyhow::bail!("requests still in flight {DRAIN_TIMEOUT:?} after shutdown began"),
    }
    Ok(())
}

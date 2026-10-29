use std::sync::Arc;
use std::time::Duration;

use tokio::net::TcpListener;
use tokio::signal::unix::{SignalKind, signal};
use tokio::sync::Notify;
use tracing_subscriber::EnvFilter;
use tracing_subscriber::filter::LevelFilter;

use maproulette::config::Config;
use maproulette::{AppState, db, router};

/// How long in-flight requests get to finish after a shutdown signal.
const DRAIN_TIMEOUT: Duration = Duration::from_secs(5);

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let filter = EnvFilter::builder()
        .with_default_directive(LevelFilter::INFO.into())
        .from_env()?;
    tracing_subscriber::fmt()
        .json()
        .with_span_list(false)
        .with_env_filter(filter)
        .init();

    let config = Config::from_env()?;
    let db = db::connect(&config.database_url, config.database_max_connections).await?;
    let app = router(AppState { db });

    let listener = TcpListener::bind(config.listen_addr).await?;
    tracing::info!(addr = %config.listen_addr, "listening");

    let stopping = Arc::new(Notify::new());
    let server = axum::serve(listener, app).with_graceful_shutdown({
        let stopping = stopping.clone();
        async move {
            shutdown_signal().await;
            stopping.notify_one();
        }
    });
    tokio::select! {
        res = server.into_future() => res?,
        _ = async {
            stopping.notified().await;
            tokio::time::sleep(DRAIN_TIMEOUT).await;
        } => anyhow::bail!("requests still in flight {DRAIN_TIMEOUT:?} after shutdown signal"),
    }
    Ok(())
}

async fn shutdown_signal() {
    let mut term =
        signal(SignalKind::terminate()).expect("SIGTERM handler should be installed successfully");
    tokio::select! {
        _ = term.recv() => {}
        _ = tokio::signal::ctrl_c() => {}
    }
    tracing::info!("shutting down");
}

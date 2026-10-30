use tokio::net::TcpListener;
use tokio::signal::unix::{SignalKind, signal};
use tracing_subscriber::EnvFilter;
use tracing_subscriber::filter::LevelFilter;

use maproulette::config::Config;

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
    let app = maproulette::app(&config)?;
    let listener = TcpListener::bind(config.listen_addr).await?;
    tracing::info!(addr = %config.listen_addr, "listening");
    maproulette::serve(listener, app, shutdown_signal()).await
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

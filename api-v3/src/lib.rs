pub mod config;
pub mod db;
pub mod error;
mod routes;

pub use routes::{openapi, router};

#[derive(Clone)]
pub struct AppState {
    pub db: sqlx::PgPool,
}

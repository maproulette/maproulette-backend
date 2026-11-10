//! Integration tests. Each test starts the service on a local port and talks to it over HTTP.

mod utils;

mod auth;
mod cors;
mod db;
mod health;
mod routing;
mod tokens;

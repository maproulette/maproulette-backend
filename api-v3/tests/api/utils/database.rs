//! Each test gets its own database, cloned from a template that has `schema.sql` loaded. The
//! template is named after a hash of `schema.sql`, so editing the schema makes a new one.

use std::env;
use std::str::FromStr;
use std::sync::atomic::{AtomicUsize, Ordering};

use sha2::{Digest, Sha256};
use sqlx::postgres::PgConnectOptions;
use sqlx::{AssertSqlSafe, ConnectOptions, Connection, Executor, PgConnection, raw_sql};
use tokio::sync::OnceCell;

const SCHEMA: &str = include_str!("../../../schema.sql");

static TEMPLATE: OnceCell<String> = OnceCell::const_new();

static NEXT_ID: AtomicUsize = AtomicUsize::new(0);

/// Creates a database from the template and returns its URL.
///
/// Databases aren't dropped when the test finishes, so you can look at what a failed test left
/// behind. The next test run drops them.
pub async fn create() -> String {
    let url = env::var("TEST_DATABASE_URL").expect(
        "TEST_DATABASE_URL must name a database on a PostGIS server, \
         e.g. postgres://osm:osm@localhost:5432/postgres (see README.md)",
    );
    let server = PgConnectOptions::from_str(&url).unwrap();
    let mut admin = PgConnection::connect_with(&server).await.unwrap();
    let template = TEMPLATE
        .get_or_init(async || setup(&mut admin, &server).await)
        .await;
    let name = format!("mr_test_{}", NEXT_ID.fetch_add(1, Ordering::Relaxed));
    execute(
        &mut admin,
        format!(r#"CREATE DATABASE "{name}" TEMPLATE "{template}""#),
    )
    .await;
    server.database(&name).to_url_lossy().to_string()
}

/// Drops the databases that earlier runs left behind, builds the template if it doesn't exist
/// yet, and returns its name. Assumes no other test run is using the server at the same time.
async fn setup(admin: &mut PgConnection, server: &PgConnectOptions) -> String {
    let hash = Sha256::digest(SCHEMA.as_bytes());
    let hash: String = hash[..8].iter().map(|b| format!("{b:02x}")).collect();
    let template = format!("mr_test_template_{hash}");

    let stale: Vec<String> = sqlx::query_scalar(
        "SELECT datname FROM pg_database WHERE datname LIKE 'mr\\_test\\_%' AND datname <> $1",
    )
    .bind(&template)
    .fetch_all(&mut *admin)
    .await
    .unwrap();
    for name in stale {
        execute(admin, format!(r#"DROP DATABASE "{name}" WITH (FORCE)"#)).await;
    }

    let exists: bool =
        sqlx::query_scalar("SELECT EXISTS (SELECT FROM pg_database WHERE datname = $1)")
            .bind(&template)
            .fetch_one(&mut *admin)
            .await
            .unwrap();
    if !exists {
        let building = format!("{template}_building");
        execute(admin, format!(r#"CREATE DATABASE "{building}""#)).await;
        let mut conn = PgConnection::connect_with(&server.clone().database(&building))
            .await
            .unwrap();
        conn.execute(raw_sql(SCHEMA))
            .await
            .expect("schema.sql should load");
        conn.close().await.unwrap();
        execute(
            admin,
            format!(r#"ALTER DATABASE "{building}" RENAME TO "{template}""#),
        )
        .await;
    }

    template
}

async fn execute(conn: &mut PgConnection, sql: String) {
    conn.execute(raw_sql(AssertSqlSafe(sql))).await.unwrap();
}

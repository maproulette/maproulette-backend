//! Functions for staging a task upload and applying it to a challenge's tasks.
//!
//! The upload is first written to a `Spool` (a temp file in Postgres `COPY`
//! text format) while the body arrives (this avoids holding a db connection
//! during the upload). Once the completed upload has been validated and written
//! to the spool, the functions below are then run in a single transaction:
//! `stage` copies the spool into temp tables, `invalid_tasks` checks
//! for invalid geometries and other validation errors, `lock_tasks` locks all
//! task rows that the apply might change, and `update`, `close` and `insert`
//! apply it.
//!
//! The temp tables don't exist at compile time, so these queries can't be
//! checked by sqlx's macros.

use std::io::{self, SeekFrom};

use serde_json::{Map, Value};
use sqlx::{PgConnection, raw_sql};
use tokio::fs::File;
use tokio::io::{AsyncSeekExt, AsyncWriteExt, BufWriter};

use crate::model::{ChallengeId, InvalidTask, NewTask};

/// Tasks parsed from an upload, waiting to be staged.
pub struct Spool {
    file: BufWriter<File>,
    row: String,
}

impl Spool {
    /// Create a new spool in an anonymous temp file, which is deleted when the spool is dropped.
    pub fn new() -> io::Result<Spool> {
        Ok(Spool {
            // tokio's `File` runs every write on the blocking pool, so write in large pieces.
            file: BufWriter::with_capacity(1024 * 1024, File::from_std(tempfile::tempfile()?)),
            row: String::new(),
        })
    }

    pub async fn push(&mut self, task: NewTask) -> io::Result<()> {
        self.row.clear();
        copy_field(&mut self.row, &task.line.to_string());
        self.row.push('\t');
        copy_field(&mut self.row, &task.key);
        self.row.push('\t');
        copy_field(&mut self.row, &stored_geojson(task).to_string());
        self.row.push('\n');
        self.file.write_all(self.row.as_bytes()).await
    }
}

/// Prepare a `tasks.geojson` document for a task, to store in the database.
/// API v2 writes each task as a FeatureCollection with (in most cases) a
/// single Feature inside it; we match this behavior for now.
fn stored_geojson(task: NewTask) -> Value {
    // we aren't using the `json!` macro here because it would require an
    // unnecessary clone of the geometry and properties.
    let feature = Map::from_iter([
        ("id".to_owned(), Value::String(task.key)),
        ("type".to_owned(), "Feature".into()),
        ("geometry".to_owned(), task.geometry),
        ("properties".to_owned(), Value::Object(task.properties)),
    ]);
    Value::Object(Map::from_iter([
        ("type".to_owned(), "FeatureCollection".into()),
        (
            "features".to_owned(),
            Value::Array(vec![Value::Object(feature)]),
        ),
    ]))
}

/// Appends a value to a row in `COPY` text format (with proper escaping)
fn copy_field(row: &mut String, value: &str) {
    for c in value.chars() {
        match c {
            '\\' => row.push_str("\\\\"),
            '\t' => row.push_str("\\t"),
            '\n' => row.push_str("\\n"),
            '\r' => row.push_str("\\r"),
            c => row.push(c),
        }
    }
}

/// Gives the transaction's statements longer timeouts than the session default.
pub async fn raise_statement_timeout(conn: &mut PgConnection) -> Result<(), sqlx::Error> {
    raw_sql("SET LOCAL statement_timeout = '120s'")
        .execute(conn)
        .await?;
    Ok(())
}

/// Copies the spool into `upload_staged`, computing each task's geometry, and returns
/// how many tasks it holds. The temp tables are dropped when the transaction ends.
pub async fn stage(conn: &mut PgConnection, spool: Spool) -> Result<u64, sqlx::Error> {
    let mut writer = spool.file;
    writer.flush().await?;
    let mut file = writer.into_inner();
    file.seek(SeekFrom::Start(0)).await?;
    raw_sql(
        "CREATE TEMP TABLE upload_rows (
            line integer NOT NULL,
            key text NOT NULL,
            geojson jsonb NOT NULL
        ) ON COMMIT DROP",
    )
    .execute(&mut *conn)
    .await?;
    let mut copy = conn
        .copy_in_raw("COPY upload_rows (line, key, geojson) FROM STDIN")
        .await?;
    copy.read_from(file).await?;
    let rows = copy.finish().await?;
    raw_sql(
        "CREATE TEMP TABLE upload_staged ON COMMIT DROP AS
        SELECT line, key, lower(key) AS lower_key, geojson,
               ST_Force2D(ST_SetSRID(ST_GeomFromGeoJSON(geojson->'features'->0->'geometry'), 4326))
                   AS geom
        FROM upload_rows;
        DROP TABLE upload_rows;
        CREATE INDEX ON upload_staged (lower_key);
        ANALYZE upload_staged",
    )
    .execute(&mut *conn)
    .await?;
    Ok(rows)
}

/// Staged tasks that can't be stored, at most `limit` of them, in line order: duplicate keys
/// (compared as the unique index on `tasks` compares them) and invalid geometry.
pub async fn invalid_tasks(
    conn: &mut PgConnection,
    limit: usize,
) -> Result<Vec<InvalidTask>, sqlx::Error> {
    let rows: Vec<(i32, String, String, String)> = sqlx::query_as(
        "SELECT line, key, pointer, message FROM (
            SELECT s.line, s.key, '/id' AS pointer,
                   'line ' || d.first || ' has the same id (ids are compared ignoring case)'
                       AS message
            FROM upload_staged s
            JOIN (
                SELECT lower_key, min(line) AS first FROM upload_staged
                GROUP BY lower_key HAVING count(*) > 1
            ) d USING (lower_key)
            WHERE s.line <> d.first
            UNION ALL
            SELECT s.line, s.key, '/geometry',
                   'is invalid: ' || v.reason || coalesce(' at ' || ST_AsText(v.location), '')
            FROM upload_staged s
            CROSS JOIN LATERAL ST_IsValidDetail(s.geom) v
            WHERE NOT v.valid
        ) e
        ORDER BY line
        LIMIT $1",
    )
    .persistent(false)
    .bind(limit as i64)
    .fetch_all(conn)
    .await?;
    Ok(rows
        .into_iter()
        .map(|(line, key, pointer, message)| InvalidTask {
            line: line as u64,
            key,
            pointer,
            message,
        })
        .collect())
}

/// Locks the challenge's tasks that the apply may change: those whose key is in the
/// upload, and those that are open (status 0, 3 or 6). Records them in `upload_locked`.
pub async fn lock_tasks(
    conn: &mut PgConnection,
    challenge: ChallengeId,
) -> Result<(), sqlx::Error> {
    raw_sql("CREATE TEMP TABLE upload_locked (id integer PRIMARY KEY) ON COMMIT DROP")
        .execute(&mut *conn)
        .await?;
    sqlx::query(
        "INSERT INTO upload_locked
        SELECT id FROM tasks
        WHERE parent_id = $1
          AND (lower(name) IN (SELECT lower_key FROM upload_staged) OR status IN (0, 3, 6))
        ORDER BY id
        FOR NO KEY UPDATE",
    )
    .persistent(false)
    .bind(challenge.0)
    .execute(&mut *conn)
    .await?;
    raw_sql("ANALYZE upload_locked").execute(conn).await?;
    Ok(())
}

/// What `update` changed.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Updated {
    /// Tasks that weren't closed, whose definitions were rewritten. Their statuses are unchanged.
    pub updated: u64,
    /// Closed tasks (status 4 or 9), which are open (status 0) again, with their definitions
    /// rewritten.
    pub reopened: u64,
}

/// Rewrites the definitions of tasks whose keys are in the upload, where they differ, and
/// reopens the closed ones. v3 stores no per-task instruction or cooperative work, so a task
/// that has either counts as differing.
pub async fn update(
    conn: &mut PgConnection,
    challenge: ChallengeId,
) -> Result<Updated, sqlx::Error> {
    let (updated, reopened): (i64, i64) = sqlx::query_as(
        "WITH changed AS (
            UPDATE tasks t SET
                status = CASE WHEN c.reopen THEN 0 ELSE t.status END,
                name = c.key, instruction = '', geojson = c.geojson, cooperative_work_json = NULL,
                geom = c.geom, location = ST_Centroid(c.geom)
            FROM (
                SELECT t.id, t.status IN (4, 9) AS reopen, s.key, s.geojson, s.geom
                FROM tasks t
                JOIN upload_staged s ON lower(t.name) = s.lower_key
                WHERE t.id IN (SELECT id FROM upload_locked) AND t.parent_id = $1
                  AND (t.status IN (4, 9)
                       OR t.name <> s.key
                       OR t.instruction <> ''
                       OR t.geojson IS DISTINCT FROM s.geojson
                       OR t.cooperative_work_json IS NOT NULL)
            ) c
            WHERE t.id = c.id
            RETURNING c.reopen
        )
        SELECT count(*) FILTER (WHERE NOT reopen), count(*) FILTER (WHERE reopen) FROM changed",
    )
    .persistent(false)
    .bind(challenge.0)
    .fetch_one(conn)
    .await?;
    Ok(Updated {
        updated: updated as u64,
        reopened: reopened as u64,
    })
}

/// Closes (status 9) open tasks whose keys aren't in the upload. Returns how many it closed.
pub async fn close(conn: &mut PgConnection, challenge: ChallengeId) -> Result<u64, sqlx::Error> {
    Ok(sqlx::query(
        "UPDATE tasks t SET status = 9
        WHERE t.id IN (SELECT id FROM upload_locked)
          AND t.parent_id = $1 AND t.status IN (0, 3, 6)
          AND NOT EXISTS (SELECT 1 FROM upload_staged s WHERE s.lower_key = lower(t.name))",
    )
    .persistent(false)
    .bind(challenge.0)
    .execute(conn)
    .await?
    .rows_affected())
}

/// Creates open tasks for the keys in the upload that the challenge doesn't have, at `priority`.
/// Returns how many it created. A key that v2 inserts concurrently is left to v2's row.
pub async fn insert(
    conn: &mut PgConnection,
    challenge: ChallengeId,
    priority: i32,
) -> Result<u64, sqlx::Error> {
    Ok(sqlx::query(
        "INSERT INTO tasks
            (name, parent_id, instruction, status, priority, geojson, geom, location)
        SELECT s.key, $1, '', 0, $2, s.geojson, s.geom, ST_Centroid(s.geom)
        FROM upload_staged s
        WHERE NOT EXISTS (
            SELECT 1 FROM tasks t WHERE t.parent_id = $1 AND lower(t.name) = s.lower_key
        )
        ORDER BY s.line
        ON CONFLICT (parent_id, lower(name)) DO NOTHING",
    )
    .persistent(false)
    .bind(challenge.0)
    .bind(priority)
    .execute(conn)
    .await?
    .rows_affected())
}

use sqlx::PgConnection;

use crate::db;
use crate::db::uploads::Spool;
use crate::model::{ChallengeId, InvalidTask, UploadCounts, UploadTarget};

#[derive(Debug, thiserror::Error)]
pub enum UploadError {
    /// The challenge, or its project, was deleted.
    #[error("challenge not found")]
    NotFound,
    /// The challenge is built from an Overpass query. Converting it needs its tasks renamed from
    /// v2's bare OSM ids to element keys, which isn't built yet.
    #[error("challenge is built from an Overpass query")]
    Overpass,
    /// The challenge's tasks carry cooperative work, which v3 doesn't support yet.
    #[error("challenge is cooperative")]
    Cooperative,
    /// Problems with the upload that only the database can find.
    #[error("invalid tasks")]
    Invalid(Vec<InvalidTask>),
    #[error(transparent)]
    Database(#[from] sqlx::Error),
}

/// Checks that an upload may replace the challenge's tasks. `apply` checks again,
/// but callers can use this to fail before receiving the body.
pub fn check(target: &UploadTarget) -> Result<(), UploadError> {
    if target.overpass {
        return Err(UploadError::Overpass);
    }
    if target.cooperative {
        return Err(UploadError::Cooperative);
    }
    Ok(())
}

/// Fails with the staged tasks that can't be stored, if there are any.
async fn validate(conn: &mut PgConnection, max_errors: usize) -> Result<(), UploadError> {
    let invalid = db::uploads::invalid_tasks(conn, max_errors).await?;
    if invalid.is_empty() {
        Ok(())
    } else {
        Err(UploadError::Invalid(invalid))
    }
}

/// Makes the challenge's tasks match the spooled upload, reporting at most `max_errors` invalid
/// tasks. The caller should open a transaction before calling this and commit afterwards if it
/// returns Ok(_).
///
/// Tasks are matched by key. A task in the upload is created if it's new and reopened if it's
/// closed, and its definition is rewritten if it changed. Open tasks that aren't in the upload
/// are closed. Statuses added by mappers ("fixed", "not an issue", etc) are left unchanged.
pub async fn apply(
    conn: &mut PgConnection,
    challenge: ChallengeId,
    spool: Spool,
    max_errors: usize,
) -> Result<UploadCounts, UploadError> {
    db::uploads::raise_statement_timeout(conn).await?;
    db::challenges::lock_upload(&mut *conn, challenge).await?;
    let target = db::challenges::upload_target(&mut *conn, challenge)
        .await?
        .ok_or(UploadError::NotFound)?;
    check(&target)?;
    let staged = db::uploads::stage(conn, spool).await?;
    validate(conn, max_errors).await?;
    db::uploads::lock_tasks(conn, challenge).await?;
    let db::uploads::Updated { updated, reopened } = db::uploads::update(conn, challenge).await?;
    let closed = db::uploads::close(conn, challenge).await?;
    let created = db::uploads::insert(conn, challenge, target.default_priority).await?;
    let open_tasks = db::challenges::finish_upload(&mut *conn, challenge).await?;
    Ok(UploadCounts {
        created,
        updated,
        unchanged: staged - created - updated - reopened,
        reopened,
        closed,
        open_tasks,
    })
}

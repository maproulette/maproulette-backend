//! A challenge's task set: `PUT /challenges/:id/tasks`.

use std::io;

use axum::Json;
use axum::extract::State;
use serde::Serialize;
use tokio::time::{Instant, timeout};
use utoipa::ToSchema;

use super::extract::Path;
use super::feature::{self, Invalid, NDJSON_HINT};
use super::ndjson::{self, Lines, Ndjson, ReadError};
use crate::AppState;
use crate::auth::Principal;
use crate::authz;
use crate::config::UploadLimits;
use crate::db;
use crate::db::uploads::Spool;
use crate::error::{Error, FieldError, Problem};
use crate::model::{ChallengeId, UploadCounts};
use crate::service;
use crate::service::uploads::UploadError;

/// The most validation errors one response lists.
const MAX_ERRORS: usize = 100;

/// Summary of what an upload changed. Every feature in the upload is counted
/// exactly once in `created`, `updated`, `unchanged` or `reopened`.
#[derive(Serialize, ToSchema)]
pub struct UploadSummary {
    /// Tasks created for ids the challenge didn't have.
    created: u64,
    /// Tasks whose geometry or properties changed. Their status is kept.
    updated: u64,
    /// Tasks that already matched the upload.
    unchanged: u64,
    /// Closed tasks whose ids were in the upload, which are open again.
    reopened: u64,
    /// Open tasks whose ids weren't in the upload, which are now closed.
    closed: u64,
    /// Open tasks in the challenge after the upload.
    open_tasks: u64,
}

impl From<UploadCounts> for UploadSummary {
    fn from(c: UploadCounts) -> UploadSummary {
        UploadSummary {
            created: c.created,
            updated: c.updated,
            unchanged: c.unchanged,
            reopened: c.reopened,
            closed: c.closed,
            open_tasks: c.open_tasks,
        }
    }
}

impl From<UploadError> for Error {
    fn from(e: UploadError) -> Error {
        match e {
            UploadError::NotFound => Error::NotFound,
            UploadError::Overpass => Error::Conflict(
                "This challenge is built from an Overpass query, and API v3 can't upload tasks to \
                 it yet. Rebuild it on MapRoulette, or upload to a new challenge."
                    .into(),
            ),
            UploadError::Cooperative => Error::Conflict(
                "This is a cooperative challenge, and API v3 can't upload tasks to it yet. \
                 Rebuild it on MapRoulette."
                    .into(),
            ),
            UploadError::Invalid(tasks) => Error::Validation(
                tasks
                    .into_iter()
                    .map(|t| FieldError::line(t.line, Some(t.key), Some(t.pointer), t.message))
                    .collect(),
            ),
            UploadError::Database(e) => e.into(),
        }
    }
}

/// Replaces the challenge's tasks with the uploaded set.
///
/// The body is NDJSON, with one GeoJSON Feature per line, and represents the complete set
/// of tasks the challenge should have after the operation. When replacing existing tasks,
/// new and old tasks are matched by ID:
///
/// - a new ID creates a new open task in the challenge
/// - an existing task whose definition changed gets updated. its status (open, fixed,
///   not an issue, etc) is unchanged.
/// - an open task whose ID isn't in the upload is closed
/// - a closed task whose ID _is_ in the upload is reopened
///
/// Uploads are atomic; the entire upload is either applied or rejected. Challenges built
/// from an Overpass query, and cooperative challenges, can't be updated using this endpoint
/// (yet).
#[utoipa::path(
    put,
    path = "/challenges/{id}/tasks",
    tag = "challenges",
    security(("session" = []), ("bearer" = ["challenges:write"])),
    params(("id" = ChallengeId, Path, description = "The challenge's id")),
    request_body(
        content = String,
        content_type = "application/x-ndjson",
        description = "One GeoJSON Feature per line, optionally gzip-compressed",
    ),
    responses(
        (status = 200, description = "The challenge's tasks now match the upload", body = UploadSummary),
        (status = 400, description = "The body couldn't be read", body = Problem, content_type = "application/problem+json"),
        (status = 401, description = "Not authenticated", body = Problem, content_type = "application/problem+json"),
        (status = 403, description = "Not allowed to write to the challenge", body = Problem, content_type = "application/problem+json"),
        (status = 404, description = "No such challenge", body = Problem, content_type = "application/problem+json"),
        (status = 408, description = "The body took too long to arrive", body = Problem, content_type = "application/problem+json"),
        (status = 409, description = "The challenge is of a type that doesn't support uploads yet", body = Problem, content_type = "application/problem+json"),
        (status = 413, description = "The upload is too large", body = Problem, content_type = "application/problem+json"),
        (status = 415, description = "Unsupported content type or encoding", body = Problem, content_type = "application/problem+json"),
        (status = 422, description = "Some features are invalid, as described in `errors`", body = Problem, content_type = "application/problem+json"),
        (status = 503, description = "The server is busy. Retry after `Retry-After` seconds", body = Problem, content_type = "application/problem+json"),
    ),
)]
pub async fn upload(
    State(state): State<AppState>,
    principal: Principal,
    Path(id): Path<ChallengeId>,
    body: Ndjson,
) -> Result<Json<UploadSummary>, Error> {
    let start = Instant::now();
    let uploads = &state.uploads;
    let limits = &uploads.limits;
    let lines = body.lines(limits.into())?;

    let access = db::authz::challenge_access(&state.db, principal.user_id, id).await?;
    authz::upload_tasks(&principal, access)?;
    let target = db::challenges::upload_target(&state.db, id)
        .await?
        .ok_or(Error::NotFound)?;
    service::uploads::check(&target)?;

    let _receiving = uploads
        .receiving
        .try_acquire()
        .map_err(|_| Error::Unavailable(anyhow::anyhow!("too many uploads are being received")))?;
    let received = receive(lines, limits).await?;

    let _applying = uploads.applying.acquire().await.expect("never closed");
    let mut tx = state.db.begin().await?;
    let counts = service::uploads::apply(&mut tx, id, received.spool, MAX_ERRORS).await?;
    tx.commit().await?;

    tracing::info!(
        challenge_id = %id,
        user_id = %principal.user_id,
        features = received.features,
        bytes = received.bytes,
        created = counts.created,
        updated = counts.updated,
        unchanged = counts.unchanged,
        reopened = counts.reopened,
        closed = counts.closed,
        open_tasks = counts.open_tasks,
        duration_ms = start.elapsed().as_millis() as u64,
        "uploaded tasks"
    );
    Ok(Json(counts.into()))
}

/// An uploaded NDJSON which has been validated and spooled to a temporary file
struct Received {
    /// The spool where the data is stored
    spool: Spool,
    /// The number of features in the upload
    features: u64,
    /// The size in bytes of the upload (after any decompression)
    bytes: u64,
}

/// Receives the request body and spools it to a file, within `receive_timeout`. A body that's
/// rejected partway through is read to the end by `drain::drain_on_drop`.
async fn receive(lines: Lines, limits: &UploadLimits) -> Result<Received, Error> {
    timeout(limits.receive_timeout, spool(lines, limits))
        .await
        .unwrap_or(Err(Error::RequestTimeout))
}

/// Reads every line, writing each valid feature to a spool. Errors if validation fails.
async fn spool(mut lines: Lines, limits: &UploadLimits) -> Result<Received, Error> {
    let spool_error = |e: io::Error| Error::Unexpected(anyhow::Error::new(e).context("spooling"));
    let mut spool = Spool::new().map_err(spool_error)?;
    let mut errors = Vec::new();
    let mut features = 0;
    while let Some((line, text)) = lines.next().await.map_err(read_error)? {
        if text.trim_ascii().is_empty() {
            continue;
        }
        features += 1;
        if features > limits.max_features {
            return Err(Error::PayloadTooLarge);
        }
        match feature::parse(line, text) {
            Ok(task) if errors.is_empty() => spool.push(task).await.map_err(spool_error)?,
            Ok(_) => {}
            // Most likely the body isn't NDJSON at all, so every line would fail.
            Err(Invalid::Json(e)) if features == 1 => {
                return Err(Error::Validation(vec![FieldError::line(
                    line,
                    None,
                    None,
                    format!("not valid JSON ({e}). {NDJSON_HINT}"),
                )]));
            }
            Err(Invalid::Json(e)) => errors.push(FieldError::line(
                line,
                None,
                None,
                format!("not valid JSON: {e}"),
            )),
            Err(Invalid::Feature(e)) => errors.extend(e),
        }
        if errors.len() >= MAX_ERRORS {
            break;
        }
    }
    if !errors.is_empty() {
        errors.truncate(MAX_ERRORS);
        return Err(Error::Validation(errors));
    }
    Ok(Received {
        spool,
        features,
        bytes: lines.bytes(),
    })
}

fn read_error(e: ReadError) -> Error {
    match e {
        ReadError::TooLarge => Error::PayloadTooLarge,
        ReadError::LineTooLong { line, max } => {
            let message = match line {
                // if the very first line is too long, it's likely that
                // the user was trying to upload the task data as one big
                // FeatureCollection, so add a hint.
                1 => format!("must be at most {max} bytes. {NDJSON_HINT}"),
                _ => format!("must be at most {max} bytes"),
            };
            Error::Validation(vec![FieldError::line(line, None, None, message)])
        }
        ReadError::TimedOut => Error::RequestTimeout,
        ReadError::Io(e) => Error::BadRequest(format!("The body couldn't be read: {e}")),
    }
}

impl From<&UploadLimits> for ndjson::Limits {
    fn from(limits: &UploadLimits) -> ndjson::Limits {
        ndjson::Limits {
            max_bytes: limits.max_bytes,
            max_line_bytes: limits.max_line_bytes,
            idle_timeout: limits.idle_timeout,
        }
    }
}

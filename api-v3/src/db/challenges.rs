use sqlx::PgExecutor;

use crate::model::{ChallengeId, UploadTarget};

/// We use a two-key advisory lock to serialize overlapping uploads to any
/// single challege. The key is (UPLOAD_LOCK, challenge_id).
const UPLOAD_LOCK: i32 = 1;

/// Acquires the challenge's upload lock. If the lock is already held, this will wait
/// for up to the connection's `lock_timeout` setting before returning an error. The
/// lock is automatically released at the end of the current transaction.
pub async fn lock_upload(db: impl PgExecutor<'_>, id: ChallengeId) -> Result<(), sqlx::Error> {
    sqlx::query!("SELECT pg_advisory_xact_lock($1, $2)", UPLOAD_LOCK, id.0)
        .execute(db)
        .await?;
    Ok(())
}

/// Fetches the info that the upload endpoint needs to know about a challenge before
/// it can prepare to update its tasks. Returns None if the challenge does not exist,
/// or if it or its project are marked as deleted.
pub async fn upload_target(
    db: impl PgExecutor<'_>,
    id: ChallengeId,
) -> Result<Option<UploadTarget>, sqlx::Error> {
    let row = sqlx::query!(
        r#"
        SELECT coalesce(c.default_priority, 0) AS "default_priority!",
               coalesce(trim(c.overpass_ql), '') <> '' AS "overpass!",
               coalesce(c.cooperative_type, 0) <> 0 AS "cooperative!"
        FROM challenges c
        JOIN projects p ON p.id = c.parent_id
        WHERE c.id = $1 AND NOT coalesce(c.deleted, false) AND NOT coalesce(p.deleted, false)
        "#,
        id.0
    )
    .fetch_optional(db)
    .await?;
    Ok(row.map(|r| UploadTarget {
        default_priority: r.default_priority,
        overpass: r.overpass,
        cooperative: r.cooperative,
    }))
}

/// Completes a challenge task upload (updating its mtime, bbox, status, etc)
/// and returns a count of how many tasks are now open in the challenge.
pub async fn finish_upload(db: impl PgExecutor<'_>, id: ChallengeId) -> Result<u64, sqlx::Error> {
    let open = sqlx::query_scalar!(
        r#"
        WITH open AS (
            SELECT count(*) AS n FROM tasks WHERE parent_id = $1 AND status IN (0, 3, 6)
        )
        UPDATE challenges c SET
            last_task_refresh = now(),
            data_origin_date = now(),
            -- clear any stored Overpass QL query or remote GeoJSON URL; turning
            -- this challenge into what v2 calls a "local geojson challenge"
            -- (one created by direct upload)
            overpass_ql = NULL,
            remote_geo_json = NULL,
            -- this matches v2's logic in ChallengeDAL.updateBoundingBox
            bounding = coalesce(
                (
                    SELECT ST_Envelope(
                        ST_Buffer((ST_SetSRID(ST_Extent(location), 4326))::geography, 2)::geometry
                    )
                    FROM tasks
                    WHERE parent_id = $1 AND location IS NOT NULL
                ),
                c.bounding
            ),
            --- 3 is "ready", 5 is "finished"
            status = CASE WHEN open.n > 0 THEN 3 ELSE 5 END,
            status_message = NULL
        FROM open
        WHERE c.id = $1
        RETURNING open.n AS "open!"
        "#,
        id.0,
    )
    .fetch_one(db)
    .await?;
    Ok(open as u64)
}

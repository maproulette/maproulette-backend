-- =============================================================================
-- One-shot data fix for tasks whose location column holds non-WGS84 coordinates.
--
-- Background: a number of challenges were ingested with EPSG:3857 (Web Mercator
-- meters) written directly into tasks.location, which is declared as 4326. A
-- smaller set were ingested with lat/lng axes swapped, and a handful are pure
-- garbage. Running ST_Transform(t.location, 3857) over these rows during the
-- tile-aggregate rebuild raises proj error 2049 ("Invalid coordinate") and
-- aborts the entire rebuild loop.
--
-- This script:
--   1. Reprojects tasks in challenges identified as 3857-in-4326.
--   2. Flips coordinates in challenges identified as having swapped axes.
--   3. Nulls location on rows with unrecoverable garbage values.
--   4. Refreshes challenges.location (centroid) and challenges.bounding for
--      every touched challenge so downstream summaries match the corrected
--      task points.
--
-- Categories B (unknown local CRS — Brazilian/Ghanaian/Austrian/UTM-like) and
-- D (real WGS84 points past the Web Mercator ±85.05° limit) are intentionally
-- left untouched and require per-challenge investigation.
--
-- Run inside a single transaction so failure during any step rolls back the
-- whole batch. Spot-check the verification queries at the end before COMMIT.
-- =============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- Snapshot affected rows before mutating, in case manual inspection is needed
-- after the fact. The temp table dies with the session; copy it elsewhere if
-- you want a durable record.
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE bad_task_location_snapshot AS
SELECT t.id            AS task_id,
       t.parent_id     AS challenge_id,
       ST_AsText(t.location) AS old_location_wkt,
       NOW()           AS captured_at
FROM tasks t
WHERE t.location IS NOT NULL
  AND NOT ST_IsEmpty(t.location)
  AND (ST_Y(t.location) NOT BETWEEN -85.05112878 AND 85.05112878
       OR ST_X(t.location) NOT BETWEEN -180 AND 180);

-- ---------------------------------------------------------------------------
-- Category A: reproject 3857-in-4326 tasks.
--
-- The WHERE clause re-checks coordinate range to guard against running this
-- script twice and double-transforming rows that have already been fixed.
-- Only rows whose X is outside [-180,180] are touched.
-- ---------------------------------------------------------------------------
UPDATE tasks
SET location = ST_Transform(ST_SetSRID(location, 3857), 4326)
WHERE parent_id IN (
        15450,  -- Avcılar Building Update           (26762 tasks)
        25301,  -- [Middle East] Keepright           (17162)
        38427,  -- BuechnerGIS Test Challenge        (16648)
        12946,  -- grid                              (3380)
        30030,  -- Bridges                           (2775)
        3928,   -- Missing buildings Kyrgyzstan      (1332)
        25254,  -- Serbia road overlapping           (126)
        20160,  -- Test Challenge Nienke             (120)
        20161,  -- Test Challenge                    (120)
        3498,   -- Complétude Grand Avignon          (45)
        26221,  -- [Oceania] Keepright               (40)
        30031,  -- Bridges V2                        (10)
        3521    -- K2TEST                            (1)
      )
  AND location IS NOT NULL
  AND NOT ST_IsEmpty(location)
  AND (ST_X(location) NOT BETWEEN -180 AND 180
       OR ST_Y(location) NOT BETWEEN -85.05112878 AND 85.05112878);

-- ---------------------------------------------------------------------------
-- Category C: swap lat/lng for tasks where ST_FlipCoordinates produces a
-- valid WGS84 point. Some challenges have a mix of valid and flipped rows
-- (e.g. 21247 INEPDATA), so we filter per-row rather than per-challenge:
-- only flip when the current value is invalid AND the flipped value is
-- valid. That makes this idempotent and safe to re-run.
-- ---------------------------------------------------------------------------
UPDATE tasks
SET location = ST_FlipCoordinates(location)
WHERE parent_id IN (
        39914,  -- POI/AOI                           (199)
        21247,  -- INEPDATA - MapeiaMA Escolas       (5)
        16999,  -- INEPDATA - escolas canoas         (1)
        23114   -- Example Challenge                 (1)
      )
  AND location IS NOT NULL
  AND NOT ST_IsEmpty(location)
  AND (ST_Y(location) NOT BETWEEN -85.05112878 AND 85.05112878
       OR ST_X(location) NOT BETWEEN -180 AND 180)
  AND ST_Y(ST_FlipCoordinates(location)) BETWEEN -85.05112878 AND 85.05112878
  AND ST_X(ST_FlipCoordinates(location)) BETWEEN -180 AND 180;

-- ---------------------------------------------------------------------------
-- Category E: unrecoverable garbage. Null out the location so the rebuild
-- skips these rows. The tasks themselves remain — only the geometry is
-- cleared. Operators can reopen them once a usable location is supplied.
-- ---------------------------------------------------------------------------
UPDATE tasks
SET location = NULL
WHERE parent_id IN (
        9027,   -- National Register of Historic Places  (18, x = 2^64 overflow)
        224     -- Floating Ways in China                (1, lng = 5.6e8)
      )
  AND location IS NOT NULL
  AND (ST_X(location) NOT BETWEEN -180 AND 180
       OR ST_Y(location) NOT BETWEEN -90 AND 90);

-- ---------------------------------------------------------------------------
-- Refresh challenges.location (centroid of tasks) and challenges.bounding
-- (buffered envelope) for every touched challenge. These are derived columns
-- and will otherwise still hold the pre-fix garbage.
--
-- Uses the same buffered-envelope formula as the original migration in
-- evolutions/11.sql so the result is identical to a fresh ingest.
-- ---------------------------------------------------------------------------
WITH touched_challenges AS (
  SELECT DISTINCT challenge_id FROM bad_task_location_snapshot
)
UPDATE challenges c
SET location = sub.centroid,
    bounding = sub.bounding
FROM (
  SELECT t.parent_id AS challenge_id,
         ST_Centroid(ST_Collect(ST_MakeValid(t.location))) AS centroid,
         ST_Envelope(
           ST_Buffer(
             (ST_SetSRID(ST_Extent(t.location), 4326))::geography,
             2
           )::geometry
         ) AS bounding
  FROM tasks t
  WHERE t.parent_id IN (SELECT challenge_id FROM touched_challenges)
    AND t.location IS NOT NULL
    AND NOT ST_IsEmpty(t.location)
  GROUP BY t.parent_id
) sub
WHERE c.id = sub.challenge_id;

-- ---------------------------------------------------------------------------
-- Mark every tile that overlaps a touched challenge dirty so the next run of
-- rebuild_dirty_tiles regenerates the cluster aggregates with the corrected
-- coordinates. We scan zoom 0..12 (the precomputed tier) for each updated
-- task and let mark_tile_dirty_for_point handle neighbor-buffer dirtying.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
  r RECORD;
BEGIN
  FOR r IN
    SELECT t.id, ST_X(t.location) AS lng, ST_Y(t.location) AS lat
    FROM tasks t
    JOIN bad_task_location_snapshot s ON s.task_id = t.id
    WHERE t.location IS NOT NULL AND NOT ST_IsEmpty(t.location)
  LOOP
    PERFORM mark_tile_dirty_for_point(r.lng, r.lat);
  END LOOP;
END$$;

-- ---------------------------------------------------------------------------
-- Verification queries. Inspect these BEFORE issuing COMMIT.
-- ---------------------------------------------------------------------------

-- 1. Should return zero rows. Anything left here is in Category B or D and
--    was intentionally not touched by this script.
SELECT t.parent_id AS challenge_id, COUNT(*) AS still_invalid
FROM tasks t
WHERE t.location IS NOT NULL
  AND NOT ST_IsEmpty(t.location)
  AND (ST_Y(t.location) NOT BETWEEN -85.05112878 AND 85.05112878
       OR ST_X(t.location) NOT BETWEEN -180 AND 180)
  AND t.parent_id IN (
        15450, 25301, 38427, 12946, 30030, 3928, 25254, 20160, 20161,
        3498, 26221, 30031, 3521,
        39914, 21247, 16999, 23114,
        9027, 224
      )
GROUP BY t.parent_id;

-- 2. Spot-check that the reprojected points landed in plausible geography.
--    Compare each row's lat/lng to the challenge name.
SELECT c.id, c.name,
       ST_AsText(ST_Centroid(ST_Collect(t.location))) AS new_centroid
FROM challenges c
JOIN tasks t ON t.parent_id = c.id
WHERE c.id IN (15450, 25301, 38427, 3928, 3498, 20160, 26221)
  AND t.location IS NOT NULL
GROUP BY c.id, c.name
ORDER BY c.id;

-- 3. Confirm challenges.location/bounding refresh.
SELECT id, name,
       ST_AsText(location) AS centroid,
       ST_AsText(ST_Envelope(bounding)) AS bbox
FROM challenges
WHERE id IN (SELECT DISTINCT challenge_id FROM bad_task_location_snapshot)
ORDER BY id;

-- ---------------------------------------------------------------------------
-- If verification looks good:
--   COMMIT;
-- If anything is off:
--   ROLLBACK;
-- ---------------------------------------------------------------------------

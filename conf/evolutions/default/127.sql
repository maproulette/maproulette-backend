# --- MapRoulette Scheme

# --- !Ups

-- The explore map no longer filters tasks: challenge-level filters (difficulty,
-- global, keywords) live on the grid and list views, and the map shows all
-- available work. So a cell needs one count, not a bucket per filter
-- combination, and `counts_by_filter` goes away along with the difficulty/global
-- parameters on the MVT endpoint.
--
-- Correctness needs no rebuild: `task_count` was always COUNT(*) over the same
-- rows the buckets partitioned, so every remaining column is already the value
-- the unfiltered tile reads. The rebuild at the end is for space -- DROP COLUMN
-- only marks the attribute dead, and existing rows keep carrying its bytes
-- until the table is rewritten, which the TRUNCATE + reinsert inside
-- rebuild_all_tile_cells() does.
--
-- Redefines the three pyramid writers from evolutions 107 and 122, and narrows
-- the challenge dirty-marking trigger: `difficulty` and `is_global` decided
-- which bucket a task counted into, so changing either used to invalidate every
-- cell holding one of the challenge's tasks. Without buckets neither affects a
-- cell's contents at all, so those two columns no longer mark anything dirty.
-- The eligibility filter itself is unchanged -- keep it in sync with the live
-- MVT queries in TileAggregateRepository.

ALTER TABLE tile_cells DROP COLUMN IF EXISTS counts_by_filter;;

CREATE OR REPLACE FUNCTION rebuild_leaf_cell(p_cx INTEGER, p_cy INTEGER) RETURNS VOID AS $$
DECLARE
    env geometry := tile_envelope_4326(15, p_cx, p_cy);;
BEGIN
    DELETE FROM tile_cells WHERE z = 11 AND cx = p_cx AND cy = p_cy;;

    INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng)
    SELECT
        11, p_cx, p_cy,
        COUNT(*)::INTEGER,
        SUM(ST_Y(t.location)),
        SUM(ST_X(t.location))
    FROM tasks t
    INNER JOIN challenges c ON c.id = t.parent_id
    INNER JOIN projects   p ON p.id = c.parent_id
    WHERE t.location && env
      AND lng_to_tile_x(ST_X(t.location), 15) = p_cx
      AND lat_to_tile_y(ST_Y(t.location), 15) = p_cy
      AND NOT ST_IsEmpty(t.location)
      AND ST_X(t.location) BETWEEN -180 AND 180
      AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
      AND t.status IN (0, 3, 6)
      AND t.archived = FALSE
      AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
      AND c.paused = FALSE
      AND p.deleted = FALSE AND p.enabled = TRUE
    HAVING COUNT(*) > 0;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

CREATE OR REPLACE FUNCTION rollup_cell(p_z INTEGER, p_cx INTEGER, p_cy INTEGER) RETURNS VOID AS $$
BEGIN
    DELETE FROM tile_cells WHERE z = p_z AND cx = p_cx AND cy = p_cy;;

    INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng)
    SELECT
        p_z, p_cx, p_cy,
        SUM(task_count)::INTEGER,
        SUM(sum_lat),
        SUM(sum_lng)
    FROM tile_cells
    WHERE z = p_z + 1
      AND cx BETWEEN p_cx * 2 AND p_cx * 2 + 1
      AND cy BETWEEN p_cy * 2 AND p_cy * 2 + 1
    HAVING SUM(task_count) > 0;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

CREATE OR REPLACE FUNCTION rebuild_all_tile_cells() RETURNS INTEGER AS $$
DECLARE
    i_z   INTEGER;;
    total INTEGER := 0;;
    n     INTEGER;;
BEGIN
    TRUNCATE tile_cells, tile_dirty_cells;;

    -- Leaf level (display z=11) straight from the base tables.
    INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng)
    SELECT
        11,
        lng_to_tile_x(ST_X(t.location), 15),
        lat_to_tile_y(ST_Y(t.location), 15),
        COUNT(*)::INTEGER,
        SUM(ST_Y(t.location)),
        SUM(ST_X(t.location))
    FROM tasks t
    INNER JOIN challenges c ON c.id = t.parent_id
    INNER JOIN projects   p ON p.id = c.parent_id
    WHERE t.location IS NOT NULL
      AND NOT ST_IsEmpty(t.location)
      AND ST_X(t.location) BETWEEN -180 AND 180
      AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
      AND t.status IN (0, 3, 6)
      AND t.archived = FALSE
      AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
      AND c.paused = FALSE
      AND p.deleted = FALSE AND p.enabled = TRUE
    GROUP BY 2, 3;;
    GET DIAGNOSTICS n = ROW_COUNT;;
    total := total + n;;

    -- Roll up display z = 10 .. 0 by summation.
    FOR i_z IN REVERSE 10..0 LOOP
        INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng)
        SELECT
            i_z, cx >> 1, cy >> 1,
            SUM(task_count)::INTEGER,
            SUM(sum_lat),
            SUM(sum_lng)
        FROM tile_cells
        WHERE z = i_z + 1
        GROUP BY cx >> 1, cy >> 1;;
        GET DIAGNOSTICS n = ROW_COUNT;;
        total := total + n;;
    END LOOP;;

    RETURN total;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

-- Only eligibility columns mark cells dirty now.
CREATE OR REPLACE FUNCTION mark_dirty_on_challenge_change() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.deleted     IS NOT DISTINCT FROM NEW.deleted
       AND OLD.enabled     IS NOT DISTINCT FROM NEW.enabled
       AND OLD.is_archived IS NOT DISTINCT FROM NEW.is_archived
       AND OLD.paused      IS NOT DISTINCT FROM NEW.paused THEN
        RETURN NEW;;
    END IF;;

    INSERT INTO tile_dirty_cells (cx, cy)
    SELECT DISTINCT
        lng_to_tile_x(ST_X(t.location), 15),
        lat_to_tile_y(ST_Y(t.location), 15)
    FROM tasks t
    WHERE t.parent_id = NEW.id
      AND t.location IS NOT NULL
      AND NOT ST_IsEmpty(t.location)
      AND ST_X(t.location) BETWEEN -180 AND 180
      AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
    ON CONFLICT (cx, cy) DO NOTHING;;

    RETURN NEW;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

DROP TRIGGER IF EXISTS mark_dirty_on_challenge_change_trigger ON challenges;;
CREATE TRIGGER mark_dirty_on_challenge_change_trigger
    AFTER UPDATE OF deleted, enabled, is_archived, paused ON challenges
    FOR EACH ROW EXECUTE PROCEDURE mark_dirty_on_challenge_change();;

-- Rewrite the table so the dropped column's bytes are actually reclaimed.
SELECT rebuild_all_tile_cells();;

# --- !Downs

-- Restore the bucketed pyramid from evolution 122. The column comes back empty,
-- so every cell has to be recomputed before a filtered tile would return
-- anything -- hence the rebuild at the end rather than just a column add.

ALTER TABLE tile_cells
    ADD COLUMN IF NOT EXISTS counts_by_filter JSONB NOT NULL DEFAULT '{}'::jsonb;;

CREATE OR REPLACE FUNCTION rebuild_leaf_cell(p_cx INTEGER, p_cy INTEGER) RETURNS VOID AS $$
DECLARE
    env geometry := tile_envelope_4326(15, p_cx, p_cy);;
BEGIN
    DELETE FROM tile_cells WHERE z = 11 AND cx = p_cx AND cy = p_cy;;

    INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng, counts_by_filter)
    SELECT
        11, p_cx, p_cy,
        COUNT(*)::INTEGER,
        SUM(ST_Y(t.location)),
        SUM(ST_X(t.location)),
        jsonb_build_object(
            'd1_gf', COUNT(*) FILTER (WHERE COALESCE(c.difficulty,0) = 1 AND NOT COALESCE(c.is_global,false)),
            'd1_gt', COUNT(*) FILTER (WHERE COALESCE(c.difficulty,0) = 1 AND     COALESCE(c.is_global,false)),
            'd2_gf', COUNT(*) FILTER (WHERE COALESCE(c.difficulty,0) = 2 AND NOT COALESCE(c.is_global,false)),
            'd2_gt', COUNT(*) FILTER (WHERE COALESCE(c.difficulty,0) = 2 AND     COALESCE(c.is_global,false)),
            'd3_gf', COUNT(*) FILTER (WHERE COALESCE(c.difficulty,0) = 3 AND NOT COALESCE(c.is_global,false)),
            'd3_gt', COUNT(*) FILTER (WHERE COALESCE(c.difficulty,0) = 3 AND     COALESCE(c.is_global,false)),
            'd0_gf', COUNT(*) FILTER (WHERE COALESCE(c.difficulty,0) NOT IN (1,2,3) AND NOT COALESCE(c.is_global,false)),
            'd0_gt', COUNT(*) FILTER (WHERE COALESCE(c.difficulty,0) NOT IN (1,2,3) AND     COALESCE(c.is_global,false))
        )
    FROM tasks t
    INNER JOIN challenges c ON c.id = t.parent_id
    INNER JOIN projects   p ON p.id = c.parent_id
    WHERE t.location && env
      AND lng_to_tile_x(ST_X(t.location), 15) = p_cx
      AND lat_to_tile_y(ST_Y(t.location), 15) = p_cy
      AND NOT ST_IsEmpty(t.location)
      AND ST_X(t.location) BETWEEN -180 AND 180
      AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
      AND t.status IN (0, 3, 6)
      AND t.archived = FALSE
      AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
      AND c.paused = FALSE
      AND p.deleted = FALSE AND p.enabled = TRUE
    HAVING COUNT(*) > 0;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

CREATE OR REPLACE FUNCTION rollup_cell(p_z INTEGER, p_cx INTEGER, p_cy INTEGER) RETURNS VOID AS $$
BEGIN
    DELETE FROM tile_cells WHERE z = p_z AND cx = p_cx AND cy = p_cy;;

    INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng, counts_by_filter)
    SELECT
        p_z, p_cx, p_cy,
        SUM(task_count)::INTEGER,
        SUM(sum_lat),
        SUM(sum_lng),
        jsonb_build_object(
            'd1_gf', SUM(COALESCE((counts_by_filter->>'d1_gf')::int, 0)),
            'd1_gt', SUM(COALESCE((counts_by_filter->>'d1_gt')::int, 0)),
            'd2_gf', SUM(COALESCE((counts_by_filter->>'d2_gf')::int, 0)),
            'd2_gt', SUM(COALESCE((counts_by_filter->>'d2_gt')::int, 0)),
            'd3_gf', SUM(COALESCE((counts_by_filter->>'d3_gf')::int, 0)),
            'd3_gt', SUM(COALESCE((counts_by_filter->>'d3_gt')::int, 0)),
            'd0_gf', SUM(COALESCE((counts_by_filter->>'d0_gf')::int, 0)),
            'd0_gt', SUM(COALESCE((counts_by_filter->>'d0_gt')::int, 0))
        )
    FROM tile_cells
    WHERE z = p_z + 1
      AND cx BETWEEN p_cx * 2 AND p_cx * 2 + 1
      AND cy BETWEEN p_cy * 2 AND p_cy * 2 + 1
    HAVING SUM(task_count) > 0;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

CREATE OR REPLACE FUNCTION rebuild_all_tile_cells() RETURNS INTEGER AS $$
DECLARE
    i_z   INTEGER;;
    total INTEGER := 0;;
    n     INTEGER;;
BEGIN
    TRUNCATE tile_cells, tile_dirty_cells;;

    INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng, counts_by_filter)
    SELECT
        11,
        lng_to_tile_x(ST_X(t.location), 15),
        lat_to_tile_y(ST_Y(t.location), 15),
        COUNT(*)::INTEGER,
        SUM(ST_Y(t.location)),
        SUM(ST_X(t.location)),
        jsonb_build_object(
            'd1_gf', COUNT(*) FILTER (WHERE COALESCE(c.difficulty,0) = 1 AND NOT COALESCE(c.is_global,false)),
            'd1_gt', COUNT(*) FILTER (WHERE COALESCE(c.difficulty,0) = 1 AND     COALESCE(c.is_global,false)),
            'd2_gf', COUNT(*) FILTER (WHERE COALESCE(c.difficulty,0) = 2 AND NOT COALESCE(c.is_global,false)),
            'd2_gt', COUNT(*) FILTER (WHERE COALESCE(c.difficulty,0) = 2 AND     COALESCE(c.is_global,false)),
            'd3_gf', COUNT(*) FILTER (WHERE COALESCE(c.difficulty,0) = 3 AND NOT COALESCE(c.is_global,false)),
            'd3_gt', COUNT(*) FILTER (WHERE COALESCE(c.difficulty,0) = 3 AND     COALESCE(c.is_global,false)),
            'd0_gf', COUNT(*) FILTER (WHERE COALESCE(c.difficulty,0) NOT IN (1,2,3) AND NOT COALESCE(c.is_global,false)),
            'd0_gt', COUNT(*) FILTER (WHERE COALESCE(c.difficulty,0) NOT IN (1,2,3) AND     COALESCE(c.is_global,false))
        )
    FROM tasks t
    INNER JOIN challenges c ON c.id = t.parent_id
    INNER JOIN projects   p ON p.id = c.parent_id
    WHERE t.location IS NOT NULL
      AND NOT ST_IsEmpty(t.location)
      AND ST_X(t.location) BETWEEN -180 AND 180
      AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
      AND t.status IN (0, 3, 6)
      AND t.archived = FALSE
      AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
      AND c.paused = FALSE
      AND p.deleted = FALSE AND p.enabled = TRUE
    GROUP BY 2, 3;;
    GET DIAGNOSTICS n = ROW_COUNT;;
    total := total + n;;

    FOR i_z IN REVERSE 10..0 LOOP
        INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng, counts_by_filter)
        SELECT
            i_z, cx >> 1, cy >> 1,
            SUM(task_count)::INTEGER,
            SUM(sum_lat),
            SUM(sum_lng),
            jsonb_build_object(
                'd1_gf', SUM(COALESCE((counts_by_filter->>'d1_gf')::int, 0)),
                'd1_gt', SUM(COALESCE((counts_by_filter->>'d1_gt')::int, 0)),
                'd2_gf', SUM(COALESCE((counts_by_filter->>'d2_gf')::int, 0)),
                'd2_gt', SUM(COALESCE((counts_by_filter->>'d2_gt')::int, 0)),
                'd3_gf', SUM(COALESCE((counts_by_filter->>'d3_gf')::int, 0)),
                'd3_gt', SUM(COALESCE((counts_by_filter->>'d3_gt')::int, 0)),
                'd0_gf', SUM(COALESCE((counts_by_filter->>'d0_gf')::int, 0)),
                'd0_gt', SUM(COALESCE((counts_by_filter->>'d0_gt')::int, 0))
            )
        FROM tile_cells
        WHERE z = i_z + 1
        GROUP BY cx >> 1, cy >> 1;;
        GET DIAGNOSTICS n = ROW_COUNT;;
        total := total + n;;
    END LOOP;;

    RETURN total;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

CREATE OR REPLACE FUNCTION mark_dirty_on_challenge_change() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.deleted     IS NOT DISTINCT FROM NEW.deleted
       AND OLD.enabled     IS NOT DISTINCT FROM NEW.enabled
       AND OLD.is_archived IS NOT DISTINCT FROM NEW.is_archived
       AND OLD.paused      IS NOT DISTINCT FROM NEW.paused
       AND OLD.is_global   IS NOT DISTINCT FROM NEW.is_global
       AND OLD.difficulty  IS NOT DISTINCT FROM NEW.difficulty THEN
        RETURN NEW;;
    END IF;;

    INSERT INTO tile_dirty_cells (cx, cy)
    SELECT DISTINCT
        lng_to_tile_x(ST_X(t.location), 15),
        lat_to_tile_y(ST_Y(t.location), 15)
    FROM tasks t
    WHERE t.parent_id = NEW.id
      AND t.location IS NOT NULL
      AND NOT ST_IsEmpty(t.location)
      AND ST_X(t.location) BETWEEN -180 AND 180
      AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
    ON CONFLICT (cx, cy) DO NOTHING;;

    RETURN NEW;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

DROP TRIGGER IF EXISTS mark_dirty_on_challenge_change_trigger ON challenges;;
CREATE TRIGGER mark_dirty_on_challenge_change_trigger
    AFTER UPDATE OF deleted, enabled, is_archived, paused, is_global, difficulty ON challenges
    FOR EACH ROW EXECUTE PROCEDURE mark_dirty_on_challenge_change();;

SELECT rebuild_all_tile_cells();;

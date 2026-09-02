# --- MapRoulette Scheme

# --- !Ups

-- Paused challenges are not available work: their tasks cannot be locked,
-- completed or reviewed until the challenge is resumed. Fold `paused` into the
-- tile eligibility filter so their tasks stop showing up on the explore map,
-- matching the live MVT queries in TileAggregateRepository. Redefines the two
-- recompute functions and widens the challenge dirty-marking trigger from
-- evolution 107 -- keep all four paths in sync.

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

CREATE OR REPLACE FUNCTION rebuild_all_tile_cells() RETURNS INTEGER AS $$
DECLARE
    i_z   INTEGER;;
    total INTEGER := 0;;
    n     INTEGER;;
BEGIN
    TRUNCATE tile_cells, tile_dirty_cells;;

    -- Leaf level (display z=11) straight from the base tables.
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

    -- Roll up display z = 10 .. 0 by summation.
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

-- Existing cells were built with the old filter, so any leaf cell holding a
-- task of an already-paused challenge is now stale. Mark just those cells and
-- let the scheduled drain recompute them, rather than rebuilding the whole
-- pyramid.
INSERT INTO tile_dirty_cells (cx, cy)
SELECT DISTINCT
    lng_to_tile_x(ST_X(t.location), 15),
    lat_to_tile_y(ST_Y(t.location), 15)
FROM tasks t
INNER JOIN challenges c ON c.id = t.parent_id
WHERE c.paused = true
  AND c.deleted = false
  AND t.location IS NOT NULL
  AND NOT ST_IsEmpty(t.location)
  AND ST_X(t.location) BETWEEN -180 AND 180
  AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
ON CONFLICT (cx, cy) DO NOTHING;;

# --- !Downs

-- Restore the evolution 107 definitions (no `paused` in the filter).

CREATE OR REPLACE FUNCTION mark_dirty_on_challenge_change() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.deleted     IS NOT DISTINCT FROM NEW.deleted
       AND OLD.enabled     IS NOT DISTINCT FROM NEW.enabled
       AND OLD.is_archived IS NOT DISTINCT FROM NEW.is_archived
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
    AFTER UPDATE OF deleted, enabled, is_archived, is_global, difficulty ON challenges
    FOR EACH ROW EXECUTE PROCEDURE mark_dirty_on_challenge_change();;

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
      AND p.deleted = FALSE AND p.enabled = TRUE
    HAVING COUNT(*) > 0;;
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

INSERT INTO tile_dirty_cells (cx, cy)
SELECT DISTINCT
    lng_to_tile_x(ST_X(t.location), 15),
    lat_to_tile_y(ST_Y(t.location), 15)
FROM tasks t
INNER JOIN challenges c ON c.id = t.parent_id
WHERE c.paused = true
  AND c.deleted = false
  AND t.location IS NOT NULL
  AND NOT ST_IsEmpty(t.location)
  AND ST_X(t.location) BETWEEN -180 AND 180
  AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
ON CONFLICT (cx, cy) DO NOTHING;;

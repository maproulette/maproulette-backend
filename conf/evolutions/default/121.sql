# --- MapRoulette Scheme

# --- !Ups
-- =============================================================================
-- Coarsen the tile-system grid: CELL_BITS 4 -> 3.
--
-- A cell at display zoom z is a slippy tile at zoom z + CELL_BITS. Evolution 107
-- used CELL_BITS = 4, so the leaf level (display z=11) was slippy zoom 15 and a
-- display tile held 16x16 = 256 cells. Lowering CELL_BITS to 3 makes the leaf
-- slippy zoom 14 and a display tile hold 8x8 = 64 cells, each twice as wide.
-- Coarser bins merge nearby tasks from further away -> larger clusters.
--
-- Only the cell<->slippy-zoom mapping changes: the leaf functions that hardcode
-- the leaf zoom move from 15 to 14. The roll-up (parent = four children, cx>>1)
-- is independent of CELL_BITS and is unchanged. Keep this in sync with
-- TileAggregateRepository.CELL_BITS (now 3).
--
-- Existing tile_cells rows are at the old (zoom-15) coordinates, so after the
-- functions are redefined the whole pyramid is rebuilt from the base tables.
-- =============================================================================

CREATE OR REPLACE FUNCTION mark_dirty_leaf_cell(p_loc geometry) RETURNS VOID AS $$
DECLARE
    leaf_z CONSTANT INTEGER := 14;; -- leaf slippy zoom = MAX_CELL_ZOOM(11) + CELL_BITS(3)
BEGIN
    IF p_loc IS NULL OR ST_IsEmpty(p_loc) THEN
        RETURN;;
    END IF;;
    IF ST_X(p_loc) < -180 OR ST_X(p_loc) > 180
       OR ST_Y(p_loc) < -85.05112878 OR ST_Y(p_loc) > 85.05112878 THEN
        RETURN;;
    END IF;;
    INSERT INTO tile_dirty_cells (cx, cy)
    VALUES (lng_to_tile_x(ST_X(p_loc), leaf_z), lat_to_tile_y(ST_Y(p_loc), leaf_z))
    ON CONFLICT (cx, cy) DO NOTHING;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

CREATE OR REPLACE FUNCTION mark_dirty_on_challenge_change() RETURNS TRIGGER AS $$
DECLARE
    leaf_z CONSTANT INTEGER := 14;; -- leaf slippy zoom = MAX_CELL_ZOOM(11) + CELL_BITS(3)
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
        lng_to_tile_x(ST_X(t.location), leaf_z),
        lat_to_tile_y(ST_Y(t.location), leaf_z)
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

CREATE OR REPLACE FUNCTION mark_dirty_on_project_change() RETURNS TRIGGER AS $$
DECLARE
    leaf_z CONSTANT INTEGER := 14;; -- leaf slippy zoom = MAX_CELL_ZOOM(11) + CELL_BITS(3)
BEGIN
    IF OLD.deleted IS NOT DISTINCT FROM NEW.deleted
       AND OLD.enabled IS NOT DISTINCT FROM NEW.enabled THEN
        RETURN NEW;;
    END IF;;

    INSERT INTO tile_dirty_cells (cx, cy)
    SELECT DISTINCT
        lng_to_tile_x(ST_X(t.location), leaf_z),
        lat_to_tile_y(ST_Y(t.location), leaf_z)
    FROM tasks t
    INNER JOIN challenges c ON c.id = t.parent_id
    WHERE c.parent_id = NEW.id
      AND t.location IS NOT NULL
      AND NOT ST_IsEmpty(t.location)
      AND ST_X(t.location) BETWEEN -180 AND 180
      AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
    ON CONFLICT (cx, cy) DO NOTHING;;

    RETURN NEW;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

CREATE OR REPLACE FUNCTION rebuild_leaf_cell(p_cx INTEGER, p_cy INTEGER) RETURNS VOID AS $$
DECLARE
    leaf_z CONSTANT INTEGER := 14;; -- leaf slippy zoom = MAX_CELL_ZOOM(11) + CELL_BITS(3)
    env geometry := tile_envelope_4326(leaf_z, p_cx, p_cy);;
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
      AND lng_to_tile_x(ST_X(t.location), leaf_z) = p_cx
      AND lat_to_tile_y(ST_Y(t.location), leaf_z) = p_cy
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
    leaf_z CONSTANT INTEGER := 14;; -- leaf slippy zoom = MAX_CELL_ZOOM(11) + CELL_BITS(3)
    i_z   INTEGER;;
    total INTEGER := 0;;
    n     INTEGER;;
BEGIN
    TRUNCATE tile_cells, tile_dirty_cells;;

    -- Leaf level (display z=11, slippy zoom 14) straight from the base tables.
    INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng, counts_by_filter)
    SELECT
        11,
        lng_to_tile_x(ST_X(t.location), leaf_z),
        lat_to_tile_y(ST_Y(t.location), leaf_z),
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

-- Rebuild the whole pyramid at the new grid resolution.
SELECT rebuild_all_tile_cells();;

# --- !Downs

-- Restore the CELL_BITS = 4 leaf zoom (slippy 15) and rebuild.

CREATE OR REPLACE FUNCTION mark_dirty_leaf_cell(p_loc geometry) RETURNS VOID AS $$
DECLARE
    leaf_z CONSTANT INTEGER := 15;; -- leaf slippy zoom = MAX_CELL_ZOOM(11) + CELL_BITS(4)
BEGIN
    IF p_loc IS NULL OR ST_IsEmpty(p_loc) THEN
        RETURN;;
    END IF;;
    IF ST_X(p_loc) < -180 OR ST_X(p_loc) > 180
       OR ST_Y(p_loc) < -85.05112878 OR ST_Y(p_loc) > 85.05112878 THEN
        RETURN;;
    END IF;;
    INSERT INTO tile_dirty_cells (cx, cy)
    VALUES (lng_to_tile_x(ST_X(p_loc), leaf_z), lat_to_tile_y(ST_Y(p_loc), leaf_z))
    ON CONFLICT (cx, cy) DO NOTHING;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

CREATE OR REPLACE FUNCTION mark_dirty_on_challenge_change() RETURNS TRIGGER AS $$
DECLARE
    leaf_z CONSTANT INTEGER := 15;; -- leaf slippy zoom = MAX_CELL_ZOOM(11) + CELL_BITS(4)
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
        lng_to_tile_x(ST_X(t.location), leaf_z),
        lat_to_tile_y(ST_Y(t.location), leaf_z)
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

CREATE OR REPLACE FUNCTION mark_dirty_on_project_change() RETURNS TRIGGER AS $$
DECLARE
    leaf_z CONSTANT INTEGER := 15;; -- leaf slippy zoom = MAX_CELL_ZOOM(11) + CELL_BITS(4)
BEGIN
    IF OLD.deleted IS NOT DISTINCT FROM NEW.deleted
       AND OLD.enabled IS NOT DISTINCT FROM NEW.enabled THEN
        RETURN NEW;;
    END IF;;

    INSERT INTO tile_dirty_cells (cx, cy)
    SELECT DISTINCT
        lng_to_tile_x(ST_X(t.location), leaf_z),
        lat_to_tile_y(ST_Y(t.location), leaf_z)
    FROM tasks t
    INNER JOIN challenges c ON c.id = t.parent_id
    WHERE c.parent_id = NEW.id
      AND t.location IS NOT NULL
      AND NOT ST_IsEmpty(t.location)
      AND ST_X(t.location) BETWEEN -180 AND 180
      AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
    ON CONFLICT (cx, cy) DO NOTHING;;

    RETURN NEW;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

CREATE OR REPLACE FUNCTION rebuild_leaf_cell(p_cx INTEGER, p_cy INTEGER) RETURNS VOID AS $$
DECLARE
    leaf_z CONSTANT INTEGER := 15;; -- leaf slippy zoom = MAX_CELL_ZOOM(11) + CELL_BITS(4)
    env geometry := tile_envelope_4326(leaf_z, p_cx, p_cy);;
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
      AND lng_to_tile_x(ST_X(t.location), leaf_z) = p_cx
      AND lat_to_tile_y(ST_Y(t.location), leaf_z) = p_cy
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
    leaf_z CONSTANT INTEGER := 15;; -- leaf slippy zoom = MAX_CELL_ZOOM(11) + CELL_BITS(4)
    i_z   INTEGER;;
    total INTEGER := 0;;
    n     INTEGER;;
BEGIN
    TRUNCATE tile_cells, tile_dirty_cells;;

    -- Leaf level (display z=11, slippy zoom 15) straight from the base tables.
    INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng, counts_by_filter)
    SELECT
        11,
        lng_to_tile_x(ST_X(t.location), leaf_z),
        lat_to_tile_y(ST_Y(t.location), leaf_z),
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

SELECT rebuild_all_tile_cells();;

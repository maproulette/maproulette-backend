# --- MapRoulette Scheme

# --- !Ups
-- =============================================================================
-- Tile system: hierarchical grid-binned task aggregation for the explore map.
--
-- Design
-- ------
--   * Display zoom 0..11 are PRE-COMPUTED. Each zoom is aggregated onto a fixed
--     grid of cells: a cell at display zoom z is exactly a slippy tile at zoom
--     z + CELL_BITS (CELL_BITS = 4 -> 16x16 = 256 cells per display tile, each
--     ~256 MVT pixels). Because the grid is hierarchical, a cell at zoom z is
--     EXACTLY the union of its four children at zoom z+1, so roll-up is plain
--     additive summation -- exact, deterministic, no clustering algorithm.
--   * Display zoom 12 is served live from `tasks` (see TileAggregateRepository)
--     as individual / overlap-deduped task markers, and is NOT stored here.
--   * Display zoom 13+ is overzoomed client-side from z=12.
--
-- Per-cell we store only additive quantities (task_count, sum_lat, sum_lng,
-- counts_by_filter). The emitted centroid is sum_lat/task_count, sum_lng/
-- task_count -- itself additive, so roll-up stays exact.
--
-- Updates
-- -------
--   * Triggers on `tasks` / `challenges` mark affected LEAF cells (z=11) dirty
--     in `tile_dirty_cells` and emit a `tile_dirty` NOTIFY.
--   * A drain (rebuild_dirty_cells) recomputes each dirty leaf cell from the
--     base tables -- AUTHORITATIVE, so it is immune to the races a pure delta
--     scheme suffers -- then rolls the change up z=10..0 by summation.
--   * The drain holds a single global advisory lock, so only one drainer
--     touches the pyramid at a time and concurrent rollups never race.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Storage
-- ---------------------------------------------------------------------------

-- Pre-computed grid cells for display zoom 0..11.
-- (cx, cy) are slippy-tile coordinates at zoom (z + 4).
CREATE TABLE IF NOT EXISTS tile_cells (
    z                SMALLINT NOT NULL,
    cx               INTEGER  NOT NULL,
    cy               INTEGER  NOT NULL,
    task_count       INTEGER  NOT NULL,
    sum_lat          DOUBLE PRECISION NOT NULL,
    sum_lng          DOUBLE PRECISION NOT NULL,
    counts_by_filter JSONB    NOT NULL DEFAULT '{}'::jsonb,
    last_updated     TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (z, cx, cy)
);;

-- Queue of leaf cells (z=11 grid, i.e. slippy zoom 15) awaiting recompute.
CREATE TABLE IF NOT EXISTS tile_dirty_cells (
    cx        INTEGER NOT NULL,
    cy        INTEGER NOT NULL,
    marked_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (cx, cy)
);;
CREATE INDEX IF NOT EXISTS idx_tile_dirty_cells_marked_at ON tile_dirty_cells (marked_at);;

-- ---------------------------------------------------------------------------
-- Coordinate helpers
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION lng_to_tile_x(lng DOUBLE PRECISION, zoom INTEGER) RETURNS INTEGER AS $$
    SELECT FLOOR((lng + 180.0) / 360.0 * (1 << zoom))::INTEGER
$$ LANGUAGE SQL IMMUTABLE PARALLEL SAFE;;

CREATE OR REPLACE FUNCTION lat_to_tile_y(lat DOUBLE PRECISION, zoom INTEGER) RETURNS INTEGER AS $$
    SELECT FLOOR((1.0 - LN(TAN(RADIANS(GREATEST(-85.0511, LEAST(85.0511, lat)))) +
           1.0 / COS(RADIANS(GREATEST(-85.0511, LEAST(85.0511, lat))))) / PI()) / 2.0 * (1 << zoom))::INTEGER
$$ LANGUAGE SQL IMMUTABLE PARALLEL SAFE;;

-- Lon/lat (EPSG:4326) envelope of a slippy tile. Used to drive GiST-indexed
-- bbox prefilters (t.location && tile_envelope_4326(...)).
CREATE OR REPLACE FUNCTION tile_envelope_4326(p_tz INTEGER, p_tx INTEGER, p_ty INTEGER)
RETURNS geometry AS $$
    SELECT ST_MakeEnvelope(
        p_tx::double precision       / (1 << p_tz) * 360.0 - 180.0,
        DEGREES(ATAN(SINH(PI() * (1.0 - 2.0 * (p_ty + 1)::double precision / (1 << p_tz))))),
        (p_tx + 1)::double precision / (1 << p_tz) * 360.0 - 180.0,
        DEGREES(ATAN(SINH(PI() * (1.0 - 2.0 *  p_ty::double precision      / (1 << p_tz))))),
        4326)
$$ LANGUAGE SQL IMMUTABLE PARALLEL SAFE;;

-- ---------------------------------------------------------------------------
-- Dirty marking (trigger side)
-- ---------------------------------------------------------------------------

-- Enqueue the leaf cell (z=11 grid == slippy zoom 15) covering a point.
-- No neighbour buffering is needed: with grid binning a task belongs to
-- exactly one cell, and the recompute is authoritative.
CREATE OR REPLACE FUNCTION mark_dirty_leaf_cell(p_loc geometry) RETURNS VOID AS $$
BEGIN
    IF p_loc IS NULL OR ST_IsEmpty(p_loc) THEN
        RETURN;;
    END IF;;
    IF ST_X(p_loc) < -180 OR ST_X(p_loc) > 180
       OR ST_Y(p_loc) < -85.05112878 OR ST_Y(p_loc) > 85.05112878 THEN
        RETURN;;
    END IF;;
    INSERT INTO tile_dirty_cells (cx, cy)
    VALUES (lng_to_tile_x(ST_X(p_loc), 15), lat_to_tile_y(ST_Y(p_loc), 15))
    ON CONFLICT (cx, cy) DO NOTHING;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

-- A single task changed: mark the leaf cell of its old and new locations.
-- Fires for INSERT / UPDATE / DELETE -- the body decides what is relevant
-- (status, location, parent_id, archived). A bare AFTER ... trigger -- with no
-- column list -- is used so this evolution has no DDL dependency on the
-- `archived` column (added by a later evolution) -- it is resolved lazily.
CREATE OR REPLACE FUNCTION mark_dirty_on_task_change() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.status    IS NOT DISTINCT FROM NEW.status
       AND OLD.parent_id IS NOT DISTINCT FROM NEW.parent_id
       AND OLD.archived  IS NOT DISTINCT FROM NEW.archived
       AND ST_Equals(COALESCE(OLD.location, ST_GeomFromText('POINT EMPTY', 4326)),
                     COALESCE(NEW.location, ST_GeomFromText('POINT EMPTY', 4326))) THEN
        RETURN NEW;;
    END IF;;

    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        PERFORM mark_dirty_leaf_cell(OLD.location);;
    END IF;;
    IF TG_OP IN ('UPDATE', 'INSERT') THEN
        PERFORM mark_dirty_leaf_cell(NEW.location);;
    END IF;;

    PERFORM pg_notify('tile_dirty', '');;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;;
    END IF;;
    RETURN NEW;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

DROP TRIGGER IF EXISTS mark_tiles_dirty_on_task_change_trigger ON tasks;;
DROP TRIGGER IF EXISTS mark_dirty_on_task_change_trigger ON tasks;;
CREATE TRIGGER mark_dirty_on_task_change_trigger
    AFTER INSERT OR UPDATE OR DELETE ON tasks
    FOR EACH ROW EXECUTE PROCEDURE mark_dirty_on_task_change();;

-- A challenge attribute that changes task eligibility (deleted / enabled /
-- is_archived) or filter bucketing (is_global / difficulty) flipped: mark
-- every leaf cell holding one of its tasks. The recompute applies the real
-- eligibility filter, so over-marking here is harmless.
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

    PERFORM pg_notify('tile_dirty', '');;
    RETURN NEW;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

DROP TRIGGER IF EXISTS mark_tiles_dirty_on_challenge_change_trigger ON challenges;;
DROP TRIGGER IF EXISTS mark_dirty_on_challenge_change_trigger ON challenges;;
CREATE TRIGGER mark_dirty_on_challenge_change_trigger
    AFTER UPDATE OF deleted, enabled, is_archived, is_global, difficulty ON challenges
    FOR EACH ROW EXECUTE PROCEDURE mark_dirty_on_challenge_change();;

-- A project attribute that changes task eligibility (deleted / enabled)
-- flipped: mark every leaf cell holding a task under any of the project's
-- challenges. Hard project deletes are already covered -- they cascade to
-- tasks, firing the per-task trigger.
CREATE OR REPLACE FUNCTION mark_dirty_on_project_change() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.deleted IS NOT DISTINCT FROM NEW.deleted
       AND OLD.enabled IS NOT DISTINCT FROM NEW.enabled THEN
        RETURN NEW;;
    END IF;;

    INSERT INTO tile_dirty_cells (cx, cy)
    SELECT DISTINCT
        lng_to_tile_x(ST_X(t.location), 15),
        lat_to_tile_y(ST_Y(t.location), 15)
    FROM tasks t
    INNER JOIN challenges c ON c.id = t.parent_id
    WHERE c.parent_id = NEW.id
      AND t.location IS NOT NULL
      AND NOT ST_IsEmpty(t.location)
      AND ST_X(t.location) BETWEEN -180 AND 180
      AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
    ON CONFLICT (cx, cy) DO NOTHING;;

    PERFORM pg_notify('tile_dirty', '');;
    RETURN NEW;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

DROP TRIGGER IF EXISTS mark_dirty_on_project_change_trigger ON projects;;
CREATE TRIGGER mark_dirty_on_project_change_trigger
    AFTER UPDATE OF deleted, enabled ON projects
    FOR EACH ROW EXECUTE PROCEDURE mark_dirty_on_project_change();;

-- ---------------------------------------------------------------------------
-- Recompute (drain side)
-- ---------------------------------------------------------------------------

-- Recompute one leaf cell (display z=11) from the base tables. The eligibility
-- filter is the single source of truth for "available work", and is mirrored
-- verbatim by rebuild_all_tile_cells() and by the live MVT queries in
-- TileAggregateRepository -- keep all four in sync.
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

-- Recompute one cell at display zoom p_z (0..10) by summing its four children
-- at p_z + 1. Exact: a parent cell is the precise union of its four children.
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

-- Full rebuild of the whole pyramid. Used for initial population and as a
-- crash-recovery safety net. Cheap relative to the old design: one scan of
-- `tasks` for the leaf level, then 11 additive roll-up passes.
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

-- Drain the dirty-cell queue: pop up to p_limit leaf cells, recompute them
-- from the base tables, then roll the changes up z=10..0.
--
-- A single global advisory lock (key 6552071) is held for the call so only
-- one drainer mutates the pyramid at a time -- this is what makes concurrent
-- roll-ups (scheduled drain vs. synchronous post-commit drain) safe. The lock
-- is transaction-scoped, so each call releases it on commit, and callers
-- on it block for at most one batch.
--
-- p_newest_first drains the most recently marked cells first, used by
-- the synchronous post-commit drain so a user sees their own edit immediately.
CREATE OR REPLACE FUNCTION rebuild_dirty_cells(
    p_limit        INTEGER DEFAULT 512,
    p_newest_first BOOLEAN DEFAULT FALSE
) RETURNS INTEGER AS $$
DECLARE
    processed INTEGER := 0;;
    i_z       INTEGER;;
    rec       RECORD;;
BEGIN
    PERFORM pg_advisory_xact_lock(6552071);;

    CREATE TEMP TABLE IF NOT EXISTS _tile_work (
        z  SMALLINT NOT NULL,
        cx INTEGER  NOT NULL,
        cy INTEGER  NOT NULL,
        PRIMARY KEY (z, cx, cy)
    ) ON COMMIT DROP;;
    TRUNCATE _tile_work;;

    -- Pop leaf cells (display z=11) off the queue into the work set. The
    -- DELETE ... RETURNING must sit in a WITH clause: a data-modifying
    -- statement cannot be a plain subquery in FROM.
    WITH popped AS (
        DELETE FROM tile_dirty_cells
        WHERE (cx, cy) IN (
            SELECT cx, cy FROM tile_dirty_cells
            ORDER BY (CASE WHEN p_newest_first THEN marked_at END) DESC NULLS LAST,
                     marked_at ASC
            LIMIT p_limit
        )
        RETURNING cx, cy
    )
    INSERT INTO _tile_work (z, cx, cy)
    SELECT 11, cx, cy FROM popped
    ON CONFLICT DO NOTHING;;

    SELECT COUNT(*) INTO processed FROM _tile_work WHERE z = 11;;
    IF processed = 0 THEN
        RETURN 0;;
    END IF;;

    -- Recompute the leaf cells.
    FOR rec IN SELECT cx, cy FROM _tile_work WHERE z = 11 LOOP
        PERFORM rebuild_leaf_cell(rec.cx, rec.cy);;
    END LOOP;;

    -- Roll up: each level's dirty set is the distinct parents of the level below.
    FOR i_z IN REVERSE 10..0 LOOP
        INSERT INTO _tile_work (z, cx, cy)
        SELECT i_z, cx >> 1, cy >> 1 FROM _tile_work WHERE z = i_z + 1
        ON CONFLICT DO NOTHING;;

        FOR rec IN SELECT cx, cy FROM _tile_work WHERE z = i_z LOOP
            PERFORM rollup_cell(i_z, rec.cx, rec.cy);;
        END LOOP;;
    END LOOP;;

    RETURN processed;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

# --- !Downs

DROP TRIGGER IF EXISTS mark_dirty_on_project_change_trigger ON projects;;
DROP TRIGGER IF EXISTS mark_dirty_on_challenge_change_trigger ON challenges;;
DROP TRIGGER IF EXISTS mark_dirty_on_task_change_trigger ON tasks;;
DROP FUNCTION IF EXISTS mark_dirty_on_project_change();;
DROP FUNCTION IF EXISTS mark_dirty_on_challenge_change();;
DROP FUNCTION IF EXISTS mark_dirty_on_task_change();;
DROP FUNCTION IF EXISTS rebuild_dirty_cells(INTEGER, BOOLEAN);;
DROP FUNCTION IF EXISTS rebuild_all_tile_cells();;
DROP FUNCTION IF EXISTS rollup_cell(INTEGER, INTEGER, INTEGER);;
DROP FUNCTION IF EXISTS rebuild_leaf_cell(INTEGER, INTEGER);;
DROP FUNCTION IF EXISTS mark_dirty_leaf_cell(geometry);;
DROP FUNCTION IF EXISTS tile_envelope_4326(INTEGER, INTEGER, INTEGER);;
DROP FUNCTION IF EXISTS lat_to_tile_y(DOUBLE PRECISION, INTEGER);;
DROP FUNCTION IF EXISTS lng_to_tile_x(DOUBLE PRECISION, INTEGER);;
DROP INDEX IF EXISTS idx_tile_dirty_cells_marked_at;;
DROP TABLE IF EXISTS tile_dirty_cells;;
DROP TABLE IF EXISTS tile_cells;;

# --- MapRoulette Scheme

# --- !Ups

-- =============================================================================
-- TILE PYRAMID AGGREGATION SYSTEM
-- =============================================================================

CREATE TABLE IF NOT EXISTS tile_aggregates (
    id SERIAL PRIMARY KEY,
    z SMALLINT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    task_count INTEGER DEFAULT 0,
    counts_by_filter JSONB DEFAULT '{}'::jsonb,
    centroid_lat DOUBLE PRECISION,
    centroid_lng DOUBLE PRECISION,
    last_updated TIMESTAMP WITHOUT TIME ZONE DEFAULT NOW(),
    CONSTRAINT tile_aggregates_unique UNIQUE (z, x, y)
);;

SELECT create_index_if_not_exists('tile_aggregates', 'zxy', '(z, x, y)');;
CREATE INDEX IF NOT EXISTS idx_tile_aggregates_count ON tile_aggregates (task_count) WHERE task_count > 0;;
CREATE INDEX IF NOT EXISTS idx_tile_aggregates_counts_gin ON tile_aggregates USING GIN (counts_by_filter);;

CREATE TABLE IF NOT EXISTS tile_refresh_queue (
    id SERIAL PRIMARY KEY,
    z SMALLINT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    queued_at TIMESTAMP WITHOUT TIME ZONE DEFAULT NOW(),
    CONSTRAINT tile_refresh_queue_unique UNIQUE (z, x, y)
);;

SELECT create_index_if_not_exists('tile_refresh_queue', 'z', '(z)');;

-- Tile coordinate conversion functions
CREATE OR REPLACE FUNCTION lng_to_tile_x(lng DOUBLE PRECISION, zoom INTEGER) RETURNS INTEGER as $$
BEGIN
    RETURN FLOOR((lng + 180.0) / 360.0 * (1 << zoom))::INTEGER;;
END
$$
LANGUAGE plpgsql IMMUTABLE;;

CREATE OR REPLACE FUNCTION lat_to_tile_y(lat DOUBLE PRECISION, zoom INTEGER) RETURNS INTEGER as $$
DECLARE
    lat_rad DOUBLE PRECISION;;
    lat_clamped DOUBLE PRECISION;;
BEGIN
    lat_clamped := GREATEST(-85.0511, LEAST(85.0511, lat));;
    lat_rad := RADIANS(lat_clamped);;
    RETURN FLOOR((1.0 - LN(TAN(lat_rad) + 1.0 / COS(lat_rad)) / PI()) / 2.0 * (1 << zoom))::INTEGER;;
END
$$
LANGUAGE plpgsql IMMUTABLE;;

CREATE OR REPLACE FUNCTION tile_to_lng(x INTEGER, zoom INTEGER) RETURNS DOUBLE PRECISION as $$
BEGIN
    RETURN x::DOUBLE PRECISION / (1 << zoom) * 360.0 - 180.0;;
END
$$
LANGUAGE plpgsql IMMUTABLE;;

CREATE OR REPLACE FUNCTION tile_to_lat(y INTEGER, zoom INTEGER) RETURNS DOUBLE PRECISION as $$
DECLARE
    n DOUBLE PRECISION;;
BEGIN
    n := PI() - 2.0 * PI() * y::DOUBLE PRECISION / (1 << zoom);;
    RETURN DEGREES(ATAN(SINH(n)));;
END
$$
LANGUAGE plpgsql IMMUTABLE;;

CREATE OR REPLACE FUNCTION tile_bounds(p_z INTEGER, p_x INTEGER, p_y INTEGER) RETURNS geometry as $$
DECLARE
    west DOUBLE PRECISION;;
    east DOUBLE PRECISION;;
    north DOUBLE PRECISION;;
    south DOUBLE PRECISION;;
BEGIN
    west := tile_to_lng(p_x, p_z);;
    east := tile_to_lng(p_x + 1, p_z);;
    north := tile_to_lat(p_y, p_z);;
    south := tile_to_lat(p_y + 1, p_z);;
    RETURN ST_MakeEnvelope(west, south, east, north, 4326);;
END
$$
LANGUAGE plpgsql IMMUTABLE;;

-- Trigger function to queue tiles for refresh
CREATE OR REPLACE FUNCTION queue_tile_refresh() RETURNS TRIGGER as $$
DECLARE
    task_lng DOUBLE PRECISION;;
    task_lat DOUBLE PRECISION;;
    old_lng DOUBLE PRECISION;;
    old_lat DOUBLE PRECISION;;
    zoom_level INTEGER;;
    should_process_new BOOLEAN := FALSE;;
    should_process_old BOOLEAN := FALSE;;
BEGIN
    IF TG_OP != 'DELETE' AND NEW.location IS NOT NULL THEN
        IF NEW.status IN (0, 3, 6) THEN
            should_process_new := TRUE;;
            task_lng := ST_X(NEW.location);;
            task_lat := ST_Y(NEW.location);;
        END IF;;
    END IF;;

    IF TG_OP != 'INSERT' AND OLD.location IS NOT NULL THEN
        IF OLD.status IN (0, 3, 6) THEN
            should_process_old := TRUE;;
            old_lng := ST_X(OLD.location);;
            old_lat := ST_Y(OLD.location);;
        END IF;;
    END IF;;

    IF should_process_new THEN
        FOR zoom_level IN 0..14 LOOP
            INSERT INTO tile_refresh_queue (z, x, y)
            VALUES (zoom_level, lng_to_tile_x(task_lng, zoom_level), lat_to_tile_y(task_lat, zoom_level))
            ON CONFLICT (z, x, y) DO NOTHING;;
        END LOOP;;
    END IF;;

    IF should_process_old THEN
        IF NOT should_process_new OR (should_process_new AND NOT ST_Equals(OLD.location, NEW.location)) THEN
            FOR zoom_level IN 0..14 LOOP
                INSERT INTO tile_refresh_queue (z, x, y)
                VALUES (zoom_level, lng_to_tile_x(old_lng, zoom_level), lat_to_tile_y(old_lat, zoom_level))
                ON CONFLICT (z, x, y) DO NOTHING;;
            END LOOP;;
        END IF;;
    END IF;;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;;
    ELSE
        RETURN NEW;;
    END IF;;
END
$$
LANGUAGE plpgsql;;

DROP TRIGGER IF EXISTS task_tile_refresh_insert ON tasks;;
CREATE TRIGGER task_tile_refresh_insert
    AFTER INSERT ON tasks
    FOR EACH ROW
    EXECUTE FUNCTION queue_tile_refresh();;

DROP TRIGGER IF EXISTS task_tile_refresh_update ON tasks;;
CREATE TRIGGER task_tile_refresh_update
    AFTER UPDATE OF location, status ON tasks
    FOR EACH ROW
    EXECUTE FUNCTION queue_tile_refresh();;

DROP TRIGGER IF EXISTS task_tile_refresh_delete ON tasks;;
CREATE TRIGGER task_tile_refresh_delete
    AFTER DELETE ON tasks
    FOR EACH ROW
    EXECUTE FUNCTION queue_tile_refresh();;

-- Function to rebuild a single tile
CREATE OR REPLACE FUNCTION rebuild_tile_aggregate(p_z INTEGER, p_x INTEGER, p_y INTEGER) RETURNS VOID as $$
DECLARE
    tile_geom geometry;;
    task_count_val INTEGER;;
    counts_json JSONB;;
    centroid_lat_val DOUBLE PRECISION;;
    centroid_lng_val DOUBLE PRECISION;;
BEGIN
    tile_geom := tile_bounds(p_z, p_x, p_y);;

    WITH task_data AS (
        SELECT
            t.location,
            COALESCE(c.difficulty, 0) as difficulty,
            COALESCE(c.is_global, false) as is_global
        FROM tasks t
        INNER JOIN challenges c ON c.id = t.parent_id
        INNER JOIN projects p ON p.id = c.parent_id
        WHERE t.location && tile_geom
          AND ST_Intersects(t.location, tile_geom)
          AND t.status IN (0, 3, 6)
          AND c.deleted = FALSE
          AND c.enabled = TRUE
          AND c.is_archived = FALSE
          AND p.deleted = FALSE
          AND p.enabled = TRUE
    ),
    aggregated AS (
        SELECT
            COUNT(*) as total,
            AVG(ST_Y(location)) as avg_lat,
            AVG(ST_X(location)) as avg_lng,
            COUNT(*) FILTER (WHERE difficulty = 1 AND NOT is_global) as d1_gf,
            COUNT(*) FILTER (WHERE difficulty = 1 AND is_global) as d1_gt,
            COUNT(*) FILTER (WHERE difficulty = 2 AND NOT is_global) as d2_gf,
            COUNT(*) FILTER (WHERE difficulty = 2 AND is_global) as d2_gt,
            COUNT(*) FILTER (WHERE difficulty = 3 AND NOT is_global) as d3_gf,
            COUNT(*) FILTER (WHERE difficulty = 3 AND is_global) as d3_gt,
            COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND NOT is_global) as d0_gf,
            COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND is_global) as d0_gt
        FROM task_data
    )
    SELECT
        total,
        jsonb_build_object(
            'd1_gf', d1_gf, 'd1_gt', d1_gt,
            'd2_gf', d2_gf, 'd2_gt', d2_gt,
            'd3_gf', d3_gf, 'd3_gt', d3_gt,
            'd0_gf', d0_gf, 'd0_gt', d0_gt
        ),
        avg_lat,
        avg_lng
    INTO task_count_val, counts_json, centroid_lat_val, centroid_lng_val
    FROM aggregated;;

    IF task_count_val IS NULL THEN
        task_count_val := 0;;
        counts_json := '{}'::jsonb;;
    END IF;;

    IF task_count_val > 0 THEN
        INSERT INTO tile_aggregates (z, x, y, task_count, counts_by_filter, centroid_lat, centroid_lng, last_updated)
        VALUES (p_z, p_x, p_y, task_count_val, counts_json, centroid_lat_val, centroid_lng_val, NOW())
        ON CONFLICT (z, x, y) DO UPDATE SET
            task_count = EXCLUDED.task_count,
            counts_by_filter = EXCLUDED.counts_by_filter,
            centroid_lat = EXCLUDED.centroid_lat,
            centroid_lng = EXCLUDED.centroid_lng,
            last_updated = NOW();;
    ELSE
        DELETE FROM tile_aggregates WHERE z = p_z AND x = p_x AND y = p_y;;
    END IF;;
END
$$
LANGUAGE plpgsql;;

-- Function to process queued tiles
CREATE OR REPLACE FUNCTION process_tile_refresh_queue(batch_size INTEGER DEFAULT 1000) RETURNS INTEGER as $$
DECLARE
    processed INTEGER := 0;;
    tile_rec RECORD;;
BEGIN
    FOR tile_rec IN
        SELECT z, x, y
        FROM tile_refresh_queue
        ORDER BY z ASC, queued_at ASC
        LIMIT batch_size
        FOR UPDATE SKIP LOCKED
    LOOP
        PERFORM rebuild_tile_aggregate(tile_rec.z, tile_rec.x, tile_rec.y);;
        DELETE FROM tile_refresh_queue WHERE z = tile_rec.z AND x = tile_rec.x AND y = tile_rec.y;;
        processed := processed + 1;;
    END LOOP;;

    RETURN processed;;
END
$$
LANGUAGE plpgsql;;

-- Function to rebuild all tiles for a zoom level
CREATE OR REPLACE FUNCTION rebuild_zoom_level(p_zoom INTEGER) RETURNS INTEGER as $$
DECLARE
    tiles_created INTEGER := 0;;
    tile_rec RECORD;;
BEGIN
    DELETE FROM tile_aggregates WHERE z = p_zoom;;

    FOR tile_rec IN
        SELECT DISTINCT
            lng_to_tile_x(ST_X(t.location), p_zoom) AS tx,
            lat_to_tile_y(ST_Y(t.location), p_zoom) AS ty
        FROM tasks t
        INNER JOIN challenges c ON c.id = t.parent_id
        INNER JOIN projects p ON p.id = c.parent_id
        WHERE t.location IS NOT NULL
          AND t.status IN (0, 3, 6)
          AND c.deleted = FALSE
          AND c.enabled = TRUE
          AND c.is_archived = FALSE
          AND p.deleted = FALSE
          AND p.enabled = TRUE
    LOOP
        PERFORM rebuild_tile_aggregate(p_zoom, tile_rec.tx, tile_rec.ty);;
        tiles_created := tiles_created + 1;;
    END LOOP;;

    RETURN tiles_created;;
END
$$
LANGUAGE plpgsql;;

-- Function to get child tile coordinates
CREATE OR REPLACE FUNCTION get_child_tile_coords(p_z INTEGER, p_x INTEGER, p_y INTEGER)
RETURNS TABLE(child_z INTEGER, child_x INTEGER, child_y INTEGER) as $$
BEGIN
    RETURN QUERY
    SELECT p_z + 1, p_x * 2, p_y * 2
    UNION ALL
    SELECT p_z + 1, p_x * 2 + 1, p_y * 2
    UNION ALL
    SELECT p_z + 1, p_x * 2, p_y * 2 + 1
    UNION ALL
    SELECT p_z + 1, p_x * 2 + 1, p_y * 2 + 1;;
END
$$
LANGUAGE plpgsql IMMUTABLE;;

# --- !Downs

DROP TRIGGER IF EXISTS task_tile_refresh_insert ON tasks;;
DROP TRIGGER IF EXISTS task_tile_refresh_update ON tasks;;
DROP TRIGGER IF EXISTS task_tile_refresh_delete ON tasks;;
DROP FUNCTION IF EXISTS queue_tile_refresh();;
DROP FUNCTION IF EXISTS process_tile_refresh_queue(INTEGER);;
DROP FUNCTION IF EXISTS rebuild_tile_aggregate(INTEGER, INTEGER, INTEGER);;
DROP FUNCTION IF EXISTS rebuild_zoom_level(INTEGER);;
DROP FUNCTION IF EXISTS get_child_tile_coords(INTEGER, INTEGER, INTEGER);;
DROP FUNCTION IF EXISTS tile_bounds(INTEGER, INTEGER, INTEGER);;
DROP FUNCTION IF EXISTS tile_to_lat(INTEGER, INTEGER);;
DROP FUNCTION IF EXISTS tile_to_lng(INTEGER, INTEGER);;
DROP FUNCTION IF EXISTS lat_to_tile_y(DOUBLE PRECISION, INTEGER);;
DROP FUNCTION IF EXISTS lng_to_tile_x(DOUBLE PRECISION, INTEGER);;
DROP TABLE IF EXISTS tile_refresh_queue;;
DROP TABLE IF EXISTS tile_aggregates;;

# --- !Ups

-- Table for storing pre-computed task groups at all zoom levels (0-14)
-- Zoom 0-13: One entry per tile - can be single, overlapping, or cluster
--   - Single (1 task at 1 location): group_type=0, has task_ids
--   - Overlapping (N tasks at 1 location): group_type=1, has task_ids
--   - Cluster (N tasks at multiple locations): group_type=2, no task_ids
-- Zoom 14: One entry per overlap group (for frontend clustering at 14-22)
--   - Single: group_type=0, has task_ids
--   - Overlapping: group_type=1, has task_ids
CREATE TABLE tile_task_groups (
    id SERIAL PRIMARY KEY,
    z SMALLINT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    group_type SMALLINT NOT NULL,  -- 0=single task, 1=overlapping tasks, 2=cluster
    centroid_lat DOUBLE PRECISION NOT NULL,
    centroid_lng DOUBLE PRECISION NOT NULL,
    task_ids BIGINT[] NOT NULL,
    task_count INTEGER NOT NULL,
    counts_by_filter JSONB DEFAULT '{}'::jsonb,
    last_updated TIMESTAMP WITHOUT TIME ZONE DEFAULT NOW()
);;

CREATE INDEX idx_tile_task_groups_coords ON tile_task_groups (z, x, y);;
CREATE INDEX idx_tile_task_groups_zoom ON tile_task_groups (z) WHERE task_count > 0;;

-- Coordinate conversion functions
CREATE FUNCTION lng_to_tile_x(lng DOUBLE PRECISION, zoom INTEGER) RETURNS INTEGER AS $$
    SELECT FLOOR((lng + 180.0) / 360.0 * (1 << zoom))::INTEGER
$$ LANGUAGE SQL IMMUTABLE PARALLEL SAFE;;

CREATE FUNCTION lat_to_tile_y(lat DOUBLE PRECISION, zoom INTEGER) RETURNS INTEGER AS $$
    SELECT FLOOR((1.0 - LN(TAN(RADIANS(GREATEST(-85.0511, LEAST(85.0511, lat)))) +
           1.0 / COS(RADIANS(GREATEST(-85.0511, LEAST(85.0511, lat))))) / PI()) / 2.0 * (1 << zoom))::INTEGER
$$ LANGUAGE SQL IMMUTABLE PARALLEL SAFE;;

-- Function to rebuild a specific zoom level
CREATE FUNCTION rebuild_zoom_level(p_zoom INTEGER) RETURNS INTEGER AS $$
DECLARE
    groups_created INTEGER;;
    start_time TIMESTAMP;;
    deleted_count INTEGER;;
BEGIN
    start_time := clock_timestamp();;
    RAISE NOTICE 'Zoom %: Starting rebuild...', p_zoom;;

    DELETE FROM tile_task_groups WHERE z = p_zoom;;
    GET DIAGNOSTICS deleted_count = ROW_COUNT;;
    RAISE NOTICE 'Zoom %: Deleted % existing groups', p_zoom, deleted_count;;

    IF p_zoom = 14 THEN
        -- Zoom 14: Full detail - one entry per overlap group with task_ids
        -- Frontend will handle clustering for zoom levels 14-22
        INSERT INTO tile_task_groups (z, x, y, group_type, centroid_lat, centroid_lng, task_ids, task_count, counts_by_filter)
        WITH task_clusters AS (
            SELECT
                t.id as task_id,
                ST_Y(t.location) as lat,
                ST_X(t.location) as lng,
                lng_to_tile_x(ST_X(t.location), p_zoom) as tile_x,
                lat_to_tile_y(ST_Y(t.location), p_zoom) as tile_y,
                COALESCE(c.difficulty, 0) as difficulty,
                COALESCE(c.is_global, false) as is_global,
                ST_ClusterDBSCAN(t.location, eps := 0.000001, minpoints := 1) OVER (
                    PARTITION BY lng_to_tile_x(ST_X(t.location), p_zoom), lat_to_tile_y(ST_Y(t.location), p_zoom)
                ) as cluster_id
            FROM tasks t
            INNER JOIN challenges c ON c.id = t.parent_id
            INNER JOIN projects p ON p.id = c.parent_id
            WHERE t.location IS NOT NULL
              AND NOT ST_IsEmpty(t.location)
              AND ST_X(t.location) IS NOT NULL
              AND ST_Y(t.location) IS NOT NULL
              AND t.status IN (0, 3, 6)
              AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
              AND p.deleted = FALSE AND p.enabled = TRUE
        ),
        grouped_tasks AS (
            SELECT
                tile_x,
                tile_y,
                cluster_id,
                AVG(lat) as centroid_lat,
                AVG(lng) as centroid_lng,
                ARRAY_AGG(task_id ORDER BY task_id) as task_ids,
                COUNT(*)::INTEGER as task_count,
                jsonb_build_object(
                    'd1_gf', COUNT(*) FILTER (WHERE difficulty = 1 AND NOT is_global),
                    'd1_gt', COUNT(*) FILTER (WHERE difficulty = 1 AND is_global),
                    'd2_gf', COUNT(*) FILTER (WHERE difficulty = 2 AND NOT is_global),
                    'd2_gt', COUNT(*) FILTER (WHERE difficulty = 2 AND is_global),
                    'd3_gf', COUNT(*) FILTER (WHERE difficulty = 3 AND NOT is_global),
                    'd3_gt', COUNT(*) FILTER (WHERE difficulty = 3 AND is_global),
                    'd0_gf', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND NOT is_global),
                    'd0_gt', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND is_global)
                ) as counts_by_filter
            FROM task_clusters
            GROUP BY tile_x, tile_y, cluster_id
        )
        SELECT
            p_zoom as z,
            tile_x as x,
            tile_y as y,
            CASE WHEN task_count = 1 THEN 0 ELSE 1 END as group_type,  -- 0=single, 1=overlapping
            centroid_lat,
            centroid_lng,
            task_ids,
            task_count,
            counts_by_filter
        FROM grouped_tasks;;

        GET DIAGNOSTICS groups_created = ROW_COUNT;;
        RAISE NOTICE 'Zoom %: Created % task/overlap groups (full detail for frontend clustering)', p_zoom, groups_created;;
    ELSE
        -- Zoom 0-13: One entry per tile
        -- Detect if tile has one location (single/overlap) or multiple locations (cluster)
        INSERT INTO tile_task_groups (z, x, y, group_type, centroid_lat, centroid_lng, task_ids, task_count, counts_by_filter)
        WITH tile_tasks AS (
            SELECT
                t.id as task_id,
                ST_Y(t.location) as lat,
                ST_X(t.location) as lng,
                lng_to_tile_x(ST_X(t.location), p_zoom) as tile_x,
                lat_to_tile_y(ST_Y(t.location), p_zoom) as tile_y,
                COALESCE(c.difficulty, 0) as difficulty,
                COALESCE(c.is_global, false) as is_global,
                -- Detect overlaps within the tile (~0.1 meter precision)
                ST_ClusterDBSCAN(t.location, eps := 0.000001, minpoints := 1) OVER (
                    PARTITION BY lng_to_tile_x(ST_X(t.location), p_zoom), lat_to_tile_y(ST_Y(t.location), p_zoom)
                ) as overlap_cluster_id
            FROM tasks t
            INNER JOIN challenges c ON c.id = t.parent_id
            INNER JOIN projects p ON p.id = c.parent_id
            WHERE t.location IS NOT NULL
              AND NOT ST_IsEmpty(t.location)
              AND ST_X(t.location) IS NOT NULL
              AND ST_Y(t.location) IS NOT NULL
              AND t.status IN (0, 3, 6)
              AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
              AND p.deleted = FALSE AND p.enabled = TRUE
        ),
        tile_summary AS (
            SELECT
                tile_x,
                tile_y,
                COUNT(DISTINCT overlap_cluster_id)::INTEGER as num_locations,
                AVG(lat) as centroid_lat,
                AVG(lng) as centroid_lng,
                ARRAY_AGG(task_id ORDER BY task_id) as task_ids,
                COUNT(*)::INTEGER as task_count,
                jsonb_build_object(
                    'd1_gf', COUNT(*) FILTER (WHERE difficulty = 1 AND NOT is_global),
                    'd1_gt', COUNT(*) FILTER (WHERE difficulty = 1 AND is_global),
                    'd2_gf', COUNT(*) FILTER (WHERE difficulty = 2 AND NOT is_global),
                    'd2_gt', COUNT(*) FILTER (WHERE difficulty = 2 AND is_global),
                    'd3_gf', COUNT(*) FILTER (WHERE difficulty = 3 AND NOT is_global),
                    'd3_gt', COUNT(*) FILTER (WHERE difficulty = 3 AND is_global),
                    'd0_gf', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND NOT is_global),
                    'd0_gt', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND is_global)
                ) as counts_by_filter
            FROM tile_tasks
            GROUP BY tile_x, tile_y
        )
        SELECT
            p_zoom as z,
            tile_x as x,
            tile_y as y,
            CASE
                WHEN num_locations = 1 AND task_count = 1 THEN 0  -- single task (isolated)
                WHEN num_locations = 1 THEN 1                     -- overlapping tasks (same location)
                ELSE 2                                            -- cluster (multiple locations)
            END as group_type,
            centroid_lat,
            centroid_lng,
            CASE
                WHEN num_locations = 1 THEN task_ids              -- single/overlap: store task_ids
                ELSE ARRAY[]::BIGINT[]                            -- cluster: no task_ids
            END as task_ids,
            task_count,
            counts_by_filter
        FROM tile_summary;;

        GET DIAGNOSTICS groups_created = ROW_COUNT;;
        RAISE NOTICE 'Zoom %: Created % tile entries (singles/overlaps/clusters)', p_zoom, groups_created;;
    END IF;;

    RAISE NOTICE 'Zoom %: Completed in % ms', p_zoom, EXTRACT(MILLISECONDS FROM (clock_timestamp() - start_time))::INTEGER;;
    RETURN groups_created;;
END
$$ LANGUAGE plpgsql;;

-- Function to rebuild all zoom levels
CREATE FUNCTION rebuild_all_tile_aggregates() RETURNS TABLE(zoom_level INTEGER, tiles_created INTEGER) AS $$
DECLARE
    total_start TIMESTAMP;;
    total_groups INTEGER := 0;;
BEGIN
    total_start := clock_timestamp();;
    RAISE NOTICE '=== Starting full tile aggregate rebuild ===';;
    RAISE NOTICE 'Zoom 0-13: Singles, overlaps, or clusters per tile';;
    RAISE NOTICE 'Zoom 14: Individual tasks + overlaps (for frontend clustering at 14-22)';;
    RAISE NOTICE '';;

    FOR zoom_level IN 0..14 LOOP
        tiles_created := rebuild_zoom_level(zoom_level);;
        total_groups := total_groups + tiles_created;;
        RETURN NEXT;;
    END LOOP;;

    RAISE NOTICE '';;
    RAISE NOTICE '=== Rebuild complete ===';;
    RAISE NOTICE 'Total groups created: %', total_groups;;
    RAISE NOTICE 'Total time: % ms', EXTRACT(MILLISECONDS FROM (clock_timestamp() - total_start))::INTEGER;;
END
$$ LANGUAGE plpgsql;;

-- Run after setup: SELECT * FROM rebuild_all_tile_aggregates();

# --- !Downs

DROP FUNCTION IF EXISTS rebuild_all_tile_aggregates();;
DROP FUNCTION IF EXISTS rebuild_zoom_level(INTEGER);;
DROP FUNCTION IF EXISTS lat_to_tile_y(DOUBLE PRECISION, INTEGER);;
DROP FUNCTION IF EXISTS lng_to_tile_x(DOUBLE PRECISION, INTEGER);;
DROP INDEX IF EXISTS idx_tile_task_groups_zoom;;
DROP INDEX IF EXISTS idx_tile_task_groups_coords;;
DROP TABLE IF EXISTS tile_task_groups;;

# --- !Ups

CREATE TABLE tile_aggregates (
    z SMALLINT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    task_count INTEGER DEFAULT 0,
    counts_by_filter JSONB DEFAULT '{}'::jsonb,
    centroid_lat DOUBLE PRECISION,
    centroid_lng DOUBLE PRECISION,
    last_updated TIMESTAMP WITHOUT TIME ZONE DEFAULT NOW(),
    PRIMARY KEY (z, x, y)
);;

CREATE INDEX idx_tile_aggregates_count ON tile_aggregates (task_count) WHERE task_count > 0;;

CREATE FUNCTION lng_to_tile_x(lng DOUBLE PRECISION, zoom INTEGER) RETURNS INTEGER AS $$
    SELECT FLOOR((lng + 180.0) / 360.0 * (1 << zoom))::INTEGER
$$ LANGUAGE SQL IMMUTABLE PARALLEL SAFE;;

CREATE FUNCTION lat_to_tile_y(lat DOUBLE PRECISION, zoom INTEGER) RETURNS INTEGER AS $$
    SELECT FLOOR((1.0 - LN(TAN(RADIANS(GREATEST(-85.0511, LEAST(85.0511, lat)))) +
           1.0 / COS(RADIANS(GREATEST(-85.0511, LEAST(85.0511, lat))))) / PI()) / 2.0 * (1 << zoom))::INTEGER
$$ LANGUAGE SQL IMMUTABLE PARALLEL SAFE;;

CREATE FUNCTION rebuild_zoom_level(p_zoom INTEGER) RETURNS INTEGER AS $$
DECLARE
    tiles_affected INTEGER;;
BEGIN
    DELETE FROM tile_aggregates WHERE z = p_zoom;;

    INSERT INTO tile_aggregates (z, x, y, task_count, counts_by_filter, centroid_lat, centroid_lng)
    SELECT
        p_zoom,
        lng_to_tile_x(ST_X(t.location), p_zoom),
        lat_to_tile_y(ST_Y(t.location), p_zoom),
        COUNT(*)::INTEGER,
        jsonb_build_object(
            'd1_gf', COUNT(*) FILTER (WHERE COALESCE(c.difficulty, 0) = 1 AND NOT COALESCE(c.is_global, false)),
            'd1_gt', COUNT(*) FILTER (WHERE COALESCE(c.difficulty, 0) = 1 AND COALESCE(c.is_global, false)),
            'd2_gf', COUNT(*) FILTER (WHERE COALESCE(c.difficulty, 0) = 2 AND NOT COALESCE(c.is_global, false)),
            'd2_gt', COUNT(*) FILTER (WHERE COALESCE(c.difficulty, 0) = 2 AND COALESCE(c.is_global, false)),
            'd3_gf', COUNT(*) FILTER (WHERE COALESCE(c.difficulty, 0) = 3 AND NOT COALESCE(c.is_global, false)),
            'd3_gt', COUNT(*) FILTER (WHERE COALESCE(c.difficulty, 0) = 3 AND COALESCE(c.is_global, false)),
            'd0_gf', COUNT(*) FILTER (WHERE COALESCE(c.difficulty, 0) NOT IN (1,2,3) AND NOT COALESCE(c.is_global, false)),
            'd0_gt', COUNT(*) FILTER (WHERE COALESCE(c.difficulty, 0) NOT IN (1,2,3) AND COALESCE(c.is_global, false))
        ),
        AVG(ST_Y(t.location)),
        AVG(ST_X(t.location))
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
    GROUP BY lng_to_tile_x(ST_X(t.location), p_zoom), lat_to_tile_y(ST_Y(t.location), p_zoom);;

    GET DIAGNOSTICS tiles_affected = ROW_COUNT;;
    RETURN tiles_affected;;
END
$$ LANGUAGE plpgsql;;

CREATE FUNCTION rebuild_all_tile_aggregates() RETURNS TABLE(zoom_level INTEGER, tiles_created INTEGER) AS $$
BEGIN
    FOR zoom_level IN 0..14 LOOP
        tiles_created := rebuild_zoom_level(zoom_level);;
        RETURN NEXT;;
    END LOOP;;
END
$$ LANGUAGE plpgsql;;

-- Run after setup: SELECT * FROM rebuild_all_tile_aggregates();

# --- !Downs

DROP FUNCTION IF EXISTS rebuild_zoom_level(INTEGER);;
DROP FUNCTION IF EXISTS rebuild_all_tile_aggregates();;
DROP FUNCTION IF EXISTS lat_to_tile_y(DOUBLE PRECISION, INTEGER);;
DROP FUNCTION IF EXISTS lng_to_tile_x(DOUBLE PRECISION, INTEGER);;
DROP TABLE IF EXISTS tile_aggregates;;

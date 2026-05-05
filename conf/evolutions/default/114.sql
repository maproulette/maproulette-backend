# --- MapRoulette Scheme

# --- !Ups
-- =============================================================================
-- Coordinate-range guard for tile aggregate rebuild paths.
--
-- A handful of challenges have been ingested with non-WGS84 coordinates in
-- tasks.location (most commonly raw EPSG:3857 meters, occasionally swapped
-- lat/lng or genuine garbage). When the rebuild pipeline calls
-- ST_Transform(t.location, 3857) on those rows PROJ raises error 2049
-- ("Invalid coordinate") and aborts the entire rebuild loop, so a single
-- bad row at any zoom level prevents every tile from being rebuilt.
--
-- This evolution re-creates rebuild_zoom_level, rebuild_dirty_tiles, and
-- rebuild_recent_dirty_tiles with an additional coordinate-range filter at
-- the eligible-tasks predicate. Rows whose location is outside Web Mercator's
-- valid range are silently skipped instead of aborting the rebuild. The
-- filter is also applied at z=12 (which doesn't call ST_Transform) for
-- consistency, so a malformed task is excluded uniformly across zooms
-- rather than appearing on the high-zoom map but breaking lower zooms.
--
-- The script in scripts/fix_bad_task_locations.sql repairs the existing
-- bad rows; this guard keeps a future bad import from taking down the
-- rebuild loop again. Both are needed: cleanup heals the data, the guard
-- contains the blast radius.
-- =============================================================================

CREATE OR REPLACE FUNCTION rebuild_zoom_level(p_zoom INTEGER) RETURNS INTEGER AS $$
DECLARE
  groups_created INTEGER := 0;;
  start_time TIMESTAMP;;
  eps_meters DOUBLE PRECISION;;
  pt RECORD;;
  neighbor_ids BIGINT[];;
  cluster_rec RECORD;;
BEGIN
  IF p_zoom < 0 OR p_zoom > 12 THEN
    RAISE NOTICE 'Zoom % is outside supported range 0..12, skipping', p_zoom;;
    RETURN 0;;
  END IF;;
  start_time := clock_timestamp();;
  RAISE NOTICE 'Zoom %: Starting rebuild...', p_zoom;;

  DELETE FROM tile_task_groups WHERE z = p_zoom;;

  IF p_zoom = 12 THEN
    INSERT INTO tile_task_groups (z, x, y, group_type, centroid_lat, centroid_lng, task_ids, task_count, counts_by_filter)
    WITH eligible_tasks AS (
      SELECT
        t.id, t.location,
        ST_Y(t.location) AS lat,
        ST_X(t.location) AS lng,
        lng_to_tile_x(ST_X(t.location), p_zoom) AS tile_x,
        lat_to_tile_y(ST_Y(t.location), p_zoom) AS tile_y,
        COALESCE(c.difficulty, 0) AS difficulty,
        COALESCE(c.is_global, false) AS is_global
      FROM tasks t
      INNER JOIN challenges c ON c.id = t.parent_id
      INNER JOIN projects   p ON p.id = c.parent_id
      WHERE t.location IS NOT NULL
        AND NOT ST_IsEmpty(t.location)
        AND ST_X(t.location) BETWEEN -180 AND 180
        AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
        AND t.status IN (0, 3, 6)
        AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
        AND p.deleted = FALSE AND p.enabled = TRUE
    ),
    overlap_groups AS (
      SELECT
        tile_x, tile_y,
        ST_ClusterDBSCAN(location, eps := 0.000001, minpoints := 1)
          OVER (PARTITION BY tile_x, tile_y) AS overlap_cluster_id,
        id, lat, lng, difficulty, is_global
      FROM eligible_tasks
    )
    SELECT
      p_zoom,
      tile_x, tile_y,
      CASE WHEN COUNT(*) = 1 THEN 0 ELSE 1 END,
      AVG(lat), AVG(lng),
      ARRAY_AGG(id ORDER BY id),
      COUNT(*)::INTEGER,
      jsonb_build_object(
        'd1_gf', COUNT(*) FILTER (WHERE difficulty = 1 AND NOT is_global),
        'd1_gt', COUNT(*) FILTER (WHERE difficulty = 1 AND is_global),
        'd2_gf', COUNT(*) FILTER (WHERE difficulty = 2 AND NOT is_global),
        'd2_gt', COUNT(*) FILTER (WHERE difficulty = 2 AND is_global),
        'd3_gf', COUNT(*) FILTER (WHERE difficulty = 3 AND NOT is_global),
        'd3_gt', COUNT(*) FILTER (WHERE difficulty = 3 AND is_global),
        'd0_gf', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND NOT is_global),
        'd0_gt', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND is_global)
      )
    FROM overlap_groups
    GROUP BY tile_x, tile_y, overlap_cluster_id;;

    GET DIAGNOSTICS groups_created = ROW_COUNT;;
  ELSE
    eps_meters := 200.0 * tile_pixel_size_meters(p_zoom);;

    DROP TABLE IF EXISTS tmp_cluster_pts;;
    CREATE TEMP TABLE tmp_cluster_pts (
      id BIGINT PRIMARY KEY,
      loc3857 geometry(Point, 3857),
      lat DOUBLE PRECISION,
      lng DOUBLE PRECISION,
      difficulty INTEGER,
      is_global BOOLEAN,
      claimed BOOLEAN NOT NULL DEFAULT false
    );;
    INSERT INTO tmp_cluster_pts (id, loc3857, lat, lng, difficulty, is_global)
    SELECT t.id,
           ST_Transform(t.location, 3857),
           ST_Y(t.location),
           ST_X(t.location),
           COALESCE(c.difficulty, 0),
           COALESCE(c.is_global, false)
    FROM tasks t
    INNER JOIN challenges c ON c.id = t.parent_id
    INNER JOIN projects   p ON p.id = c.parent_id
    WHERE t.location IS NOT NULL
      AND NOT ST_IsEmpty(t.location)
      AND ST_X(t.location) BETWEEN -180 AND 180
      AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
      AND t.status IN (0, 3, 6)
      AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
      AND p.deleted = FALSE AND p.enabled = TRUE;;
    CREATE INDEX ON tmp_cluster_pts USING GIST (loc3857);;
    CREATE INDEX ON tmp_cluster_pts (claimed) WHERE claimed = false;;
    ANALYZE tmp_cluster_pts;;

    FOR pt IN
      SELECT id, loc3857 FROM tmp_cluster_pts ORDER BY id
    LOOP
      PERFORM 1 FROM tmp_cluster_pts WHERE id = pt.id AND claimed = true;;
      IF FOUND THEN CONTINUE;; END IF;;

      SELECT
        ARRAY_AGG(id ORDER BY id),
        ST_Y(ST_Transform(ST_Centroid(ST_Collect(loc3857)), 4326)),
        ST_X(ST_Transform(ST_Centroid(ST_Collect(loc3857)), 4326)),
        COUNT(*)::int,
        jsonb_build_object(
          'd1_gf', COUNT(*) FILTER (WHERE difficulty = 1 AND NOT is_global),
          'd1_gt', COUNT(*) FILTER (WHERE difficulty = 1 AND is_global),
          'd2_gf', COUNT(*) FILTER (WHERE difficulty = 2 AND NOT is_global),
          'd2_gt', COUNT(*) FILTER (WHERE difficulty = 2 AND is_global),
          'd3_gf', COUNT(*) FILTER (WHERE difficulty = 3 AND NOT is_global),
          'd3_gt', COUNT(*) FILTER (WHERE difficulty = 3 AND is_global),
          'd0_gf', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND NOT is_global),
          'd0_gt', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND is_global)
        )
      INTO cluster_rec
      FROM tmp_cluster_pts
      WHERE NOT claimed
        AND ST_DWithin(loc3857, pt.loc3857, eps_meters);;

      neighbor_ids := cluster_rec.array_agg;;

      UPDATE tmp_cluster_pts
      SET claimed = true
      WHERE id = ANY(neighbor_ids);;

      INSERT INTO tile_task_groups (
        z, x, y, group_type,
        centroid_lat, centroid_lng,
        task_ids, task_count, counts_by_filter
      )
      VALUES (
        p_zoom,
        lng_to_tile_x(cluster_rec.st_x, p_zoom),
        lat_to_tile_y(cluster_rec.st_y, p_zoom),
        CASE WHEN cluster_rec.count = 1 THEN 0 ELSE 2 END,
        cluster_rec.st_y,
        cluster_rec.st_x,
        CASE WHEN cluster_rec.count = 1 THEN neighbor_ids ELSE ARRAY[]::BIGINT[] END,
        cluster_rec.count,
        cluster_rec.jsonb_build_object
      );;

      groups_created := groups_created + 1;;
    END LOOP;;

    DROP TABLE IF EXISTS tmp_cluster_pts;;
  END IF;;

  DELETE FROM tile_dirty_marks WHERE z = p_zoom;;

  RAISE NOTICE 'Zoom %: Created % entries in % ms', p_zoom, groups_created,
    EXTRACT(MILLISECONDS FROM (clock_timestamp() - start_time))::INTEGER;;
  RETURN groups_created;;
END;;
$$ LANGUAGE plpgsql;;

CREATE OR REPLACE FUNCTION rebuild_dirty_tiles(
  p_limit INTEGER DEFAULT 500,
  p_min_zoom INTEGER DEFAULT 0,
  p_max_zoom INTEGER DEFAULT 22
) RETURNS INTEGER AS $$
DECLARE
  processed INTEGER := 0;;
  mark RECORD;;
  eps_meters DOUBLE PRECISION;;
  pt RECORD;;
  neighbor_ids BIGINT[];;
  cluster_rec RECORD;;
BEGIN
  FOR mark IN
    DELETE FROM tile_dirty_marks
    WHERE (z, x, y) IN (
      SELECT z, x, y FROM tile_dirty_marks
      WHERE z BETWEEN p_min_zoom AND p_max_zoom
      ORDER BY marked_at ASC
      LIMIT p_limit
    )
    RETURNING z, x, y
  LOOP
    DELETE FROM tile_task_groups WHERE z = mark.z AND x = mark.x AND y = mark.y;;

    IF mark.z = 12 THEN
      INSERT INTO tile_task_groups (z, x, y, group_type, centroid_lat, centroid_lng, task_ids, task_count, counts_by_filter)
      WITH eligible_tasks AS (
        SELECT t.id, t.location,
               ST_Y(t.location) AS lat, ST_X(t.location) AS lng,
               COALESCE(c.difficulty, 0) AS difficulty,
               COALESCE(c.is_global, false) AS is_global
        FROM tasks t
        INNER JOIN challenges c ON c.id = t.parent_id
        INNER JOIN projects   p ON p.id = c.parent_id
        WHERE t.location IS NOT NULL AND NOT ST_IsEmpty(t.location)
          AND ST_X(t.location) BETWEEN -180 AND 180
          AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
          AND t.status IN (0, 3, 6)
          AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
          AND p.deleted = FALSE AND p.enabled = TRUE
          AND lng_to_tile_x(ST_X(t.location), mark.z) = mark.x
          AND lat_to_tile_y(ST_Y(t.location), mark.z) = mark.y
      ),
      overlap_groups AS (
        SELECT ST_ClusterDBSCAN(location, eps := 0.000001, minpoints := 1) OVER () AS overlap_cluster_id,
               id, lat, lng, difficulty, is_global
        FROM eligible_tasks
      )
      SELECT mark.z, mark.x, mark.y,
             CASE WHEN COUNT(*) = 1 THEN 0 ELSE 1 END,
             AVG(lat), AVG(lng),
             ARRAY_AGG(id ORDER BY id),
             COUNT(*)::INTEGER,
             jsonb_build_object(
               'd1_gf', COUNT(*) FILTER (WHERE difficulty = 1 AND NOT is_global),
               'd1_gt', COUNT(*) FILTER (WHERE difficulty = 1 AND is_global),
               'd2_gf', COUNT(*) FILTER (WHERE difficulty = 2 AND NOT is_global),
               'd2_gt', COUNT(*) FILTER (WHERE difficulty = 2 AND is_global),
               'd3_gf', COUNT(*) FILTER (WHERE difficulty = 3 AND NOT is_global),
               'd3_gt', COUNT(*) FILTER (WHERE difficulty = 3 AND is_global),
               'd0_gf', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND NOT is_global),
               'd0_gt', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND is_global)
             )
      FROM overlap_groups
      GROUP BY overlap_cluster_id;;
    ELSE
      eps_meters := 200.0 * tile_pixel_size_meters(mark.z);;

      DROP TABLE IF EXISTS tmp_cluster_tile;;
      CREATE TEMP TABLE tmp_cluster_tile (
        id BIGINT PRIMARY KEY,
        loc3857 geometry(Point, 3857),
        lat DOUBLE PRECISION,
        lng DOUBLE PRECISION,
        difficulty INTEGER,
        is_global BOOLEAN,
        claimed BOOLEAN NOT NULL DEFAULT false
      );;
      INSERT INTO tmp_cluster_tile (id, loc3857, lat, lng, difficulty, is_global)
      SELECT t.id, ST_Transform(t.location, 3857),
             ST_Y(t.location), ST_X(t.location),
             COALESCE(c.difficulty, 0), COALESCE(c.is_global, false)
      FROM tasks t
      INNER JOIN challenges c ON c.id = t.parent_id
      INNER JOIN projects   p ON p.id = c.parent_id
      WHERE t.location IS NOT NULL AND NOT ST_IsEmpty(t.location)
        AND ST_X(t.location) BETWEEN -180 AND 180
        AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
        AND t.status IN (0, 3, 6)
        AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
        AND p.deleted = FALSE AND p.enabled = TRUE
        AND lng_to_tile_x(ST_X(t.location), mark.z) = mark.x
        AND lat_to_tile_y(ST_Y(t.location), mark.z) = mark.y;;
      CREATE INDEX ON tmp_cluster_tile USING GIST (loc3857);;

      FOR pt IN SELECT id, loc3857 FROM tmp_cluster_tile ORDER BY id LOOP
        PERFORM 1 FROM tmp_cluster_tile WHERE id = pt.id AND claimed = true;;
        IF FOUND THEN CONTINUE;; END IF;;

        SELECT ARRAY_AGG(id ORDER BY id),
               ST_Y(ST_Transform(ST_Centroid(ST_Collect(loc3857)), 4326)),
               ST_X(ST_Transform(ST_Centroid(ST_Collect(loc3857)), 4326)),
               COUNT(*)::int,
               jsonb_build_object(
                 'd1_gf', COUNT(*) FILTER (WHERE difficulty = 1 AND NOT is_global),
                 'd1_gt', COUNT(*) FILTER (WHERE difficulty = 1 AND is_global),
                 'd2_gf', COUNT(*) FILTER (WHERE difficulty = 2 AND NOT is_global),
                 'd2_gt', COUNT(*) FILTER (WHERE difficulty = 2 AND is_global),
                 'd3_gf', COUNT(*) FILTER (WHERE difficulty = 3 AND NOT is_global),
                 'd3_gt', COUNT(*) FILTER (WHERE difficulty = 3 AND is_global),
                 'd0_gf', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND NOT is_global),
                 'd0_gt', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND is_global)
               )
          INTO cluster_rec
          FROM tmp_cluster_tile
          WHERE NOT claimed
            AND ST_DWithin(loc3857, pt.loc3857, eps_meters);;

        neighbor_ids := cluster_rec.array_agg;;
        UPDATE tmp_cluster_tile SET claimed = true WHERE id = ANY(neighbor_ids);;

        INSERT INTO tile_task_groups (
          z, x, y, group_type, centroid_lat, centroid_lng,
          task_ids, task_count, counts_by_filter
        ) VALUES (
          mark.z, mark.x, mark.y,
          CASE WHEN cluster_rec.count = 1 THEN 0 ELSE 1 END,
          cluster_rec.st_y, cluster_rec.st_x,
          neighbor_ids, cluster_rec.count, cluster_rec.jsonb_build_object
        );;
      END LOOP;;
    END IF;;

    processed := processed + 1;;
  END LOOP;;

  RETURN processed;;
END;;
$$ LANGUAGE plpgsql;;

CREATE OR REPLACE FUNCTION rebuild_recent_dirty_tiles(
  p_limit INTEGER DEFAULT 20,
  p_min_zoom INTEGER DEFAULT 13,
  p_max_zoom INTEGER DEFAULT 22
) RETURNS INTEGER AS $$
DECLARE
  processed INTEGER := 0;;
  mark RECORD;;
  eps_meters DOUBLE PRECISION;;
  pt RECORD;;
  neighbor_ids BIGINT[];;
  cluster_rec RECORD;;
BEGIN
  FOR mark IN
    DELETE FROM tile_dirty_marks
    WHERE (z, x, y) IN (
      SELECT z, x, y FROM tile_dirty_marks
      WHERE z BETWEEN p_min_zoom AND p_max_zoom
      ORDER BY marked_at DESC
      LIMIT p_limit
    )
    RETURNING z, x, y
  LOOP
    DELETE FROM tile_task_groups WHERE z = mark.z AND x = mark.x AND y = mark.y;;

    IF mark.z = 12 THEN
      INSERT INTO tile_task_groups (z, x, y, group_type, centroid_lat, centroid_lng, task_ids, task_count, counts_by_filter)
      WITH eligible_tasks AS (
        SELECT t.id, t.location,
               ST_Y(t.location) AS lat, ST_X(t.location) AS lng,
               COALESCE(c.difficulty, 0) AS difficulty,
               COALESCE(c.is_global, false) AS is_global
        FROM tasks t
        INNER JOIN challenges c ON c.id = t.parent_id
        INNER JOIN projects   p ON p.id = c.parent_id
        WHERE t.location IS NOT NULL AND NOT ST_IsEmpty(t.location)
          AND ST_X(t.location) BETWEEN -180 AND 180
          AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
          AND t.status IN (0, 3, 6)
          AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
          AND p.deleted = FALSE AND p.enabled = TRUE
          AND lng_to_tile_x(ST_X(t.location), mark.z) = mark.x
          AND lat_to_tile_y(ST_Y(t.location), mark.z) = mark.y
      ),
      overlap_groups AS (
        SELECT ST_ClusterDBSCAN(location, eps := 0.000001, minpoints := 1) OVER () AS overlap_cluster_id,
               id, lat, lng, difficulty, is_global
        FROM eligible_tasks
      )
      SELECT mark.z, mark.x, mark.y,
             CASE WHEN COUNT(*) = 1 THEN 0 ELSE 1 END,
             AVG(lat), AVG(lng),
             ARRAY_AGG(id ORDER BY id),
             COUNT(*)::INTEGER,
             jsonb_build_object(
               'd1_gf', COUNT(*) FILTER (WHERE difficulty = 1 AND NOT is_global),
               'd1_gt', COUNT(*) FILTER (WHERE difficulty = 1 AND is_global),
               'd2_gf', COUNT(*) FILTER (WHERE difficulty = 2 AND NOT is_global),
               'd2_gt', COUNT(*) FILTER (WHERE difficulty = 2 AND is_global),
               'd3_gf', COUNT(*) FILTER (WHERE difficulty = 3 AND NOT is_global),
               'd3_gt', COUNT(*) FILTER (WHERE difficulty = 3 AND is_global),
               'd0_gf', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND NOT is_global),
               'd0_gt', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND is_global)
             )
      FROM overlap_groups
      GROUP BY overlap_cluster_id;;
    ELSE
      eps_meters := 200.0 * tile_pixel_size_meters(mark.z);;

      DROP TABLE IF EXISTS tmp_cluster_tile;;
      CREATE TEMP TABLE tmp_cluster_tile (
        id BIGINT PRIMARY KEY,
        loc3857 geometry(Point, 3857),
        lat DOUBLE PRECISION,
        lng DOUBLE PRECISION,
        difficulty INTEGER,
        is_global BOOLEAN,
        claimed BOOLEAN NOT NULL DEFAULT false
      );;
      INSERT INTO tmp_cluster_tile (id, loc3857, lat, lng, difficulty, is_global)
      SELECT t.id, ST_Transform(t.location, 3857),
             ST_Y(t.location), ST_X(t.location),
             COALESCE(c.difficulty, 0), COALESCE(c.is_global, false)
      FROM tasks t
      INNER JOIN challenges c ON c.id = t.parent_id
      INNER JOIN projects   p ON p.id = c.parent_id
      WHERE t.location IS NOT NULL AND NOT ST_IsEmpty(t.location)
        AND ST_X(t.location) BETWEEN -180 AND 180
        AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
        AND t.status IN (0, 3, 6)
        AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
        AND p.deleted = FALSE AND p.enabled = TRUE
        AND lng_to_tile_x(ST_X(t.location), mark.z) = mark.x
        AND lat_to_tile_y(ST_Y(t.location), mark.z) = mark.y;;
      CREATE INDEX ON tmp_cluster_tile USING GIST (loc3857);;

      FOR pt IN SELECT id, loc3857 FROM tmp_cluster_tile ORDER BY id LOOP
        PERFORM 1 FROM tmp_cluster_tile WHERE id = pt.id AND claimed = true;;
        IF FOUND THEN CONTINUE;; END IF;;

        SELECT ARRAY_AGG(id ORDER BY id),
               ST_Y(ST_Transform(ST_Centroid(ST_Collect(loc3857)), 4326)),
               ST_X(ST_Transform(ST_Centroid(ST_Collect(loc3857)), 4326)),
               COUNT(*)::int,
               jsonb_build_object(
                 'd1_gf', COUNT(*) FILTER (WHERE difficulty = 1 AND NOT is_global),
                 'd1_gt', COUNT(*) FILTER (WHERE difficulty = 1 AND is_global),
                 'd2_gf', COUNT(*) FILTER (WHERE difficulty = 2 AND NOT is_global),
                 'd2_gt', COUNT(*) FILTER (WHERE difficulty = 2 AND is_global),
                 'd3_gf', COUNT(*) FILTER (WHERE difficulty = 3 AND NOT is_global),
                 'd3_gt', COUNT(*) FILTER (WHERE difficulty = 3 AND is_global),
                 'd0_gf', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND NOT is_global),
                 'd0_gt', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND is_global)
               )
          INTO cluster_rec
          FROM tmp_cluster_tile
          WHERE NOT claimed
            AND ST_DWithin(loc3857, pt.loc3857, eps_meters);;

        neighbor_ids := cluster_rec.array_agg;;
        UPDATE tmp_cluster_tile SET claimed = true WHERE id = ANY(neighbor_ids);;

        INSERT INTO tile_task_groups (
          z, x, y, group_type, centroid_lat, centroid_lng,
          task_ids, task_count, counts_by_filter
        ) VALUES (
          mark.z, mark.x, mark.y,
          CASE WHEN cluster_rec.count = 1 THEN 0 ELSE 1 END,
          cluster_rec.st_y, cluster_rec.st_x,
          neighbor_ids, cluster_rec.count, cluster_rec.jsonb_build_object
        );;
      END LOOP;;
    END IF;;

    processed := processed + 1;;
  END LOOP;;

  RETURN processed;;
END;;
$$ LANGUAGE plpgsql;;

# --- !Downs
-- Restore the pre-guard function bodies from evolutions 111 and 113.

CREATE OR REPLACE FUNCTION rebuild_zoom_level(p_zoom INTEGER) RETURNS INTEGER AS $$
DECLARE
  groups_created INTEGER := 0;;
  start_time TIMESTAMP;;
  eps_meters DOUBLE PRECISION;;
  pt RECORD;;
  neighbor_ids BIGINT[];;
  cluster_rec RECORD;;
BEGIN
  IF p_zoom < 0 OR p_zoom > 12 THEN
    RAISE NOTICE 'Zoom % is outside supported range 0..12, skipping', p_zoom;;
    RETURN 0;;
  END IF;;
  start_time := clock_timestamp();;
  RAISE NOTICE 'Zoom %: Starting rebuild...', p_zoom;;

  DELETE FROM tile_task_groups WHERE z = p_zoom;;

  IF p_zoom = 12 THEN
    INSERT INTO tile_task_groups (z, x, y, group_type, centroid_lat, centroid_lng, task_ids, task_count, counts_by_filter)
    WITH eligible_tasks AS (
      SELECT
        t.id, t.location,
        ST_Y(t.location) AS lat,
        ST_X(t.location) AS lng,
        lng_to_tile_x(ST_X(t.location), p_zoom) AS tile_x,
        lat_to_tile_y(ST_Y(t.location), p_zoom) AS tile_y,
        COALESCE(c.difficulty, 0) AS difficulty,
        COALESCE(c.is_global, false) AS is_global
      FROM tasks t
      INNER JOIN challenges c ON c.id = t.parent_id
      INNER JOIN projects   p ON p.id = c.parent_id
      WHERE t.location IS NOT NULL
        AND NOT ST_IsEmpty(t.location)
        AND t.status IN (0, 3, 6)
        AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
        AND p.deleted = FALSE AND p.enabled = TRUE
    ),
    overlap_groups AS (
      SELECT
        tile_x, tile_y,
        ST_ClusterDBSCAN(location, eps := 0.000001, minpoints := 1)
          OVER (PARTITION BY tile_x, tile_y) AS overlap_cluster_id,
        id, lat, lng, difficulty, is_global
      FROM eligible_tasks
    )
    SELECT
      p_zoom,
      tile_x, tile_y,
      CASE WHEN COUNT(*) = 1 THEN 0 ELSE 1 END,
      AVG(lat), AVG(lng),
      ARRAY_AGG(id ORDER BY id),
      COUNT(*)::INTEGER,
      jsonb_build_object(
        'd1_gf', COUNT(*) FILTER (WHERE difficulty = 1 AND NOT is_global),
        'd1_gt', COUNT(*) FILTER (WHERE difficulty = 1 AND is_global),
        'd2_gf', COUNT(*) FILTER (WHERE difficulty = 2 AND NOT is_global),
        'd2_gt', COUNT(*) FILTER (WHERE difficulty = 2 AND is_global),
        'd3_gf', COUNT(*) FILTER (WHERE difficulty = 3 AND NOT is_global),
        'd3_gt', COUNT(*) FILTER (WHERE difficulty = 3 AND is_global),
        'd0_gf', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND NOT is_global),
        'd0_gt', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND is_global)
      )
    FROM overlap_groups
    GROUP BY tile_x, tile_y, overlap_cluster_id;;

    GET DIAGNOSTICS groups_created = ROW_COUNT;;
  ELSE
    eps_meters := 200.0 * tile_pixel_size_meters(p_zoom);;

    DROP TABLE IF EXISTS tmp_cluster_pts;;
    CREATE TEMP TABLE tmp_cluster_pts (
      id BIGINT PRIMARY KEY,
      loc3857 geometry(Point, 3857),
      lat DOUBLE PRECISION,
      lng DOUBLE PRECISION,
      difficulty INTEGER,
      is_global BOOLEAN,
      claimed BOOLEAN NOT NULL DEFAULT false
    );;
    INSERT INTO tmp_cluster_pts (id, loc3857, lat, lng, difficulty, is_global)
    SELECT t.id,
           ST_Transform(t.location, 3857),
           ST_Y(t.location),
           ST_X(t.location),
           COALESCE(c.difficulty, 0),
           COALESCE(c.is_global, false)
    FROM tasks t
    INNER JOIN challenges c ON c.id = t.parent_id
    INNER JOIN projects   p ON p.id = c.parent_id
    WHERE t.location IS NOT NULL
      AND NOT ST_IsEmpty(t.location)
      AND t.status IN (0, 3, 6)
      AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
      AND p.deleted = FALSE AND p.enabled = TRUE;;
    CREATE INDEX ON tmp_cluster_pts USING GIST (loc3857);;
    CREATE INDEX ON tmp_cluster_pts (claimed) WHERE claimed = false;;
    ANALYZE tmp_cluster_pts;;

    FOR pt IN
      SELECT id, loc3857 FROM tmp_cluster_pts ORDER BY id
    LOOP
      PERFORM 1 FROM tmp_cluster_pts WHERE id = pt.id AND claimed = true;;
      IF FOUND THEN CONTINUE;; END IF;;

      SELECT
        ARRAY_AGG(id ORDER BY id),
        ST_Y(ST_Transform(ST_Centroid(ST_Collect(loc3857)), 4326)),
        ST_X(ST_Transform(ST_Centroid(ST_Collect(loc3857)), 4326)),
        COUNT(*)::int,
        jsonb_build_object(
          'd1_gf', COUNT(*) FILTER (WHERE difficulty = 1 AND NOT is_global),
          'd1_gt', COUNT(*) FILTER (WHERE difficulty = 1 AND is_global),
          'd2_gf', COUNT(*) FILTER (WHERE difficulty = 2 AND NOT is_global),
          'd2_gt', COUNT(*) FILTER (WHERE difficulty = 2 AND is_global),
          'd3_gf', COUNT(*) FILTER (WHERE difficulty = 3 AND NOT is_global),
          'd3_gt', COUNT(*) FILTER (WHERE difficulty = 3 AND is_global),
          'd0_gf', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND NOT is_global),
          'd0_gt', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND is_global)
        )
      INTO cluster_rec
      FROM tmp_cluster_pts
      WHERE NOT claimed
        AND ST_DWithin(loc3857, pt.loc3857, eps_meters);;

      neighbor_ids := cluster_rec.array_agg;;

      UPDATE tmp_cluster_pts
      SET claimed = true
      WHERE id = ANY(neighbor_ids);;

      INSERT INTO tile_task_groups (
        z, x, y, group_type,
        centroid_lat, centroid_lng,
        task_ids, task_count, counts_by_filter
      )
      VALUES (
        p_zoom,
        lng_to_tile_x(cluster_rec.st_x, p_zoom),
        lat_to_tile_y(cluster_rec.st_y, p_zoom),
        CASE WHEN cluster_rec.count = 1 THEN 0 ELSE 2 END,
        cluster_rec.st_y,
        cluster_rec.st_x,
        CASE WHEN cluster_rec.count = 1 THEN neighbor_ids ELSE ARRAY[]::BIGINT[] END,
        cluster_rec.count,
        cluster_rec.jsonb_build_object
      );;

      groups_created := groups_created + 1;;
    END LOOP;;

    DROP TABLE IF EXISTS tmp_cluster_pts;;
  END IF;;

  DELETE FROM tile_dirty_marks WHERE z = p_zoom;;

  RAISE NOTICE 'Zoom %: Created % entries in % ms', p_zoom, groups_created,
    EXTRACT(MILLISECONDS FROM (clock_timestamp() - start_time))::INTEGER;;
  RETURN groups_created;;
END;;
$$ LANGUAGE plpgsql;;

CREATE OR REPLACE FUNCTION rebuild_dirty_tiles(
  p_limit INTEGER DEFAULT 500,
  p_min_zoom INTEGER DEFAULT 0,
  p_max_zoom INTEGER DEFAULT 22
) RETURNS INTEGER AS $$
DECLARE
  processed INTEGER := 0;;
  mark RECORD;;
  eps_meters DOUBLE PRECISION;;
  pt RECORD;;
  neighbor_ids BIGINT[];;
  cluster_rec RECORD;;
BEGIN
  FOR mark IN
    DELETE FROM tile_dirty_marks
    WHERE (z, x, y) IN (
      SELECT z, x, y FROM tile_dirty_marks
      WHERE z BETWEEN p_min_zoom AND p_max_zoom
      ORDER BY marked_at ASC
      LIMIT p_limit
    )
    RETURNING z, x, y
  LOOP
    DELETE FROM tile_task_groups WHERE z = mark.z AND x = mark.x AND y = mark.y;;

    IF mark.z = 12 THEN
      INSERT INTO tile_task_groups (z, x, y, group_type, centroid_lat, centroid_lng, task_ids, task_count, counts_by_filter)
      WITH eligible_tasks AS (
        SELECT t.id, t.location,
               ST_Y(t.location) AS lat, ST_X(t.location) AS lng,
               COALESCE(c.difficulty, 0) AS difficulty,
               COALESCE(c.is_global, false) AS is_global
        FROM tasks t
        INNER JOIN challenges c ON c.id = t.parent_id
        INNER JOIN projects   p ON p.id = c.parent_id
        WHERE t.location IS NOT NULL AND NOT ST_IsEmpty(t.location)
          AND t.status IN (0, 3, 6)
          AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
          AND p.deleted = FALSE AND p.enabled = TRUE
          AND lng_to_tile_x(ST_X(t.location), mark.z) = mark.x
          AND lat_to_tile_y(ST_Y(t.location), mark.z) = mark.y
      ),
      overlap_groups AS (
        SELECT ST_ClusterDBSCAN(location, eps := 0.000001, minpoints := 1) OVER () AS overlap_cluster_id,
               id, lat, lng, difficulty, is_global
        FROM eligible_tasks
      )
      SELECT mark.z, mark.x, mark.y,
             CASE WHEN COUNT(*) = 1 THEN 0 ELSE 1 END,
             AVG(lat), AVG(lng),
             ARRAY_AGG(id ORDER BY id),
             COUNT(*)::INTEGER,
             jsonb_build_object(
               'd1_gf', COUNT(*) FILTER (WHERE difficulty = 1 AND NOT is_global),
               'd1_gt', COUNT(*) FILTER (WHERE difficulty = 1 AND is_global),
               'd2_gf', COUNT(*) FILTER (WHERE difficulty = 2 AND NOT is_global),
               'd2_gt', COUNT(*) FILTER (WHERE difficulty = 2 AND is_global),
               'd3_gf', COUNT(*) FILTER (WHERE difficulty = 3 AND NOT is_global),
               'd3_gt', COUNT(*) FILTER (WHERE difficulty = 3 AND is_global),
               'd0_gf', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND NOT is_global),
               'd0_gt', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND is_global)
             )
      FROM overlap_groups
      GROUP BY overlap_cluster_id;;
    ELSE
      eps_meters := 200.0 * tile_pixel_size_meters(mark.z);;

      DROP TABLE IF EXISTS tmp_cluster_tile;;
      CREATE TEMP TABLE tmp_cluster_tile (
        id BIGINT PRIMARY KEY,
        loc3857 geometry(Point, 3857),
        lat DOUBLE PRECISION,
        lng DOUBLE PRECISION,
        difficulty INTEGER,
        is_global BOOLEAN,
        claimed BOOLEAN NOT NULL DEFAULT false
      );;
      INSERT INTO tmp_cluster_tile (id, loc3857, lat, lng, difficulty, is_global)
      SELECT t.id, ST_Transform(t.location, 3857),
             ST_Y(t.location), ST_X(t.location),
             COALESCE(c.difficulty, 0), COALESCE(c.is_global, false)
      FROM tasks t
      INNER JOIN challenges c ON c.id = t.parent_id
      INNER JOIN projects   p ON p.id = c.parent_id
      WHERE t.location IS NOT NULL AND NOT ST_IsEmpty(t.location)
        AND t.status IN (0, 3, 6)
        AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
        AND p.deleted = FALSE AND p.enabled = TRUE
        AND lng_to_tile_x(ST_X(t.location), mark.z) = mark.x
        AND lat_to_tile_y(ST_Y(t.location), mark.z) = mark.y;;
      CREATE INDEX ON tmp_cluster_tile USING GIST (loc3857);;

      FOR pt IN SELECT id, loc3857 FROM tmp_cluster_tile ORDER BY id LOOP
        PERFORM 1 FROM tmp_cluster_tile WHERE id = pt.id AND claimed = true;;
        IF FOUND THEN CONTINUE;; END IF;;

        SELECT ARRAY_AGG(id ORDER BY id),
               ST_Y(ST_Transform(ST_Centroid(ST_Collect(loc3857)), 4326)),
               ST_X(ST_Transform(ST_Centroid(ST_Collect(loc3857)), 4326)),
               COUNT(*)::int,
               jsonb_build_object(
                 'd1_gf', COUNT(*) FILTER (WHERE difficulty = 1 AND NOT is_global),
                 'd1_gt', COUNT(*) FILTER (WHERE difficulty = 1 AND is_global),
                 'd2_gf', COUNT(*) FILTER (WHERE difficulty = 2 AND NOT is_global),
                 'd2_gt', COUNT(*) FILTER (WHERE difficulty = 2 AND is_global),
                 'd3_gf', COUNT(*) FILTER (WHERE difficulty = 3 AND NOT is_global),
                 'd3_gt', COUNT(*) FILTER (WHERE difficulty = 3 AND is_global),
                 'd0_gf', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND NOT is_global),
                 'd0_gt', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND is_global)
               )
          INTO cluster_rec
          FROM tmp_cluster_tile
          WHERE NOT claimed
            AND ST_DWithin(loc3857, pt.loc3857, eps_meters);;

        neighbor_ids := cluster_rec.array_agg;;
        UPDATE tmp_cluster_tile SET claimed = true WHERE id = ANY(neighbor_ids);;

        INSERT INTO tile_task_groups (
          z, x, y, group_type, centroid_lat, centroid_lng,
          task_ids, task_count, counts_by_filter
        ) VALUES (
          mark.z, mark.x, mark.y,
          CASE WHEN cluster_rec.count = 1 THEN 0 ELSE 1 END,
          cluster_rec.st_y, cluster_rec.st_x,
          neighbor_ids, cluster_rec.count, cluster_rec.jsonb_build_object
        );;
      END LOOP;;
    END IF;;

    processed := processed + 1;;
  END LOOP;;

  RETURN processed;;
END;;
$$ LANGUAGE plpgsql;;

CREATE OR REPLACE FUNCTION rebuild_recent_dirty_tiles(
  p_limit INTEGER DEFAULT 20,
  p_min_zoom INTEGER DEFAULT 13,
  p_max_zoom INTEGER DEFAULT 22
) RETURNS INTEGER AS $$
DECLARE
  processed INTEGER := 0;;
  mark RECORD;;
  eps_meters DOUBLE PRECISION;;
  pt RECORD;;
  neighbor_ids BIGINT[];;
  cluster_rec RECORD;;
BEGIN
  FOR mark IN
    DELETE FROM tile_dirty_marks
    WHERE (z, x, y) IN (
      SELECT z, x, y FROM tile_dirty_marks
      WHERE z BETWEEN p_min_zoom AND p_max_zoom
      ORDER BY marked_at DESC
      LIMIT p_limit
    )
    RETURNING z, x, y
  LOOP
    DELETE FROM tile_task_groups WHERE z = mark.z AND x = mark.x AND y = mark.y;;

    IF mark.z = 12 THEN
      INSERT INTO tile_task_groups (z, x, y, group_type, centroid_lat, centroid_lng, task_ids, task_count, counts_by_filter)
      WITH eligible_tasks AS (
        SELECT t.id, t.location,
               ST_Y(t.location) AS lat, ST_X(t.location) AS lng,
               COALESCE(c.difficulty, 0) AS difficulty,
               COALESCE(c.is_global, false) AS is_global
        FROM tasks t
        INNER JOIN challenges c ON c.id = t.parent_id
        INNER JOIN projects   p ON p.id = c.parent_id
        WHERE t.location IS NOT NULL AND NOT ST_IsEmpty(t.location)
          AND t.status IN (0, 3, 6)
          AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
          AND p.deleted = FALSE AND p.enabled = TRUE
          AND lng_to_tile_x(ST_X(t.location), mark.z) = mark.x
          AND lat_to_tile_y(ST_Y(t.location), mark.z) = mark.y
      ),
      overlap_groups AS (
        SELECT ST_ClusterDBSCAN(location, eps := 0.000001, minpoints := 1) OVER () AS overlap_cluster_id,
               id, lat, lng, difficulty, is_global
        FROM eligible_tasks
      )
      SELECT mark.z, mark.x, mark.y,
             CASE WHEN COUNT(*) = 1 THEN 0 ELSE 1 END,
             AVG(lat), AVG(lng),
             ARRAY_AGG(id ORDER BY id),
             COUNT(*)::INTEGER,
             jsonb_build_object(
               'd1_gf', COUNT(*) FILTER (WHERE difficulty = 1 AND NOT is_global),
               'd1_gt', COUNT(*) FILTER (WHERE difficulty = 1 AND is_global),
               'd2_gf', COUNT(*) FILTER (WHERE difficulty = 2 AND NOT is_global),
               'd2_gt', COUNT(*) FILTER (WHERE difficulty = 2 AND is_global),
               'd3_gf', COUNT(*) FILTER (WHERE difficulty = 3 AND NOT is_global),
               'd3_gt', COUNT(*) FILTER (WHERE difficulty = 3 AND is_global),
               'd0_gf', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND NOT is_global),
               'd0_gt', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND is_global)
             )
      FROM overlap_groups
      GROUP BY overlap_cluster_id;;
    ELSE
      eps_meters := 200.0 * tile_pixel_size_meters(mark.z);;

      DROP TABLE IF EXISTS tmp_cluster_tile;;
      CREATE TEMP TABLE tmp_cluster_tile (
        id BIGINT PRIMARY KEY,
        loc3857 geometry(Point, 3857),
        lat DOUBLE PRECISION,
        lng DOUBLE PRECISION,
        difficulty INTEGER,
        is_global BOOLEAN,
        claimed BOOLEAN NOT NULL DEFAULT false
      );;
      INSERT INTO tmp_cluster_tile (id, loc3857, lat, lng, difficulty, is_global)
      SELECT t.id, ST_Transform(t.location, 3857),
             ST_Y(t.location), ST_X(t.location),
             COALESCE(c.difficulty, 0), COALESCE(c.is_global, false)
      FROM tasks t
      INNER JOIN challenges c ON c.id = t.parent_id
      INNER JOIN projects   p ON p.id = c.parent_id
      WHERE t.location IS NOT NULL AND NOT ST_IsEmpty(t.location)
        AND t.status IN (0, 3, 6)
        AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
        AND p.deleted = FALSE AND p.enabled = TRUE
        AND lng_to_tile_x(ST_X(t.location), mark.z) = mark.x
        AND lat_to_tile_y(ST_Y(t.location), mark.z) = mark.y;;
      CREATE INDEX ON tmp_cluster_tile USING GIST (loc3857);;

      FOR pt IN SELECT id, loc3857 FROM tmp_cluster_tile ORDER BY id LOOP
        PERFORM 1 FROM tmp_cluster_tile WHERE id = pt.id AND claimed = true;;
        IF FOUND THEN CONTINUE;; END IF;;

        SELECT ARRAY_AGG(id ORDER BY id),
               ST_Y(ST_Transform(ST_Centroid(ST_Collect(loc3857)), 4326)),
               ST_X(ST_Transform(ST_Centroid(ST_Collect(loc3857)), 4326)),
               COUNT(*)::int,
               jsonb_build_object(
                 'd1_gf', COUNT(*) FILTER (WHERE difficulty = 1 AND NOT is_global),
                 'd1_gt', COUNT(*) FILTER (WHERE difficulty = 1 AND is_global),
                 'd2_gf', COUNT(*) FILTER (WHERE difficulty = 2 AND NOT is_global),
                 'd2_gt', COUNT(*) FILTER (WHERE difficulty = 2 AND is_global),
                 'd3_gf', COUNT(*) FILTER (WHERE difficulty = 3 AND NOT is_global),
                 'd3_gt', COUNT(*) FILTER (WHERE difficulty = 3 AND is_global),
                 'd0_gf', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND NOT is_global),
                 'd0_gt', COUNT(*) FILTER (WHERE difficulty NOT IN (1,2,3) AND is_global)
               )
          INTO cluster_rec
          FROM tmp_cluster_tile
          WHERE NOT claimed
            AND ST_DWithin(loc3857, pt.loc3857, eps_meters);;

        neighbor_ids := cluster_rec.array_agg;;
        UPDATE tmp_cluster_tile SET claimed = true WHERE id = ANY(neighbor_ids);;

        INSERT INTO tile_task_groups (
          z, x, y, group_type, centroid_lat, centroid_lng,
          task_ids, task_count, counts_by_filter
        ) VALUES (
          mark.z, mark.x, mark.y,
          CASE WHEN cluster_rec.count = 1 THEN 0 ELSE 1 END,
          cluster_rec.st_y, cluster_rec.st_x,
          neighbor_ids, cluster_rec.count, cluster_rec.jsonb_build_object
        );;
      END LOOP;;
    END IF;;

    processed := processed + 1;;
  END LOOP;;

  RETURN processed;;
END;;
$$ LANGUAGE plpgsql;;

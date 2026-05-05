# --- MapRoulette Scheme

# --- !Ups
-- =============================================================================
-- Zoom-tiered dirty-tile rebuild
--
-- Adds a 3-arg overload of rebuild_dirty_tiles that drains only marks within
-- the given inclusive zoom range. Lets the scheduler run a fast loop for
-- high-zoom tiles (z >= 13) and a slower loop for low-zoom clustered tiles
-- without one starving the other when bulk imports flood the queue.
-- =============================================================================

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
$$ LANGUAGE plpgsql;

-- Drain the most-recently-marked dirty tiles first. Called synchronously
-- after a single task mutation so the originating user sees their own change
-- without waiting for the FIFO scheduler loop to reach their tiles.
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
$$ LANGUAGE plpgsql;

-- Neighbor-buffer dirtying for clustered low-zoom tiles. A task whose point
-- falls within the 200-MVT-pixel cluster radius of a tile edge could end up
-- visually clustered with tasks in the adjacent tile, so the neighbor's
-- precomputed groups depend on this task too. Mark the neighboring tiles
-- dirty in that case (cardinal + diagonal when within radius of a corner).
-- Applied only at z=0..11 — at z=12 the DBSCAN epsilon is microscopic and
-- cross-tile clustering doesn't occur.
CREATE OR REPLACE FUNCTION mark_tile_dirty_for_point(p_lng DOUBLE PRECISION, p_lat DOUBLE PRECISION) RETURNS VOID AS $$
DECLARE
  i_z INTEGER;;
  tile_x INTEGER;;
  tile_y INTEGER;;
  px_size DOUBLE PRECISION;;
  cluster_radius_m DOUBLE PRECISION;;
  tile_size_m DOUBLE PRECISION;;
  tx_min DOUBLE PRECISION;;
  ty_max DOUBLE PRECISION;;
  pt_x_m DOUBLE PRECISION;;
  pt_y_m DOUBLE PRECISION;;
  half_extent CONSTANT DOUBLE PRECISION := 20037508.342789244;;
  near_left BOOLEAN;;
  near_right BOOLEAN;;
  near_top BOOLEAN;;
  near_bottom BOOLEAN;;
  pt_3857 geometry;;
BEGIN
  IF p_lng IS NULL OR p_lat IS NULL THEN
    RETURN;;
  END IF;;

  -- Project point once to web mercator for edge-distance math.
  pt_3857 := ST_Transform(ST_SetSRID(ST_MakePoint(p_lng, p_lat), 4326), 3857);;
  pt_x_m := ST_X(pt_3857);;
  pt_y_m := ST_Y(pt_3857);;

  FOR i_z IN 0..12 LOOP
    tile_x := lng_to_tile_x(p_lng, i_z);;
    tile_y := lat_to_tile_y(p_lat, i_z);;

    INSERT INTO tile_dirty_marks (z, x, y) VALUES (i_z, tile_x, tile_y)
    ON CONFLICT (z, x, y) DO NOTHING;;

    -- z=12 has effectively-zero cluster epsilon; no neighbor dirtying needed.
    IF i_z >= 12 THEN
      CONTINUE;;
    END IF;;

    px_size := tile_pixel_size_meters(i_z);;
    cluster_radius_m := 200.0 * px_size;;
    tile_size_m := 4096.0 * px_size;;

    tx_min := -half_extent + tile_x * tile_size_m;;
    ty_max :=  half_extent - tile_y * tile_size_m;;

    near_left   := (pt_x_m - tx_min) < cluster_radius_m;;
    near_right  := ((tx_min + tile_size_m) - pt_x_m) < cluster_radius_m;;
    near_top    := (ty_max - pt_y_m) < cluster_radius_m;;
    near_bottom := (pt_y_m - (ty_max - tile_size_m)) < cluster_radius_m;;

    IF near_left AND tile_x > 0 THEN
      INSERT INTO tile_dirty_marks (z, x, y) VALUES (i_z, tile_x - 1, tile_y)
      ON CONFLICT (z, x, y) DO NOTHING;;
    END IF;;
    IF near_right AND tile_x < ((1 << i_z) - 1) THEN
      INSERT INTO tile_dirty_marks (z, x, y) VALUES (i_z, tile_x + 1, tile_y)
      ON CONFLICT (z, x, y) DO NOTHING;;
    END IF;;
    IF near_top AND tile_y > 0 THEN
      INSERT INTO tile_dirty_marks (z, x, y) VALUES (i_z, tile_x, tile_y - 1)
      ON CONFLICT (z, x, y) DO NOTHING;;
    END IF;;
    IF near_bottom AND tile_y < ((1 << i_z) - 1) THEN
      INSERT INTO tile_dirty_marks (z, x, y) VALUES (i_z, tile_x, tile_y + 1)
      ON CONFLICT (z, x, y) DO NOTHING;;
    END IF;;

    IF near_left AND near_top AND tile_x > 0 AND tile_y > 0 THEN
      INSERT INTO tile_dirty_marks (z, x, y) VALUES (i_z, tile_x - 1, tile_y - 1)
      ON CONFLICT (z, x, y) DO NOTHING;;
    END IF;;
    IF near_right AND near_top AND tile_x < ((1 << i_z) - 1) AND tile_y > 0 THEN
      INSERT INTO tile_dirty_marks (z, x, y) VALUES (i_z, tile_x + 1, tile_y - 1)
      ON CONFLICT (z, x, y) DO NOTHING;;
    END IF;;
    IF near_left AND near_bottom AND tile_x > 0 AND tile_y < ((1 << i_z) - 1) THEN
      INSERT INTO tile_dirty_marks (z, x, y) VALUES (i_z, tile_x - 1, tile_y + 1)
      ON CONFLICT (z, x, y) DO NOTHING;;
    END IF;;
    IF near_right AND near_bottom AND tile_x < ((1 << i_z) - 1) AND tile_y < ((1 << i_z) - 1) THEN
      INSERT INTO tile_dirty_marks (z, x, y) VALUES (i_z, tile_x + 1, tile_y + 1)
      ON CONFLICT (z, x, y) DO NOTHING;;
    END IF;;
  END LOOP;;
END;;
$$ LANGUAGE plpgsql VOLATILE;

# --- !Downs
DROP FUNCTION IF EXISTS rebuild_recent_dirty_tiles(INTEGER, INTEGER, INTEGER);;
DROP FUNCTION IF EXISTS rebuild_dirty_tiles(INTEGER, INTEGER, INTEGER);;
-- Restore the simpler non-buffered version of mark_tile_dirty_for_point.
CREATE OR REPLACE FUNCTION mark_tile_dirty_for_point(p_lng DOUBLE PRECISION, p_lat DOUBLE PRECISION) RETURNS VOID AS $$
DECLARE
  i_z INTEGER;;
BEGIN
  IF p_lng IS NULL OR p_lat IS NULL THEN
    RETURN;;
  END IF;;
  FOR i_z IN 0..12 LOOP
    INSERT INTO tile_dirty_marks (z, x, y)
    VALUES (i_z, lng_to_tile_x(p_lng, i_z), lat_to_tile_y(p_lat, i_z))
    ON CONFLICT (z, x, y) DO NOTHING;;
  END LOOP;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

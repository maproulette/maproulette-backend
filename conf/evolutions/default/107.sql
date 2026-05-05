# --- MapRoulette Scheme

# --- !Ups
-- =============================================================================
-- Tile aggregation system (consolidated)
--
-- Pre-computes task groupings per (z, x, y) tile so the explore-page MVT
-- endpoint can serve large-scale maps without recomputing clusters per
-- request. Build standard:
--
--   * Zoom 0..11: clustered. Single-pass greedy "claim neighbors" mirroring
--     Supercluster's `_cluster` (supercluster/index.js). Clustering is GLOBAL
--     per rebuild so clusters can span tile boundaries; each cluster is
--     assigned to the tile where its centroid falls.
--   * Zoom 12: unclustered. One row per distinct ground location
--     (overlap-aware via a microscopic-eps DBSCAN). The frontend overzooms or
--     the on-the-fly tasks query takes over for z > 12.
--
-- Clustering radius matches Supercluster's 25-pixel radius exactly.
-- Supercluster normalizes r = radius / (extent * 2^z), default extent=512.
-- With MVT extent=4096, 25/512 == 200/4096, so the Web Mercator epsilon is
-- `200 * tile_pixel_size_meters(z)` meters.
--
-- A `tile_dirty_marks` queue decouples writes from rebuilds. Triggers on
-- `tasks` record affected (z, x, y) coordinates and a scheduled job processes
-- the queue in batches. Because cluster radii near tile edges may merge
-- cross-tile, when a point is within radius of an edge the neighboring tile
-- is also marked dirty (cardinal + diagonal at corners). At z=12 the DBSCAN
-- epsilon is microscopic and cross-tile clustering doesn't occur, so
-- neighbor dirtying only applies at z=0..11.
--
-- Rebuilds are zoom-tiered: callers pass an inclusive zoom range to drain
-- only marks within that range, so a fast loop for high-zoom tiles
-- (z >= 13 in the recent-first variant; the regular loop covers all zooms)
-- doesn't starve the slower clustered low-zoom rebuilds when bulk imports
-- flood the queue.
--
-- Coordinate-range guard: rows whose tasks.location is outside Web Mercator's
-- valid range (rare ingestion bugs — raw EPSG:3857 meters mistakenly written
-- into a geometry tagged 4326, swapped lat/lng, garbage) are silently
-- skipped during rebuild. Without the guard, ST_Transform on a single bad
-- row raises PROJ error 2049 and aborts the entire rebuild loop.
-- =============================================================================

-- ----------- Storage -----------

-- Pre-computed task groups per tile.
--   * group_type=0: single isolated task (task_ids has one entry)
--   * group_type=1: overlapping tasks at the same location (task_ids has all)
--   * group_type=2: cluster of tasks at multiple locations (task_ids empty)
CREATE TABLE IF NOT EXISTS tile_task_groups (
    id SERIAL PRIMARY KEY,
    z SMALLINT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    group_type SMALLINT NOT NULL,
    centroid_lat DOUBLE PRECISION NOT NULL,
    centroid_lng DOUBLE PRECISION NOT NULL,
    task_ids BIGINT[] NOT NULL,
    task_count INTEGER NOT NULL,
    counts_by_filter JSONB DEFAULT '{}'::jsonb,
    last_updated TIMESTAMP WITHOUT TIME ZONE DEFAULT NOW()
);;

CREATE INDEX IF NOT EXISTS idx_tile_task_groups_coords ON tile_task_groups (z, x, y);;
CREATE INDEX IF NOT EXISTS idx_tile_task_groups_zoom ON tile_task_groups (z) WHERE task_count > 0;;

-- Queue of (z, x, y) tiles whose precomputed groups need rebuilding.
CREATE TABLE IF NOT EXISTS tile_dirty_marks (
  z SMALLINT NOT NULL,
  x INTEGER NOT NULL,
  y INTEGER NOT NULL,
  marked_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
  PRIMARY KEY (z, x, y)
);;
CREATE INDEX IF NOT EXISTS idx_tile_dirty_marks_marked_at ON tile_dirty_marks (marked_at);;

-- ----------- Coordinate helpers -----------

CREATE OR REPLACE FUNCTION lng_to_tile_x(lng DOUBLE PRECISION, zoom INTEGER) RETURNS INTEGER AS $$
    SELECT FLOOR((lng + 180.0) / 360.0 * (1 << zoom))::INTEGER
$$ LANGUAGE SQL IMMUTABLE PARALLEL SAFE;;

CREATE OR REPLACE FUNCTION lat_to_tile_y(lat DOUBLE PRECISION, zoom INTEGER) RETURNS INTEGER AS $$
    SELECT FLOOR((1.0 - LN(TAN(RADIANS(GREATEST(-85.0511, LEAST(85.0511, lat)))) +
           1.0 / COS(RADIANS(GREATEST(-85.0511, LEAST(85.0511, lat))))) / PI()) / 2.0 * (1 << zoom))::INTEGER
$$ LANGUAGE SQL IMMUTABLE PARALLEL SAFE;;

-- Web Mercator envelope for a standard z/x/y tile.
CREATE OR REPLACE FUNCTION tile_envelope_3857(p_z INTEGER, p_x INTEGER, p_y INTEGER)
RETURNS geometry AS $$
DECLARE
  half_extent CONSTANT DOUBLE PRECISION := 20037508.342789244;;
  tile_size DOUBLE PRECISION;;
  x_min DOUBLE PRECISION;;
  y_max DOUBLE PRECISION;;
BEGIN
  tile_size := (half_extent * 2) / (1 << p_z);;
  x_min := -half_extent + p_x * tile_size;;
  y_max :=  half_extent - p_y * tile_size;;
  RETURN ST_MakeEnvelope(x_min, y_max - tile_size, x_min + tile_size, y_max, 3857);;
END;;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;;

-- Size in meters of one MVT pixel (extent=4096) at the given zoom.
CREATE OR REPLACE FUNCTION tile_pixel_size_meters(p_z INTEGER) RETURNS DOUBLE PRECISION AS $$
  SELECT (20037508.342789244 * 2) / ((1 << p_z) * 4096.0);;
$$ LANGUAGE SQL IMMUTABLE PARALLEL SAFE;;

-- ----------- Eligibility -----------

CREATE OR REPLACE FUNCTION task_is_tile_eligible(p_task_id BIGINT) RETURNS BOOLEAN AS $$
  SELECT EXISTS (
    SELECT 1 FROM tasks t
    INNER JOIN challenges c ON c.id = t.parent_id
    INNER JOIN projects   p ON p.id = c.parent_id
    WHERE t.id = p_task_id
      AND t.status IN (0, 3, 6)
      AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
      AND p.deleted = FALSE AND p.enabled = TRUE
  );;
$$ LANGUAGE SQL STABLE;;

-- ----------- Dirty-mark queue -----------

-- Mark every zoom 0..12 tile that a given point affects, plus neighbors when
-- the point is within the cluster radius of a tile edge (z=0..11 only).
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

  pt_3857 := ST_Transform(ST_SetSRID(ST_MakePoint(p_lng, p_lat), 4326), 3857);;
  pt_x_m := ST_X(pt_3857);;
  pt_y_m := ST_Y(pt_3857);;

  FOR i_z IN 0..12 LOOP
    tile_x := lng_to_tile_x(p_lng, i_z);;
    tile_y := lat_to_tile_y(p_lat, i_z);;

    INSERT INTO tile_dirty_marks (z, x, y) VALUES (i_z, tile_x, tile_y)
    ON CONFLICT (z, x, y) DO NOTHING;;

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
$$ LANGUAGE plpgsql VOLATILE;;

-- Trigger: mark affected tiles dirty on task insert/update/delete.
CREATE OR REPLACE FUNCTION mark_tiles_dirty_on_task_change() RETURNS TRIGGER AS $$
DECLARE
  old_lng DOUBLE PRECISION;;
  old_lat DOUBLE PRECISION;;
  new_lng DOUBLE PRECISION;;
  new_lat DOUBLE PRECISION;;
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') AND OLD.location IS NOT NULL AND NOT ST_IsEmpty(OLD.location) THEN
    old_lng := ST_X(OLD.location);;
    old_lat := ST_Y(OLD.location);;
  END IF;;
  IF TG_OP IN ('UPDATE', 'INSERT') AND NEW.location IS NOT NULL AND NOT ST_IsEmpty(NEW.location) THEN
    new_lng := ST_X(NEW.location);;
    new_lat := ST_Y(NEW.location);;
  END IF;;

  IF TG_OP = 'UPDATE'
     AND OLD.status = NEW.status
     AND OLD.parent_id = NEW.parent_id
     AND ST_Equals(COALESCE(OLD.location, ST_GeomFromText('POINT EMPTY', 4326)),
                   COALESCE(NEW.location, ST_GeomFromText('POINT EMPTY', 4326))) THEN
    RETURN NEW;;
  END IF;;

  PERFORM mark_tile_dirty_for_point(old_lng, old_lat);;
  PERFORM mark_tile_dirty_for_point(new_lng, new_lat);;

  IF TG_OP = 'DELETE' THEN
    RETURN OLD;;
  END IF;;
  RETURN NEW;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

DROP TRIGGER IF EXISTS mark_tiles_dirty_on_task_change_trigger ON tasks;;
CREATE TRIGGER mark_tiles_dirty_on_task_change_trigger
  AFTER INSERT OR UPDATE OF status, location, parent_id OR DELETE ON tasks
  FOR EACH ROW EXECUTE PROCEDURE mark_tiles_dirty_on_task_change();;

-- ----------- Rebuild functions -----------

-- Full rebuild of one zoom level. Single-pass Supercluster-style greedy
-- merge at z=0..11; overlap-only at z=12. Coord-range guard skips bad rows.
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

CREATE OR REPLACE FUNCTION rebuild_all_tile_aggregates()
RETURNS TABLE(zoom_level INTEGER, tiles_created INTEGER) AS $$
DECLARE
  total_start TIMESTAMP;;
  total_groups INTEGER := 0;;
BEGIN
  total_start := clock_timestamp();;
  RAISE NOTICE '=== Full tile aggregate rebuild (zoom 0..12, Supercluster alignment) ===';;

  FOR zoom_level IN 0..12 LOOP
    tiles_created := rebuild_zoom_level(zoom_level);;
    total_groups := total_groups + tiles_created;;
    RETURN NEXT;;
  END LOOP;;

  RAISE NOTICE 'Total groups created: % in % ms', total_groups,
    EXTRACT(MILLISECONDS FROM (clock_timestamp() - total_start))::INTEGER;;
END;;
$$ LANGUAGE plpgsql;;

-- Zoom-tiered drain of the dirty-tile queue (FIFO). Per-tile rebuilds
-- recluster only the tasks currently in that tile, so they're an
-- approximation; a periodic full rebuild corrects cross-tile drift.
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

DROP TRIGGER IF EXISTS mark_tiles_dirty_on_task_change_trigger ON tasks;;
DROP FUNCTION IF EXISTS mark_tiles_dirty_on_task_change();;
DROP FUNCTION IF EXISTS rebuild_recent_dirty_tiles(INTEGER, INTEGER, INTEGER);;
DROP FUNCTION IF EXISTS rebuild_dirty_tiles(INTEGER, INTEGER, INTEGER);;
DROP FUNCTION IF EXISTS rebuild_all_tile_aggregates();;
DROP FUNCTION IF EXISTS rebuild_zoom_level(INTEGER);;
DROP FUNCTION IF EXISTS mark_tile_dirty_for_point(DOUBLE PRECISION, DOUBLE PRECISION);;
DROP FUNCTION IF EXISTS task_is_tile_eligible(BIGINT);;
DROP FUNCTION IF EXISTS tile_pixel_size_meters(INTEGER);;
DROP FUNCTION IF EXISTS tile_envelope_3857(INTEGER, INTEGER, INTEGER);;
DROP FUNCTION IF EXISTS lat_to_tile_y(DOUBLE PRECISION, INTEGER);;
DROP FUNCTION IF EXISTS lng_to_tile_x(DOUBLE PRECISION, INTEGER);;
DROP INDEX IF EXISTS idx_tile_dirty_marks_marked_at;;
DROP TABLE IF EXISTS tile_dirty_marks;;
DROP INDEX IF EXISTS idx_tile_task_groups_zoom;;
DROP INDEX IF EXISTS idx_tile_task_groups_coords;;
DROP TABLE IF EXISTS tile_task_groups;;

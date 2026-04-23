# --- MapRoulette Scheme

# --- !Ups
-- =============================================================================
-- Part 1: CompletionMetrics on challenges and projects
-- =============================================================================
--
-- Persistent task-status completion metrics stored as a single JSONB blob on
-- challenges and projects so the counts are available directly on the object
-- without a separate stats call.

ALTER TABLE challenges ADD COLUMN IF NOT EXISTS completion_metrics JSONB NOT NULL DEFAULT '{}'::jsonb;;
ALTER TABLE projects   ADD COLUMN IF NOT EXISTS completion_metrics JSONB NOT NULL DEFAULT '{}'::jsonb;;

-- Build a CompletionMetrics JSON object from raw task counts.
CREATE OR REPLACE FUNCTION build_completion_metrics(
  total INTEGER, available INTEGER, fixed INTEGER, false_positive INTEGER,
  skipped INTEGER, deleted INTEGER, already_fixed INTEGER, too_hard INTEGER,
  answered INTEGER, validated INTEGER, disabled INTEGER
) RETURNS JSONB AS $$
BEGIN
  RETURN jsonb_build_object(
    'total',         COALESCE(total, 0),
    'available',     COALESCE(available, 0),
    'fixed',         COALESCE(fixed, 0),
    'falsePositive', COALESCE(false_positive, 0),
    'skipped',       COALESCE(skipped, 0),
    'deleted',       COALESCE(deleted, 0),
    'alreadyFixed',  COALESCE(already_fixed, 0),
    'tooHard',       COALESCE(too_hard, 0),
    'answered',      COALESCE(answered, 0),
    'validated',     COALESCE(validated, 0),
    'disabled',      COALESCE(disabled, 0)
  );;
END;;
$$ LANGUAGE plpgsql IMMUTABLE;;

CREATE OR REPLACE FUNCTION empty_completion_metrics() RETURNS JSONB AS $$
  SELECT build_completion_metrics(0,0,0,0,0,0,0,0,0,0,0);;
$$ LANGUAGE SQL IMMUTABLE;;

CREATE OR REPLACE FUNCTION metrics_get(metrics JSONB, key TEXT) RETURNS INTEGER AS $$
  SELECT COALESCE((metrics ->> key)::int, 0);;
$$ LANGUAGE SQL IMMUTABLE;;

CREATE OR REPLACE FUNCTION metrics_apply_delta(metrics JSONB, key TEXT, delta INTEGER)
RETURNS JSONB AS $$
  SELECT jsonb_set(
    COALESCE(metrics, empty_completion_metrics()),
    ARRAY[key],
    to_jsonb(GREATEST(metrics_get(COALESCE(metrics, empty_completion_metrics()), key) + delta, 0))
  );;
$$ LANGUAGE SQL IMMUTABLE;;

CREATE OR REPLACE FUNCTION task_status_metric_key(status INTEGER) RETURNS TEXT AS $$
  SELECT CASE status
    WHEN 0 THEN 'available'
    WHEN 1 THEN 'fixed'
    WHEN 2 THEN 'falsePositive'
    WHEN 3 THEN 'skipped'
    WHEN 4 THEN 'deleted'
    WHEN 5 THEN 'alreadyFixed'
    WHEN 6 THEN 'tooHard'
    WHEN 7 THEN 'answered'
    WHEN 8 THEN 'validated'
    WHEN 9 THEN 'disabled'
    ELSE NULL
  END;;
$$ LANGUAGE SQL IMMUTABLE;;

CREATE OR REPLACE FUNCTION metrics_add(a JSONB, b JSONB) RETURNS JSONB AS $$
  SELECT build_completion_metrics(
    metrics_get(a, 'total')         + metrics_get(b, 'total'),
    metrics_get(a, 'available')     + metrics_get(b, 'available'),
    metrics_get(a, 'fixed')         + metrics_get(b, 'fixed'),
    metrics_get(a, 'falsePositive') + metrics_get(b, 'falsePositive'),
    metrics_get(a, 'skipped')       + metrics_get(b, 'skipped'),
    metrics_get(a, 'deleted')       + metrics_get(b, 'deleted'),
    metrics_get(a, 'alreadyFixed')  + metrics_get(b, 'alreadyFixed'),
    metrics_get(a, 'tooHard')       + metrics_get(b, 'tooHard'),
    metrics_get(a, 'answered')      + metrics_get(b, 'answered'),
    metrics_get(a, 'validated')     + metrics_get(b, 'validated'),
    metrics_get(a, 'disabled')      + metrics_get(b, 'disabled')
  );;
$$ LANGUAGE SQL IMMUTABLE;;

CREATE OR REPLACE FUNCTION metrics_sub(a JSONB, b JSONB) RETURNS JSONB AS $$
  SELECT build_completion_metrics(
    GREATEST(metrics_get(a, 'total')         - metrics_get(b, 'total'),         0),
    GREATEST(metrics_get(a, 'available')     - metrics_get(b, 'available'),     0),
    GREATEST(metrics_get(a, 'fixed')         - metrics_get(b, 'fixed'),         0),
    GREATEST(metrics_get(a, 'falsePositive') - metrics_get(b, 'falsePositive'), 0),
    GREATEST(metrics_get(a, 'skipped')       - metrics_get(b, 'skipped'),       0),
    GREATEST(metrics_get(a, 'deleted')       - metrics_get(b, 'deleted'),       0),
    GREATEST(metrics_get(a, 'alreadyFixed')  - metrics_get(b, 'alreadyFixed'),  0),
    GREATEST(metrics_get(a, 'tooHard')       - metrics_get(b, 'tooHard'),       0),
    GREATEST(metrics_get(a, 'answered')      - metrics_get(b, 'answered'),      0),
    GREATEST(metrics_get(a, 'validated')     - metrics_get(b, 'validated'),     0),
    GREATEST(metrics_get(a, 'disabled')      - metrics_get(b, 'disabled'),      0)
  );;
$$ LANGUAGE SQL IMMUTABLE;;

-- Backfill challenge completion_metrics and the legacy
-- completion_percentage column (tasks_remaining from evolution 84 is
-- left alone).
UPDATE challenges c
SET
  completion_metrics = build_completion_metrics(
    agg.total, agg.available, agg.fixed, agg.false_positive, agg.skipped,
    agg.deleted, agg.already_fixed, agg.too_hard, agg.answered, agg.validated,
    agg.disabled
  ),
  completion_percentage = CASE
    WHEN COALESCE(agg.completable_total, 0) = 0 THEN 0
    ELSE ROUND(
      (COALESCE(agg.fixed, 0) + COALESCE(agg.false_positive, 0) + COALESCE(agg.already_fixed, 0))::numeric
      * 100 / agg.completable_total
    )::int
  END
FROM (
  SELECT parent_id,
    COUNT(*)::int                                              AS total,
    SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END)::int           AS available,
    SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END)::int           AS fixed,
    SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END)::int           AS false_positive,
    SUM(CASE WHEN status = 3 THEN 1 ELSE 0 END)::int           AS skipped,
    SUM(CASE WHEN status = 4 THEN 1 ELSE 0 END)::int           AS deleted,
    SUM(CASE WHEN status = 5 THEN 1 ELSE 0 END)::int           AS already_fixed,
    SUM(CASE WHEN status = 6 THEN 1 ELSE 0 END)::int           AS too_hard,
    SUM(CASE WHEN status = 7 THEN 1 ELSE 0 END)::int           AS answered,
    SUM(CASE WHEN status = 8 THEN 1 ELSE 0 END)::int           AS validated,
    SUM(CASE WHEN status = 9 THEN 1 ELSE 0 END)::int           AS disabled,
    SUM(CASE WHEN status NOT IN (4, 9) THEN 1 ELSE 0 END)::int AS completable_total
  FROM tasks
  GROUP BY parent_id
) agg
WHERE c.id = agg.parent_id;;

UPDATE challenges
SET completion_metrics = empty_completion_metrics()
WHERE completion_metrics = '{}'::jsonb OR completion_metrics IS NULL;;

UPDATE projects p
SET completion_metrics = agg.metrics
FROM (
  SELECT parent_id,
    build_completion_metrics(
      SUM(metrics_get(completion_metrics, 'total'))::int,
      SUM(metrics_get(completion_metrics, 'available'))::int,
      SUM(metrics_get(completion_metrics, 'fixed'))::int,
      SUM(metrics_get(completion_metrics, 'falsePositive'))::int,
      SUM(metrics_get(completion_metrics, 'skipped'))::int,
      SUM(metrics_get(completion_metrics, 'deleted'))::int,
      SUM(metrics_get(completion_metrics, 'alreadyFixed'))::int,
      SUM(metrics_get(completion_metrics, 'tooHard'))::int,
      SUM(metrics_get(completion_metrics, 'answered'))::int,
      SUM(metrics_get(completion_metrics, 'validated'))::int,
      SUM(metrics_get(completion_metrics, 'disabled'))::int
    ) AS metrics
  FROM challenges
  WHERE deleted = false
  GROUP BY parent_id
) agg
WHERE p.id = agg.parent_id;;

UPDATE projects
SET completion_metrics = empty_completion_metrics()
WHERE completion_metrics = '{}'::jsonb OR completion_metrics IS NULL;;

-- Trigger: maintain challenge completion_metrics on task insert/update/delete.
CREATE OR REPLACE FUNCTION update_challenge_completion_metrics() RETURNS TRIGGER AS $$
DECLARE
  old_key TEXT;;
  new_key TEXT;;
BEGIN
  IF TG_OP = 'INSERT' THEN
    new_key := task_status_metric_key(NEW.status);;
    UPDATE challenges SET completion_metrics =
      metrics_apply_delta(
        metrics_apply_delta(completion_metrics, 'total', 1),
        new_key, 1
      )
    WHERE id = NEW.parent_id AND new_key IS NOT NULL;;
    RETURN NEW;;
  ELSIF TG_OP = 'DELETE' THEN
    old_key := task_status_metric_key(OLD.status);;
    UPDATE challenges SET completion_metrics =
      metrics_apply_delta(
        metrics_apply_delta(completion_metrics, 'total', -1),
        old_key, -1
      )
    WHERE id = OLD.parent_id AND old_key IS NOT NULL;;
    RETURN OLD;;
  ELSIF TG_OP = 'UPDATE' THEN
    IF OLD.parent_id IS DISTINCT FROM NEW.parent_id THEN
      old_key := task_status_metric_key(OLD.status);;
      new_key := task_status_metric_key(NEW.status);;
      UPDATE challenges SET completion_metrics =
        metrics_apply_delta(
          metrics_apply_delta(completion_metrics, 'total', -1),
          old_key, -1
        )
      WHERE id = OLD.parent_id AND old_key IS NOT NULL;;
      UPDATE challenges SET completion_metrics =
        metrics_apply_delta(
          metrics_apply_delta(completion_metrics, 'total', 1),
          new_key, 1
        )
      WHERE id = NEW.parent_id AND new_key IS NOT NULL;;
    ELSIF OLD.status IS DISTINCT FROM NEW.status THEN
      old_key := task_status_metric_key(OLD.status);;
      new_key := task_status_metric_key(NEW.status);;
      UPDATE challenges SET completion_metrics =
        metrics_apply_delta(
          metrics_apply_delta(completion_metrics, COALESCE(old_key, 'total'), CASE WHEN old_key IS NULL THEN 0 ELSE -1 END),
          COALESCE(new_key, 'total'), CASE WHEN new_key IS NULL THEN 0 ELSE 1 END
        )
      WHERE id = NEW.parent_id;;
    END IF;;
    RETURN NEW;;
  END IF;;
  RETURN NULL;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

DROP TRIGGER IF EXISTS update_challenge_completion_metrics_trigger ON tasks;;
CREATE TRIGGER update_challenge_completion_metrics_trigger
  AFTER INSERT OR UPDATE OR DELETE ON tasks
  FOR EACH ROW EXECUTE PROCEDURE update_challenge_completion_metrics();;

-- Trigger: roll challenge-level deltas up to the parent project.
CREATE OR REPLACE FUNCTION update_project_completion_metrics() RETURNS TRIGGER AS $$
DECLARE
  old_contributes BOOLEAN := (TG_OP IN ('UPDATE', 'DELETE')) AND NOT OLD.deleted;;
  new_contributes BOOLEAN := (TG_OP IN ('INSERT', 'UPDATE')) AND NOT NEW.deleted;;
BEGIN
  IF TG_OP = 'DELETE' THEN
    new_contributes := false;;
  END IF;;
  IF TG_OP = 'INSERT' THEN
    old_contributes := false;;
  END IF;;

  IF old_contributes THEN
    UPDATE projects
    SET completion_metrics = metrics_sub(completion_metrics, OLD.completion_metrics)
    WHERE id = OLD.parent_id;;
  END IF;;

  IF new_contributes THEN
    UPDATE projects
    SET completion_metrics = metrics_add(completion_metrics, NEW.completion_metrics)
    WHERE id = NEW.parent_id;;
  END IF;;

  IF TG_OP = 'DELETE' THEN
    RETURN OLD;;
  END IF;;
  RETURN NEW;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

DROP TRIGGER IF EXISTS update_project_completion_metrics_trigger ON challenges;;
CREATE TRIGGER update_project_completion_metrics_trigger
  AFTER INSERT OR UPDATE OR DELETE ON challenges
  FOR EACH ROW EXECUTE PROCEDURE update_project_completion_metrics();;


-- =============================================================================
-- Part 2: Tile aggregate rebuild with dirty-tile queue
-- =============================================================================
--
-- Tile building standard:
--   * Zoom 0..11: clustered. Uses a single-pass greedy "claim neighbors"
--     algorithm that mirrors Supercluster's `_cluster` method
--     (supercluster/index.js). Clustering is GLOBAL per rebuild so clusters
--     can span tile boundaries. Each cluster is assigned to the tile where
--     its centroid falls.
--   * Zoom 12: unclustered. One row per distinct ground location
--     (overlap-aware). The frontend overzooms or the on-the-fly tasks query
--     takes over for z > 12.
--
-- Clustering radius matches Supercluster's 25-pixel radius exactly.
-- Supercluster normalizes: r = radius / (extent * 2^z), default extent=512.
-- With MVT extent=4096, 25/512 == 200/4096, so the Web Mercator epsilon is
-- `200 * tile_pixel_size_meters(z)` meters.
--
-- A `tile_dirty_marks` table decouples writes (tasks transitioning into or
-- out of display-eligible status) from tile rebuilds. Triggers on `tasks`
-- record affected (z, x, y) coordinates and a scheduled job processes the
-- queue in batches.

-- Drop any existing rows above the new ceiling.
DELETE FROM tile_task_groups WHERE z > 12;;

CREATE TABLE IF NOT EXISTS tile_dirty_marks (
  z SMALLINT NOT NULL,
  x INTEGER NOT NULL,
  y INTEGER NOT NULL,
  marked_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
  PRIMARY KEY (z, x, y)
);;
CREATE INDEX IF NOT EXISTS idx_tile_dirty_marks_marked_at ON tile_dirty_marks (marked_at);;

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

-- Helper: is this task currently eligible for display?
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

-- Mark every zoom 0..12 tile that a given point affects.
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

  -- Skip no-ops.
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

-- Full rebuild of one zoom level. Single-pass Supercluster-style greedy
-- merge at z=0..11, overlap-only at z=12.
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

-- Incremental rebuild that drains the dirty-tile queue. Because the full
-- rebuild clusters globally, per-tile dirty rebuilds are an approximation:
-- they recluster just the tasks currently in that tile. A periodic full
-- rebuild corrects cross-tile drift.
CREATE OR REPLACE FUNCTION rebuild_dirty_tiles(p_limit INTEGER DEFAULT 500) RETURNS INTEGER AS $$
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
          CASE WHEN cluster_rec.count = 1 THEN 0 ELSE 2 END,
          cluster_rec.st_y, cluster_rec.st_x,
          CASE WHEN cluster_rec.count = 1 THEN neighbor_ids ELSE ARRAY[]::BIGINT[] END,
          cluster_rec.count,
          cluster_rec.jsonb_build_object
        );;
      END LOOP;;

      DROP TABLE IF EXISTS tmp_cluster_tile;;
    END IF;;

    processed := processed + 1;;
  END LOOP;;

  RETURN processed;;
END;;
$$ LANGUAGE plpgsql;;

# --- !Downs
DROP TRIGGER IF EXISTS mark_tiles_dirty_on_task_change_trigger ON tasks;;
DROP FUNCTION IF EXISTS mark_tiles_dirty_on_task_change();;
DROP FUNCTION IF EXISTS mark_tile_dirty_for_point(DOUBLE PRECISION, DOUBLE PRECISION);;
DROP FUNCTION IF EXISTS rebuild_dirty_tiles(INTEGER);;
DROP FUNCTION IF EXISTS rebuild_all_tile_aggregates();;
DROP FUNCTION IF EXISTS rebuild_zoom_level(INTEGER);;
DROP FUNCTION IF EXISTS task_is_tile_eligible(BIGINT);;
DROP FUNCTION IF EXISTS tile_pixel_size_meters(INTEGER);;
DROP FUNCTION IF EXISTS tile_envelope_3857(INTEGER, INTEGER, INTEGER);;
DROP TABLE IF EXISTS tile_dirty_marks;;

DROP TRIGGER IF EXISTS update_project_completion_metrics_trigger ON challenges;;
DROP FUNCTION IF EXISTS update_project_completion_metrics();;
DROP TRIGGER IF EXISTS update_challenge_completion_metrics_trigger ON tasks;;
DROP FUNCTION IF EXISTS update_challenge_completion_metrics();;
DROP FUNCTION IF EXISTS metrics_sub(JSONB, JSONB);;
DROP FUNCTION IF EXISTS metrics_add(JSONB, JSONB);;
DROP FUNCTION IF EXISTS task_status_metric_key(INTEGER);;
DROP FUNCTION IF EXISTS metrics_apply_delta(JSONB, TEXT, INTEGER);;
DROP FUNCTION IF EXISTS metrics_get(JSONB, TEXT);;
DROP FUNCTION IF EXISTS empty_completion_metrics();;
DROP FUNCTION IF EXISTS build_completion_metrics(INTEGER, INTEGER, INTEGER, INTEGER, INTEGER, INTEGER, INTEGER, INTEGER, INTEGER, INTEGER, INTEGER);;

ALTER TABLE IF EXISTS projects   DROP COLUMN IF EXISTS completion_metrics;;
ALTER TABLE IF EXISTS challenges DROP COLUMN IF EXISTS completion_metrics;;

# --- MapRoulette Scheme

# --- !Ups

CREATE OR REPLACE FUNCTION metrics_apply_deltas(metrics JSONB, deltas JSONB) RETURNS JSONB AS $$
  SELECT build_completion_metrics(
    GREATEST(metrics_get(metrics, 'total')         + metrics_get(deltas, 'total'),         0),
    GREATEST(metrics_get(metrics, 'available')     + metrics_get(deltas, 'available'),     0),
    GREATEST(metrics_get(metrics, 'fixed')         + metrics_get(deltas, 'fixed'),         0),
    GREATEST(metrics_get(metrics, 'falsePositive') + metrics_get(deltas, 'falsePositive'), 0),
    GREATEST(metrics_get(metrics, 'skipped')       + metrics_get(deltas, 'skipped'),       0),
    GREATEST(metrics_get(metrics, 'deleted')       + metrics_get(deltas, 'deleted'),       0),
    GREATEST(metrics_get(metrics, 'alreadyFixed')  + metrics_get(deltas, 'alreadyFixed'),  0),
    GREATEST(metrics_get(metrics, 'tooHard')       + metrics_get(deltas, 'tooHard'),       0),
    GREATEST(metrics_get(metrics, 'answered')      + metrics_get(deltas, 'answered'),      0),
    GREATEST(metrics_get(metrics, 'validated')     + metrics_get(deltas, 'validated'),     0),
    GREATEST(metrics_get(metrics, 'disabled')      + metrics_get(deltas, 'disabled'),      0)
  );;
$$ LANGUAGE SQL IMMUTABLE;;

CREATE OR REPLACE FUNCTION apply_task_changes_to_challenges(
  touched_ids BIGINT[], challenge_ids BIGINT[], metric_keys TEXT[], deltas INTEGER[]
) RETURNS VOID AS $$
BEGIN
  IF challenge_ids IS NULL THEN
    UPDATE challenges SET modified = NOW() WHERE id = ANY(touched_ids);;
    RETURN;;
  END IF;;

  UPDATE challenges c
  SET modified = NOW(),
      completion_metrics = COALESCE(
        metrics_apply_deltas(c.completion_metrics, d.deltas), c.completion_metrics
      )
  FROM (
    SELECT DISTINCT UNNEST(touched_ids) AS challenge_id
  ) t
  LEFT JOIN (
    SELECT challenge_id, jsonb_object_agg(metric_key, delta) AS deltas
    FROM (
      SELECT challenge_id, metric_key, SUM(delta)::int AS delta
      FROM UNNEST(challenge_ids, metric_keys, deltas) AS u(challenge_id, metric_key, delta)
      GROUP BY challenge_id, metric_key
      HAVING SUM(delta) <> 0
    ) per_key
    GROUP BY challenge_id
  ) d ON d.challenge_id = t.challenge_id
  WHERE c.id = t.challenge_id;;
END
$$ LANGUAGE plpgsql VOLATILE;;

CREATE OR REPLACE FUNCTION mark_dirty_leaf_cells(locations GEOMETRY[]) RETURNS VOID AS $$
BEGIN
  IF locations IS NULL THEN
    RETURN;;
  END IF;;

  INSERT INTO tile_dirty_cells (cx, cy)
  SELECT DISTINCT lng_to_tile_x(ST_X(loc), 15), lat_to_tile_y(ST_Y(loc), 15)
  FROM UNNEST(locations) AS loc
  WHERE loc IS NOT NULL
    AND NOT ST_IsEmpty(loc)
    AND ST_X(loc) BETWEEN -180 AND 180
    AND ST_Y(loc) BETWEEN -85.05112878 AND 85.05112878
  ON CONFLICT (cx, cy) DO NOTHING;;
END
$$ LANGUAGE plpgsql VOLATILE;;

CREATE OR REPLACE FUNCTION on_tasks_inserted() RETURNS TRIGGER AS $$
DECLARE
  touched_ids BIGINT[];;
  challenge_ids BIGINT[];;
  metric_keys TEXT[];;
  deltas INTEGER[];;
  locations GEOMETRY[];;
BEGIN
  SELECT array_agg(DISTINCT parent_id), array_agg(location)
  INTO touched_ids, locations
  FROM new_tasks;;

  SELECT array_agg(challenge_id), array_agg(metric_key), array_agg(delta)
  INTO challenge_ids, metric_keys, deltas
  FROM (
    SELECT parent_id AS challenge_id, 'total' AS metric_key, 1 AS delta
    FROM new_tasks WHERE task_status_metric_key(status) IS NOT NULL
    UNION ALL
    SELECT parent_id, task_status_metric_key(status), 1
    FROM new_tasks WHERE task_status_metric_key(status) IS NOT NULL
  ) changes;;

  PERFORM apply_task_changes_to_challenges(touched_ids, challenge_ids, metric_keys, deltas);;
  PERFORM mark_dirty_leaf_cells(locations);;
  RETURN NULL;;
END
$$ LANGUAGE plpgsql VOLATILE;;

CREATE OR REPLACE FUNCTION on_tasks_deleted() RETURNS TRIGGER AS $$
DECLARE
  touched_ids BIGINT[];;
  challenge_ids BIGINT[];;
  metric_keys TEXT[];;
  deltas INTEGER[];;
  locations GEOMETRY[];;
BEGIN
  SELECT array_agg(DISTINCT parent_id), array_agg(location)
  INTO touched_ids, locations
  FROM old_tasks;;

  SELECT array_agg(challenge_id), array_agg(metric_key), array_agg(delta)
  INTO challenge_ids, metric_keys, deltas
  FROM (
    SELECT parent_id AS challenge_id, 'total' AS metric_key, -1 AS delta
    FROM old_tasks WHERE task_status_metric_key(status) IS NOT NULL
    UNION ALL
    SELECT parent_id, task_status_metric_key(status), -1
    FROM old_tasks WHERE task_status_metric_key(status) IS NOT NULL
  ) changes;;

  PERFORM apply_task_changes_to_challenges(touched_ids, challenge_ids, metric_keys, deltas);;
  PERFORM mark_dirty_leaf_cells(locations);;
  RETURN NULL;;
END
$$ LANGUAGE plpgsql VOLATILE;;

CREATE OR REPLACE FUNCTION on_tasks_updated() RETURNS TRIGGER AS $$
DECLARE
  touched_ids BIGINT[];;
  challenge_ids BIGINT[];;
  metric_keys TEXT[];;
  deltas INTEGER[];;
  locations GEOMETRY[];;
BEGIN
  WITH versions AS (
    SELECT id, FALSE AS is_new, parent_id, status, archived, location FROM old_tasks
    UNION ALL
    SELECT id, TRUE, parent_id, status, archived, location FROM new_tasks
  ), pairs AS (
    SELECT id,
           MAX(parent_id) FILTER (WHERE NOT is_new) AS old_parent_id,
           MAX(parent_id) FILTER (WHERE is_new) AS new_parent_id,
           MAX(status) FILTER (WHERE NOT is_new) AS old_status,
           MAX(status) FILTER (WHERE is_new) AS new_status,
           BOOL_OR(archived) FILTER (WHERE NOT is_new) AS old_archived,
           BOOL_OR(archived) FILTER (WHERE is_new) AS new_archived,
           (ARRAY_AGG(location) FILTER (WHERE NOT is_new))[1] AS old_location,
           (ARRAY_AGG(location) FILTER (WHERE is_new))[1] AS new_location
    FROM versions
    GROUP BY id
  ), status_changes AS (
    SELECT old_parent_id, new_parent_id,
           task_status_metric_key(old_status) AS old_key,
           task_status_metric_key(new_status) AS new_key,
           old_parent_id IS DISTINCT FROM new_parent_id AS moved
    FROM pairs
    WHERE old_parent_id IS DISTINCT FROM new_parent_id
       OR old_status IS DISTINCT FROM new_status
  ), metric_changes AS (
    SELECT old_parent_id AS challenge_id, 'total' AS metric_key, -1 AS delta
    FROM status_changes WHERE moved AND old_key IS NOT NULL
    UNION ALL
    SELECT old_parent_id, old_key, -1
    FROM status_changes WHERE old_key IS NOT NULL
    UNION ALL
    SELECT new_parent_id, 'total', 1
    FROM status_changes WHERE moved AND new_key IS NOT NULL
    UNION ALL
    SELECT new_parent_id, new_key, 1
    FROM status_changes WHERE new_key IS NOT NULL
  ), dirty_locations AS (
    SELECT UNNEST(ARRAY[old_location, new_location]) AS loc
    FROM pairs
    WHERE old_status IS DISTINCT FROM new_status
       OR old_parent_id IS DISTINCT FROM new_parent_id
       OR old_archived IS DISTINCT FROM new_archived
       OR NOT ST_Equals(COALESCE(old_location, ST_GeomFromText('POINT EMPTY', 4326)),
                        COALESCE(new_location, ST_GeomFromText('POINT EMPTY', 4326)))
  )
  SELECT (SELECT array_agg(DISTINCT parent_id) FROM versions),
         (SELECT array_agg(challenge_id) FROM metric_changes),
         (SELECT array_agg(metric_key) FROM metric_changes),
         (SELECT array_agg(delta) FROM metric_changes),
         (SELECT array_agg(loc) FROM dirty_locations)
  INTO touched_ids, challenge_ids, metric_keys, deltas, locations;;

  PERFORM apply_task_changes_to_challenges(touched_ids, challenge_ids, metric_keys, deltas);;
  PERFORM mark_dirty_leaf_cells(locations);;
  RETURN NULL;;
END
$$ LANGUAGE plpgsql VOLATILE;;

DROP TRIGGER IF EXISTS update_tasks_trigger ON tasks;;
DROP TRIGGER IF EXISTS update_challenge_completion_metrics_insert_trigger ON tasks;;
DROP TRIGGER IF EXISTS update_challenge_completion_metrics_delete_trigger ON tasks;;
DROP TRIGGER IF EXISTS update_challenge_completion_metrics_update_trigger ON tasks;;
DROP TRIGGER IF EXISTS mark_dirty_on_task_change_trigger ON tasks;;

CREATE TRIGGER update_tasks_modified_trigger BEFORE INSERT OR UPDATE ON tasks
  FOR EACH ROW EXECUTE PROCEDURE update_modified();;

CREATE TRIGGER on_tasks_inserted_trigger
  AFTER INSERT ON tasks REFERENCING NEW TABLE AS new_tasks
  FOR EACH STATEMENT EXECUTE PROCEDURE on_tasks_inserted();;

CREATE TRIGGER on_tasks_deleted_trigger
  AFTER DELETE ON tasks REFERENCING OLD TABLE AS old_tasks
  FOR EACH STATEMENT EXECUTE PROCEDURE on_tasks_deleted();;

CREATE TRIGGER on_tasks_updated_trigger
  AFTER UPDATE ON tasks REFERENCING OLD TABLE AS old_tasks NEW TABLE AS new_tasks
  FOR EACH STATEMENT EXECUTE PROCEDURE on_tasks_updated();;

CREATE OR REPLACE FUNCTION task_geometry_from_geojson(geo_json JSONB) RETURNS GEOMETRY AS $$
BEGIN
  RETURN (
    SELECT ST_Collect(ST_SetSRID(ST_MakeValid(ST_GeomFromGeoJSON(feature->>'geometry')), 4326))
    FROM jsonb_array_elements(geo_json->'features') AS feature
  );;
EXCEPTION WHEN OTHERS THEN
  RETURN NULL;;
END
$$ LANGUAGE plpgsql IMMUTABLE;;

# --- !Downs

DROP FUNCTION IF EXISTS task_geometry_from_geojson(JSONB);;

DROP TRIGGER IF EXISTS on_tasks_inserted_trigger ON tasks;;
DROP TRIGGER IF EXISTS on_tasks_deleted_trigger ON tasks;;
DROP TRIGGER IF EXISTS on_tasks_updated_trigger ON tasks;;
DROP TRIGGER IF EXISTS update_tasks_modified_trigger ON tasks;;
DROP FUNCTION IF EXISTS on_tasks_inserted();;
DROP FUNCTION IF EXISTS on_tasks_deleted();;
DROP FUNCTION IF EXISTS on_tasks_updated();;
DROP FUNCTION IF EXISTS mark_dirty_leaf_cells(GEOMETRY[]);;
DROP FUNCTION IF EXISTS apply_task_changes_to_challenges(BIGINT[], BIGINT[], TEXT[], INTEGER[]);;
DROP FUNCTION IF EXISTS metrics_apply_deltas(JSONB, JSONB);;

CREATE TRIGGER update_tasks_trigger BEFORE UPDATE OR INSERT OR DELETE ON tasks
  FOR EACH ROW EXECUTE PROCEDURE update_tasks();;

CREATE TRIGGER update_challenge_completion_metrics_insert_trigger
  AFTER INSERT ON tasks
  FOR EACH ROW EXECUTE PROCEDURE update_challenge_completion_metrics();;
CREATE TRIGGER update_challenge_completion_metrics_delete_trigger
  AFTER DELETE ON tasks
  FOR EACH ROW EXECUTE PROCEDURE update_challenge_completion_metrics();;
CREATE TRIGGER update_challenge_completion_metrics_update_trigger
  AFTER UPDATE OF status, parent_id ON tasks
  FOR EACH ROW
  WHEN (OLD.parent_id IS DISTINCT FROM NEW.parent_id
        OR OLD.status IS DISTINCT FROM NEW.status)
  EXECUTE PROCEDURE update_challenge_completion_metrics();;

CREATE TRIGGER mark_dirty_on_task_change_trigger
  AFTER INSERT OR UPDATE OR DELETE ON tasks
  FOR EACH ROW EXECUTE PROCEDURE mark_dirty_on_task_change();;

# --- MapRoulette Scheme

# --- !Ups
-- =============================================================================
-- CompletionMetrics on challenges and projects
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


# --- !Downs
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

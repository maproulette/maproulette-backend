# --- MapRoulette Scheme

# --- !Ups

-- Drop the legacy completion_percentage and tasks_remaining columns on challenges
-- (replaced by completion_metrics JSONB column added in evolution 111).
ALTER TABLE challenges DROP COLUMN IF EXISTS completion_percentage;;
ALTER TABLE challenges DROP COLUMN IF EXISTS tasks_remaining;;

-- These indexes were only needed to support the old scheduled stats job
DROP INDEX IF EXISTS idx_tasks_status_non_zero;;
DROP INDEX IF EXISTS idx_challenges_id_deleted_archived;;

# --- !Downs

-- Restore the indexes dropped above.
CREATE INDEX IF NOT EXISTS idx_tasks_status_non_zero ON tasks(status) WHERE status != 0;;
CREATE INDEX IF NOT EXISTS idx_challenges_id_deleted_archived ON challenges (id) WHERE NOT deleted AND NOT is_archived;;

-- Restore the columns with their original definitions (evolution 84) and
-- backfill from completion_metrics so existing rows have plausible values.
ALTER TABLE challenges ADD COLUMN IF NOT EXISTS completion_percentage INTEGER DEFAULT 0;;
ALTER TABLE challenges ADD COLUMN IF NOT EXISTS tasks_remaining INTEGER DEFAULT 0;;

UPDATE challenges SET
  completion_percentage = CASE
    WHEN (metrics_get(completion_metrics, 'total')
          - metrics_get(completion_metrics, 'deleted')
          - metrics_get(completion_metrics, 'disabled')) <= 0 THEN 0
    ELSE ROUND(
      (metrics_get(completion_metrics, 'fixed')
       + metrics_get(completion_metrics, 'falsePositive')
       + metrics_get(completion_metrics, 'alreadyFixed'))::numeric
      * 100
      / (metrics_get(completion_metrics, 'total')
         - metrics_get(completion_metrics, 'deleted')
         - metrics_get(completion_metrics, 'disabled'))
    )::int
  END,
  tasks_remaining = metrics_get(completion_metrics, 'available')
                  + metrics_get(completion_metrics, 'skipped')
                  + metrics_get(completion_metrics, 'tooHard');;

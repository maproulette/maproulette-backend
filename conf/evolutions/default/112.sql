# --- MapRoulette Scheme

# --- !Ups
-- =============================================================================
-- Part 1: Per-task skip_count
-- =============================================================================

ALTER TABLE IF EXISTS tasks
  ADD COLUMN IF NOT EXISTS skip_count INTEGER NOT NULL DEFAULT 0;;

CREATE INDEX IF NOT EXISTS idx_tasks_skip_count ON tasks(skip_count);;

-- =============================================================================
-- Part 2: Task archive flag (for bulk archive without deleting)
-- =============================================================================

ALTER TABLE IF EXISTS tasks
  ADD COLUMN IF NOT EXISTS archived BOOLEAN NOT NULL DEFAULT FALSE;;

CREATE INDEX IF NOT EXISTS idx_tasks_archived ON tasks(archived);;

# --- !Downs
DROP INDEX IF EXISTS idx_tasks_skip_count;;
ALTER TABLE IF EXISTS tasks DROP COLUMN IF EXISTS skip_count;;
DROP INDEX IF EXISTS idx_tasks_archived;;
ALTER TABLE IF EXISTS tasks DROP COLUMN IF EXISTS archived;;

# --- MapRoulette Scheme

# --- !Ups

-- Hash the task 'name' (unique key) to ensure indexed values are below the btree size
-- entry limit. Prod already has this index (created by hand at some point?) so this
-- is meant to be a no-op if this index already exists with the correct type.
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_indexes
    WHERE indexname = 'idx_tasks_parent_id_name' AND indexdef LIKE '%md5(lower(%'
  ) THEN
    DROP INDEX IF EXISTS idx_tasks_parent_id_name;;
    CREATE UNIQUE INDEX idx_tasks_parent_id_name ON tasks (parent_id, md5(lower(name)));;
  END IF;;
END
$$;;

# --- !Downs

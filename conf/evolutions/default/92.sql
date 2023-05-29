# --- !Ups
CREATE INDEX IF NOT EXISTS idx_tasks_status_non_zero ON tasks(status) WHERE status != 0;
CREATE INDEX IF NOT EXISTS idx_tasks_parent_id_status ON tasks (parent_id, status);
CREATE INDEX IF NOT EXISTS idx_challenges_id_deleted_archived ON challenges (id) WHERE NOT deleted AND NOT is_archived;

# --- !Downs
DROP INDEX IF EXISTS idx_tasks_status_non_zero;
DROP INDEX IF EXISTS idx_tasks_parent_id_status;
DROP INDEX IF EXISTS idx_challenges_id_deleted_archived;

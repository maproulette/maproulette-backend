# --- !Ups
ALTER TABLE users ADD COLUMN disable_task_confirm BOOLEAN DEFAULT false;;
ALTER TABLE challenges ADD COLUMN require_comment BOOLEAN DEFAULT false;;
ALTER TABLE projects ADD COLUMN require_comment BOOLEAN DEFAULT false;;

# --- !Downs
ALTER TABLE IF EXISTS users DROP COLUMN disable_task_confirm;;
ALTER TABLE IF EXISTS challenges DROP COLUMN require_comment;;
ALTER TABLE IF EXISTS projects DROP COLUMN require_comment;;

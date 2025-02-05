# --- !Ups
ALTER TABLE task_comments ADD COLUMN edited BOOLEAN DEFAULT false;;

# --- !Downs
ALTER TABLE IF EXISTS task_comments DROP COLUMN edited;;

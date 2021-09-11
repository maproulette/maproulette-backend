# --- !Ups
ALTER TABLE challenges ADD COLUMN completion_percentage INTEGER DEFAULT 0;;
ALTER TABLE challenges ADD COLUMN tasks_remaining INTEGER DEFAULT 0;;

# --- !Downs
ALTER TABLE IF EXISTS challenges DROP COLUMN completion_percentage;;
ALTER TABLE IF EXISTS challenges DROP COLUMN tasks_remaining;;

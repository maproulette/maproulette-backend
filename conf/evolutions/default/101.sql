# --- !Ups
CREATE EXTENSION IF NOT EXISTS btree_gist; 

CREATE INDEX IF NOT EXISTS idx_tasks_parent_location ON tasks USING GIST (parent_id, location);

# --- !Downs

DROP INDEX IF EXISTS idx_tasks_parent_location;

DROP EXTENSION IF EXISTS btree_gist;

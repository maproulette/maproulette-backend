# --- !Ups
CREATE EXTENSION btree_gist; 

CREATE INDEX idx_tasks_parent_location ON tasks USING GIST (parent_id, location);

# --- !Downs

DROP INDEX idx_tasks_parent_location;

DROP EXTENSION btree_gist;

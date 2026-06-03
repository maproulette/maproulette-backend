# --- !Ups

CREATE EXTENSION IF NOT EXISTS pg_trgm;;
CREATE INDEX IF NOT EXISTS idx_tasks_name_trgm ON tasks USING gin (name gin_trgm_ops);;

# --- !Downs

DROP INDEX IF EXISTS idx_tasks_name_trgm;;

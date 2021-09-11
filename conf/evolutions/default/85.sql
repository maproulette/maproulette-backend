# --- !Ups
ALTER TABLE challenges ADD COLUMN system_archived_at TIMESTAMP WITHOUT TIME ZONE DEFAULT NULL;;

# --- !Downs
ALTER TABLE IF EXISTS challenges DROP COLUMN system_archived_at;;
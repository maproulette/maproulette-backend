# --- !Ups
ALTER TABLE IF EXISTS challenges DROP COLUMN changeset_url;;

# --- !Downs
ALTER TABLE challenges ADD COLUMN changeset_url BOOLEAN DEFAULT false;;

# --- !Ups
ALTER TABLE challenges ADD COLUMN dataset_url VARCHAR DEFAULT NULL;;

# --- !Downs
ALTER TABLE IF EXISTS challenges DROP COLUMN dataset_url;;

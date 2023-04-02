# --- !Ups
ALTER TABLE IF EXISTS challenges
ADD COLUMN review_setting INTEGER DEFAULT 0;;

# --- !Downs
ALTER TABLE IF EXISTS challenges
DROP COLUMN review_setting;;

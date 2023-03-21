# --- !Ups
ALTER TABLE IF EXISTS challenges
ADD COLUMN review_settings BOOLEAN DEFAULT false;;

# --- !Downs
ALTER TABLE IF EXISTS challenges
DROP COLUMN review_settings;;
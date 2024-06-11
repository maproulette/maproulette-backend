# --- !Ups
ALTER TABLE IF EXISTS challenges
ADD COLUMN default_overlay VARCHAR;

# --- !Downs
ALTER TABLE IF EXISTS challenges
DROP COLUMN default_overlay;

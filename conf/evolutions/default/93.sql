-- --- !Ups
ALTER TABLE IF EXISTS challenges
ADD COLUMN layout_json text DEFAULT '';

-- --- !Downs
ALTER TABLE IF EXISTS challenges
DROP COLUMN layout_json;
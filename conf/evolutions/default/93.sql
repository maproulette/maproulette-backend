-- --- !Ups
ALTER TABLE IF EXISTS challenges
ADD COLUMN widget_layout text DEFAULT '';

-- --- !Downs
ALTER TABLE IF EXISTS challenges
DROP COLUMN widget_layout;
-- --- !Ups
ALTER TABLE IF EXISTS challenges
ADD COLUMN widget_layout jsonb NOT NULL DEFAULT '{}'::jsonb;

-- --- !Downs
ALTER TABLE IF EXISTS challenges
DROP COLUMN widget_layout;

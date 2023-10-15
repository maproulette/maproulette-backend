-- --- !Ups
ALTER TABLE IF EXISTS challenges
    ADD COLUMN widget_layout jsonb NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN challenges.widget_layout IS
    'The challenges.widget_layout is a json body that the GUI uses as a "suggested layout" when displaying the challenge to editors.';

-- --- !Downs
ALTER TABLE IF EXISTS challenges
DROP COLUMN widget_layout;

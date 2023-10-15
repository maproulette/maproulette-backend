-- --- !Ups
ALTER TABLE IF EXISTS challenges
    ADD COLUMN task_widget_layout jsonb NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN challenges.task_widget_layout IS
    'The challenges.task_widget_layout is json that the GUI uses as a "suggested layout" when displaying the Task Completion page.';

-- --- !Downs
ALTER TABLE IF EXISTS challenges
DROP COLUMN task_widget_layout;

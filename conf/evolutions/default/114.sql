# --- MapRoulette Scheme

# --- !Ups
-- Normalize legacy no-value sentinel strings in challenge JSON columns to SQL
-- NULL (or the column default for NOT NULL columns). This lets us simplify
-- the read path in the code since we don't need to check for these values.

UPDATE challenges
SET task_widget_layout = '{}'::jsonb
WHERE jsonb_typeof(task_widget_layout) = 'string'
  AND task_widget_layout::text = '""';;

UPDATE challenges SET task_styles = NULL
WHERE task_styles IN ('', '[]', 'null');;

UPDATE challenges SET high_priority_rule   = NULL WHERE high_priority_rule   IN ('', '{}');;
UPDATE challenges SET medium_priority_rule = NULL WHERE medium_priority_rule IN ('', '{}');;
UPDATE challenges SET low_priority_rule    = NULL WHERE low_priority_rule    IN ('', '{}');;

UPDATE challenges SET high_priority_bounds   = NULL WHERE high_priority_bounds   IN ('', '[]');;
UPDATE challenges SET medium_priority_bounds = NULL WHERE medium_priority_bounds IN ('', '[]');;
UPDATE challenges SET low_priority_bounds    = NULL WHERE low_priority_bounds    IN ('', '[]');;

# --- !Downs
SELECT 1;;

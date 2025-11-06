# --- MapRoulette Scheme

# --- !Ups
-- Modify unique constraint on (parent_id, name) to exclude deleted challenges
-- This allows challenge names to be reused after deletion
DROP INDEX IF EXISTS idx_challenges_parent_id_name;;

CREATE UNIQUE INDEX idx_challenges_parent_id_name ON challenges (parent_id, lower(name)) 
WHERE (deleted = false OR deleted IS NULL);;

# --- !Downs
-- Restore original unique constraint on (parent_id, name) without deleted filter
DROP INDEX IF EXISTS idx_challenges_parent_id_name;;

SELECT create_index_if_not_exists('challenges', 'parent_id_name', '(parent_id, lower(name))', true);;


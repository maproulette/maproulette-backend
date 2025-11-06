# --- MapRoulette Scheme

# --- !Ups
-- Modify unique constraint on (parent_id, name) to exclude deleted challenges
-- This allows challenge names to be reused after deletion
DROP INDEX IF EXISTS idx_challenges_parent_id_name;;

CREATE UNIQUE INDEX idx_challenges_parent_id_name ON challenges (parent_id, lower(name)) 
WHERE (deleted = false OR deleted IS NULL);;

# --- !Downs
DROP INDEX IF EXISTS idx_challenges_parent_id_name;;

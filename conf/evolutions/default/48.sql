# --- MapRoulette Scheme

# --- !Ups

-- Setup constraints on virtual_project_challenges to restrict duplicate
-- entries.

-- Find and fix prior duplicate entries.
DELETE FROM
    virtual_project_challenges a
     USING virtual_project_challenges b
WHERE
    a.id < b.id
    AND a.project_id = b.project_id
    AND a.challenge_id = b.challenge_id;;

-- Add unique constraint.
CREATE UNIQUE INDEX virtual_project_challenges_projects
  ON virtual_project_challenges (project_id, challenge_id);;

ALTER TABLE virtual_project_challenges DROP CONSTRAINT
    IF EXISTS unique_virtual_project_challenges_projects;;
ALTER TABLE virtual_project_challenges
  ADD CONSTRAINT unique_virtual_project_challenges_projects
  UNIQUE USING INDEX virtual_project_challenges_projects;;

# --- !Downs
ALTER TABLE virtual_project_challenges
  DROP CONSTRAINT IF EXISTS unique_virtual_project_challenges;;

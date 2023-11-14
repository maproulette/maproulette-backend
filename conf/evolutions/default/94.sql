# --- !Ups
-- Remove the constraint for a distinct virtual challenge (id,name) tuple, and allow users to create virtual challenges with the same name.
ALTER TABLE virtual_challenges DROP CONSTRAINT IF EXISTS CON_VIRTUAL_CHALLENGES_USER_ID_NAME;
DROP INDEX IF EXISTS idx_challenges_parent_id_name;

# -- !Downs
ALTER TABLE virtual_challenges ADD CONSTRAINT CON_VIRTUAL_CHALLENGES_USER_ID_NAME
  UNIQUE (owner_id, name);

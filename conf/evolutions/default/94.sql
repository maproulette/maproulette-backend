# --- !Ups
-- Remove the constraint for a distinct virtual challenge (id,name) tuple, and allow users to create virtual challenges with the same name.
ALTER TABLE virtual_challenges DROP CONSTRAINT IF EXISTS CON_VIRTUAL_CHALLENGES_USER_ID_NAME;

# -- !Downs
ALTER TABLE virtual_challenges ADD CONSTRAINT CON_VIRTUAL_CHALLENGES_USER_ID_NAME
  UNIQUE (owner_id, name);

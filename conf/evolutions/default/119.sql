# --- MapRoulette Scheme

# --- !Ups

-- Pausing a challenge locks its tasks against completion/review without
-- touching individual task statuses, unlike disabling the challenge (which
-- leaves existing tasks fully workable via the completion widget).
ALTER TABLE challenges ADD COLUMN IF NOT EXISTS paused BOOLEAN NOT NULL DEFAULT false;;

# --- !Downs

ALTER TABLE IF EXISTS challenges DROP COLUMN IF EXISTS paused;;

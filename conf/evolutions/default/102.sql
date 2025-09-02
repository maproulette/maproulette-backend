# --- !Ups
ALTER TABLE challenges ADD COLUMN IF NOT EXISTS require_reject_reason BOOLEAN DEFAULT false;;

# --- !Downs
ALTER TABLE IF EXISTS challenges DROP COLUMN IF EXISTS require_reject_reason;;
# --- !Ups
ALTER TABLE challenges ADD COLUMN require_reject_reason BOOLEAN DEFAULT false;;

# --- !Downs
ALTER TABLE IF EXISTS challenges DROP COLUMN require_reject_reason;;
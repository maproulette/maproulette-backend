# --- !Ups
ALTER TABLE IF EXISTS tags
ADD COLUMN active Boolean DEFAULT true;;

# --- !Downs
ALTER TABLE IF EXISTS tags
DROP COLUMN active;;

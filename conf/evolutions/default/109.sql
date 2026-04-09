# --- MapRoulette Scheme

# --- !Ups
-- Add plugins column to users table to store user's installed plugins configuration
ALTER TABLE users ADD COLUMN plugins TEXT;;

# --- !Downs
ALTER TABLE users DROP COLUMN plugins;;

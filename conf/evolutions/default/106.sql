# --- !Ups
ALTER TABLE users ADD COLUMN show_priority_marker_colors BOOLEAN DEFAULT false;;

# --- !Downs
ALTER TABLE IF EXISTS users DROP COLUMN show_priority_marker_colors;;


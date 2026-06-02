# --- MapRoulette Scheme

# --- !Ups

-- Add NOT NULL to tasks.geom and tasks.location. Both columns are derived
-- from tasks.geojson at insert time, and a NULL value here would mean the
-- row's GeoJSON could not be parsed into a PostGIS geometry.
--
-- Adding these constraints only locks the tasks table briefly.
-- See https://dba.stackexchange.com/questions/267947/
ALTER TABLE tasks
  ADD CONSTRAINT tasks_geom_not_null CHECK (geom IS NOT NULL) NOT VALID;;
ALTER TABLE tasks VALIDATE CONSTRAINT tasks_geom_not_null;;
ALTER TABLE tasks ALTER COLUMN geom SET NOT NULL;;
ALTER TABLE tasks DROP CONSTRAINT tasks_geom_not_null;;

ALTER TABLE tasks
  ADD CONSTRAINT tasks_location_not_null CHECK (location IS NOT NULL) NOT VALID;;
ALTER TABLE tasks VALIDATE CONSTRAINT tasks_location_not_null;;
ALTER TABLE tasks ALTER COLUMN location SET NOT NULL;;
ALTER TABLE tasks DROP CONSTRAINT tasks_location_not_null;;

# --- !Downs
ALTER TABLE tasks ALTER COLUMN geom DROP NOT NULL;;
ALTER TABLE tasks ALTER COLUMN location DROP NOT NULL;;

# --- MapRoulette Scheme

# --- !Ups

-- Drop the legacy task_geometries table and its associated update_geometry
-- function. This table was originally used to store task geojsons, but
-- commit b95bfe2d moved this data into the main tasks table. migration
-- was done lazily at read time in the Scala code. today, every task in
-- the prod database has a non-null geojson column, so the task_geometries
-- table can safely be dropped.
DROP FUNCTION IF EXISTS update_geometry(bigint);;
DROP TABLE IF EXISTS task_geometries CASCADE;;

-- Add NOT NULL to tasks.geojson. This only locks briefly.
-- See https://dba.stackexchange.com/questions/267947/
ALTER TABLE tasks
  ADD CONSTRAINT tasks_geojson_not_null CHECK (geojson IS NOT NULL) NOT VALID;;
ALTER TABLE tasks VALIDATE CONSTRAINT tasks_geojson_not_null;;
ALTER TABLE tasks ALTER COLUMN geojson SET NOT NULL;;
ALTER TABLE tasks DROP CONSTRAINT tasks_geojson_not_null;;

# --- !Downs
ALTER TABLE tasks ALTER COLUMN geojson DROP NOT NULL;;

-- Restore task_geometries. Copied verbatim from evolution 1.sql (lines 293-313).
CREATE TABLE IF NOT EXISTS task_geometries
(
  id SERIAL NOT NULL PRIMARY KEY,
  task_id integer NOT NULL,
  properties HSTORE,
  CONSTRAINT task_geometries_task_id_fkey FOREIGN KEY (task_id)
    REFERENCES tasks (id) MATCH SIMPLE
    ON UPDATE CASCADE ON DELETE CASCADE
    DEFERRABLE INITIALLY DEFERRED
);;

DO $$
BEGIN
  PERFORM column_name FROM information_schema.columns WHERE table_name = 'task_geometries' AND column_name = 'geom';;
  IF NOT FOUND THEN
    PERFORM AddGeometryColumn('task_geometries', 'geom', 4326, 'GEOMETRY', 2);;
  END IF;;
END$$;;

CREATE INDEX IF NOT EXISTS idx_task_geometries_geom ON task_geometries USING GIST (geom);;
SELECT create_index_if_not_exists('task_geometries', 'task_id', '(task_id)');;

-- Restore update_geometry. Copied verbatim from evolution 61.sql (lines 132-153).
DROP FUNCTION IF EXISTS update_geometry(bigint);;
CREATE OR REPLACE FUNCTION update_geometry(task_identifier bigint)
	RETURNS TABLE(geo TEXT, loc TEXT, fix_geo TEXT) AS $$
BEGIN
    UPDATE tasks t SET geojson = geoms.geometries FROM (SELECT ROW_TO_JSON(fc)::JSONB AS geometries
                      FROM ( SELECT 'FeatureCollection' AS type, ARRAY_TO_JSON(array_agg(f)) AS features
                               FROM ( SELECT 'Feature' AS type,
                                              ST_AsGeoJSON(lg.geom)::JSONB AS geometry,
                                              HSTORE_TO_JSON(lg.properties) AS properties
                                      FROM task_geometries AS lg
                                      WHERE task_id = task_identifier
                                ) AS f
                        )  AS fc) AS geoms WHERE id = task_identifier;;
    -- Update the geometry and location columns
    UPDATE tasks t SET geom = geoms.geometry, location = ST_CENTROID(geoms.geometry)
    FROM (SELECT ST_COLLECT(ST_MAKEVALID(geom)) AS geometry FROM (
            SELECT geom FROM task_geometries WHERE task_id = task_identifier
         ) AS innerQuery) AS geoms WHERE id = task_identifier;;
	 RETURN QUERY SELECT geojson::TEXT, ST_AsGeoJSON(location) AS geo_location, cooperative_work_json::TEXT FROM tasks
	 WHERE id = task_identifier;;
END
$$ LANGUAGE plpgsql VOLATILE;;

-- The schema that the Play evolutions in conf/evolutions/default/ produce, dumped with pg_dump.
-- Generated, so do not edit it by hand. Regenerate it after adding or changing an evolution:
-- see README.md.

--
-- PostgreSQL database dump
--


-- Dumped from database version 17.6 (Debian 17.6-1.pgdg12+1)
-- Dumped by pg_dump version 17.6 (Debian 17.6-1.pgdg12+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: btree_gist; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;


--
-- Name: EXTENSION btree_gist; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION btree_gist IS 'support for indexing common datatypes in GiST';


--
-- Name: hstore; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS hstore WITH SCHEMA public;


--
-- Name: EXTENSION hstore; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION hstore IS 'data type for storing sets of (key, value) pairs';


--
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;


--
-- Name: EXTENSION pg_trgm; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pg_trgm IS 'text similarity measurement and index searching based on trigrams';


--
-- Name: postgis; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;


--
-- Name: EXTENSION postgis; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION postgis IS 'PostGIS geometry and geography spatial types and functions';


--
-- Name: add_drop_column(character varying, character varying, character varying, boolean); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.add_drop_column(tablename character varying, colname character varying, coltype character varying, addcolumn boolean DEFAULT true) RETURNS character varying
    LANGUAGE plpgsql
    AS $$
DECLARE
col_name varchar;
BEGIN
EXECUTE 'SELECT column_name FROM information_schema.columns WHERE table_name =' || quote_literal(tablename) || ' and column_name = ' || quote_literal(colname) into col_name;
RAISE INFO ' the val : % ', col_name;
IF (col_name IS NULL AND addcolumn) THEN
EXECUTE 'ALTER TABLE IF EXISTS ' || tablename || ' ADD COLUMN ' || colname || ' ' || coltype;
ELSEIF (col_name IS NOT NULL AND NOT addcolumn) THEN
EXECUTE 'ALTER TABLE IF EXISTS ' || tablename || ' DROP COLUMN ' || colname;
END IF;
RETURN col_name;
END
$$;


--
-- Name: apply_task_changes_to_challenges(bigint[], bigint[], text[], integer[]); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.apply_task_changes_to_challenges(touched_ids bigint[], challenge_ids bigint[], metric_keys text[], deltas integer[]) RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
IF challenge_ids IS NULL THEN
UPDATE challenges SET modified = NOW() WHERE id = ANY(touched_ids);
RETURN;
END IF;

UPDATE challenges c
SET modified = NOW(),
completion_metrics = COALESCE(
metrics_apply_deltas(c.completion_metrics, d.deltas), c.completion_metrics
)
FROM (
SELECT DISTINCT UNNEST(touched_ids) AS challenge_id
) t
LEFT JOIN (
SELECT challenge_id, jsonb_object_agg(metric_key, delta) AS deltas
FROM (
SELECT challenge_id, metric_key, SUM(delta)::int AS delta
FROM UNNEST(challenge_ids, metric_keys, deltas) AS u(challenge_id, metric_key, delta)
GROUP BY challenge_id, metric_key
HAVING SUM(delta) <> 0
) per_key
GROUP BY challenge_id
) d ON d.challenge_id = t.challenge_id
WHERE c.id = t.challenge_id;
END
$$;


--
-- Name: array_distinct(anyarray); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.array_distinct(arr anyarray) RETURNS anyarray
    LANGUAGE sql IMMUTABLE
    AS $$
SELECT array_agg(elem order by ord)
from (
SELECT DISTINCT on(elem) elem, ord
FROM unnest(arr) WITH ordinality AS arr(elem, ord)
ORDER BY elem, ord
) s
$$;


--
-- Name: build_completion_metrics(integer, integer, integer, integer, integer, integer, integer, integer, integer, integer, integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.build_completion_metrics(total integer, available integer, fixed integer, false_positive integer, skipped integer, deleted integer, already_fixed integer, too_hard integer, answered integer, validated integer, disabled integer) RETURNS jsonb
    LANGUAGE plpgsql IMMUTABLE
    AS $$
BEGIN
RETURN jsonb_build_object(
'total',         COALESCE(total, 0),
'available',     COALESCE(available, 0),
'fixed',         COALESCE(fixed, 0),
'falsePositive', COALESCE(false_positive, 0),
'skipped',       COALESCE(skipped, 0),
'deleted',       COALESCE(deleted, 0),
'alreadyFixed',  COALESCE(already_fixed, 0),
'tooHard',       COALESCE(too_hard, 0),
'answered',      COALESCE(answered, 0),
'validated',     COALESCE(validated, 0),
'disabled',      COALESCE(disabled, 0)
);
END;
$$;


--
-- Name: create_index_if_not_exists(text, text, text, boolean); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.create_index_if_not_exists(t_name text, i_name text, index_sql text, unq boolean DEFAULT false) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
full_index_name varchar;
schema_name varchar;
unqValue varchar;
BEGIN
full_index_name = 'idx_' || t_name || '_' || i_name;
schema_name = 'public';
unqValue = '';
IF unq THEN
unqValue = 'UNIQUE ';
END IF;

IF NOT EXISTS (
SELECT 1
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relname = full_index_name
AND n.nspname = schema_name
) THEN
execute 'CREATE ' || unqValue || 'INDEX ' || full_index_name || ' ON ' || schema_name || '.' || t_name || ' ' || index_sql;
END IF;
END
$$;


--
-- Name: create_update_task(text, bigint, text, integer, bigint, integer, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.create_update_task(task_name text, task_parent_id bigint, task_instruction text, task_status integer, task_id bigint DEFAULT '-1'::integer, task_priority integer DEFAULT 0, reset_interval text DEFAULT '7 days'::text) RETURNS integer
    LANGUAGE plpgsql
    AS $$
DECLARE
return_id integer;
BEGIN
return_id := task_id;
IF (SELECT task_id) = -1 THEN
BEGIN
INSERT INTO tasks (name, parent_id, instruction, priority) VALUES (task_name, task_parent_id, task_instruction, task_priority) RETURNING id INTO return_id;
EXCEPTION WHEN UNIQUE_VIOLATION THEN
SELECT INTO return_id update_task(task_name, task_parent_id, task_instruction, task_status, task_id, task_priority, reset_interval);
END;
ELSE
PERFORM update_task(task_name, task_parent_id, task_instruction, task_status, task_id, task_priority, reset_interval);
END IF;
RETURN return_id;
END
$$;


--
-- Name: create_update_task(text, bigint, text, integer, bigint, integer, bigint, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.create_update_task(task_name text, task_parent_id bigint, task_instruction text, task_status integer, task_id bigint DEFAULT '-1'::integer, task_priority integer DEFAULT 0, task_changeset_id bigint DEFAULT '-1'::integer, reset_interval text DEFAULT '7 days'::text) RETURNS integer
    LANGUAGE plpgsql
    AS $$
DECLARE
return_id integer;
BEGIN
return_id := task_id;
IF (SELECT task_id) = -1 THEN
BEGIN
INSERT INTO tasks (name, parent_id, instruction,  priority) VALUES (task_name, task_parent_id, task_instruction, task_priority) RETURNING id INTO return_id;
EXCEPTION WHEN UNIQUE_VIOLATION THEN
SELECT INTO return_id update_task(task_name, task_parent_id, task_instruction, task_status, task_id, task_priority, task_changeset_id, reset_interval);
END;
ELSE
PERFORM update_task(task_name, task_parent_id, task_instruction, task_status, task_id, task_priority, task_changeset_id, reset_interval);
END IF;
RETURN return_id;
END
$$;


--
-- Name: create_update_task(text, bigint, text, integer, bigint, integer, bigint, text, timestamp without time zone, integer, integer, integer, timestamp without time zone); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.create_update_task(task_name text, task_parent_id bigint, task_instruction text, task_status integer, task_id bigint DEFAULT '-1'::integer, task_priority integer DEFAULT 0, task_changeset_id bigint DEFAULT '-1'::integer, reset_interval text DEFAULT '7 days'::text, task_mapped_on timestamp without time zone DEFAULT NULL::timestamp without time zone, task_review_status integer DEFAULT NULL::integer, task_review_requested_by integer DEFAULT NULL::integer, task_reviewed_by integer DEFAULT NULL::integer, task_reviewed_at timestamp without time zone DEFAULT NULL::timestamp without time zone) RETURNS integer
    LANGUAGE plpgsql
    AS $$
DECLARE
return_id integer;
BEGIN
return_id := task_id;
IF (SELECT task_id) = -1 THEN
BEGIN
INSERT INTO tasks (name, parent_id, instruction,  priority) VALUES (task_name, task_parent_id, task_instruction, task_priority) RETURNING id INTO return_id;
EXCEPTION WHEN UNIQUE_VIOLATION THEN
SELECT INTO return_id update_task(task_name, task_parent_id, task_instruction, task_status, task_id, task_priority, task_changeset_id, reset_interval, task_mapped_on, task_review_status, task_review_requested_by, task_reviewed_by, task_reviewed_at);
END;
ELSE
PERFORM update_task(task_name, task_parent_id, task_instruction, task_status, task_id, task_priority, task_changeset_id, reset_interval, task_mapped_on, task_review_status, task_review_requested_by, task_reviewed_by, task_reviewed_at);
END IF;
RETURN return_id;
END
$$;


--
-- Name: create_update_task(text, bigint, text, integer, jsonb, jsonb, bigint, integer, bigint, text, timestamp without time zone, integer, integer, integer, timestamp without time zone); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.create_update_task(task_name text, task_parent_id bigint, task_instruction text, task_status integer, geo_json jsonb, cooperative_work jsonb DEFAULT NULL::jsonb, task_id bigint DEFAULT '-1'::integer, task_priority integer DEFAULT 0, task_changeset_id bigint DEFAULT '-1'::integer, reset_interval text DEFAULT '7 days'::text, task_mapped_on timestamp without time zone DEFAULT NULL::timestamp without time zone, task_review_status integer DEFAULT NULL::integer, task_review_requested_by integer DEFAULT NULL::integer, task_reviewed_by integer DEFAULT NULL::integer, task_reviewed_at timestamp without time zone DEFAULT NULL::timestamp without time zone) RETURNS integer
    LANGUAGE plpgsql
    AS $$
DECLARE
return_id integer;
geojson_geom geometry;
new_task_task_status integer;
BEGIN
return_id := task_id;
IF (SELECT task_id) = -1 THEN
IF (task_status IS NULL) THEN
-- default to created
new_task_task_status := 0;
ELSE
-- set task status to passed in status
new_task_task_status := task_status;
END IF;

BEGIN
SELECT ST_COLLECT(ST_SETSRID(ST_MAKEVALID(ST_GEOMFROMGEOJSON(jsonb_array_elements_Text::JSONB->>'geometry')), 4326)) INTO geojson_geom
FROM JSONB_ARRAY_ELEMENTS_TEXT(geo_json->'features');

INSERT INTO tasks (name, parent_id, status, instruction, priority, geojson, location, geom, cooperative_work_json)
VALUES (task_name, task_parent_id, new_task_task_status, task_instruction, task_priority, geo_json, ST_Centroid(geojson_geom), geojson_geom, cooperative_work) RETURNING id INTO return_id;
EXCEPTION WHEN UNIQUE_VIOLATION THEN
SELECT INTO return_id update_task(task_name, task_parent_id, task_instruction, task_status, geo_json, cooperative_work, task_id, task_priority,
task_changeset_id, reset_interval, task_mapped_on, task_review_status, task_review_requested_by, task_reviewed_by, task_reviewed_at);
END;
ELSE
PERFORM update_task(task_name, task_parent_id, task_instruction, task_status, geo_json, cooperative_work, task_id, task_priority,
task_changeset_id, reset_interval, task_mapped_on, task_review_status, task_review_requested_by, task_reviewed_by, task_reviewed_at);
END IF;
RETURN return_id;
END
$$;


--
-- Name: empty_completion_metrics(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.empty_completion_metrics() RETURNS jsonb
    LANGUAGE sql IMMUTABLE
    AS $$
SELECT build_completion_metrics(0,0,0,0,0,0,0,0,0,0,0);
$$;


--
-- Name: lat_to_tile_y(double precision, integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.lat_to_tile_y(lat double precision, zoom integer) RETURNS integer
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $$
SELECT FLOOR((1.0 - LN(TAN(RADIANS(GREATEST(-85.0511, LEAST(85.0511, lat)))) +
1.0 / COS(RADIANS(GREATEST(-85.0511, LEAST(85.0511, lat))))) / PI()) / 2.0 * (1 << zoom))::INTEGER
$$;


--
-- Name: lng_to_tile_x(double precision, integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.lng_to_tile_x(lng double precision, zoom integer) RETURNS integer
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $$
SELECT FLOOR((lng + 180.0) / 360.0 * (1 << zoom))::INTEGER
$$;


--
-- Name: mark_dirty_leaf_cell(public.geometry); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.mark_dirty_leaf_cell(p_loc public.geometry) RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
IF p_loc IS NULL OR ST_IsEmpty(p_loc) THEN
RETURN;
END IF;
IF ST_X(p_loc) < -180 OR ST_X(p_loc) > 180
OR ST_Y(p_loc) < -85.05112878 OR ST_Y(p_loc) > 85.05112878 THEN
RETURN;
END IF;
INSERT INTO tile_dirty_cells (cx, cy)
VALUES (lng_to_tile_x(ST_X(p_loc), 15), lat_to_tile_y(ST_Y(p_loc), 15))
ON CONFLICT (cx, cy) DO NOTHING;
END;
$$;


--
-- Name: mark_dirty_leaf_cells(public.geometry[]); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.mark_dirty_leaf_cells(locations public.geometry[]) RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
IF locations IS NULL THEN
RETURN;
END IF;

INSERT INTO tile_dirty_cells (cx, cy)
SELECT DISTINCT lng_to_tile_x(ST_X(loc), 15), lat_to_tile_y(ST_Y(loc), 15)
FROM UNNEST(locations) AS loc
WHERE loc IS NOT NULL
AND NOT ST_IsEmpty(loc)
AND ST_X(loc) BETWEEN -180 AND 180
AND ST_Y(loc) BETWEEN -85.05112878 AND 85.05112878
ON CONFLICT (cx, cy) DO NOTHING;
END
$$;


--
-- Name: mark_dirty_on_challenge_change(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.mark_dirty_on_challenge_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
IF OLD.deleted     IS NOT DISTINCT FROM NEW.deleted
AND OLD.enabled     IS NOT DISTINCT FROM NEW.enabled
AND OLD.is_archived IS NOT DISTINCT FROM NEW.is_archived
AND OLD.paused      IS NOT DISTINCT FROM NEW.paused THEN
RETURN NEW;
END IF;

INSERT INTO tile_dirty_cells (cx, cy)
SELECT DISTINCT
lng_to_tile_x(ST_X(t.location), 15),
lat_to_tile_y(ST_Y(t.location), 15)
FROM tasks t
WHERE t.parent_id = NEW.id
AND t.location IS NOT NULL
AND NOT ST_IsEmpty(t.location)
AND ST_X(t.location) BETWEEN -180 AND 180
AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
ON CONFLICT (cx, cy) DO NOTHING;

RETURN NEW;
END;
$$;


--
-- Name: mark_dirty_on_project_change(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.mark_dirty_on_project_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
IF OLD.deleted IS NOT DISTINCT FROM NEW.deleted
AND OLD.enabled IS NOT DISTINCT FROM NEW.enabled THEN
RETURN NEW;
END IF;

INSERT INTO tile_dirty_cells (cx, cy)
SELECT DISTINCT
lng_to_tile_x(ST_X(t.location), 15),
lat_to_tile_y(ST_Y(t.location), 15)
FROM tasks t
INNER JOIN challenges c ON c.id = t.parent_id
WHERE c.parent_id = NEW.id
AND t.location IS NOT NULL
AND NOT ST_IsEmpty(t.location)
AND ST_X(t.location) BETWEEN -180 AND 180
AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
ON CONFLICT (cx, cy) DO NOTHING;

RETURN NEW;
END;
$$;


--
-- Name: mark_dirty_on_task_change(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.mark_dirty_on_task_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
IF TG_OP = 'UPDATE'
AND OLD.status    IS NOT DISTINCT FROM NEW.status
AND OLD.parent_id IS NOT DISTINCT FROM NEW.parent_id
AND OLD.archived  IS NOT DISTINCT FROM NEW.archived
AND ST_Equals(COALESCE(OLD.location, ST_GeomFromText('POINT EMPTY', 4326)),
COALESCE(NEW.location, ST_GeomFromText('POINT EMPTY', 4326))) THEN
RETURN NEW;
END IF;

IF TG_OP IN ('UPDATE', 'DELETE') THEN
PERFORM mark_dirty_leaf_cell(OLD.location);
END IF;
IF TG_OP IN ('UPDATE', 'INSERT') THEN
PERFORM mark_dirty_leaf_cell(NEW.location);
END IF;

IF TG_OP = 'DELETE' THEN
RETURN OLD;
END IF;
RETURN NEW;
END;
$$;


--
-- Name: metrics_add(jsonb, jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.metrics_add(a jsonb, b jsonb) RETURNS jsonb
    LANGUAGE sql IMMUTABLE
    AS $$
SELECT build_completion_metrics(
metrics_get(a, 'total')         + metrics_get(b, 'total'),
metrics_get(a, 'available')     + metrics_get(b, 'available'),
metrics_get(a, 'fixed')         + metrics_get(b, 'fixed'),
metrics_get(a, 'falsePositive') + metrics_get(b, 'falsePositive'),
metrics_get(a, 'skipped')       + metrics_get(b, 'skipped'),
metrics_get(a, 'deleted')       + metrics_get(b, 'deleted'),
metrics_get(a, 'alreadyFixed')  + metrics_get(b, 'alreadyFixed'),
metrics_get(a, 'tooHard')       + metrics_get(b, 'tooHard'),
metrics_get(a, 'answered')      + metrics_get(b, 'answered'),
metrics_get(a, 'validated')     + metrics_get(b, 'validated'),
metrics_get(a, 'disabled')      + metrics_get(b, 'disabled')
);
$$;


--
-- Name: metrics_apply_delta(jsonb, text, integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.metrics_apply_delta(metrics jsonb, key text, delta integer) RETURNS jsonb
    LANGUAGE sql IMMUTABLE
    AS $$
SELECT jsonb_set(
COALESCE(metrics, empty_completion_metrics()),
ARRAY[key],
to_jsonb(GREATEST(metrics_get(COALESCE(metrics, empty_completion_metrics()), key) + delta, 0))
);
$$;


--
-- Name: metrics_apply_deltas(jsonb, jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.metrics_apply_deltas(metrics jsonb, deltas jsonb) RETURNS jsonb
    LANGUAGE sql IMMUTABLE
    AS $$
SELECT build_completion_metrics(
GREATEST(metrics_get(metrics, 'total')         + metrics_get(deltas, 'total'),         0),
GREATEST(metrics_get(metrics, 'available')     + metrics_get(deltas, 'available'),     0),
GREATEST(metrics_get(metrics, 'fixed')         + metrics_get(deltas, 'fixed'),         0),
GREATEST(metrics_get(metrics, 'falsePositive') + metrics_get(deltas, 'falsePositive'), 0),
GREATEST(metrics_get(metrics, 'skipped')       + metrics_get(deltas, 'skipped'),       0),
GREATEST(metrics_get(metrics, 'deleted')       + metrics_get(deltas, 'deleted'),       0),
GREATEST(metrics_get(metrics, 'alreadyFixed')  + metrics_get(deltas, 'alreadyFixed'),  0),
GREATEST(metrics_get(metrics, 'tooHard')       + metrics_get(deltas, 'tooHard'),       0),
GREATEST(metrics_get(metrics, 'answered')      + metrics_get(deltas, 'answered'),      0),
GREATEST(metrics_get(metrics, 'validated')     + metrics_get(deltas, 'validated'),     0),
GREATEST(metrics_get(metrics, 'disabled')      + metrics_get(deltas, 'disabled'),      0)
);
$$;


--
-- Name: metrics_get(jsonb, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.metrics_get(metrics jsonb, key text) RETURNS integer
    LANGUAGE sql IMMUTABLE
    AS $$
SELECT COALESCE((metrics ->> key)::int, 0);
$$;


--
-- Name: metrics_sub(jsonb, jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.metrics_sub(a jsonb, b jsonb) RETURNS jsonb
    LANGUAGE sql IMMUTABLE
    AS $$
SELECT build_completion_metrics(
GREATEST(metrics_get(a, 'total')         - metrics_get(b, 'total'),         0),
GREATEST(metrics_get(a, 'available')     - metrics_get(b, 'available'),     0),
GREATEST(metrics_get(a, 'fixed')         - metrics_get(b, 'fixed'),         0),
GREATEST(metrics_get(a, 'falsePositive') - metrics_get(b, 'falsePositive'), 0),
GREATEST(metrics_get(a, 'skipped')       - metrics_get(b, 'skipped'),       0),
GREATEST(metrics_get(a, 'deleted')       - metrics_get(b, 'deleted'),       0),
GREATEST(metrics_get(a, 'alreadyFixed')  - metrics_get(b, 'alreadyFixed'),  0),
GREATEST(metrics_get(a, 'tooHard')       - metrics_get(b, 'tooHard'),       0),
GREATEST(metrics_get(a, 'answered')      - metrics_get(b, 'answered'),      0),
GREATEST(metrics_get(a, 'validated')     - metrics_get(b, 'validated'),     0),
GREATEST(metrics_get(a, 'disabled')      - metrics_get(b, 'disabled'),      0)
);
$$;


--
-- Name: on_project_delete_update(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.on_project_delete_update() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
IF new.deleted = true AND old.deleted = false THEN
UPDATE challenges SET deleted = true WHERE parent_id = new.id;
ELSEIF new.deleted = false AND old.deleted = true THEN
UPDATE challenges SET deleted = false WHERE parent_id = new.id;
END IF;
RETURN new;
END
$$;


--
-- Name: on_task_comment_insert(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.on_task_comment_insert() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
IF new.challenge_id IS NULL OR new.challenge_id = -1 THEN
new.challenge_id := (SELECT parent_id FROM tasks WHERE id = new.task_id);
END IF;
IF new.project_id IS NULL OR new.project_id = -1 THEN
new.project_id := (SELECT parent_id FROM challenges WHERE id = new.challenge_id);
END IF;
RETURN new;
END
$$;


--
-- Name: on_tasks_deleted(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.on_tasks_deleted() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
touched_ids BIGINT[];
challenge_ids BIGINT[];
metric_keys TEXT[];
deltas INTEGER[];
locations GEOMETRY[];
BEGIN
SELECT array_agg(DISTINCT parent_id), array_agg(location)
INTO touched_ids, locations
FROM old_tasks;

SELECT array_agg(challenge_id), array_agg(metric_key), array_agg(delta)
INTO challenge_ids, metric_keys, deltas
FROM (
SELECT parent_id AS challenge_id, 'total' AS metric_key, -1 AS delta
FROM old_tasks WHERE task_status_metric_key(status) IS NOT NULL
UNION ALL
SELECT parent_id, task_status_metric_key(status), -1
FROM old_tasks WHERE task_status_metric_key(status) IS NOT NULL
) changes;

PERFORM apply_task_changes_to_challenges(touched_ids, challenge_ids, metric_keys, deltas);
PERFORM mark_dirty_leaf_cells(locations);
RETURN NULL;
END
$$;


--
-- Name: on_tasks_inserted(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.on_tasks_inserted() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
touched_ids BIGINT[];
challenge_ids BIGINT[];
metric_keys TEXT[];
deltas INTEGER[];
locations GEOMETRY[];
BEGIN
SELECT array_agg(DISTINCT parent_id), array_agg(location)
INTO touched_ids, locations
FROM new_tasks;

SELECT array_agg(challenge_id), array_agg(metric_key), array_agg(delta)
INTO challenge_ids, metric_keys, deltas
FROM (
SELECT parent_id AS challenge_id, 'total' AS metric_key, 1 AS delta
FROM new_tasks WHERE task_status_metric_key(status) IS NOT NULL
UNION ALL
SELECT parent_id, task_status_metric_key(status), 1
FROM new_tasks WHERE task_status_metric_key(status) IS NOT NULL
) changes;

PERFORM apply_task_changes_to_challenges(touched_ids, challenge_ids, metric_keys, deltas);
PERFORM mark_dirty_leaf_cells(locations);
RETURN NULL;
END
$$;


--
-- Name: on_tasks_updated(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.on_tasks_updated() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
touched_ids BIGINT[];
challenge_ids BIGINT[];
metric_keys TEXT[];
deltas INTEGER[];
locations GEOMETRY[];
BEGIN
WITH versions AS (
SELECT id, FALSE AS is_new, parent_id, status, archived, location FROM old_tasks
UNION ALL
SELECT id, TRUE, parent_id, status, archived, location FROM new_tasks
), pairs AS (
SELECT id,
MAX(parent_id) FILTER (WHERE NOT is_new) AS old_parent_id,
MAX(parent_id) FILTER (WHERE is_new) AS new_parent_id,
MAX(status) FILTER (WHERE NOT is_new) AS old_status,
MAX(status) FILTER (WHERE is_new) AS new_status,
BOOL_OR(archived) FILTER (WHERE NOT is_new) AS old_archived,
BOOL_OR(archived) FILTER (WHERE is_new) AS new_archived,
(ARRAY_AGG(location) FILTER (WHERE NOT is_new))[1] AS old_location,
(ARRAY_AGG(location) FILTER (WHERE is_new))[1] AS new_location
FROM versions
GROUP BY id
), status_changes AS (
SELECT old_parent_id, new_parent_id,
task_status_metric_key(old_status) AS old_key,
task_status_metric_key(new_status) AS new_key,
old_parent_id IS DISTINCT FROM new_parent_id AS moved
FROM pairs
WHERE old_parent_id IS DISTINCT FROM new_parent_id
OR old_status IS DISTINCT FROM new_status
), metric_changes AS (
SELECT old_parent_id AS challenge_id, 'total' AS metric_key, -1 AS delta
FROM status_changes WHERE moved AND old_key IS NOT NULL
UNION ALL
SELECT old_parent_id, old_key, -1
FROM status_changes WHERE old_key IS NOT NULL
UNION ALL
SELECT new_parent_id, 'total', 1
FROM status_changes WHERE moved AND new_key IS NOT NULL
UNION ALL
SELECT new_parent_id, new_key, 1
FROM status_changes WHERE new_key IS NOT NULL
), dirty_locations AS (
SELECT UNNEST(ARRAY[old_location, new_location]) AS loc
FROM pairs
WHERE old_status IS DISTINCT FROM new_status
OR old_parent_id IS DISTINCT FROM new_parent_id
OR old_archived IS DISTINCT FROM new_archived
OR NOT ST_Equals(COALESCE(old_location, ST_GeomFromText('POINT EMPTY', 4326)),
COALESCE(new_location, ST_GeomFromText('POINT EMPTY', 4326)))
)
SELECT (SELECT array_agg(DISTINCT parent_id) FROM versions),
(SELECT array_agg(challenge_id) FROM metric_changes),
(SELECT array_agg(metric_key) FROM metric_changes),
(SELECT array_agg(delta) FROM metric_changes),
(SELECT array_agg(loc) FROM dirty_locations)
INTO touched_ids, challenge_ids, metric_keys, deltas, locations;

PERFORM apply_task_changes_to_challenges(touched_ids, challenge_ids, metric_keys, deltas);
PERFORM mark_dirty_leaf_cells(locations);
RETURN NULL;
END
$$;


--
-- Name: on_user_delete(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.on_user_delete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
DELETE FROM grants WHERE grantee_type = 5 AND grantee_id = old.id;
DELETE FROM locked WHERE user_id = old.id;
RETURN old;
END
$$;


--
-- Name: rebuild_all_tile_cells(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.rebuild_all_tile_cells() RETURNS integer
    LANGUAGE plpgsql
    AS $$
DECLARE
i_z   INTEGER;
total INTEGER := 0;
n     INTEGER;
BEGIN
TRUNCATE tile_cells, tile_dirty_cells;

-- Leaf level (display z=11) straight from the base tables.
INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng)
SELECT
11,
lng_to_tile_x(ST_X(t.location), 15),
lat_to_tile_y(ST_Y(t.location), 15),
COUNT(*)::INTEGER,
SUM(ST_Y(t.location)),
SUM(ST_X(t.location))
FROM tasks t
INNER JOIN challenges c ON c.id = t.parent_id
INNER JOIN projects   p ON p.id = c.parent_id
WHERE t.location IS NOT NULL
AND NOT ST_IsEmpty(t.location)
AND ST_X(t.location) BETWEEN -180 AND 180
AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
AND t.status IN (0, 3, 6)
AND t.archived = FALSE
AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
AND c.paused = FALSE
AND p.deleted = FALSE AND p.enabled = TRUE
GROUP BY 2, 3;
GET DIAGNOSTICS n = ROW_COUNT;
total := total + n;

-- Roll up display z = 10 .. 0 by summation.
FOR i_z IN REVERSE 10..0 LOOP
INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng)
SELECT
i_z, cx >> 1, cy >> 1,
SUM(task_count)::INTEGER,
SUM(sum_lat),
SUM(sum_lng)
FROM tile_cells
WHERE z = i_z + 1
GROUP BY cx >> 1, cy >> 1;
GET DIAGNOSTICS n = ROW_COUNT;
total := total + n;
END LOOP;

RETURN total;
END;
$$;


--
-- Name: rebuild_dirty_cells(integer, boolean); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.rebuild_dirty_cells(p_limit integer DEFAULT 512, p_newest_first boolean DEFAULT false) RETURNS integer
    LANGUAGE plpgsql
    AS $$
DECLARE
processed INTEGER := 0;
i_z       INTEGER;
rec       RECORD;
BEGIN
PERFORM pg_advisory_xact_lock(6552071);

CREATE TEMP TABLE IF NOT EXISTS _tile_work (
z  SMALLINT NOT NULL,
cx INTEGER  NOT NULL,
cy INTEGER  NOT NULL,
PRIMARY KEY (z, cx, cy)
) ON COMMIT DROP;
TRUNCATE _tile_work;

-- Pop leaf cells (display z=11) off the queue into the work set. The
-- DELETE ... RETURNING must sit in a WITH clause: a data-modifying
-- statement cannot be a plain subquery in FROM.
WITH popped AS (
DELETE FROM tile_dirty_cells
WHERE (cx, cy) IN (
SELECT cx, cy FROM tile_dirty_cells
ORDER BY (CASE WHEN p_newest_first THEN marked_at END) DESC NULLS LAST,
marked_at ASC
LIMIT p_limit
)
RETURNING cx, cy
)
INSERT INTO _tile_work (z, cx, cy)
SELECT 11, cx, cy FROM popped
ON CONFLICT DO NOTHING;

SELECT COUNT(*) INTO processed FROM _tile_work WHERE z = 11;
IF processed = 0 THEN
RETURN 0;
END IF;

-- Recompute the leaf cells.
FOR rec IN SELECT cx, cy FROM _tile_work WHERE z = 11 LOOP
PERFORM rebuild_leaf_cell(rec.cx, rec.cy);
END LOOP;

-- Roll up: each level's dirty set is the distinct parents of the level below.
FOR i_z IN REVERSE 10..0 LOOP
INSERT INTO _tile_work (z, cx, cy)
SELECT i_z, cx >> 1, cy >> 1 FROM _tile_work WHERE z = i_z + 1
ON CONFLICT DO NOTHING;

FOR rec IN SELECT cx, cy FROM _tile_work WHERE z = i_z LOOP
PERFORM rollup_cell(i_z, rec.cx, rec.cy);
END LOOP;
END LOOP;

RETURN processed;
END;
$$;


--
-- Name: rebuild_leaf_cell(integer, integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.rebuild_leaf_cell(p_cx integer, p_cy integer) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
env geometry := tile_envelope_4326(15, p_cx, p_cy);
BEGIN
DELETE FROM tile_cells WHERE z = 11 AND cx = p_cx AND cy = p_cy;

INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng)
SELECT
11, p_cx, p_cy,
COUNT(*)::INTEGER,
SUM(ST_Y(t.location)),
SUM(ST_X(t.location))
FROM tasks t
INNER JOIN challenges c ON c.id = t.parent_id
INNER JOIN projects   p ON p.id = c.parent_id
WHERE t.location && env
AND lng_to_tile_x(ST_X(t.location), 15) = p_cx
AND lat_to_tile_y(ST_Y(t.location), 15) = p_cy
AND NOT ST_IsEmpty(t.location)
AND ST_X(t.location) BETWEEN -180 AND 180
AND ST_Y(t.location) BETWEEN -85.05112878 AND 85.05112878
AND t.status IN (0, 3, 6)
AND t.archived = FALSE
AND c.deleted = FALSE AND c.enabled = TRUE AND c.is_archived = FALSE
AND c.paused = FALSE
AND p.deleted = FALSE AND p.enabled = TRUE
HAVING COUNT(*) > 0;
END;
$$;


--
-- Name: rollup_cell(integer, integer, integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.rollup_cell(p_z integer, p_cx integer, p_cy integer) RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
DELETE FROM tile_cells WHERE z = p_z AND cx = p_cx AND cy = p_cy;

INSERT INTO tile_cells (z, cx, cy, task_count, sum_lat, sum_lng)
SELECT
p_z, p_cx, p_cy,
SUM(task_count)::INTEGER,
SUM(sum_lat),
SUM(sum_lng)
FROM tile_cells
WHERE z = p_z + 1
AND cx BETWEEN p_cx * 2 AND p_cx * 2 + 1
AND cy BETWEEN p_cy * 2 AND p_cy * 2 + 1
HAVING SUM(task_count) > 0;
END;
$$;


--
-- Name: task_geometry_from_geojson(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.task_geometry_from_geojson(geo_json jsonb) RETURNS public.geometry
    LANGUAGE plpgsql IMMUTABLE
    AS $$
BEGIN
RETURN (
SELECT ST_Collect(ST_SetSRID(ST_MakeValid(ST_GeomFromGeoJSON(feature->>'geometry')), 4326))
FROM jsonb_array_elements(geo_json->'features') AS feature
);
EXCEPTION WHEN OTHERS THEN
RETURN NULL;
END
$$;


--
-- Name: task_status_metric_key(integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.task_status_metric_key(status integer) RETURNS text
    LANGUAGE sql IMMUTABLE
    AS $$
SELECT CASE status
WHEN 0 THEN 'available'
WHEN 1 THEN 'fixed'
WHEN 2 THEN 'falsePositive'
WHEN 3 THEN 'skipped'
WHEN 4 THEN 'deleted'
WHEN 5 THEN 'alreadyFixed'
WHEN 6 THEN 'tooHard'
WHEN 7 THEN 'answered'
WHEN 8 THEN 'validated'
WHEN 9 THEN 'disabled'
ELSE NULL
END;
$$;


--
-- Name: tile_envelope_4326(integer, integer, integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.tile_envelope_4326(p_tz integer, p_tx integer, p_ty integer) RETURNS public.geometry
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $$
SELECT ST_MakeEnvelope(
p_tx::double precision       / (1 << p_tz) * 360.0 - 180.0,
DEGREES(ATAN(SINH(PI() * (1.0 - 2.0 * (p_ty + 1)::double precision / (1 << p_tz))))),
(p_tx + 1)::double precision / (1 << p_tz) * 360.0 - 180.0,
DEGREES(ATAN(SINH(PI() * (1.0 - 2.0 *  p_ty::double precision      / (1 << p_tz))))),
4326)
$$;


--
-- Name: update_challenge_completion_metrics(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_challenge_completion_metrics() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
old_key TEXT;
new_key TEXT;
BEGIN
IF TG_OP = 'INSERT' THEN
new_key := task_status_metric_key(NEW.status);
UPDATE challenges SET completion_metrics =
metrics_apply_delta(
metrics_apply_delta(completion_metrics, 'total', 1),
new_key, 1
)
WHERE id = NEW.parent_id AND new_key IS NOT NULL;
RETURN NEW;
ELSIF TG_OP = 'DELETE' THEN
old_key := task_status_metric_key(OLD.status);
UPDATE challenges SET completion_metrics =
metrics_apply_delta(
metrics_apply_delta(completion_metrics, 'total', -1),
old_key, -1
)
WHERE id = OLD.parent_id AND old_key IS NOT NULL;
RETURN OLD;
ELSIF TG_OP = 'UPDATE' THEN
IF OLD.parent_id IS DISTINCT FROM NEW.parent_id THEN
old_key := task_status_metric_key(OLD.status);
new_key := task_status_metric_key(NEW.status);
UPDATE challenges SET completion_metrics =
metrics_apply_delta(
metrics_apply_delta(completion_metrics, 'total', -1),
old_key, -1
)
WHERE id = OLD.parent_id AND old_key IS NOT NULL;
UPDATE challenges SET completion_metrics =
metrics_apply_delta(
metrics_apply_delta(completion_metrics, 'total', 1),
new_key, 1
)
WHERE id = NEW.parent_id AND new_key IS NOT NULL;
ELSIF OLD.status IS DISTINCT FROM NEW.status THEN
old_key := task_status_metric_key(OLD.status);
new_key := task_status_metric_key(NEW.status);
UPDATE challenges SET completion_metrics =
metrics_apply_delta(
metrics_apply_delta(completion_metrics, COALESCE(old_key, 'total'), CASE WHEN old_key IS NULL THEN 0 ELSE -1 END),
COALESCE(new_key, 'total'), CASE WHEN new_key IS NULL THEN 0 ELSE 1 END
)
WHERE id = NEW.parent_id;
END IF;
RETURN NEW;
END IF;
RETURN NULL;
END;
$$;


--
-- Name: update_challenge_geometry(bigint); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_challenge_geometry(challenge_identifier bigint) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
identifier BIGINT;
BEGIN
FOR identifier IN SELECT id FROM tasks WHERE parent_id = challenge_identifier AND geojson IS NULL LOOP
PERFORM update_geometry(identifier);
END LOOP;
END
$$;


--
-- Name: update_modified(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_modified() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
NEW.modified = NOW();
RETURN NEW;
END
$$;


--
-- Name: update_project_completion_metrics(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_project_completion_metrics() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
old_contributes BOOLEAN := (TG_OP IN ('UPDATE', 'DELETE')) AND NOT OLD.deleted;
new_contributes BOOLEAN := (TG_OP IN ('INSERT', 'UPDATE')) AND NOT NEW.deleted;
BEGIN
IF TG_OP = 'DELETE' THEN
new_contributes := false;
END IF;
IF TG_OP = 'INSERT' THEN
old_contributes := false;
END IF;

IF old_contributes THEN
UPDATE projects
SET completion_metrics = metrics_sub(completion_metrics, OLD.completion_metrics)
WHERE id = OLD.parent_id;
END IF;

IF new_contributes THEN
UPDATE projects
SET completion_metrics = metrics_add(completion_metrics, NEW.completion_metrics)
WHERE id = NEW.parent_id;
END IF;

IF TG_OP = 'DELETE' THEN
RETURN OLD;
END IF;
RETURN NEW;
END;
$$;


--
-- Name: update_task(text, bigint, text, integer, bigint, integer, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_task(task_name text, task_parent_id bigint, task_instruction text, task_status integer, task_id bigint DEFAULT '-1'::integer, task_priority integer DEFAULT 0, reset_interval text DEFAULT '7 days'::text) RETURNS integer
    LANGUAGE plpgsql
    AS $$
DECLARE
update_id integer;
update_modified timestamp without time zone;
update_status integer;
new_status integer;
BEGIN
IF (SELECT task_id) = -1 THEN
SELECT id, modified, status INTO update_id, update_modified, update_status FROM tasks WHERE name = task_name AND parent_id = task_parent_id;
ELSE
SELECT id, modified, status INTO update_id, update_modified, update_status FROM tasks WHERE id = task_id;
END IF;
IF task_status = 0 THEN
task_status = update_status;
END IF;
new_status := task_status;
IF update_status = task_status AND (SELECT AGE(NOW(), update_modified)) > reset_interval::INTERVAL THEN
new_status := 0;
END IF;
UPDATE tasks SET name = task_name, instruction = task_instruction, status = new_status, priority = task_priority WHERE id = update_id;
RETURN update_id;
END
$$;


--
-- Name: update_task(text, bigint, text, integer, bigint, integer, bigint, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_task(task_name text, task_parent_id bigint, task_instruction text, task_status integer, task_id bigint DEFAULT '-1'::integer, task_priority integer DEFAULT 0, task_changeset_id bigint DEFAULT '-1'::integer, reset_interval text DEFAULT '7 days'::text) RETURNS integer
    LANGUAGE plpgsql
    AS $$
DECLARE
update_id integer;
update_modified timestamp without time zone;
update_status integer;
new_status integer;
BEGIN
IF (SELECT task_id) = -1 THEN
SELECT id, modified, status INTO update_id, update_modified, update_status FROM tasks WHERE name = task_name AND parent_id = task_parent_id;
ELSE
SELECT id, modified, status INTO update_id, update_modified, update_status FROM tasks WHERE id = task_id;
END IF;
new_status := task_status;
IF update_status = task_status AND (SELECT AGE(NOW(), update_modified)) > reset_interval::INTERVAL THEN
new_status := 0;
END IF;
UPDATE tasks SET name = task_name, instruction = task_instruction, status = new_status, priority = task_priority, changeset_id = task_changeset_id WHERE id = update_id;
RETURN update_id;
END
$$;


--
-- Name: update_task(text, bigint, text, integer, bigint, integer, bigint, text, timestamp without time zone, integer, integer, integer, timestamp without time zone); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_task(task_name text, task_parent_id bigint, task_instruction text, task_status integer, task_id bigint DEFAULT '-1'::integer, task_priority integer DEFAULT 0, task_changeset_id bigint DEFAULT '-1'::integer, reset_interval text DEFAULT '7 days'::text, task_mapped_on timestamp without time zone DEFAULT NULL::timestamp without time zone, task_review_status integer DEFAULT NULL::integer, task_review_requested_by integer DEFAULT NULL::integer, task_reviewed_by integer DEFAULT NULL::integer, task_reviewed_at timestamp without time zone DEFAULT NULL::timestamp without time zone) RETURNS integer
    LANGUAGE plpgsql
    AS $$
DECLARE
update_id integer;
update_modified timestamp without time zone;
update_status integer;
new_status integer;
BEGIN
IF (SELECT task_id) = -1 THEN
SELECT id, modified, status INTO update_id, update_modified, update_status FROM tasks WHERE name = task_name AND parent_id = task_parent_id;
ELSE
SELECT id, modified, status INTO update_id, update_modified, update_status FROM tasks WHERE id = task_id;
END IF;
new_status := task_status;
IF update_status = task_status AND (SELECT AGE(NOW(), update_modified)) > reset_interval::INTERVAL THEN
new_status := 0;
END IF;
UPDATE tasks SET name = task_name, instruction = task_instruction, status = new_status, priority = task_priority,
changeset_id = task_changeset_id, mapped_on = task_mapped_on WHERE id = update_id;
UPDATE task_review SET review_status = task_review_status, review_requested_by = task_review_requested_by,
reviewed_by = task_reviewed_by, reviewed_at = task_reviewed_at WHERE task_review.task_id = update_id;
RETURN update_id;
END
$$;


--
-- Name: update_task(text, bigint, text, integer, jsonb, jsonb, bigint, integer, bigint, text, timestamp without time zone, integer, integer, integer, timestamp without time zone); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_task(task_name text, task_parent_id bigint, task_instruction text, task_status integer, geo_json jsonb, cooperative_work jsonb DEFAULT NULL::jsonb, task_id bigint DEFAULT '-1'::integer, task_priority integer DEFAULT 0, task_changeset_id bigint DEFAULT '-1'::integer, reset_interval text DEFAULT '7 days'::text, task_mapped_on timestamp without time zone DEFAULT NULL::timestamp without time zone, task_review_status integer DEFAULT NULL::integer, task_review_requested_by integer DEFAULT NULL::integer, task_reviewed_by integer DEFAULT NULL::integer, task_reviewed_at timestamp without time zone DEFAULT NULL::timestamp without time zone) RETURNS integer
    LANGUAGE plpgsql
    AS $$
DECLARE
update_id integer;
update_modified timestamp without time zone;
update_status integer;
new_status integer;
geojson_geom geometry;
BEGIN
IF (SELECT task_id) = -1 THEN
SELECT id, modified, status INTO update_id, update_modified, update_status FROM tasks WHERE name = task_name AND parent_id = task_parent_id;
ELSE
SELECT id, modified, status INTO update_id, update_modified, update_status FROM tasks WHERE id = task_id;
END IF;
-- Only if task status is not null set/update it to the new status
IF task_status IS NOT NULL THEN
new_status := task_status;
ELSE
new_status := update_status;
END IF;
-- only reset the status if the task is not currently disabled or set as a false positive and all other criteria is met.
IF update_status = task_status AND NOT (update_status = 9 OR update_status = 2) AND (SELECT AGE(NOW(), update_modified)) > reset_interval::INTERVAL THEN
new_status := 0;
END IF;
SELECT ST_COLLECT(ST_SETSRID(ST_MAKEVALID(ST_GEOMFROMGEOJSON(jsonb_array_elements_Text::JSONB->>'geometry')), 4326)) INTO geojson_geom
FROM JSONB_ARRAY_ELEMENTS_TEXT(geo_json->'features');
UPDATE tasks SET name = task_name, instruction = task_instruction, status = new_status, priority = task_priority,
changeset_id = task_changeset_id, geojson = geo_json,
cooperative_work_json = cooperative_work,
location = ST_Centroid(geojson_geom),
geom = geojson_geom
WHERE id = update_id;

-- Note in status actions if status has changed
-- Since only when someone with admin privileges to a challenge can call this update
-- to change the status we just set osm_user_id to -1 (thereby not impacting any user scores).
-- To determine who did the update check the actions table.
IF update_status != new_status THEN
INSERT INTO status_actions (osm_user_id, project_id, challenge_id, task_id, old_status, status)
VALUES (-1, (SELECT parent_id FROM challenges WHERE id = task_parent_id),
task_parent_id, update_id, update_status, new_status);
END IF;

--
-- Only update task_review table if we actually have a task_review_status otherwise we will
-- end up with weird empty rows. And if the new status is created we need to delete any requested review
--
IF task_review_status IS NOT NULL THEN
UPDATE task_review SET review_status = task_review_status, review_requested_by = task_review_requested_by,
reviewed_by = task_reviewed_by, reviewed_at = task_reviewed_at WHERE task_review.task_id = update_id;
ELSE IF new_status = 0 THEN
DELETE FROM task_review WHERE task_review.task_id = update_id;
END IF;
END IF;

--
-- Only update a null mapped_on if we are resetting task status back to created
--
IF task_mapped_on IS NOT NULL OR new_status = 0 THEN
UPDATE tasks SET mapped_on = task_mapped_on WHERE id = update_id;
END IF;

RETURN update_id;
END
$$;


--
-- Name: update_tasks(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_tasks() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
task RECORD;
BEGIN
IF TG_OP='DELETE' THEN
task = OLD;
ELSE
NEW.modified = NOW();
task = NEW;
END IF;
UPDATE challenges SET modified = NOW() WHERE id = task.parent_id;
RETURN task;
END
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: actions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.actions (
    id integer NOT NULL,
    created timestamp without time zone DEFAULT now(),
    osm_user_id integer,
    type_id integer,
    item_id integer,
    action integer NOT NULL,
    status integer NOT NULL,
    extra character varying
);


--
-- Name: actions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.actions_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: actions_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.actions_id_seq OWNED BY public.actions.id;


--
-- Name: bundles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bundles (
    id integer NOT NULL,
    owner_id integer NOT NULL,
    name character varying,
    description character varying,
    created timestamp without time zone DEFAULT now(),
    modified timestamp without time zone DEFAULT now()
);


--
-- Name: bundles_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.bundles_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: bundles_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.bundles_id_seq OWNED BY public.bundles.id;


--
-- Name: challenge_comments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.challenge_comments (
    id integer NOT NULL,
    osm_id integer NOT NULL,
    created timestamp without time zone DEFAULT now(),
    comment character varying,
    challenge_id integer NOT NULL,
    project_id integer NOT NULL
);


--
-- Name: challenge_comments_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.challenge_comments_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: challenge_comments_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.challenge_comments_id_seq OWNED BY public.challenge_comments.id;


--
-- Name: challenge_likes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.challenge_likes (
    id integer NOT NULL,
    created timestamp without time zone DEFAULT now(),
    user_id integer NOT NULL,
    challenge_id integer NOT NULL
);


--
-- Name: challenge_likes_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.challenge_likes_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: challenge_likes_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.challenge_likes_id_seq OWNED BY public.challenge_likes.id;


--
-- Name: challenge_presets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.challenge_presets (
    id integer NOT NULL,
    challenge_id integer NOT NULL,
    preset character varying NOT NULL
);


--
-- Name: challenge_presets_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.challenge_presets_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: challenge_presets_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.challenge_presets_id_seq OWNED BY public.challenge_presets.id;


--
-- Name: challenge_reports; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.challenge_reports (
    id integer NOT NULL,
    challenge_id integer NOT NULL,
    reporter_id integer,
    reporter_email character varying,
    comment text NOT NULL,
    status integer DEFAULT 0 NOT NULL,
    reviewed_by integer,
    reviewed_at timestamp without time zone,
    review_comment text,
    reported_at timestamp without time zone DEFAULT now()
);


--
-- Name: challenge_reports_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.challenge_reports_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: challenge_reports_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.challenge_reports_id_seq OWNED BY public.challenge_reports.id;


--
-- Name: challenge_snapshots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.challenge_snapshots (
    id integer NOT NULL,
    challenge_id integer NOT NULL,
    challenge_name text NOT NULL,
    challenge_status integer,
    created timestamp without time zone DEFAULT now(),
    completion_snapshot_id integer NOT NULL,
    low_completion_snapshot_id integer NOT NULL,
    medium_completion_snapshot_id integer NOT NULL,
    high_completion_snapshot_id integer NOT NULL,
    review_snapshot_id integer NOT NULL,
    manual boolean DEFAULT true
);


--
-- Name: challenge_snapshots_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.challenge_snapshots_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: challenge_snapshots_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.challenge_snapshots_id_seq OWNED BY public.challenge_snapshots.id;


--
-- Name: challenges; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.challenges (
    id integer NOT NULL,
    created timestamp without time zone DEFAULT now(),
    modified timestamp without time zone DEFAULT now(),
    name character varying NOT NULL,
    parent_id integer NOT NULL,
    description character varying DEFAULT ''::character varying,
    blurb character varying DEFAULT ''::character varying,
    instruction character varying DEFAULT ''::character varying,
    difficulty integer DEFAULT 1,
    enabled boolean DEFAULT false,
    featured boolean DEFAULT false,
    overpass_ql character varying DEFAULT ''::character varying,
    remote_geo_json character varying DEFAULT ''::character varying,
    status integer DEFAULT 0,
    default_priority integer DEFAULT 0,
    high_priority_rule character varying,
    medium_priority_rule character varying,
    low_priority_rule character varying,
    extra_options public.hstore,
    location public.geometry(Point,4326),
    default_zoom integer DEFAULT 13,
    min_zoom integer DEFAULT 1,
    max_zoom integer DEFAULT 19,
    default_basemap integer,
    custom_basemap character varying,
    updatetasks boolean DEFAULT false,
    owner_id integer DEFAULT '-999'::integer NOT NULL,
    last_updated timestamp without time zone DEFAULT now(),
    checkin_comment character varying,
    bounding public.geometry(Polygon,4326),
    info_link character varying,
    deleted boolean DEFAULT false,
    status_message text,
    checkin_source character varying,
    default_basemap_id character varying,
    last_task_refresh timestamp with time zone,
    popularity integer DEFAULT floor(EXTRACT(epoch FROM now())),
    cooperative_type integer DEFAULT 0,
    exportable_properties text,
    data_origin_date timestamp without time zone,
    osm_id_property character varying,
    preferred_tags character varying,
    task_styles character varying,
    requires_local boolean DEFAULT false,
    preferred_review_tags character varying,
    limit_tags boolean DEFAULT false,
    limit_review_tags boolean DEFAULT false,
    overpass_target_type character varying,
    is_archived boolean DEFAULT false,
    task_bundle_id_property character varying,
    system_archived_at timestamp without time zone,
    review_setting integer DEFAULT 0,
    task_widget_layout jsonb DEFAULT '{}'::jsonb NOT NULL,
    dataset_url character varying,
    is_global boolean DEFAULT false NOT NULL,
    require_confirmation boolean DEFAULT false,
    require_reject_reason boolean DEFAULT false,
    high_priority_bounds character varying,
    medium_priority_bounds character varying,
    low_priority_bounds character varying,
    mr_tag_metrics jsonb,
    completion_metrics jsonb DEFAULT '{}'::jsonb NOT NULL,
    paused boolean DEFAULT false NOT NULL,
    owner_team_id integer
);


--
-- Name: COLUMN challenges.task_widget_layout; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.challenges.task_widget_layout IS 'The challenges.task_widget_layout is json that the GUI uses as a "suggested layout" when displaying the Task Completion page.';


--
-- Name: COLUMN challenges.is_global; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.challenges.is_global IS 'The challenges.is_global represents if a challenge is classified as global, currently a challenge is classified as global if it is wider than 180 degrees (half the map width) or taller than 90 degrees (half the map height).';


--
-- Name: challenges_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.challenges_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: challenges_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.challenges_id_seq OWNED BY public.challenges.id;


--
-- Name: completion_snapshots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.completion_snapshots (
    id integer NOT NULL,
    type_id integer NOT NULL,
    item_id integer NOT NULL,
    created timestamp without time zone DEFAULT now(),
    priority integer,
    available integer NOT NULL,
    fixed integer NOT NULL,
    false_positive integer NOT NULL,
    skipped integer NOT NULL,
    deleted integer NOT NULL,
    already_fixed integer NOT NULL,
    too_hard integer NOT NULL,
    answered integer NOT NULL,
    validated integer NOT NULL,
    disabled integer NOT NULL,
    avg_time_spent double precision DEFAULT 0,
    tasks_with_time integer DEFAULT 0
);


--
-- Name: completion_snapshots_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.completion_snapshots_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: completion_snapshots_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.completion_snapshots_id_seq OWNED BY public.completion_snapshots.id;


--
-- Name: credentials; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.credentials (
    id bigint NOT NULL,
    user_id integer NOT NULL,
    kind text NOT NULL,
    token_hash bytea NOT NULL,
    hint text NOT NULL,
    name text,
    scopes text[] DEFAULT '{}'::text[] NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone,
    last_used_at timestamp with time zone DEFAULT now() NOT NULL,
    revoked_at timestamp with time zone,
    CONSTRAINT credentials_expiry CHECK (((kind = 'pat'::text) OR (expires_at IS NOT NULL))),
    CONSTRAINT credentials_kind_check CHECK ((kind = ANY (ARRAY['session'::text, 'pat'::text, 'editor'::text]))),
    CONSTRAINT credentials_pat_name CHECK (((kind <> 'pat'::text) OR (name IS NOT NULL))),
    CONSTRAINT credentials_session_scopes CHECK (((kind <> 'session'::text) OR (scopes = '{}'::text[]))),
    CONSTRAINT credentials_token_hash_length CHECK ((octet_length(token_hash) = 32)),
    CONSTRAINT credentials_token_scopes CHECK (((kind = 'session'::text) OR (cardinality(scopes) > 0)))
);


--
-- Name: credentials_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.credentials_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: credentials_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.credentials_id_seq OWNED BY public.credentials.id;


--
-- Name: grants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.grants (
    id integer NOT NULL,
    name character varying NOT NULL,
    grantee_id integer NOT NULL,
    grantee_type integer NOT NULL,
    role integer NOT NULL,
    object_id integer NOT NULL,
    object_type integer NOT NULL,
    CONSTRAINT grants_role_valid CHECK ((((role = '-1'::integer) AND (grantee_type = 5) AND (object_type = 0) AND (object_id = 0)) OR ((object_type = 6) AND ((role >= 0) AND (role <= 3))) OR ((object_type <> 6) AND ((role >= 1) AND (role <= 3)))))
);


--
-- Name: grants_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.grants_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: grants_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.grants_id_seq OWNED BY public.grants.id;


--
-- Name: group_members; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.group_members (
    id integer NOT NULL,
    group_id integer NOT NULL,
    member_type integer NOT NULL,
    member_id integer NOT NULL,
    status integer DEFAULT 0,
    created timestamp without time zone DEFAULT now(),
    modified timestamp without time zone DEFAULT now()
);


--
-- Name: group_members_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.group_members_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: group_members_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.group_members_id_seq OWNED BY public.group_members.id;


--
-- Name: groups; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.groups (
    id integer NOT NULL,
    name character varying NOT NULL,
    description character varying,
    avatar_url character varying,
    group_type integer DEFAULT 0,
    created timestamp without time zone DEFAULT now(),
    modified timestamp without time zone DEFAULT now()
);


--
-- Name: groups_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.groups_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: groups_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.groups_id_seq OWNED BY public.groups.id;


--
-- Name: locked; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.locked (
    id integer NOT NULL,
    locked_time timestamp without time zone DEFAULT now(),
    item_type integer NOT NULL,
    item_id integer NOT NULL,
    user_id integer NOT NULL,
    created timestamp without time zone DEFAULT now(),
    changeset_id integer DEFAULT '-1'::integer NOT NULL,
    bundled_tasks integer[] DEFAULT '{}'::integer[] NOT NULL,
    is_review_claim boolean DEFAULT false NOT NULL
);


--
-- Name: locked_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.locked_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: locked_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.locked_id_seq OWNED BY public.locked.id;


--
-- Name: play_evolutions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.play_evolutions (
    id integer NOT NULL,
    hash character varying(255) NOT NULL,
    applied_at timestamp without time zone NOT NULL,
    apply_script text,
    revert_script text,
    state character varying(255),
    last_problem text
);


--
-- Name: projects; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.projects (
    id integer NOT NULL,
    created timestamp without time zone DEFAULT now(),
    modified timestamp without time zone DEFAULT now(),
    name character varying NOT NULL,
    description character varying DEFAULT ''::character varying,
    enabled boolean DEFAULT false,
    display_name character varying,
    owner_id integer DEFAULT '-999'::integer NOT NULL,
    deleted boolean DEFAULT false,
    is_virtual boolean DEFAULT false,
    featured boolean DEFAULT false,
    is_archived boolean DEFAULT false,
    require_confirmation boolean DEFAULT false,
    completion_metrics jsonb DEFAULT '{}'::jsonb NOT NULL,
    owner_team_id integer
);


--
-- Name: projects_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.projects_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: projects_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.projects_id_seq OWNED BY public.projects.id;


--
-- Name: review_snapshots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.review_snapshots (
    id integer NOT NULL,
    type_id integer NOT NULL,
    item_id integer NOT NULL,
    created timestamp without time zone DEFAULT now(),
    requested integer NOT NULL,
    approved integer NOT NULL,
    rejected integer NOT NULL,
    assisted integer NOT NULL,
    disputed integer NOT NULL,
    total_review_time double precision DEFAULT 0,
    tasks_with_review_time integer DEFAULT 0
);


--
-- Name: review_snapshots_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.review_snapshots_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: review_snapshots_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.review_snapshots_id_seq OWNED BY public.review_snapshots.id;


--
-- Name: saved_challenges; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.saved_challenges (
    id integer NOT NULL,
    created timestamp without time zone DEFAULT now(),
    user_id integer NOT NULL,
    challenge_id integer NOT NULL
);


--
-- Name: saved_challenges_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.saved_challenges_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: saved_challenges_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.saved_challenges_id_seq OWNED BY public.saved_challenges.id;


--
-- Name: saved_tasks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.saved_tasks (
    id integer NOT NULL,
    created timestamp without time zone DEFAULT now(),
    user_id integer NOT NULL,
    task_id integer NOT NULL,
    challenge_id integer NOT NULL
);


--
-- Name: saved_tasks_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.saved_tasks_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: saved_tasks_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.saved_tasks_id_seq OWNED BY public.saved_tasks.id;


--
-- Name: status_actions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.status_actions (
    id integer NOT NULL,
    created timestamp without time zone DEFAULT now(),
    osm_user_id integer NOT NULL,
    project_id integer NOT NULL,
    challenge_id integer NOT NULL,
    task_id integer NOT NULL,
    old_status integer NOT NULL,
    status integer NOT NULL,
    started_at timestamp without time zone
);


--
-- Name: status_actions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.status_actions_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: status_actions_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.status_actions_id_seq OWNED BY public.status_actions.id;


--
-- Name: tags; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tags (
    id integer NOT NULL,
    created timestamp without time zone DEFAULT now(),
    name character varying NOT NULL,
    description character varying DEFAULT ''::character varying,
    tag_type character varying DEFAULT 'challenges'::character varying,
    active boolean DEFAULT true
);


--
-- Name: tags_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tags_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tags_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tags_id_seq OWNED BY public.tags.id;


--
-- Name: tags_on_challenges; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tags_on_challenges (
    id integer NOT NULL,
    challenge_id integer NOT NULL,
    tag_id integer NOT NULL
);


--
-- Name: tags_on_challenges_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tags_on_challenges_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tags_on_challenges_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tags_on_challenges_id_seq OWNED BY public.tags_on_challenges.id;


--
-- Name: tags_on_tasks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tags_on_tasks (
    id integer NOT NULL,
    task_id integer NOT NULL,
    tag_id integer NOT NULL
);


--
-- Name: tags_on_tasks_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tags_on_tasks_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tags_on_tasks_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tags_on_tasks_id_seq OWNED BY public.tags_on_tasks.id;


--
-- Name: task_bundles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.task_bundles (
    id integer NOT NULL,
    task_id integer NOT NULL,
    bundle_id integer NOT NULL
);


--
-- Name: task_bundles_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.task_bundles_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: task_bundles_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.task_bundles_id_seq OWNED BY public.task_bundles.id;


--
-- Name: task_comments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.task_comments (
    id integer NOT NULL,
    osm_id integer NOT NULL,
    task_id integer NOT NULL,
    created timestamp without time zone DEFAULT now(),
    comment character varying,
    action_id integer,
    challenge_id integer DEFAULT '-1'::integer NOT NULL,
    project_id integer DEFAULT '-1'::integer NOT NULL,
    edited boolean DEFAULT false
);


--
-- Name: task_comments_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.task_comments_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: task_comments_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.task_comments_id_seq OWNED BY public.task_comments.id;


--
-- Name: task_review; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.task_review (
    id integer NOT NULL,
    task_id integer NOT NULL,
    review_status integer,
    review_requested_by integer,
    reviewed_by integer,
    reviewed_at timestamp without time zone,
    review_claimed_at timestamp without time zone,
    review_claimed_by integer,
    review_started_at timestamp without time zone,
    additional_reviewers integer[],
    meta_review_started_at timestamp without time zone,
    meta_review_status integer,
    meta_reviewed_by integer,
    meta_reviewed_at timestamp without time zone,
    error_tags character varying
);


--
-- Name: task_review_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.task_review_history (
    id integer NOT NULL,
    task_id integer NOT NULL,
    requested_by integer NOT NULL,
    reviewed_by integer,
    review_status integer,
    reviewed_at timestamp without time zone,
    review_started_at timestamp without time zone,
    original_reviewer integer,
    meta_review_status integer,
    meta_reviewed_by integer,
    meta_reviewed_at timestamp without time zone,
    error_tags character varying
);


--
-- Name: task_review_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.task_review_history_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: task_review_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.task_review_history_id_seq OWNED BY public.task_review_history.id;


--
-- Name: task_review_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.task_review_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: task_review_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.task_review_id_seq OWNED BY public.task_review.id;


--
-- Name: task_suggested_fix; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.task_suggested_fix (
    id integer NOT NULL,
    task_id integer NOT NULL,
    properties public.hstore,
    geom public.geometry(Geometry,4326)
);


--
-- Name: task_suggested_fix_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.task_suggested_fix_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: task_suggested_fix_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.task_suggested_fix_id_seq OWNED BY public.task_suggested_fix.id;


--
-- Name: tasks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tasks (
    id integer NOT NULL,
    created timestamp without time zone DEFAULT now(),
    modified timestamp without time zone DEFAULT now(),
    name character varying NOT NULL,
    instruction character varying NOT NULL,
    parent_id integer NOT NULL,
    status integer DEFAULT 0 NOT NULL,
    location public.geometry(Point,4326) NOT NULL,
    priority integer DEFAULT 0,
    changeset_id integer DEFAULT '-1'::integer,
    mapped_on timestamp without time zone,
    geojson jsonb NOT NULL,
    geom public.geometry(Geometry,4326) NOT NULL,
    cooperative_work_json jsonb,
    completion_responses jsonb,
    bundle_id integer,
    is_bundle_primary boolean,
    completed_time_spent integer,
    completed_by integer,
    skip_count integer DEFAULT 0 NOT NULL,
    archived boolean DEFAULT false NOT NULL
);


--
-- Name: tasks_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tasks_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tasks_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tasks_id_seq OWNED BY public.tasks.id;


--
-- Name: team_avatars; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.team_avatars (
    team_id integer NOT NULL,
    content_type character varying NOT NULL,
    data bytea NOT NULL,
    uploaded_by integer,
    created timestamp without time zone DEFAULT now(),
    modified timestamp without time zone DEFAULT now()
);


--
-- Name: team_images; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.team_images (
    id integer NOT NULL,
    team_id integer NOT NULL,
    name character varying NOT NULL,
    content_type character varying NOT NULL,
    data bytea NOT NULL,
    status integer DEFAULT 0 NOT NULL,
    requested_by integer,
    reviewed_by integer,
    reviewed_at timestamp without time zone,
    review_comment text,
    created timestamp without time zone DEFAULT now(),
    modified timestamp without time zone DEFAULT now()
);


--
-- Name: team_images_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.team_images_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: team_images_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.team_images_id_seq OWNED BY public.team_images.id;


--
-- Name: tile_cells; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tile_cells (
    z smallint NOT NULL,
    cx integer NOT NULL,
    cy integer NOT NULL,
    task_count integer NOT NULL,
    sum_lat double precision NOT NULL,
    sum_lng double precision NOT NULL,
    last_updated timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: tile_dirty_cells; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tile_dirty_cells (
    cx integer NOT NULL,
    cy integer NOT NULL,
    marked_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: user_basemaps; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_basemaps (
    id integer NOT NULL,
    created timestamp without time zone DEFAULT now(),
    modified timestamp without time zone DEFAULT now(),
    user_id integer NOT NULL,
    url character varying NOT NULL,
    name character varying NOT NULL,
    "overlay" boolean DEFAULT false
);


--
-- Name: user_basemaps_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.user_basemaps_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_basemaps_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.user_basemaps_id_seq OWNED BY public.user_basemaps.id;


--
-- Name: user_leaderboard; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_leaderboard (
    month_duration integer NOT NULL,
    user_id integer NOT NULL,
    user_name character varying,
    user_avatar_url character varying,
    user_ranking integer NOT NULL,
    user_score integer NOT NULL,
    country_code character varying,
    created timestamp without time zone DEFAULT now(),
    completed_tasks integer,
    avg_time_spent bigint
);


--
-- Name: user_metrics; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_metrics (
    user_id integer NOT NULL,
    score integer NOT NULL,
    total_fixed integer,
    total_false_positive integer,
    total_already_fixed integer,
    total_too_hard integer,
    total_skipped integer,
    initial_rejected integer DEFAULT 0,
    initial_approved integer DEFAULT 0,
    initial_assisted integer DEFAULT 0,
    total_rejected integer DEFAULT 0,
    total_approved integer DEFAULT 0,
    total_assisted integer DEFAULT 0,
    total_disputed_as_mapper integer DEFAULT 0,
    total_disputed_as_reviewer integer DEFAULT 0,
    total_time_spent double precision DEFAULT 0,
    tasks_with_time integer DEFAULT 0,
    total_review_time double precision DEFAULT 0,
    tasks_with_review_time integer DEFAULT 0,
    achievements integer[] DEFAULT '{}'::integer[] NOT NULL
);


--
-- Name: user_metrics_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_metrics_history (
    user_id integer NOT NULL,
    score integer NOT NULL,
    total_fixed integer,
    total_false_positive integer,
    total_already_fixed integer,
    total_too_hard integer,
    total_skipped integer,
    snapshot_date timestamp without time zone DEFAULT now(),
    initial_rejected integer DEFAULT 0,
    initial_approved integer DEFAULT 0,
    initial_assisted integer DEFAULT 0,
    total_rejected integer DEFAULT 0,
    total_approved integer DEFAULT 0,
    total_assisted integer DEFAULT 0,
    total_disputed_as_mapper integer DEFAULT 0,
    total_disputed_as_reviewer integer DEFAULT 0,
    total_time_spent double precision DEFAULT 0,
    tasks_with_time integer DEFAULT 0,
    total_review_time double precision DEFAULT 0,
    tasks_with_review_time integer DEFAULT 0
);


--
-- Name: user_notification_subscriptions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_notification_subscriptions (
    id integer NOT NULL,
    user_id integer NOT NULL,
    system integer DEFAULT 1 NOT NULL,
    mention integer DEFAULT 1 NOT NULL,
    review_approved integer DEFAULT 1 NOT NULL,
    review_rejected integer DEFAULT 1 NOT NULL,
    review_again integer DEFAULT 1 NOT NULL,
    challenge_completed integer DEFAULT 1 NOT NULL,
    team integer DEFAULT 1 NOT NULL,
    follow integer DEFAULT 1 NOT NULL,
    meta_review integer DEFAULT 1 NOT NULL,
    review_count integer DEFAULT 0,
    revision_count integer DEFAULT 0
);


--
-- Name: user_notification_subscriptions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.user_notification_subscriptions_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_notification_subscriptions_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.user_notification_subscriptions_id_seq OWNED BY public.user_notification_subscriptions.id;


--
-- Name: user_notifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_notifications (
    id integer NOT NULL,
    user_id integer NOT NULL,
    notification_type integer NOT NULL,
    created timestamp without time zone DEFAULT now(),
    modified timestamp without time zone DEFAULT now(),
    description character varying,
    from_username character varying,
    is_read boolean DEFAULT false,
    email_status integer NOT NULL,
    task_id integer,
    challenge_id integer,
    project_id integer,
    target_id integer,
    extra character varying,
    error_tags character varying
);


--
-- Name: user_notifications_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.user_notifications_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_notifications_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.user_notifications_id_seq OWNED BY public.user_notifications.id;


--
-- Name: user_top_challenges; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_top_challenges (
    month_duration integer NOT NULL,
    user_id integer NOT NULL,
    challenge_id integer NOT NULL,
    challenge_name character varying,
    activity integer NOT NULL,
    country_code character varying
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id integer NOT NULL,
    osm_id integer NOT NULL,
    created timestamp without time zone DEFAULT now(),
    modified timestamp without time zone DEFAULT now(),
    osm_created timestamp without time zone NOT NULL,
    name character varying NOT NULL,
    description character varying,
    avatar_url character varying DEFAULT '/assets/images/user_no_image.png'::character varying NOT NULL,
    api_key character varying,
    oauth_token character varying NOT NULL,
    oauth_secret character varying NOT NULL,
    home_location public.geometry(Point,4326),
    default_editor integer DEFAULT '-1'::integer,
    default_basemap integer DEFAULT '-1'::integer,
    email_opt_in boolean DEFAULT false,
    locale character varying,
    theme integer DEFAULT '-1'::integer,
    properties character varying,
    leaderboard_opt_out boolean DEFAULT false,
    default_basemap_id character varying,
    needs_review integer,
    is_reviewer boolean DEFAULT false,
    email character varying,
    following_group integer,
    followers_group integer,
    allow_following boolean DEFAULT true,
    see_tag_fix_suggestions boolean DEFAULT true,
    disable_task_confirm boolean DEFAULT false,
    show_priority_marker_colors boolean DEFAULT false,
    plugins text
);


--
-- Name: users_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.users_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: users_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.users_id_seq OWNED BY public.users.id;


--
-- Name: virtual_challenge_tasks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.virtual_challenge_tasks (
    id integer NOT NULL,
    task_id integer NOT NULL,
    virtual_challenge_id integer NOT NULL
);


--
-- Name: virtual_challenge_tasks_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.virtual_challenge_tasks_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: virtual_challenge_tasks_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.virtual_challenge_tasks_id_seq OWNED BY public.virtual_challenge_tasks.id;


--
-- Name: virtual_challenges; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.virtual_challenges (
    id integer NOT NULL,
    owner_id integer NOT NULL,
    name character varying,
    created timestamp without time zone DEFAULT now(),
    modified timestamp without time zone DEFAULT now(),
    description character varying,
    search_parameters character varying NOT NULL,
    expiry timestamp with time zone DEFAULT (now() + '1 day'::interval)
);


--
-- Name: virtual_challenges_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.virtual_challenges_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: virtual_challenges_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.virtual_challenges_id_seq OWNED BY public.virtual_challenges.id;


--
-- Name: virtual_project_challenges; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.virtual_project_challenges (
    id integer NOT NULL,
    project_id integer NOT NULL,
    challenge_id integer NOT NULL,
    created timestamp without time zone DEFAULT now()
);


--
-- Name: virtual_project_challenges_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.virtual_project_challenges_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: virtual_project_challenges_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.virtual_project_challenges_id_seq OWNED BY public.virtual_project_challenges.id;


--
-- Name: actions id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.actions ALTER COLUMN id SET DEFAULT nextval('public.actions_id_seq'::regclass);


--
-- Name: bundles id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bundles ALTER COLUMN id SET DEFAULT nextval('public.bundles_id_seq'::regclass);


--
-- Name: challenge_comments id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_comments ALTER COLUMN id SET DEFAULT nextval('public.challenge_comments_id_seq'::regclass);


--
-- Name: challenge_likes id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_likes ALTER COLUMN id SET DEFAULT nextval('public.challenge_likes_id_seq'::regclass);


--
-- Name: challenge_presets id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_presets ALTER COLUMN id SET DEFAULT nextval('public.challenge_presets_id_seq'::regclass);


--
-- Name: challenge_reports id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_reports ALTER COLUMN id SET DEFAULT nextval('public.challenge_reports_id_seq'::regclass);


--
-- Name: challenge_snapshots id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_snapshots ALTER COLUMN id SET DEFAULT nextval('public.challenge_snapshots_id_seq'::regclass);


--
-- Name: challenges id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenges ALTER COLUMN id SET DEFAULT nextval('public.challenges_id_seq'::regclass);


--
-- Name: completion_snapshots id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.completion_snapshots ALTER COLUMN id SET DEFAULT nextval('public.completion_snapshots_id_seq'::regclass);


--
-- Name: credentials id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credentials ALTER COLUMN id SET DEFAULT nextval('public.credentials_id_seq'::regclass);


--
-- Name: grants id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.grants ALTER COLUMN id SET DEFAULT nextval('public.grants_id_seq'::regclass);


--
-- Name: group_members id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_members ALTER COLUMN id SET DEFAULT nextval('public.group_members_id_seq'::regclass);


--
-- Name: groups id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.groups ALTER COLUMN id SET DEFAULT nextval('public.groups_id_seq'::regclass);


--
-- Name: locked id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.locked ALTER COLUMN id SET DEFAULT nextval('public.locked_id_seq'::regclass);


--
-- Name: projects id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects ALTER COLUMN id SET DEFAULT nextval('public.projects_id_seq'::regclass);


--
-- Name: review_snapshots id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.review_snapshots ALTER COLUMN id SET DEFAULT nextval('public.review_snapshots_id_seq'::regclass);


--
-- Name: saved_challenges id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saved_challenges ALTER COLUMN id SET DEFAULT nextval('public.saved_challenges_id_seq'::regclass);


--
-- Name: saved_tasks id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saved_tasks ALTER COLUMN id SET DEFAULT nextval('public.saved_tasks_id_seq'::regclass);


--
-- Name: status_actions id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.status_actions ALTER COLUMN id SET DEFAULT nextval('public.status_actions_id_seq'::regclass);


--
-- Name: tags id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tags ALTER COLUMN id SET DEFAULT nextval('public.tags_id_seq'::regclass);


--
-- Name: tags_on_challenges id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tags_on_challenges ALTER COLUMN id SET DEFAULT nextval('public.tags_on_challenges_id_seq'::regclass);


--
-- Name: tags_on_tasks id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tags_on_tasks ALTER COLUMN id SET DEFAULT nextval('public.tags_on_tasks_id_seq'::regclass);


--
-- Name: task_bundles id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_bundles ALTER COLUMN id SET DEFAULT nextval('public.task_bundles_id_seq'::regclass);


--
-- Name: task_comments id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_comments ALTER COLUMN id SET DEFAULT nextval('public.task_comments_id_seq'::regclass);


--
-- Name: task_review id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_review ALTER COLUMN id SET DEFAULT nextval('public.task_review_id_seq'::regclass);


--
-- Name: task_review_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_review_history ALTER COLUMN id SET DEFAULT nextval('public.task_review_history_id_seq'::regclass);


--
-- Name: task_suggested_fix id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_suggested_fix ALTER COLUMN id SET DEFAULT nextval('public.task_suggested_fix_id_seq'::regclass);


--
-- Name: tasks id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tasks ALTER COLUMN id SET DEFAULT nextval('public.tasks_id_seq'::regclass);


--
-- Name: team_images id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team_images ALTER COLUMN id SET DEFAULT nextval('public.team_images_id_seq'::regclass);


--
-- Name: user_basemaps id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_basemaps ALTER COLUMN id SET DEFAULT nextval('public.user_basemaps_id_seq'::regclass);


--
-- Name: user_notification_subscriptions id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_notification_subscriptions ALTER COLUMN id SET DEFAULT nextval('public.user_notification_subscriptions_id_seq'::regclass);


--
-- Name: user_notifications id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_notifications ALTER COLUMN id SET DEFAULT nextval('public.user_notifications_id_seq'::regclass);


--
-- Name: users id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users ALTER COLUMN id SET DEFAULT nextval('public.users_id_seq'::regclass);


--
-- Name: virtual_challenge_tasks id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.virtual_challenge_tasks ALTER COLUMN id SET DEFAULT nextval('public.virtual_challenge_tasks_id_seq'::regclass);


--
-- Name: virtual_challenges id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.virtual_challenges ALTER COLUMN id SET DEFAULT nextval('public.virtual_challenges_id_seq'::regclass);


--
-- Name: virtual_project_challenges id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.virtual_project_challenges ALTER COLUMN id SET DEFAULT nextval('public.virtual_project_challenges_id_seq'::regclass);


--
-- Name: actions actions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.actions
    ADD CONSTRAINT actions_pkey PRIMARY KEY (id);


--
-- Name: bundles bundles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bundles
    ADD CONSTRAINT bundles_pkey PRIMARY KEY (id);


--
-- Name: challenge_comments challenge_comments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_comments
    ADD CONSTRAINT challenge_comments_pkey PRIMARY KEY (id);


--
-- Name: challenge_likes challenge_likes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_likes
    ADD CONSTRAINT challenge_likes_pkey PRIMARY KEY (id);


--
-- Name: challenge_presets challenge_presets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_presets
    ADD CONSTRAINT challenge_presets_pkey PRIMARY KEY (id);


--
-- Name: challenge_reports challenge_reports_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_reports
    ADD CONSTRAINT challenge_reports_pkey PRIMARY KEY (id);


--
-- Name: challenge_snapshots challenge_snapshots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_snapshots
    ADD CONSTRAINT challenge_snapshots_pkey PRIMARY KEY (id);


--
-- Name: challenges challenges_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenges
    ADD CONSTRAINT challenges_pkey PRIMARY KEY (id);


--
-- Name: completion_snapshots completion_snapshots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.completion_snapshots
    ADD CONSTRAINT completion_snapshots_pkey PRIMARY KEY (id);


--
-- Name: credentials credentials_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credentials
    ADD CONSTRAINT credentials_pkey PRIMARY KEY (id);


--
-- Name: credentials credentials_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credentials
    ADD CONSTRAINT credentials_token_hash_key UNIQUE (token_hash);


--
-- Name: grants grants_grantee_id_grantee_type_role_object_id_object_type_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.grants
    ADD CONSTRAINT grants_grantee_id_grantee_type_role_object_id_object_type_key UNIQUE (grantee_id, grantee_type, role, object_id, object_type);


--
-- Name: grants grants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.grants
    ADD CONSTRAINT grants_pkey PRIMARY KEY (id);


--
-- Name: group_members group_members_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_members
    ADD CONSTRAINT group_members_pkey PRIMARY KEY (id);


--
-- Name: groups groups_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.groups
    ADD CONSTRAINT groups_pkey PRIMARY KEY (id);


--
-- Name: locked locked_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.locked
    ADD CONSTRAINT locked_pkey PRIMARY KEY (id);


--
-- Name: play_evolutions play_evolutions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.play_evolutions
    ADD CONSTRAINT play_evolutions_pkey PRIMARY KEY (id);


--
-- Name: projects projects_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT projects_pkey PRIMARY KEY (id);


--
-- Name: review_snapshots review_snapshots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.review_snapshots
    ADD CONSTRAINT review_snapshots_pkey PRIMARY KEY (id);


--
-- Name: saved_challenges saved_challenges_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saved_challenges
    ADD CONSTRAINT saved_challenges_pkey PRIMARY KEY (id);


--
-- Name: saved_tasks saved_tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saved_tasks
    ADD CONSTRAINT saved_tasks_pkey PRIMARY KEY (id);


--
-- Name: status_actions status_actions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.status_actions
    ADD CONSTRAINT status_actions_pkey PRIMARY KEY (id);


--
-- Name: tags_on_challenges tags_on_challenges_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tags_on_challenges
    ADD CONSTRAINT tags_on_challenges_pkey PRIMARY KEY (id);


--
-- Name: tags_on_tasks tags_on_tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tags_on_tasks
    ADD CONSTRAINT tags_on_tasks_pkey PRIMARY KEY (id);


--
-- Name: tags tags_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tags
    ADD CONSTRAINT tags_pkey PRIMARY KEY (id);


--
-- Name: task_bundles task_bundles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_bundles
    ADD CONSTRAINT task_bundles_pkey PRIMARY KEY (id);


--
-- Name: task_comments task_comments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_comments
    ADD CONSTRAINT task_comments_pkey PRIMARY KEY (id);


--
-- Name: task_review_history task_review_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_review_history
    ADD CONSTRAINT task_review_history_pkey PRIMARY KEY (id);


--
-- Name: task_review task_review_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_review
    ADD CONSTRAINT task_review_pkey PRIMARY KEY (id);


--
-- Name: task_review task_review_task_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_review
    ADD CONSTRAINT task_review_task_id UNIQUE (task_id);


--
-- Name: task_suggested_fix task_suggested_fix_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_suggested_fix
    ADD CONSTRAINT task_suggested_fix_pkey PRIMARY KEY (id);


--
-- Name: tasks tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tasks
    ADD CONSTRAINT tasks_pkey PRIMARY KEY (id);


--
-- Name: team_avatars team_avatars_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team_avatars
    ADD CONSTRAINT team_avatars_pkey PRIMARY KEY (team_id);


--
-- Name: team_images team_images_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team_images
    ADD CONSTRAINT team_images_pkey PRIMARY KEY (id);


--
-- Name: tile_cells tile_cells_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tile_cells
    ADD CONSTRAINT tile_cells_pkey PRIMARY KEY (z, cx, cy);


--
-- Name: tile_dirty_cells tile_dirty_cells_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tile_dirty_cells
    ADD CONSTRAINT tile_dirty_cells_pkey PRIMARY KEY (cx, cy);


--
-- Name: virtual_project_challenges unique_virtual_project_challenges_projects; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.virtual_project_challenges
    ADD CONSTRAINT unique_virtual_project_challenges_projects UNIQUE (project_id, challenge_id);


--
-- Name: user_basemaps user_basemaps_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_basemaps
    ADD CONSTRAINT user_basemaps_pkey PRIMARY KEY (id);


--
-- Name: user_metrics user_metric_primary_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_metrics
    ADD CONSTRAINT user_metric_primary_key PRIMARY KEY (user_id);


--
-- Name: user_notification_subscriptions user_notification_subscriptions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_notification_subscriptions
    ADD CONSTRAINT user_notification_subscriptions_pkey PRIMARY KEY (id);


--
-- Name: user_notifications user_notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_notifications
    ADD CONSTRAINT user_notifications_pkey PRIMARY KEY (id);


--
-- Name: users users_api_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_api_key_key UNIQUE (api_key);


--
-- Name: users users_osm_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_osm_id_key UNIQUE (osm_id);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: virtual_challenge_tasks virtual_challenge_tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.virtual_challenge_tasks
    ADD CONSTRAINT virtual_challenge_tasks_pkey PRIMARY KEY (id);


--
-- Name: virtual_challenges virtual_challenges_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.virtual_challenges
    ADD CONSTRAINT virtual_challenges_pkey PRIMARY KEY (id);


--
-- Name: virtual_project_challenges virtual_project_challenges_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.virtual_project_challenges
    ADD CONSTRAINT virtual_project_challenges_pkey PRIMARY KEY (id);


--
-- Name: idx_actions_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_actions_created ON public.actions USING btree (created);


--
-- Name: idx_actions_item_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_actions_item_id ON public.actions USING btree (item_id);


--
-- Name: idx_actions_osm_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_actions_osm_user_id ON public.actions USING btree (osm_user_id);


--
-- Name: idx_challenge_comments_comment_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_challenge_comments_comment_trgm ON public.challenge_comments USING gin (comment public.gin_trgm_ops);


--
-- Name: idx_challenge_likes_challenge_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_challenge_likes_challenge_id ON public.challenge_likes USING btree (challenge_id);


--
-- Name: idx_challenge_likes_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_challenge_likes_user_id ON public.challenge_likes USING btree (user_id);


--
-- Name: idx_challenge_likes_user_id_challenge_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_challenge_likes_user_id_challenge_id ON public.challenge_likes USING btree (user_id, challenge_id);


--
-- Name: idx_challenge_presets_challenge_presets_challenge; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_challenge_presets_challenge_presets_challenge ON public.challenge_presets USING btree (challenge_id);


--
-- Name: idx_challenge_reports_challenge_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_challenge_reports_challenge_id ON public.challenge_reports USING btree (challenge_id);


--
-- Name: idx_challenge_reports_status_reported_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_challenge_reports_status_reported_at ON public.challenge_reports USING btree (status, reported_at DESC);


--
-- Name: idx_challenge_snapshots_cs_challenge_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_challenge_snapshots_cs_challenge_id ON public.challenge_snapshots USING btree (challenge_id);


--
-- Name: idx_challenge_snapshots_cs_challenge_id_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_challenge_snapshots_cs_challenge_id_created ON public.challenge_snapshots USING btree (challenge_id, created);


--
-- Name: idx_challenges_bounding; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_challenges_bounding ON public.challenges USING gist (bounding);


--
-- Name: idx_challenges_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_challenges_deleted ON public.challenges USING btree ((1)) WHERE deleted;


--
-- Name: idx_challenges_is_archived; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_challenges_is_archived ON public.challenges USING btree (is_archived);


--
-- Name: idx_challenges_location; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_challenges_location ON public.challenges USING gist (location);


--
-- Name: idx_challenges_owner_id_fkey; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_challenges_owner_id_fkey ON public.challenges USING btree (owner_id);


--
-- Name: idx_challenges_owner_team_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_challenges_owner_team_id ON public.challenges USING btree (owner_team_id) WHERE (owner_team_id IS NOT NULL);


--
-- Name: idx_challenges_parent_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_challenges_parent_id ON public.challenges USING btree (parent_id);


--
-- Name: idx_challenges_parent_id_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_challenges_parent_id_name ON public.challenges USING btree (parent_id, lower((name)::text)) WHERE ((deleted = false) OR (deleted IS NULL));


--
-- Name: idx_challenges_popularity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_challenges_popularity ON public.challenges USING btree (popularity);


--
-- Name: idx_completion_snapshots_cs_item_id_type_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_completion_snapshots_cs_item_id_type_id ON public.completion_snapshots USING btree (item_id, type_id);


--
-- Name: idx_completion_snapshots_cs_item_id_type_id_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_completion_snapshots_cs_item_id_type_id_created ON public.completion_snapshots USING btree (item_id, type_id, created);


--
-- Name: idx_completion_snapshots_cs_priority; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_completion_snapshots_cs_priority ON public.completion_snapshots USING btree (priority);


--
-- Name: idx_credentials_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_credentials_user_id ON public.credentials USING btree (user_id);


--
-- Name: idx_grants_grantee; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_grants_grantee ON public.grants USING btree (grantee_id, grantee_type);


--
-- Name: idx_grants_object; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_grants_object ON public.grants USING btree (object_id, object_type);


--
-- Name: idx_group_members_group_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_group_members_group_id ON public.group_members USING btree (group_id);


--
-- Name: idx_group_members_group_member_type_member_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_group_members_group_member_type_member_id ON public.group_members USING btree (group_id, member_type, member_id);


--
-- Name: idx_group_members_member_type_member_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_group_members_member_type_member_id ON public.group_members USING btree (member_type, member_id);


--
-- Name: idx_groups_group_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_groups_group_type ON public.groups USING btree (group_type);


--
-- Name: idx_groups_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_groups_name ON public.groups USING btree (lower((name)::text));


--
-- Name: idx_locked_item_type_item_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_locked_item_type_item_id ON public.locked USING btree (item_type, item_id);


--
-- Name: idx_locked_single_edit_lock; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_locked_single_edit_lock ON public.locked USING btree (user_id) WHERE (NOT is_review_claim);


--
-- Name: idx_projects_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_projects_name ON public.projects USING btree (lower((name)::text));


--
-- Name: idx_projects_owner_team_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_projects_owner_team_id ON public.projects USING btree (owner_team_id) WHERE (owner_team_id IS NOT NULL);


--
-- Name: idx_saved_challenges_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_saved_challenges_user_id ON public.saved_challenges USING btree (user_id);


--
-- Name: idx_saved_challenges_user_id_challenge_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_saved_challenges_user_id_challenge_id ON public.saved_challenges USING btree (user_id, challenge_id);


--
-- Name: idx_saved_tasks_task_id_fkey; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_saved_tasks_task_id_fkey ON public.saved_tasks USING btree (task_id);


--
-- Name: idx_saved_tasks_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_saved_tasks_user_id ON public.saved_tasks USING btree (user_id);


--
-- Name: idx_saved_tasks_user_id_challenge_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_saved_tasks_user_id_challenge_id ON public.saved_tasks USING btree (user_id, challenge_id);


--
-- Name: idx_saved_tasks_user_id_task_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_saved_tasks_user_id_task_id ON public.saved_tasks USING btree (user_id, task_id);


--
-- Name: idx_status_actions_challenge_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_status_actions_challenge_id ON public.status_actions USING btree (challenge_id);


--
-- Name: idx_status_actions_challenge_id_fkey; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_status_actions_challenge_id_fkey ON public.status_actions USING btree (challenge_id);


--
-- Name: idx_status_actions_challenge_id_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_status_actions_challenge_id_status ON public.status_actions USING btree (challenge_id, status);


--
-- Name: idx_status_actions_osm_user_id_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_status_actions_osm_user_id_created ON public.status_actions USING btree (osm_user_id, created);


--
-- Name: idx_status_actions_project_id_fkey; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_status_actions_project_id_fkey ON public.status_actions USING btree (project_id);


--
-- Name: idx_status_actions_task_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_status_actions_task_id ON public.status_actions USING btree (task_id);


--
-- Name: idx_tags_name_tag_type; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_tags_name_tag_type ON public.tags USING btree (lower((name)::text), tag_type);


--
-- Name: idx_tags_on_challenges_challenge_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tags_on_challenges_challenge_id ON public.tags_on_challenges USING btree (challenge_id);


--
-- Name: idx_tags_on_challenges_challenge_id_tag_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_tags_on_challenges_challenge_id_tag_id ON public.tags_on_challenges USING btree (challenge_id, tag_id);


--
-- Name: idx_tags_on_challenges_tag_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tags_on_challenges_tag_id ON public.tags_on_challenges USING btree (tag_id);


--
-- Name: idx_tags_on_tasks_tag_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tags_on_tasks_tag_id ON public.tags_on_tasks USING btree (tag_id);


--
-- Name: idx_tags_on_tasks_task_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tags_on_tasks_task_id ON public.tags_on_tasks USING btree (task_id);


--
-- Name: idx_tags_on_tasks_task_id_tag_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_tags_on_tasks_task_id_tag_id ON public.tags_on_tasks USING btree (task_id, tag_id);


--
-- Name: idx_tags_tags_tag_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tags_tags_tag_type ON public.tags USING btree (tag_type);


--
-- Name: idx_tags_tags_tag_type_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tags_tags_tag_type_name ON public.tags USING btree (tag_type, name);


--
-- Name: idx_task_bundles_bundle_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_bundles_bundle_id ON public.task_bundles USING btree (bundle_id);


--
-- Name: idx_task_bundles_task_id_fkey; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_bundles_task_id_fkey ON public.task_bundles USING btree (task_id);


--
-- Name: idx_task_comments_challenge_id_fkey; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_comments_challenge_id_fkey ON public.task_comments USING btree (challenge_id);


--
-- Name: idx_task_comments_comment_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_comments_comment_trgm ON public.task_comments USING gin (comment public.gin_trgm_ops);


--
-- Name: idx_task_comments_task_id_fkey; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_comments_task_id_fkey ON public.task_comments USING btree (task_id);


--
-- Name: idx_task_review_history_task_review_history_task_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_review_history_task_review_history_task_id ON public.task_review_history USING btree (task_id);


--
-- Name: idx_task_review_task_id_fkey; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_review_task_id_fkey ON public.task_review USING btree (task_id);


--
-- Name: idx_task_review_tasks_review_claimed_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_review_tasks_review_claimed_at ON public.task_review USING btree (review_claimed_at);


--
-- Name: idx_task_review_tasks_review_claimed_by; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_review_tasks_review_claimed_by ON public.task_review USING btree (review_claimed_by);


--
-- Name: idx_task_review_tasks_review_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_review_tasks_review_id ON public.task_review USING btree (task_id);


--
-- Name: idx_task_review_tasks_review_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_review_tasks_review_status ON public.task_review USING btree (review_status);


--
-- Name: idx_task_review_tasks_reviewed_by; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_review_tasks_reviewed_by ON public.task_review USING btree (reviewed_by);


--
-- Name: idx_task_suggested_fix_task_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_suggested_fix_task_id ON public.task_suggested_fix USING btree (task_id);


--
-- Name: idx_tasks_archived; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tasks_archived ON public.tasks USING btree (archived);


--
-- Name: idx_tasks_bundle_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tasks_bundle_id ON public.tasks USING btree (bundle_id);


--
-- Name: idx_tasks_challenge_parent_id_fkey; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tasks_challenge_parent_id_fkey ON public.tasks USING btree (parent_id);


--
-- Name: idx_tasks_location; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tasks_location ON public.tasks USING gist (location);


--
-- Name: idx_tasks_name_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tasks_name_trgm ON public.tasks USING gin (name public.gin_trgm_ops);


--
-- Name: idx_tasks_parent_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tasks_parent_id ON public.tasks USING btree (parent_id);


--
-- Name: idx_tasks_parent_id_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_tasks_parent_id_name ON public.tasks USING btree (parent_id, lower((name)::text));


--
-- Name: idx_tasks_parent_id_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tasks_parent_id_status ON public.tasks USING btree (parent_id, status);


--
-- Name: idx_tasks_parent_location; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tasks_parent_location ON public.tasks USING gist (parent_id, location);


--
-- Name: idx_tasks_skip_count; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tasks_skip_count ON public.tasks USING btree (skip_count);


--
-- Name: idx_tasks_status_modified; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tasks_status_modified ON public.tasks USING btree (status, modified);


--
-- Name: idx_tasks_tasks_mapped_on_status_asc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tasks_tasks_mapped_on_status_asc ON public.tasks USING btree (status, mapped_on);


--
-- Name: idx_tasks_tasks_mapped_on_status_desc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tasks_tasks_mapped_on_status_desc ON public.tasks USING btree (status, mapped_on DESC);


--
-- Name: idx_team_images_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_team_images_status ON public.team_images USING btree (created) WHERE (status = 0);


--
-- Name: idx_team_images_team_approved; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_team_images_team_approved ON public.team_images USING btree (team_id) WHERE (status = 1);


--
-- Name: idx_team_images_team_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_team_images_team_id ON public.team_images USING btree (team_id);


--
-- Name: idx_team_images_team_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_team_images_team_pending ON public.team_images USING btree (team_id) WHERE (status = 0);


--
-- Name: idx_tile_dirty_cells_marked_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tile_dirty_cells_marked_at ON public.tile_dirty_cells USING btree (marked_at);


--
-- Name: idx_user_basemaps_user_basemaps_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_basemaps_user_basemaps_user ON public.user_basemaps USING btree (user_id);


--
-- Name: idx_user_metrics_history_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_metrics_history_user_id ON public.user_metrics_history USING btree (user_id);


--
-- Name: idx_user_metrics_history_user_id_snapshot_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_metrics_history_user_id_snapshot_date ON public.user_metrics_history USING btree (user_id, snapshot_date);


--
-- Name: idx_user_metrics_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_metrics_user_id ON public.user_metrics USING btree (user_id);


--
-- Name: idx_user_notification_subscriptions_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_user_notification_subscriptions_user_id ON public.user_notification_subscriptions USING btree (user_id);


--
-- Name: idx_user_notifications_email_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_notifications_email_status ON public.user_notifications USING btree (email_status);


--
-- Name: idx_user_notifications_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_notifications_user_id ON public.user_notifications USING btree (user_id);


--
-- Name: idx_virtual_challenge_tasks_task_id_fkey; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_virtual_challenge_tasks_task_id_fkey ON public.virtual_challenge_tasks USING btree (task_id);


--
-- Name: idx_virtual_challenge_tasks_virtual_challenge_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_virtual_challenge_tasks_virtual_challenge_id ON public.virtual_challenge_tasks USING btree (virtual_challenge_id);


--
-- Name: idx_virtual_challenges_owner_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_virtual_challenges_owner_id ON public.virtual_challenges USING btree (owner_id);


--
-- Name: idx_virtual_project_challenges_vp_challenge_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_virtual_project_challenges_vp_challenge_id ON public.virtual_project_challenges USING btree (challenge_id);


--
-- Name: idx_virtual_project_challenges_vp_project_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_virtual_project_challenges_vp_project_id ON public.virtual_project_challenges USING btree (project_id);


--
-- Name: challenges mark_dirty_on_challenge_change_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER mark_dirty_on_challenge_change_trigger AFTER UPDATE OF deleted, enabled, is_archived, paused ON public.challenges FOR EACH ROW EXECUTE FUNCTION public.mark_dirty_on_challenge_change();


--
-- Name: projects mark_dirty_on_project_change_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER mark_dirty_on_project_change_trigger AFTER UPDATE OF deleted, enabled ON public.projects FOR EACH ROW EXECUTE FUNCTION public.mark_dirty_on_project_change();


--
-- Name: projects on_project_update_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER on_project_update_delete AFTER UPDATE ON public.projects FOR EACH ROW EXECUTE FUNCTION public.on_project_delete_update();


--
-- Name: task_comments on_task_comment_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER on_task_comment_insert BEFORE INSERT ON public.task_comments FOR EACH ROW EXECUTE FUNCTION public.on_task_comment_insert();


--
-- Name: tasks on_tasks_deleted_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER on_tasks_deleted_trigger AFTER DELETE ON public.tasks REFERENCING OLD TABLE AS old_tasks FOR EACH STATEMENT EXECUTE FUNCTION public.on_tasks_deleted();


--
-- Name: tasks on_tasks_inserted_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER on_tasks_inserted_trigger AFTER INSERT ON public.tasks REFERENCING NEW TABLE AS new_tasks FOR EACH STATEMENT EXECUTE FUNCTION public.on_tasks_inserted();


--
-- Name: tasks on_tasks_updated_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER on_tasks_updated_trigger AFTER UPDATE ON public.tasks REFERENCING OLD TABLE AS old_tasks NEW TABLE AS new_tasks FOR EACH STATEMENT EXECUTE FUNCTION public.on_tasks_updated();


--
-- Name: users on_user_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER on_user_delete BEFORE DELETE ON public.users FOR EACH ROW EXECUTE FUNCTION public.on_user_delete();


--
-- Name: challenges update_challenges_modified; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_challenges_modified BEFORE UPDATE ON public.challenges FOR EACH ROW EXECUTE FUNCTION public.update_modified();


--
-- Name: challenges update_project_completion_metrics_delete_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_project_completion_metrics_delete_trigger AFTER DELETE ON public.challenges FOR EACH ROW EXECUTE FUNCTION public.update_project_completion_metrics();


--
-- Name: challenges update_project_completion_metrics_insert_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_project_completion_metrics_insert_trigger AFTER INSERT ON public.challenges FOR EACH ROW EXECUTE FUNCTION public.update_project_completion_metrics();


--
-- Name: challenges update_project_completion_metrics_update_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_project_completion_metrics_update_trigger AFTER UPDATE OF completion_metrics, deleted, parent_id ON public.challenges FOR EACH ROW WHEN (((old.completion_metrics IS DISTINCT FROM new.completion_metrics) OR (old.deleted IS DISTINCT FROM new.deleted) OR (old.parent_id IS DISTINCT FROM new.parent_id))) EXECUTE FUNCTION public.update_project_completion_metrics();


--
-- Name: projects update_projects_modified; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_projects_modified BEFORE UPDATE ON public.projects FOR EACH ROW EXECUTE FUNCTION public.update_modified();


--
-- Name: tasks update_tasks_modified_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_tasks_modified_trigger BEFORE INSERT OR UPDATE ON public.tasks FOR EACH ROW EXECUTE FUNCTION public.update_modified();


--
-- Name: users update_users_modified; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER update_users_modified BEFORE UPDATE ON public.users FOR EACH ROW EXECUTE FUNCTION public.update_modified();


--
-- Name: bundles bundles_owner_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bundles
    ADD CONSTRAINT bundles_owner_id FOREIGN KEY (owner_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: challenge_comments challenge_comments_challenge_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_comments
    ADD CONSTRAINT challenge_comments_challenge_id_fkey FOREIGN KEY (challenge_id) REFERENCES public.challenges(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: challenge_comments challenge_comments_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_comments
    ADD CONSTRAINT challenge_comments_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: challenge_snapshots challenge_completion_snapshot_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_snapshots
    ADD CONSTRAINT challenge_completion_snapshot_id FOREIGN KEY (completion_snapshot_id) REFERENCES public.completion_snapshots(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: challenge_snapshots challenge_high_completion_snapshot_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_snapshots
    ADD CONSTRAINT challenge_high_completion_snapshot_id FOREIGN KEY (high_completion_snapshot_id) REFERENCES public.completion_snapshots(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: challenge_likes challenge_likes_challenge_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_likes
    ADD CONSTRAINT challenge_likes_challenge_id FOREIGN KEY (challenge_id) REFERENCES public.challenges(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: challenge_likes challenge_likes_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_likes
    ADD CONSTRAINT challenge_likes_user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: challenge_snapshots challenge_low_completion_snapshot_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_snapshots
    ADD CONSTRAINT challenge_low_completion_snapshot_id FOREIGN KEY (low_completion_snapshot_id) REFERENCES public.completion_snapshots(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: challenge_snapshots challenge_medium_completion_snapshot_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_snapshots
    ADD CONSTRAINT challenge_medium_completion_snapshot_id FOREIGN KEY (medium_completion_snapshot_id) REFERENCES public.completion_snapshots(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: challenge_presets challenge_presets_challenge_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_presets
    ADD CONSTRAINT challenge_presets_challenge_id FOREIGN KEY (challenge_id) REFERENCES public.challenges(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: challenge_reports challenge_reports_challenge_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_reports
    ADD CONSTRAINT challenge_reports_challenge_id_fkey FOREIGN KEY (challenge_id) REFERENCES public.challenges(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: challenge_reports challenge_reports_reporter_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_reports
    ADD CONSTRAINT challenge_reports_reporter_id_fkey FOREIGN KEY (reporter_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: challenge_reports challenge_reports_reviewed_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_reports
    ADD CONSTRAINT challenge_reports_reviewed_by_fkey FOREIGN KEY (reviewed_by) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: challenge_snapshots challenge_review_snapshot_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenge_snapshots
    ADD CONSTRAINT challenge_review_snapshot_id FOREIGN KEY (review_snapshot_id) REFERENCES public.review_snapshots(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: challenges challenges_owner_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenges
    ADD CONSTRAINT challenges_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.users(osm_id) ON DELETE SET DEFAULT;


--
-- Name: challenges challenges_owner_team_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenges
    ADD CONSTRAINT challenges_owner_team_id_fkey FOREIGN KEY (owner_team_id) REFERENCES public.groups(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: challenges challenges_parent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.challenges
    ADD CONSTRAINT challenges_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES public.projects(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: tags_on_challenges challenges_tags_on_challenges_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tags_on_challenges
    ADD CONSTRAINT challenges_tags_on_challenges_id_fkey FOREIGN KEY (challenge_id) REFERENCES public.challenges(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: credentials credentials_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credentials
    ADD CONSTRAINT credentials_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: group_members group_members_group_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_members
    ADD CONSTRAINT group_members_group_id FOREIGN KEY (group_id) REFERENCES public.groups(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: locked locked_users_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.locked
    ADD CONSTRAINT locked_users_user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: projects projects_owner_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT projects_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.users(osm_id) ON DELETE SET DEFAULT;


--
-- Name: projects projects_owner_team_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT projects_owner_team_id_fkey FOREIGN KEY (owner_team_id) REFERENCES public.groups(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: saved_challenges saved_challenges_challenge_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saved_challenges
    ADD CONSTRAINT saved_challenges_challenge_id FOREIGN KEY (challenge_id) REFERENCES public.challenges(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: saved_challenges saved_challenges_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saved_challenges
    ADD CONSTRAINT saved_challenges_user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: saved_tasks saved_tasks_challenge_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saved_tasks
    ADD CONSTRAINT saved_tasks_challenge_id FOREIGN KEY (challenge_id) REFERENCES public.challenges(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: saved_tasks saved_tasks_task_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saved_tasks
    ADD CONSTRAINT saved_tasks_task_id FOREIGN KEY (task_id) REFERENCES public.tasks(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: saved_tasks saved_tasks_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saved_tasks
    ADD CONSTRAINT saved_tasks_user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: status_actions status_actions_challenge_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.status_actions
    ADD CONSTRAINT status_actions_challenge_id_fkey FOREIGN KEY (challenge_id) REFERENCES public.challenges(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: status_actions status_actions_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.status_actions
    ADD CONSTRAINT status_actions_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: status_actions status_actions_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.status_actions
    ADD CONSTRAINT status_actions_task_id_fkey FOREIGN KEY (task_id) REFERENCES public.tasks(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: tags_on_challenges tags_tags_on_challenges_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tags_on_challenges
    ADD CONSTRAINT tags_tags_on_challenges_id_fkey FOREIGN KEY (tag_id) REFERENCES public.tags(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: tags_on_tasks tags_tags_on_tasks_tag_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tags_on_tasks
    ADD CONSTRAINT tags_tags_on_tasks_tag_id_fkey FOREIGN KEY (tag_id) REFERENCES public.tags(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: task_bundles task_bundles_bundle_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_bundles
    ADD CONSTRAINT task_bundles_bundle_id FOREIGN KEY (bundle_id) REFERENCES public.bundles(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: task_bundles task_bundles_task_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_bundles
    ADD CONSTRAINT task_bundles_task_id FOREIGN KEY (task_id) REFERENCES public.tasks(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: task_comments task_comments_actions_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_comments
    ADD CONSTRAINT task_comments_actions_id_fkey FOREIGN KEY (action_id) REFERENCES public.actions(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: task_comments task_comments_challenge_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_comments
    ADD CONSTRAINT task_comments_challenge_id_fkey FOREIGN KEY (challenge_id) REFERENCES public.challenges(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: task_comments task_comments_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_comments
    ADD CONSTRAINT task_comments_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: task_comments task_comments_tasks_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_comments
    ADD CONSTRAINT task_comments_tasks_id_fkey FOREIGN KEY (task_id) REFERENCES public.tasks(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: task_suggested_fix task_suggested_fix_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_suggested_fix
    ADD CONSTRAINT task_suggested_fix_task_id_fkey FOREIGN KEY (task_id) REFERENCES public.tasks(id) ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED;


--
-- Name: task_review task_task_review_cascade; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.task_review
    ADD CONSTRAINT task_task_review_cascade FOREIGN KEY (task_id) REFERENCES public.tasks(id) ON DELETE CASCADE;


--
-- Name: tasks tasks_parent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tasks
    ADD CONSTRAINT tasks_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES public.challenges(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: tags_on_tasks tasks_tags_on_tasks_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tags_on_tasks
    ADD CONSTRAINT tasks_tags_on_tasks_task_id_fkey FOREIGN KEY (task_id) REFERENCES public.tasks(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: team_avatars team_avatars_team_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team_avatars
    ADD CONSTRAINT team_avatars_team_id_fkey FOREIGN KEY (team_id) REFERENCES public.groups(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: team_avatars team_avatars_uploaded_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team_avatars
    ADD CONSTRAINT team_avatars_uploaded_by_fkey FOREIGN KEY (uploaded_by) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: team_images team_images_requested_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team_images
    ADD CONSTRAINT team_images_requested_by_fkey FOREIGN KEY (requested_by) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: team_images team_images_reviewed_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team_images
    ADD CONSTRAINT team_images_reviewed_by_fkey FOREIGN KEY (reviewed_by) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: team_images team_images_team_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team_images
    ADD CONSTRAINT team_images_team_id_fkey FOREIGN KEY (team_id) REFERENCES public.groups(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: user_basemaps user_basemaps_user_id; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_basemaps
    ADD CONSTRAINT user_basemaps_user_id FOREIGN KEY (user_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: virtual_project_challenges virtual_challenge_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.virtual_project_challenges
    ADD CONSTRAINT virtual_challenge_id_fkey FOREIGN KEY (challenge_id) REFERENCES public.challenges(id) ON DELETE CASCADE;


--
-- Name: virtual_challenge_tasks virtual_challenges_tasks_task_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.virtual_challenge_tasks
    ADD CONSTRAINT virtual_challenges_tasks_task_id_fkey FOREIGN KEY (task_id) REFERENCES public.tasks(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: virtual_challenge_tasks virtual_challenges_tasks_virtual_challenge_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.virtual_challenge_tasks
    ADD CONSTRAINT virtual_challenges_tasks_virtual_challenge_id_fkey FOREIGN KEY (virtual_challenge_id) REFERENCES public.virtual_challenges(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: virtual_project_challenges virtual_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.virtual_project_challenges
    ADD CONSTRAINT virtual_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--



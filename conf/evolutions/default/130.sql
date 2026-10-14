# --- MapRoulette Scheme

# --- !Ups

-- Append-only lock/unlock session events. A session is a lock row plus the
-- later unlock that shares lock_id (locked.id at the time of the event).
-- Duration is derived from the two created timestamps.
-- session_type is Map or Custom. sub_type is an optional label stored on
-- the lock event. Clients do not send a session type.
CREATE TABLE IF NOT EXISTS session_history
(
  id serial NOT NULL PRIMARY KEY,
  created timestamp without time zone DEFAULT NOW() NOT NULL,
  user_id integer NOT NULL,
  task_id integer NOT NULL,
  lock_id integer NOT NULL,
  event varchar NOT NULL,
  session_type varchar NOT NULL,
  sub_type varchar,
  CONSTRAINT session_history_user_id_fkey FOREIGN KEY (user_id)
    REFERENCES users(id) MATCH SIMPLE
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT session_history_task_id_fkey FOREIGN KEY (task_id)
    REFERENCES tasks(id) MATCH SIMPLE
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT session_history_event_check CHECK (event IN ('lock', 'unlock')),
  CONSTRAINT session_history_session_type_check CHECK (session_type IN ('Map', 'Custom'))
);;

SELECT create_index_if_not_exists('session_history', 'task_id_created', '(task_id, created)');;
SELECT create_index_if_not_exists('session_history', 'lock_id_event', '(lock_id, event)', true);;


-- The lock event is inserted in Scala when the row is created. The unlock
-- is written here instead, because a lock can be deleted without ever
-- calling unlockItem. Unlock-all, the stale-lock job, and the user-delete
-- trigger all delete from locked directly, and so can a plain DELETE.
-- After any of those, this copies session_type and sub_type from the lock
-- event for that locked.id and inserts the matching unlock. If the lock
-- event is missing, the unlock is recorded as Map.
CREATE OR REPLACE FUNCTION session_history_on_locked_delete() RETURNS TRIGGER AS $$
DECLARE
  v_session_type varchar;;
  v_sub_type varchar;;
BEGIN
  -- Task locks only (Actions.ITEM_TYPE_TASK = 2)
  IF OLD.item_type <> 2 THEN
    RETURN OLD;;
  END IF;;

  SELECT sh.session_type, sh.sub_type
    INTO v_session_type, v_sub_type
  FROM session_history sh
  WHERE sh.lock_id = OLD.id AND sh.event = 'lock'
  ORDER BY sh.id DESC
  LIMIT 1;;

  IF v_session_type IS NULL THEN
    v_session_type := 'Map';;
    v_sub_type := NULL;;
  END IF;;

  INSERT INTO session_history (user_id, task_id, lock_id, event, session_type, sub_type)
  VALUES (OLD.user_id, OLD.item_id, OLD.id, 'unlock', v_session_type, v_sub_type);;

  RETURN OLD;;
END;;
$$ LANGUAGE plpgsql VOLATILE;;

DROP TRIGGER IF EXISTS session_history_on_locked_delete ON locked;;
CREATE TRIGGER session_history_on_locked_delete
  AFTER DELETE ON locked
  FOR EACH ROW EXECUTE PROCEDURE session_history_on_locked_delete();;

# --- !Downs

DROP TRIGGER IF EXISTS session_history_on_locked_delete ON locked;;
DROP FUNCTION IF EXISTS session_history_on_locked_delete();;
DROP TABLE IF EXISTS session_history CASCADE;;

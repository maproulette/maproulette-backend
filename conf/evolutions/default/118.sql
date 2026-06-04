# --- MapRoulette Scheme

# --- !Ups

-- The completion metrics triggers added in evolution 111 fire every time a
-- task or challenge row is inserted/updated/deleted, regardless of which
-- columns changed. This creates unnecessary lock contention on these tables
-- and increases latency of write operations.
--
-- So instead, we'll replace each with three separate triggers. INSERTs and
-- DELETEs will always trigger metrics recalculation, but UPDATEs will only
-- do so if a column that could affect the result was changed.


DROP TRIGGER IF EXISTS update_challenge_completion_metrics_trigger ON tasks;;

CREATE TRIGGER update_challenge_completion_metrics_insert_trigger
  AFTER INSERT ON tasks
  FOR EACH ROW EXECUTE PROCEDURE update_challenge_completion_metrics();;

CREATE TRIGGER update_challenge_completion_metrics_delete_trigger
  AFTER DELETE ON tasks
  FOR EACH ROW EXECUTE PROCEDURE update_challenge_completion_metrics();;

CREATE TRIGGER update_challenge_completion_metrics_update_trigger
  AFTER UPDATE OF status, parent_id ON tasks
  FOR EACH ROW
  WHEN (OLD.parent_id IS DISTINCT FROM NEW.parent_id
        OR OLD.status IS DISTINCT FROM NEW.status)
  EXECUTE PROCEDURE update_challenge_completion_metrics();;


DROP TRIGGER IF EXISTS update_project_completion_metrics_trigger ON challenges;;

CREATE TRIGGER update_project_completion_metrics_insert_trigger
  AFTER INSERT ON challenges
  FOR EACH ROW EXECUTE PROCEDURE update_project_completion_metrics();;

CREATE TRIGGER update_project_completion_metrics_delete_trigger
  AFTER DELETE ON challenges
  FOR EACH ROW EXECUTE PROCEDURE update_project_completion_metrics();;

CREATE TRIGGER update_project_completion_metrics_update_trigger
  AFTER UPDATE OF completion_metrics, deleted, parent_id ON challenges
  FOR EACH ROW
  WHEN (OLD.completion_metrics IS DISTINCT FROM NEW.completion_metrics
        OR OLD.deleted IS DISTINCT FROM NEW.deleted
        OR OLD.parent_id IS DISTINCT FROM NEW.parent_id)
  EXECUTE PROCEDURE update_project_completion_metrics();;

# --- !Downs

DROP TRIGGER IF EXISTS update_challenge_completion_metrics_insert_trigger ON tasks;;
DROP TRIGGER IF EXISTS update_challenge_completion_metrics_delete_trigger ON tasks;;
DROP TRIGGER IF EXISTS update_challenge_completion_metrics_update_trigger ON tasks;;
CREATE TRIGGER update_challenge_completion_metrics_trigger
  AFTER INSERT OR UPDATE OR DELETE ON tasks
  FOR EACH ROW EXECUTE PROCEDURE update_challenge_completion_metrics();;

DROP TRIGGER IF EXISTS update_project_completion_metrics_insert_trigger ON challenges;;
DROP TRIGGER IF EXISTS update_project_completion_metrics_delete_trigger ON challenges;;
DROP TRIGGER IF EXISTS update_project_completion_metrics_update_trigger ON challenges;;
CREATE TRIGGER update_project_completion_metrics_trigger
  AFTER INSERT OR UPDATE OR DELETE ON challenges
  FOR EACH ROW EXECUTE PROCEDURE update_project_completion_metrics();;

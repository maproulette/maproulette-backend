# --- MapRoulette Scheme

# --- !Ups
-- Reconcile challenge status with the work actually left in each challenge.
--
-- A challenge is FINISHED (5) once it has tasks and none of them are CREATED
-- (0) or SKIPPED (3). ChallengeDAL.updateFinishedStatus keeps this in sync as
-- tasks are worked, but challenges whose tasks were completed through a path
-- that never ran it (bulk status changes, task deletions, restores) can be
-- left sitting at READY while showing 100% complete. Explore now hides
-- finished challenges, so a stale status keeps a done challenge in the list.

-- Mark as FINISHED. Restricted to statuses that mean "loaded and workable"
-- (NA, READY, PARTIALLY_LOADED) so a challenge mid-build, failed, or deleting
-- its tasks is not misreported as finished.
UPDATE challenges c SET status = 5
WHERE c.deleted = false AND
      (c.status IS NULL OR c.status IN (0, 3, 4)) AND
      0 < (SELECT COUNT(*) FROM tasks WHERE tasks.parent_id = c.id) AND
      0 = (SELECT COUNT(*) FROM tasks
           WHERE tasks.parent_id = c.id AND tasks.status IN (0, 3));;

-- Back to READY for anything marked finished that still has work left, the
-- same correction updateReadyStatus makes when a task returns to created.
UPDATE challenges c SET status = 3
WHERE c.status = 5 AND
      0 < (SELECT COUNT(*) FROM tasks
           WHERE tasks.parent_id = c.id AND tasks.status IN (0, 3));;

# --- !Downs

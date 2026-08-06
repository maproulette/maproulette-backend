# --- MapRoulette Scheme

# --- !Ups

-- bundled_tasks holds the ids of the other tasks covered by this lock when
-- the locked item is the primary task of a bundle. is_review_claim marks
-- locks created by TaskReviewRepository's review-claim flow, which are
-- exempt from the one-lock-per-user constraint below.
ALTER TABLE locked ADD COLUMN IF NOT EXISTS bundled_tasks integer[] NOT NULL DEFAULT '{}';;
ALTER TABLE locked ADD COLUMN IF NOT EXISTS is_review_claim boolean NOT NULL DEFAULT false;;

-- Keep only the most recently created edit-lock row per user before adding
-- the uniqueness constraint below, since a user could previously hold
-- multiple simultaneous locks.
DELETE FROM locked a USING locked b
  WHERE a.user_id = b.user_id AND NOT a.is_review_claim AND NOT b.is_review_claim
    AND a.id < b.id;;

-- Enforce a single active edit lock per user. Review-claim locks are
-- excluded so claiming a review doesn't conflict with an editing lock.
CREATE UNIQUE INDEX IF NOT EXISTS idx_locked_single_edit_lock ON locked(user_id)
  WHERE NOT is_review_claim;;

# --- !Downs

DROP INDEX IF EXISTS idx_locked_single_edit_lock;;
ALTER TABLE locked DROP COLUMN IF EXISTS is_review_claim;;
ALTER TABLE locked DROP COLUMN IF EXISTS bundled_tasks;;

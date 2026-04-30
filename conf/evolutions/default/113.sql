# --- MapRoulette Scheme

# --- !Ups
-- Migrate review timestamp columns from `timestamp without time zone` to
-- `timestamp with time zone`. The naive type stores wall-clock relative to
-- whatever the Postgres session TimeZone happens to be at insert, which lets
-- DST transitions silently shift values by an hour. Existing values are
-- interpreted as UTC (matches the codebase's UTC default — see
-- Utils.UTC_TIMEZONE and the LocalDateTime.now(ZoneOffset.UTC) usage in
-- RuntimeInfo / Scheduler).
--
-- NOTE: this rewrites every row of task_review_history and task_review and
-- will hold an ACCESS EXCLUSIVE lock for the duration. Run during a quiet
-- window. Verify the existing data was written under TimeZone='UTC' before
-- applying — if writes were happening under a non-UTC session, the
-- `AT TIME ZONE 'UTC'` clauses below need to be changed to that zone instead.

ALTER TABLE task_review
  ALTER COLUMN reviewed_at TYPE timestamp with time zone
    USING reviewed_at AT TIME ZONE 'UTC',
  ALTER COLUMN review_claimed_at TYPE timestamp with time zone
    USING review_claimed_at AT TIME ZONE 'UTC',
  ALTER COLUMN review_started_at TYPE timestamp with time zone
    USING review_started_at AT TIME ZONE 'UTC',
  ALTER COLUMN meta_reviewed_at TYPE timestamp with time zone
    USING meta_reviewed_at AT TIME ZONE 'UTC',
  ALTER COLUMN meta_review_started_at TYPE timestamp with time zone
    USING meta_review_started_at AT TIME ZONE 'UTC';;

ALTER TABLE task_review_history
  ALTER COLUMN reviewed_at TYPE timestamp with time zone
    USING reviewed_at AT TIME ZONE 'UTC',
  ALTER COLUMN review_started_at TYPE timestamp with time zone
    USING review_started_at AT TIME ZONE 'UTC',
  ALTER COLUMN meta_reviewed_at TYPE timestamp with time zone
    USING meta_reviewed_at AT TIME ZONE 'UTC';;


# --- !Downs
ALTER TABLE task_review
  ALTER COLUMN reviewed_at TYPE timestamp without time zone
    USING reviewed_at AT TIME ZONE 'UTC',
  ALTER COLUMN review_claimed_at TYPE timestamp without time zone
    USING review_claimed_at AT TIME ZONE 'UTC',
  ALTER COLUMN review_started_at TYPE timestamp without time zone
    USING review_started_at AT TIME ZONE 'UTC',
  ALTER COLUMN meta_reviewed_at TYPE timestamp without time zone
    USING meta_reviewed_at AT TIME ZONE 'UTC',
  ALTER COLUMN meta_review_started_at TYPE timestamp without time zone
    USING meta_review_started_at AT TIME ZONE 'UTC';;

ALTER TABLE task_review_history
  ALTER COLUMN reviewed_at TYPE timestamp without time zone
    USING reviewed_at AT TIME ZONE 'UTC',
  ALTER COLUMN review_started_at TYPE timestamp without time zone
    USING review_started_at AT TIME ZONE 'UTC',
  ALTER COLUMN meta_reviewed_at TYPE timestamp without time zone
    USING meta_reviewed_at AT TIME ZONE 'UTC';;

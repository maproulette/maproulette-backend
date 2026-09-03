# --- MapRoulette Scheme

# --- !Ups

-- Challenge reports are complaints about a challenge's design -- "this
-- challenge is poorly designed and is causing incorrect edits" -- as opposed
-- to bugs or feature requests. They used to be filed as issues in a public
-- GitHub repo, which meant shipping a write-scoped GitHub token to the browser
-- and publishing the reporter's identity. They live here instead so triage
-- happens inside MapRoulette and the reporter's contact details stay private.
CREATE TABLE IF NOT EXISTS challenge_reports
(
  id SERIAL NOT NULL PRIMARY KEY,
  challenge_id integer NOT NULL,
  -- Nullable on purpose: deleting a user must not delete the reports that an
  -- admin may still need to act on, so the row outlives its reporter.
  reporter_id integer,
  -- Contact address the reporter optionally volunteers for follow-up. Private
  -- to superusers -- never exposed on the challenge itself.
  reporter_email character varying,
  comment text NOT NULL,
  -- 0 = open, 1 = actioned, 2 = dismissed. Triage state for the admin
  -- dashboard; a report is never deleted, only resolved one way or the other.
  status integer NOT NULL DEFAULT 0,
  reviewed_by integer,
  reviewed_at timestamp without time zone,
  review_comment text,
  reported_at timestamp without time zone DEFAULT NOW(),
  CONSTRAINT challenge_reports_challenge_id_fkey FOREIGN KEY (challenge_id)
    REFERENCES challenges (id) MATCH SIMPLE
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT challenge_reports_reporter_id_fkey FOREIGN KEY (reporter_id)
    REFERENCES users (id) MATCH SIMPLE
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT challenge_reports_reviewed_by_fkey FOREIGN KEY (reviewed_by)
    REFERENCES users (id) MATCH SIMPLE
    ON UPDATE CASCADE ON DELETE SET NULL
);;

SELECT create_index_if_not_exists('challenge_reports', 'challenge_id', '(challenge_id)');;
-- The dashboard's default view is "open reports, newest first". Its leading
-- column also serves plain status filters, so no separate (status) index.
SELECT create_index_if_not_exists('challenge_reports', 'status_reported_at', '(status, reported_at DESC)');;

# --- !Downs

DROP TABLE IF EXISTS challenge_reports;;

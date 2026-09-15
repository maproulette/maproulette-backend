# --- MapRoulette Scheme

# --- !Ups

-- A team's challenge display image, moderated: a team member uploads one as a
-- request, a superuser approves it, and from then on it is the picture shown
-- on the cards of every challenge that team owns.
CREATE TABLE IF NOT EXISTS team_images
(
  id SERIAL NOT NULL PRIMARY KEY,
  team_id integer NOT NULL,
  name character varying NOT NULL,
  content_type character varying NOT NULL,
  data bytea NOT NULL,
  -- 0 = pending review, 1 = approved, 2 = rejected. Only the approved image is
  -- served publicly or shown on a card.
  status integer NOT NULL DEFAULT 0,
  requested_by integer,
  reviewed_by integer,
  reviewed_at timestamp without time zone,
  review_comment text,
  created timestamp without time zone DEFAULT NOW(),
  modified timestamp without time zone DEFAULT NOW(),
  CONSTRAINT team_images_team_id_fkey FOREIGN KEY (team_id)
    REFERENCES groups (id) MATCH SIMPLE
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT team_images_requested_by_fkey FOREIGN KEY (requested_by)
    REFERENCES users (id) MATCH SIMPLE
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT team_images_reviewed_by_fkey FOREIGN KEY (reviewed_by)
    REFERENCES users (id) MATCH SIMPLE
    ON UPDATE CASCADE ON DELETE SET NULL
);;

SELECT create_index_if_not_exists('team_images', 'team_id', '(team_id)');;
-- Partial: the only consumer is the review queue, which wants pending images
-- oldest first. Keeps the index tiny and untouched once an image is reviewed.
SELECT create_index_if_not_exists('team_images', 'status', '(created) WHERE status = 0');;

-- A team carries a single challenge image. Collapse any team that predates
-- that rule down to its newest image of each review state before enforcing it.
-- Rejected images are left alone: they are history a member is shown, never
-- something a card can point at.
WITH keepers AS (
  SELECT DISTINCT ON (team_id, status) id, team_id, status
  FROM team_images
  WHERE status IN (0, 1)
  ORDER BY team_id, status, created DESC, id DESC
)
DELETE FROM team_images ti
USING keepers k
WHERE k.team_id = ti.team_id AND k.status = ti.status AND ti.id <> k.id;;

-- The one-image rule itself: at most one approved image per team, and at most
-- one request awaiting review alongside it. Enforced here rather than only in
-- the service so a concurrent approval can't slip a second image past the
-- check and leave a team with two.
SELECT create_index_if_not_exists('team_images', 'team_approved', '(team_id) WHERE status = 1', true);;
SELECT create_index_if_not_exists('team_images', 'team_pending', '(team_id) WHERE status = 0', true);;

# --- !Downs

DROP TABLE IF EXISTS team_images;;

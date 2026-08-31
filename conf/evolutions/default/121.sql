# --- MapRoulette Scheme

# --- !Ups

-- Challenge display images are owned by a team and moderated: a team member
-- uploads one as a request, a superuser approves it, and from then on any
-- member of that team can attach it to their challenges.
CREATE TABLE IF NOT EXISTS team_images
(
  id SERIAL NOT NULL PRIMARY KEY,
  team_id integer NOT NULL,
  name character varying NOT NULL,
  content_type character varying NOT NULL,
  data bytea NOT NULL,
  -- 0 = pending review, 1 = approved, 2 = rejected. Only approved images are
  -- offered in the challenge form or served publicly.
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
SELECT create_index_if_not_exists('team_images', 'status', '(status)');;

-- Deleting an image detaches it from every challenge using it, so revoking an
-- image actually takes effect on the cards that showed it.
ALTER TABLE challenges ADD COLUMN IF NOT EXISTS team_image_id integer;;
ALTER TABLE challenges DROP CONSTRAINT IF EXISTS challenges_team_image_id_fkey;;
ALTER TABLE challenges ADD CONSTRAINT challenges_team_image_id_fkey
  FOREIGN KEY (team_image_id) REFERENCES team_images (id) MATCH SIMPLE
  ON UPDATE CASCADE ON DELETE SET NULL;;

# --- !Downs

ALTER TABLE IF EXISTS challenges DROP CONSTRAINT IF EXISTS challenges_team_image_id_fkey;;
ALTER TABLE IF EXISTS challenges DROP COLUMN IF EXISTS team_image_id;;
DROP TABLE IF EXISTS team_images;;

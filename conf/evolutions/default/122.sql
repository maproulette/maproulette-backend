# --- MapRoulette Scheme

# --- !Ups

-- A team's own avatar, uploaded rather than linked. Unlike team_images these
-- are not moderated: a team admin could already point avatar_url at any image
-- on the internet, so requiring review only for the uploaded case would gate
-- the safer of the two paths.
--
-- Keyed by team, so a team has at most one stored avatar and uploading a new
-- one replaces the old bytes rather than accumulating them.
CREATE TABLE IF NOT EXISTS team_avatars
(
  team_id integer NOT NULL PRIMARY KEY,
  content_type character varying NOT NULL,
  data bytea NOT NULL,
  uploaded_by integer,
  created timestamp without time zone DEFAULT NOW(),
  modified timestamp without time zone DEFAULT NOW(),
  CONSTRAINT team_avatars_team_id_fkey FOREIGN KEY (team_id)
    REFERENCES groups (id) MATCH SIMPLE
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT team_avatars_uploaded_by_fkey FOREIGN KEY (uploaded_by)
    REFERENCES users (id) MATCH SIMPLE
    ON UPDATE CASCADE ON DELETE SET NULL
);;

# --- !Downs

DROP TABLE IF EXISTS team_avatars;;

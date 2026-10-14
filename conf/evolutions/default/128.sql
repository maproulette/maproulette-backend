# --- MapRoulette Scheme

# --- !Ups

-- A project can be owned by a team, the same way a challenge already can. That
-- hands every one of the owners, admins and managers of that team the right to
-- manage the project, and puts the approved image of that team on its card.
--
-- This is distinct from granting a team a role on the project, which stays what
-- it has always been: one of several teams helping run it, at whatever role it
-- was given. Ownership is singular, and is what the card's picture follows.
-- Null for the projects nobody has assigned to a team, which keep working
-- purely off grants.
ALTER TABLE projects ADD COLUMN IF NOT EXISTS owner_team_id integer;;
ALTER TABLE projects DROP CONSTRAINT IF EXISTS projects_owner_team_id_fkey;;
ALTER TABLE projects ADD CONSTRAINT projects_owner_team_id_fkey
  FOREIGN KEY (owner_team_id) REFERENCES groups (id) MATCH SIMPLE
  ON UPDATE CASCADE ON DELETE SET NULL;;

-- Indexed for the referential check Postgres runs against projects whenever a
-- team is deleted, and for listing the projects a team owns. Partial, since
-- owner_team_id is null on the overwhelming majority of projects. An equality
-- lookup implies NOT NULL, so both uses still hit it.
SELECT create_index_if_not_exists('projects', 'owner_team_id', '(owner_team_id) WHERE owner_team_id IS NOT NULL');;

# --- !Downs

ALTER TABLE IF EXISTS projects DROP CONSTRAINT IF EXISTS projects_owner_team_id_fkey;;
ALTER TABLE IF EXISTS projects DROP COLUMN IF EXISTS owner_team_id;;

# --- MapRoulette Scheme

# --- !Ups

-- Teams gain an owner role above admin. Roles are ordered by privilege with
-- the lowest number the most powerful, so 0 slots in above the existing
-- admin (1), write (2) and read (3) and every `role <= admin` check already
-- in the codebase lets an owner through unchanged.
--
-- Every existing team needs exactly one: without an owner nobody could delete
-- it. Whoever creates a team is granted admin on the spot, so the oldest
-- surviving admin grant is that creator wherever they are still around, and
-- the longest-standing admin otherwise. Remaining admins stay admins.
WITH first_admin AS (
  SELECT DISTINCT ON (g.object_id) g.id
  FROM grants g
    INNER JOIN groups grp ON grp.id = g.object_id
  WHERE g.object_type = 6 AND g.grantee_type = 5 AND g.role = 1 AND grp.group_type = 1
  ORDER BY g.object_id, g.id
)
UPDATE grants SET role = 0 WHERE id IN (SELECT id FROM first_admin);;

-- A challenge can be owned by a team, which hands every one of the owners,
-- admins and managers of that team the right to manage it, and puts the
-- approved image of that team on its card. Null for the challenges nobody has
-- assigned to a team, which keep working purely off project grants.
ALTER TABLE challenges ADD COLUMN IF NOT EXISTS owner_team_id integer;;
ALTER TABLE challenges DROP CONSTRAINT IF EXISTS challenges_owner_team_id_fkey;;
ALTER TABLE challenges ADD CONSTRAINT challenges_owner_team_id_fkey
  FOREIGN KEY (owner_team_id) REFERENCES groups (id) MATCH SIMPLE
  ON UPDATE CASCADE ON DELETE SET NULL;;

-- Indexed for the referential check Postgres runs against challenges whenever
-- a team is deleted, and for listing the challenges a team owns. Partial,
-- since owner_team_id is null on the overwhelming majority of challenges. An
-- equality lookup implies NOT NULL, so both uses still hit it.
SELECT create_index_if_not_exists('challenges', 'owner_team_id', '(owner_team_id) WHERE owner_team_id IS NOT NULL');;

# --- !Downs

ALTER TABLE IF EXISTS challenges DROP CONSTRAINT IF EXISTS challenges_owner_team_id_fkey;;
ALTER TABLE IF EXISTS challenges DROP COLUMN IF EXISTS owner_team_id;;
UPDATE grants SET role = 1 WHERE role = 0 AND object_type = 6;;

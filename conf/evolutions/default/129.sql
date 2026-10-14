# --- MapRoulette Scheme

# --- !Ups

-- Make sure grant roles make sense for their types:
--   - teams can use owner/admin/manager/member
--   - projects and challenges can use admin/write/read
-- Superuser grants are special, have no role, and always target project 0.
ALTER TABLE grants DROP CONSTRAINT IF EXISTS grants_role_valid;;
ALTER TABLE grants ADD CONSTRAINT grants_role_valid CHECK (
  (role = -1 AND grantee_type = 5 AND object_type = 0 AND object_id = 0)
  OR (object_type = 6 AND role BETWEEN 0 AND 3)
  OR (object_type <> 6 AND role BETWEEN 1 AND 3)
);;

# --- !Downs

ALTER TABLE IF EXISTS grants DROP CONSTRAINT IF EXISTS grants_role_valid;;

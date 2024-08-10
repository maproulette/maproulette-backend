-- --- !Ups
-- Update any existing null avatar_url values to the default
UPDATE users
SET avatar_url = '/assets/images/user_no_image.png'
WHERE avatar_url IS NULL;

-- Alter the table to set avatar_url as NOT NULL with a default value
ALTER TABLE IF EXISTS users
ALTER COLUMN avatar_url SET DEFAULT '/assets/images/user_no_image.png',
ALTER COLUMN avatar_url SET NOT NULL;

-- Add a CHECK constraint to ensure avatar_url is a string (TEXT type)
ALTER TABLE IF EXISTS users
ADD CONSTRAINT check_avatar_url_is_string CHECK (pg_typeof(avatar_url) = 'text');

-- --- !Downs
-- Remove the CHECK constraint
ALTER TABLE IF EXISTS users
DROP CONSTRAINT IF EXISTS check_avatar_url_is_string;

-- Revert the avatar_url column to allow null values and remove the default value
ALTER TABLE IF EXISTS users
ALTER COLUMN avatar_url DROP NOT NULL,
ALTER COLUMN avatar_url DROP DEFAULT;

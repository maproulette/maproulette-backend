# --!Ups
-- Add a new column 'is_global' to the 'challenges' table with a default value
ALTER TABLE IF EXISTS challenges 
ADD COLUMN IF NOT EXISTS is_global BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN challenges.is_global IS
    'The challenges.is_global represents if a challenge is classified as global, currently a challenge is classified as global if it is wider than 180 degrees (half the map width) or taller than 90 degrees (half the map height).';

-- Update 'is_global' column based on bounding box dimensions
UPDATE challenges
SET is_global = (
    CASE
        WHEN (ST_XMax(bounding)::numeric - ST_XMin(bounding)::numeric) > 180 THEN TRUE
        WHEN (ST_YMax(bounding)::numeric - ST_YMin(bounding)::numeric) > 90 THEN TRUE
        ELSE FALSE
    END
);

# --!Downs
-- Drop the 'is_global' column
ALTER TABLE IF EXISTS challenges
DROP COLUMN is_global;
# --- !Ups

-- Support ILIKE '%term%' comment search without a full table scan.
CREATE EXTENSION IF NOT EXISTS pg_trgm;;
CREATE INDEX IF NOT EXISTS idx_task_comments_comment_trgm
    ON task_comments USING gin (comment gin_trgm_ops);;
CREATE INDEX IF NOT EXISTS idx_challenge_comments_comment_trgm
    ON challenge_comments USING gin (comment gin_trgm_ops);;

# --- !Downs

DROP INDEX IF EXISTS idx_task_comments_comment_trgm;;
DROP INDEX IF EXISTS idx_challenge_comments_comment_trgm;;

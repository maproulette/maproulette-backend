# --- MapRoulette Scheme

# --- !Ups

CREATE INDEX IF NOT EXISTS idx_challenges_name_trgm ON challenges USING gin (name gin_trgm_ops);;
CREATE INDEX IF NOT EXISTS idx_projects_label_trgm
    ON projects USING gin ((COALESCE(NULLIF(display_name, ''), name)) gin_trgm_ops);;

# --- !Downs

DROP INDEX IF EXISTS idx_challenges_name_trgm;;
DROP INDEX IF EXISTS idx_projects_label_trgm;;

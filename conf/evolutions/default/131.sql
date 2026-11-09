# --- MapRoulette Scheme

# --- !Ups

-- Stores credentials for the v3 API: personal access tokens, editor tokens, and
-- browser sessions. A token is mrt_ plus random characters and a checksum. Only
-- the token's hash is ever stored. The v2 backend never reads this table.
CREATE TABLE IF NOT EXISTS credentials (
  id            bigserial PRIMARY KEY,
  user_id       integer NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  kind          text NOT NULL CHECK (kind IN ('session', 'pat', 'editor')),
  token_hash    bytea NOT NULL UNIQUE,
  hint          text NOT NULL,
  name          text,
  scopes        text[] NOT NULL DEFAULT '{}',
  created_at    timestamptz NOT NULL DEFAULT now(),
  expires_at    timestamptz,
  last_used_at  timestamptz NOT NULL DEFAULT now(),
  revoked_at    timestamptz,
  CONSTRAINT credentials_session_scopes CHECK (kind <> 'session' OR scopes = '{}'),
  CONSTRAINT credentials_token_scopes CHECK (kind = 'session' OR cardinality(scopes) > 0),
  CONSTRAINT credentials_pat_name CHECK (kind <> 'pat' OR name IS NOT NULL),
  CONSTRAINT credentials_expiry CHECK (kind = 'pat' OR expires_at IS NOT NULL),
  CONSTRAINT credentials_token_hash_length CHECK (octet_length(token_hash) = 32)
);;

-- Used for listing a user's credentials, e.g. in the settings page of the UI.
-- When authenticating a request, we look up the token by its SHA-256 hash,
-- which uses the unique index on the token_hash column.
SELECT create_index_if_not_exists('credentials', 'user_id', '(user_id)');;

# --- !Downs

DROP TABLE IF EXISTS credentials;;

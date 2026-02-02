# --- MapRoulette Scheme

# --- !Ups
CREATE TABLE IF NOT EXISTS challenge_reviews (
  id SERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  challenge_id BIGINT NOT NULL,
  rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
  instructions_clear SMALLINT CHECK (instructions_clear BETWEEN 1 AND 5),
  challenge_interesting SMALLINT CHECK (challenge_interesting BETWEEN 1 AND 5),
  imagery_suitable SMALLINT CHECK (imagery_suitable BETWEEN 1 AND 5),
  estimated_time VARCHAR(10) CHECK (estimated_time IN ('1min','5min','15min','30min','30plus')),
  difficulty VARCHAR(15) CHECK (difficulty IN ('easy','moderate','challenging')),
  comment TEXT,
  created TIMESTAMP NOT NULL DEFAULT NOW(),
  modified TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT challenge_reviews_user_id FOREIGN KEY (user_id)
    REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT challenge_reviews_challenge_id FOREIGN KEY (challenge_id)
    REFERENCES challenges(id) ON DELETE CASCADE,
  CONSTRAINT challenge_reviews_unique UNIQUE (user_id, challenge_id)
);;

SELECT create_index_if_not_exists('challenge_reviews', 'challenge_reviews_challenge_id_idx', '(challenge_id)');;
SELECT create_index_if_not_exists('challenge_reviews', 'challenge_reviews_user_id_idx', '(user_id)');;

# --- !Downs
DROP TABLE IF EXISTS challenge_reviews;;

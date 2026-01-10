# --- MapRoulette Scheme

# --- !Ups
-- Table for challenge likes
CREATE TABLE IF NOT EXISTS challenge_likes
(
  id SERIAL NOT NULL PRIMARY KEY,
  created timestamp without time zone DEFAULT NOW(),
  user_id integer NOT NULL,
  challenge_id integer NOT NULL,
  CONSTRAINT challenge_likes_user_id FOREIGN KEY (user_id)
    REFERENCES users(id) MATCH SIMPLE
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT challenge_likes_challenge_id FOREIGN KEY (challenge_id)
    REFERENCES challenges(id) MATCH SIMPLE
    ON UPDATE CASCADE ON DELETE CASCADE
);;
SELECT create_index_if_not_exists('challenge_likes', 'user_id', '(user_id)');;
SELECT create_index_if_not_exists('challenge_likes', 'challenge_id', '(challenge_id)');;
SELECT create_index_if_not_exists('challenge_likes', 'user_id_challenge_id', '(user_id, challenge_id)', true);;

# --- !Downs
DROP TABLE IF EXISTS challenge_likes;;


# --- !Ups
CREATE TABLE IF NOT EXISTS challenge_presets(
  id SERIAL NOT NULL PRIMARY KEY,
  challenge_id INTEGER NOT NULL,
  preset VARCHAR NOT NULL,
  CONSTRAINT challenge_presets_challenge_id FOREIGN KEY (challenge_id)
    REFERENCES challenges(id) MATCH SIMPLE
    ON UPDATE CASCADE ON DELETE CASCADE
);;

SELECT create_index_if_not_exists('challenge_presets', 'challenge_presets_challenge', '(challenge_id)');;

# --- !Downs

-- Drop table challenge_presets
DROP TABLE challenge_presets;;

//! Functions for generating, parsing, and verifying credential tokens.
//!
//! A token is a string starting with `mrt_` followed by 43 random base62 characters
//! (about 256 bits of entropy) followed by a base62 CRC32 of everything before it.
//!
//! Tokens are validated by looking up their hashed value in the `credentials` table
//! in the database. The code in this module only verifies that a token is well-formed,
//! not that it is valid or belongs to a particular user.

use std::fmt;

use rand::distr::{Alphanumeric, SampleString};
use sha2::{Digest, Sha256};

const PREFIX: &str = "mrt_";
const RANDOM_LEN: usize = 43;
const CHECKSUM_LEN: usize = 6;
const LEN: usize = PREFIX.len() + RANDOM_LEN + CHECKSUM_LEN;
const HINT_LEN: usize = PREFIX.len() + 4;
const BASE62: &[u8; 62] = b"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

pub struct Token(String);

impl Token {
    pub fn generate() -> Token {
        Token::assemble(&Alphanumeric.sample_string(&mut rand::rng(), RANDOM_LEN))
    }

    fn assemble(random: &str) -> Token {
        let body = format!("{PREFIX}{random}");
        let sum = checksum(&body);
        Token(body + &sum)
    }

    // Checks that a string is a well-formed token (correct prefix, length, and valid CRC).
    // Returns Some(Token) if it is, and None if not.
    pub fn parse(s: &str) -> Option<Token> {
        let well_formed = s.len() == LEN
            && s.starts_with(PREFIX)
            && s[PREFIX.len()..].bytes().all(|b| b.is_ascii_alphanumeric());
        if !well_formed {
            return None;
        }
        let (body, sum) = s.split_at(LEN - CHECKSUM_LEN);
        (checksum(body) == sum).then(|| Token(s.to_owned()))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }

    /// The SHA-256 of the whole token.
    pub fn hash(&self) -> [u8; 32] {
        Sha256::digest(&self.0).into()
    }

    /// The first 8 characters of the token. This can be shown in server debug
    /// logs to tell tokens apart; doing so reveals only about 24 of the 256
    /// random bits.
    pub fn hint(&self) -> &str {
        &self.0[..HINT_LEN]
    }
}

impl fmt::Debug for Token {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "Token({}...)", self.hint())
    }
}

fn checksum(body: &str) -> String {
    let mut n = crc32fast::hash(body.as_bytes());
    let mut sum = [b'0'; CHECKSUM_LEN];
    for c in sum.iter_mut().rev() {
        *c = BASE62[(n % 62) as usize];
        n /= 62;
    }
    sum.iter().map(|&c| c as char).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    // These were computed with Python's zlib.crc32 and hashlib.sha256.
    const RANDOM: &str = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefg";
    const TOKEN: &str = "mrt_0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefg27X3i3";
    const HASH: &str = "2dbb7d0272e9faa2d1c02b3607ed24e9e7a4d1fdc547cee606e56340d4a4814e";

    #[test]
    fn matches_reference_vector() {
        assert_eq!(Token::assemble(RANDOM).as_str(), TOKEN);
        let hash: String = Token::parse(TOKEN)
            .unwrap()
            .hash()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        assert_eq!(hash, HASH);
    }

    #[test]
    fn generated_tokens_parse() {
        let token = Token::generate();
        assert_eq!(token.as_str().len(), LEN);
        assert!(token.as_str().starts_with(PREFIX));
        assert!(Token::parse(token.as_str()).is_some());
        assert_ne!(token.as_str(), Token::generate().as_str());
    }

    #[test]
    fn rejects_any_changed_character() {
        for i in PREFIX.len()..LEN {
            for &c in BASE62 {
                let mut s = TOKEN.as_bytes().to_vec();
                if s[i] == c {
                    continue;
                }
                s[i] = c;
                let s = String::from_utf8(s).unwrap();
                assert!(Token::parse(&s).is_none(), "{s} parsed");
            }
        }
    }

    #[test]
    fn rejects_malformed() {
        let wrong_prefix = TOKEN.replacen("mrt_", "mrx_", 1);
        let non_alphanumeric = TOKEN.replacen('A', "-", 1);
        let non_ascii = TOKEN.replacen("AB", "\u{e9}", 1);
        for s in [
            "",
            "mrt_",
            &TOKEN[..LEN - 1],
            &format!("{TOKEN}0"),
            &format!(" {}", &TOKEN[1..]),
            &wrong_prefix,
            &non_alphanumeric,
            &non_ascii,
        ] {
            assert!(Token::parse(s).is_none(), "{s:?} parsed");
        }
    }

    #[test]
    fn debug_shows_only_hint() {
        let token = Token::parse(TOKEN).unwrap();
        assert_eq!(token.hint(), "mrt_0123");
        assert_eq!(format!("{token:?}"), "Token(mrt_0123...)");
    }
}

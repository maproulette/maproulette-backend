//! Validation for v2's browser session cookie.
//!
//! This can be refactored once sessions move into the `credentials` table.

use std::collections::HashMap;
use std::time::{SystemTime, UNIX_EPOCH};

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use hmac::{Hmac, KeyInit, Mac};
use serde::Deserialize;
use sha2::Sha256;

use crate::model::UserId;

pub const COOKIE: &str = "PLAY_SESSION";

// matches v2's maproulette.session.timeout
const LIFETIME_MS: i64 = 30 * 24 * 60 * 60 * 1000;

// matches Play's default play.http.session.jwt.clockSkew
const CLOCK_SKEW_S: i64 = 5 * 60;

/// The parts of a v2 session that identify the user. The caller still has to
/// check `token_hash` against the user's current OSM token.
#[derive(Debug, PartialEq)]
pub struct Session {
    pub user_id: UserId,
    /// Lowercase hex SHA-256 of the user's OSM access token at login.
    pub token_hash: String,
}

#[derive(Deserialize)]
struct Header {
    alg: String,
}

#[derive(Deserialize)]
struct Claims {
    data: HashMap<String, String>,
    nbf: Option<i64>,
    exp: Option<i64>,
}

/// Verifies a `PLAY_SESSION` cookie value and returns the logged-in user's
/// session, or None if the cookie is invalid, expired, or belongs to a session
/// with nobody logged in (v2 stores the OAuth state there during login).
pub fn decode(cookie: &str, secret: &[u8]) -> Option<Session> {
    let (signed, signature) = cookie.rsplit_once('.')?;
    let (header, claims) = signed.split_once('.')?;

    let header: Header = serde_json::from_slice(&URL_SAFE_NO_PAD.decode(header).ok()?).ok()?;
    if header.alg != "HS256" {
        return None;
    }
    let mut mac = Hmac::<Sha256>::new_from_slice(secret).expect("HMAC accepts any key length");
    mac.update(signed.as_bytes());
    mac.verify_slice(&URL_SAFE_NO_PAD.decode(signature).ok()?)
        .ok()?;

    let claims: Claims = serde_json::from_slice(&URL_SAFE_NO_PAD.decode(claims).ok()?).ok()?;
    let now_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock is after 1970")
        .as_millis() as i64;
    let now = now_ms / 1000;
    if claims.nbf.is_some_and(|nbf| nbf > now + CLOCK_SKEW_S)
        || claims.exp.is_some_and(|exp| exp <= now - CLOCK_SKEW_S)
    {
        return None;
    }

    let login_ms: i64 = claims.data.get("userTick")?.parse().ok()?;
    if login_ms < now_ms - LIFETIME_MS {
        return None;
    }
    Some(Session {
        user_id: UserId(claims.data.get("userId")?.parse().ok()?),
        token_hash: claims.data.get("tokenHash")?.clone(),
    })
}

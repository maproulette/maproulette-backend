//! Makes v2 `PLAY_SESSION` cookies, matching Play's own logic
//! (only for testing session auth; can be removed once sessions move into the credentials table)

use std::time::{SystemTime, UNIX_EPOCH};

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use hmac::{Hmac, KeyInit, Mac};
use serde_json::{Value, json};
use sha2::Sha256;

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64
}

/// A session cookie holding `data`, issued now.
pub fn cookie(data: Value, secret: &str) -> String {
    let now = now_ms() / 1000;
    cookie_with_claims(json!({"data": data, "nbf": now, "iat": now}), secret)
}

pub fn cookie_with_claims(claims: Value, secret: &str) -> String {
    signed_jwt(json!({"alg": "HS256"}), claims, secret)
}

/// A JWT signed with HMAC-SHA256, whatever `header` says.
pub fn signed_jwt(header: Value, claims: Value, secret: &str) -> String {
    let encode = |v: &Value| URL_SAFE_NO_PAD.encode(serde_json::to_vec(v).unwrap());
    let signed = format!("{}.{}", encode(&header), encode(&claims));
    let mut mac = Hmac::<Sha256>::new_from_slice(secret.as_bytes()).unwrap();
    mac.update(signed.as_bytes());
    let signature = URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes());
    format!("{signed}.{signature}")
}

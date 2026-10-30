use time::OffsetDateTime;

use super::{CredentialId, Scope};

/// A personal access token, without its secret, which is only ever shown once.
pub struct Pat {
    pub id: CredentialId,
    pub name: String,
    pub scopes: Vec<Scope>,
    pub created_at: OffsetDateTime,
    /// None for a token that doesn't expire.
    pub expires_at: Option<OffsetDateTime>,
    pub last_used_at: OffsetDateTime,
}

/// A personal access token to be created: what the user chose.
pub struct NewPat {
    pub name: String,
    pub scopes: Vec<Scope>,
    pub expires_at: Option<OffsetDateTime>,
}

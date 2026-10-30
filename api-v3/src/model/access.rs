use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

/// A user's role on a challenge or project (ordered strongest first)
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum Role {
    SuperUser,
    Owner,
    Admin,
    Write,
    Read,
}

impl Role {
    pub fn satisfies(self, required: Role) -> bool {
        self <= required
    }
}

/// What a user may do with a challenge, as loaded from the database.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ChallengeAccess {
    /// No such challenge, or it or its project is deleted. Callers respond 404
    /// whatever the user's role.
    NotFound,
    /// The challenge exists, and this is the user's role on it, if any.
    Role(Option<Role>),
}

/// What a token may be used for. A session may do anything its user's role allows; a token is
/// limited to its scopes. The `credentials` table lists every scope that can be stored.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, ToSchema)]
pub enum Scope {
    /// Replace the task sets of challenges the user can write to.
    #[serde(rename = "challenges:write")]
    ChallengesWrite,
}

impl Scope {
    /// The scope stored as `s`, or None for one this version doesn't know.
    pub fn parse(s: &str) -> Option<Scope> {
        match s {
            "challenges:write" => Some(Scope::ChallengesWrite),
            _ => None,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Scope::ChallengesWrite => "challenges:write",
        }
    }
}

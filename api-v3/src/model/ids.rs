use std::fmt;

use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

macro_rules! id {
    ($(#[$doc:meta])* $name:ident($inner:ty)) => {
        $(#[$doc])*
        #[derive(
            Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize, ToSchema, sqlx::Type,
        )]
        #[serde(transparent)]
        #[sqlx(transparent)]
        pub struct $name(pub $inner);

        impl fmt::Display for $name {
            fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                self.0.fmt(f)
            }
        }
    };
}

id!(
    /// A MapRoulette user (`users.id`).
    UserId(i32)
);
id!(
    /// An OpenStreetMap user (`users.osm_id`, and v2's `owner_id` columns).
    OsmUserId(i32)
);
id!(
    /// A project (`projects.id`).
    ProjectId(i32)
);
id!(
    /// A challenge (`challenges.id`).
    ChallengeId(i32)
);
id!(
    /// A task (`tasks.id`).
    TaskId(i32)
);
id!(
    /// A credential: a personal access token, editor token or session (`credentials.id`).
    CredentialId(i64)
);

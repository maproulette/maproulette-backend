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

//! This module implements authorization rules, i.e. who may do what. These functions
//! just take a Principal and a Resource and return whether the action named by the
//! function is allowed; they do not touch the database (that's left for the caller).

use crate::auth::{Principal, Via};
use crate::error::Error;
use crate::model::{ChallengeAccess, Role, Scope};

/// Is this user allowed to replace the set of tasks in the given challenge?
pub fn upload_tasks(p: &Principal, access: ChallengeAccess) -> Result<(), Error> {
    let ChallengeAccess::Role(role) = access else {
        return Err(Error::NotFound);
    };
    p.require_scope(Scope::ChallengesWrite)?;
    match role {
        Some(role) if role.satisfies(Role::Write) => Ok(()),
        _ => Err(Error::Forbidden(
            "Uploading tasks needs write access to the challenge",
        )),
    }
}

/// Is this principal allowed to list, create or revoke their own personal access tokens?
/// (Only if they authenticated via a session token; we don't allow PATs to be used to
/// create other PATs)
pub fn manage_tokens(p: &Principal) -> Result<(), Error> {
    match p.via {
        Via::Session => Ok(()),
        Via::Token { .. } => Err(Error::Forbidden(
            "Tokens can only be managed while logged in to MapRoulette, not with a token",
        )),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::TokenKind;
    use crate::model::{CredentialId, UserId};

    fn session() -> Principal {
        Principal {
            user_id: UserId(1),
            via: Via::Session,
        }
    }

    fn token(scopes: &[Scope]) -> Principal {
        Principal {
            user_id: UserId(1),
            via: Via::Token {
                credential_id: CredentialId(1),
                kind: TokenKind::Pat,
                scopes: scopes.to_vec(),
            },
        }
    }

    /// A short name for a policy's decision, so cases can be compared.
    fn outcome(result: Result<(), Error>) -> &'static str {
        match result {
            Ok(()) => "allowed",
            Err(Error::NotFound) => "not found",
            Err(Error::Forbidden(_)) => "forbidden",
            Err(Error::InsufficientScope(_)) => "insufficient scope",
            Err(e) => panic!("unexpected policy error {e}"),
        }
    }

    #[test]
    fn upload_tasks_needs_write_and_the_scope() {
        use ChallengeAccess::{NotFound, Role as R};
        let write = [Scope::ChallengesWrite];
        let cases = [
            (session(), NotFound, "not found"),
            (session(), R(None), "forbidden"),
            (session(), R(Some(Role::Read)), "forbidden"),
            (session(), R(Some(Role::Write)), "allowed"),
            (session(), R(Some(Role::Admin)), "allowed"),
            (session(), R(Some(Role::Owner)), "allowed"),
            (session(), R(Some(Role::SuperUser)), "allowed"),
            (token(&write), R(Some(Role::Write)), "allowed"),
            (token(&write), R(Some(Role::Read)), "forbidden"),
            (token(&write), NotFound, "not found"),
            (token(&[]), R(Some(Role::SuperUser)), "insufficient scope"),
            (token(&[]), R(None), "insufficient scope"),
            (token(&[]), NotFound, "not found"),
        ];
        for (principal, access, expected) in cases {
            assert_eq!(
                outcome(upload_tasks(&principal, access)),
                expected,
                "{principal:?} with {access:?}"
            );
        }
    }

    #[test]
    fn manage_tokens_needs_a_session() {
        assert_eq!(outcome(manage_tokens(&session())), "allowed");
        let write = [Scope::ChallengesWrite];
        assert_eq!(outcome(manage_tokens(&token(&write))), "forbidden");
        assert_eq!(outcome(manage_tokens(&token(&[]))), "forbidden");
    }
}

use sqlx::PgExecutor;

use crate::model::{ChallengeAccess, ChallengeId, Role, UserId};

/// The user's access to a challenge (mirror's v2's `Permission.effectiveRole`)
pub async fn challenge_access(
    db: impl PgExecutor<'_>,
    user_id: UserId,
    challenge_id: ChallengeId,
) -> Result<ChallengeAccess, sqlx::Error> {
    // Grant object and grantee types: 0 project, 1 challenge, 5 user, 6 team.
    // Team member status 1 is "invited".
    let row = sqlx::query!(
        r#"
        WITH team_roles AS (
            SELECT g.object_id AS team_id, min(g.role) AS role
            FROM grants g
            JOIN group_members m
              ON m.group_id = g.object_id
             AND m.member_type = 5
             AND m.member_id = $1
             AND m.status <> 1
            WHERE g.grantee_type = 5 AND g.grantee_id = $1 AND g.object_type = 6
            GROUP BY g.object_id
            HAVING min(g.role) <= 2
        ),
        target AS (
            SELECT c.id AS challenge_id, c.owner_team_id AS challenge_team_id,
                   p.id AS project_id, p.owner_team_id AS project_team_id,
                   coalesce(c.deleted, false) OR coalesce(p.deleted, false) AS deleted
            FROM challenges c
            JOIN projects p ON p.id = c.parent_id
            WHERE c.id = $2
        ),
        challenge_roles AS (
            SELECT g.role
            FROM grants g, target t
            WHERE g.grantee_type = 5 AND g.grantee_id = $1
              AND g.object_type = 1 AND g.object_id = t.challenge_id
            UNION ALL
            SELECT r.role
            FROM team_roles r, target t
            WHERE r.team_id = t.challenge_team_id
               OR r.team_id IN (
                   SELECT g.grantee_id FROM grants g
                   WHERE g.grantee_type = 6 AND g.object_type = 1 AND g.object_id = t.challenge_id
               )
        ),
        project_roles AS (
            SELECT g.role
            FROM grants g, target t
            WHERE g.grantee_type = 5 AND g.grantee_id = $1
              AND g.object_type = 0 AND g.object_id = t.project_id
            UNION ALL
            SELECT r.role
            FROM team_roles r, target t
            WHERE r.team_id = t.project_team_id
               OR r.team_id IN (
                   SELECT g.grantee_id FROM grants g
                   WHERE g.grantee_type = 6 AND g.object_type = 0 AND g.object_id = t.project_id
               )
        )
        SELECT
            t.deleted AS "deleted!",
            CASE
                WHEN EXISTS (
                    SELECT 1 FROM grants
                    WHERE grantee_type = 5 AND grantee_id = $1
                      AND role = -1 AND object_type = 0 AND object_id = 0
                ) THEN -1
                ELSE coalesce(
                    (SELECT min(role) FROM challenge_roles),
                    (SELECT min(role) FROM project_roles)
                )
            END AS role
        FROM target t
        "#,
        user_id.0,
        challenge_id.0,
    )
    .fetch_optional(db)
    .await?;
    Ok(match row {
        None => ChallengeAccess::NotFound,
        Some(r) if r.deleted => ChallengeAccess::NotFound,
        Some(r) => ChallengeAccess::Role(r.role.map(role)),
    })
}

fn role(rank: i32) -> Role {
    match rank {
        -1 => Role::SuperUser,
        0 => Role::Owner,
        1 => Role::Admin,
        2 => Role::Write,
        3 => Role::Read,
        // the grants_role_valid constraint enforces that only the above ranks exist
        _ => unreachable!("invalid grant role {rank}"),
    }
}

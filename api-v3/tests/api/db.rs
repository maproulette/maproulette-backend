use crate::utils::TestApp;

#[tokio::test]
async fn connections_have_timeouts_and_utc() {
    let app = TestApp::spawn().await;
    let (statement, lock, idle, time_zone): (String, String, String, String) = sqlx::query_as(
        "SELECT current_setting('statement_timeout'), current_setting('lock_timeout'), \
         current_setting('idle_in_transaction_session_timeout'), current_setting('TimeZone')",
    )
    .fetch_one(&app.db)
    .await
    .unwrap();
    assert_eq!(statement, "25s");
    assert_eq!(lock, "5s");
    assert_eq!(idle, "1min");
    // v2's timestamp columns have no time zone, and both backends assume they're in UTC.
    assert_eq!(time_zone, "UTC");
}

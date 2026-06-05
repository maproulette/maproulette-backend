# --- MapRoulette Scheme

# --- !Ups

-- Track whether an unlock warning email has been sent for a given lock so
-- the sendTaskLockExpiryReminders job does not email the same user about
-- the same lock more than once.
ALTER TABLE IF EXISTS locked
  ADD COLUMN reminder_sent_at timestamp without time zone DEFAULT NULL;;

-- New per-user subscription controlling task-lock-expiry reminder emails.
-- Defaults to NOTIFICATION_EMAIL_NONE (1) so the reminder is opt-in.
ALTER TABLE IF EXISTS user_notification_subscriptions
  ADD COLUMN task_unlock_warning integer NOT NULL DEFAULT 1;;


# --- !Downs

ALTER TABLE IF EXISTS user_notification_subscriptions DROP COLUMN task_unlock_warning;;
ALTER TABLE IF EXISTS locked DROP COLUMN reminder_sent_at;;

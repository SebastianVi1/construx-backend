-- Run once against an existing PostgreSQL database before Hibernate schema update.
-- Existing accounts must verify their email before the new login flow accepts them.
ALTER TABLE "user-account" ALTER COLUMN password DROP NOT NULL;
ALTER TABLE "user-account" ADD COLUMN IF NOT EXISTS email_verified boolean NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS auth_session (
    id uuid PRIMARY KEY,
    user_account_id uuid NOT NULL REFERENCES "user-account"(id),
    refresh_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz
);
CREATE INDEX IF NOT EXISTS ix_auth_session_user ON auth_session(user_account_id);

CREATE TABLE IF NOT EXISTS google_identity (
    id uuid PRIMARY KEY,
    subject varchar(255) NOT NULL UNIQUE,
    user_account_id uuid NOT NULL REFERENCES "user-account"(id)
);
CREATE INDEX IF NOT EXISTS ix_google_identity_user ON google_identity(user_account_id);

CREATE TABLE IF NOT EXISTS email_verification (
    id uuid PRIMARY KEY,
    user_account_id uuid NOT NULL REFERENCES "user-account"(id),
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at timestamptz
);
CREATE INDEX IF NOT EXISTS ix_email_verification_user ON email_verification(user_account_id);

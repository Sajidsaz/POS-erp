-- V14 — Multi-factor authentication (FR-AUTH-005, FR-AUTH-007).
--
-- MFA state lives in the platform schema alongside app_user, not in a tenant schema: it is
-- resolved during authentication, before a tenant context exists, so it is read on the same
-- elevated connection as the user record itself. Secrets and recovery codes are credentials,
-- so recovery codes are stored only as hashes (SEC-010), exactly like passwords.

CREATE TABLE platform.user_mfa (
    user_id      uuid PRIMARY KEY REFERENCES platform.app_user (id) ON DELETE CASCADE,
    -- The Base32 TOTP shared secret. Present once enrollment starts; only trusted once enabled.
    secret       text NOT NULL,
    enabled      boolean NOT NULL DEFAULT false,
    confirmed_at timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE platform.mfa_recovery_code (
    id         uuid PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES platform.app_user (id) ON DELETE CASCADE,
    -- Single-use, stored as a bcrypt hash; the plaintext is shown once at generation.
    code_hash  text NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX mfa_recovery_code_user_idx ON platform.mfa_recovery_code (user_id) WHERE used_at IS NULL;

-- FR-AUTH-007: MFA is enforceable by organization policy for privileged accounts.
ALTER TABLE platform.organization
    ADD COLUMN mfa_required_privileged boolean NOT NULL DEFAULT false;

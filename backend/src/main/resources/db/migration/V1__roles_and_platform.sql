-- V1 — database roles and the non-tenant platform schema.
--
-- Appendix D, decision D1: the application connects as a role that is subject to
-- row-level security. Migrations run as the table owner, which bypasses RLS by
-- design so that DDL and backfills work. DB-011 forbids the application from
-- ever using the owner role at runtime.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'erp_app') THEN
    CREATE ROLE erp_app LOGIN PASSWORD 'erp_app';
  END IF;
END
$$;

-- erp_app must never be able to sidestep a policy.
ALTER ROLE erp_app NOBYPASSRLS;

-- ---------------------------------------------------------------------------
-- platform schema: rows that are NOT tenant-scoped and carry no RLS.
-- Only Platform Operators (Section 13) reach these through dedicated endpoints.
-- ---------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS platform;
GRANT USAGE ON SCHEMA platform TO erp_app;

CREATE TABLE platform.plan (
    id            uuid PRIMARY KEY,
    code          text NOT NULL UNIQUE,
    name          text NOT NULL,
    entitlements  jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- FR-ORG-004: trial, active, suspended, closed.
CREATE TABLE platform.organization (
    id            uuid PRIMARY KEY,
    code          text NOT NULL UNIQUE,
    name          text NOT NULL,
    status        text NOT NULL CHECK (status IN ('TRIAL', 'ACTIVE', 'SUSPENDED', 'CLOSED')),
    plan_id       uuid REFERENCES platform.plan (id),
    currency      text NOT NULL DEFAULT 'LKR',
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- Identity lives at platform level because a user is resolved before any tenant
-- context exists. org_id here is the user's home organization; shop scope
-- (FR-ORG-003) arrives in M1.
CREATE TABLE platform.app_user (
    id             uuid PRIMARY KEY,
    org_id         uuid REFERENCES platform.organization (id),
    email          text NOT NULL,
    password_hash  text,
    display_name   text NOT NULL,
    is_platform_operator boolean NOT NULL DEFAULT false,
    active         boolean NOT NULL DEFAULT true,
    last_login_at  timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT app_user_email_unique UNIQUE (email),
    -- A platform operator has no home organization; everyone else must have one.
    CONSTRAINT app_user_org_required CHECK (is_platform_operator OR org_id IS NOT NULL)
);

-- FR-API-006 to FR-API-010: integration tokens. SEC-010 / FR-API-007 — the secret
-- is stored only as a hash, and shown once at creation.
CREATE TABLE platform.integration_token (
    id             uuid PRIMARY KEY,
    org_id         uuid NOT NULL REFERENCES platform.organization (id),
    name           text NOT NULL,
    token_hash     text NOT NULL UNIQUE,
    scopes         text[] NOT NULL DEFAULT '{}',
    issued_by      uuid NOT NULL REFERENCES platform.app_user (id),
    expires_at     timestamptz,
    revoked_at     timestamptz,
    last_used_at   timestamptz,
    last_used_ip   inet,
    created_at     timestamptz NOT NULL DEFAULT now()
);

-- FR-TERM-001 / FR-TERM-004: terminals authenticate as themselves and can be
-- disabled centrally.
CREATE TABLE platform.terminal_credential (
    id             uuid PRIMARY KEY,
    org_id         uuid NOT NULL REFERENCES platform.organization (id),
    terminal_code  text NOT NULL,
    token_hash     text NOT NULL UNIQUE,
    disabled_at    timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT terminal_code_unique_per_org UNIQUE (org_id, terminal_code)
);

GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA platform TO erp_app;

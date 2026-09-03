-- V13 — Notifications (Section 13, FR-NOT-001..005).
--
-- In-application delivery is the always-available channel (FR-NOT-002); email is delivered
-- only where a provider is configured, so the schema carries preferences per channel and the
-- application decides at send time. A notification may target one user, or be org-wide
-- (user_id NULL) for alerts that concern anyone with the relevant permission.

CREATE TABLE app.notification (
    id             uuid PRIMARY KEY,
    org_id         uuid NOT NULL,
    -- NULL means org-wide: shown to every user in the tenant (FR-NOT-004 still applies at read).
    user_id        uuid REFERENCES platform.app_user (id),
    type           text NOT NULL,
    severity       text NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    title          text NOT NULL,
    body           text,
    reference_type text,
    reference_id   text,
    read_at        timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX notification_user_idx ON app.notification (org_id, user_id, created_at DESC);
CREATE INDEX notification_unread_idx ON app.notification (org_id, created_at DESC) WHERE read_at IS NULL;

CREATE TABLE app.notification_preference (
    id       uuid PRIMARY KEY,
    org_id   uuid NOT NULL,
    user_id  uuid NOT NULL REFERENCES platform.app_user (id),
    type     text NOT NULL,
    channel  text NOT NULL CHECK (channel IN ('IN_APP', 'EMAIL')),
    enabled  boolean NOT NULL DEFAULT true,
    CONSTRAINT notification_pref_unique UNIQUE (org_id, user_id, type, channel)
);

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['notification', 'notification_preference'] LOOP
        EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I ON app.%I USING (org_id = app.current_org()) '
            'WITH CHECK (org_id = app.current_org())', t || '_tenant', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON app.%I TO erp_app', t);
    END LOOP;
END
$$;

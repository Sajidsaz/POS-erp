-- V2 — the tenant kernel: RLS machinery, audit, outbox, idempotency.
--
-- Every table in the `app` schema is tenant-scoped and carries a policy keyed to
-- app.current_org(), which reads a per-transaction GUC set by the application
-- (Appendix D, decision D1; DB-010).

CREATE SCHEMA IF NOT EXISTS app;
GRANT USAGE ON SCHEMA app TO erp_app;

-- Fail closed: with no GUC set, this returns NULL, every `org_id = NULL`
-- comparison is NULL, and the policy admits no rows at all.
CREATE OR REPLACE FUNCTION app.current_org() RETURNS uuid
LANGUAGE sql STABLE AS $$
    SELECT nullif(current_setting('app.org_id', true), '')::uuid
$$;

-- ---------------------------------------------------------------------------
-- AUD-001 / AUD-002 / AUD-006
-- ---------------------------------------------------------------------------
CREATE TABLE app.audit_log (
    id             uuid PRIMARY KEY,
    org_id         uuid NOT NULL,
    shop_id        uuid,
    terminal_id    uuid,
    actor_user_id  uuid,
    actor_type     text NOT NULL,
    action         text NOT NULL,
    entity_type    text NOT NULL,
    entity_id      text,
    prior_value    jsonb,
    new_value      jsonb,
    outcome        text NOT NULL CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED')),
    correlation_id text,
    occurred_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_org_time_idx ON app.audit_log (org_id, occurred_at DESC);
CREATE INDEX audit_log_entity_idx ON app.audit_log (org_id, entity_type, entity_id);

-- ---------------------------------------------------------------------------
-- FR-API-005 / FR-API-004: transactional outbox.
-- ---------------------------------------------------------------------------
CREATE TABLE app.outbox_event (
    id              uuid PRIMARY KEY,
    org_id          uuid NOT NULL,
    event_type      text NOT NULL,
    aggregate_type  text NOT NULL,
    aggregate_id    text NOT NULL,
    payload         jsonb NOT NULL,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz,
    attempts        integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error      text
);
-- The worker's claim query: unpublished rows that are due.
CREATE INDEX outbox_unpublished_idx ON app.outbox_event (next_attempt_at)
    WHERE published_at IS NULL;

-- ---------------------------------------------------------------------------
-- FR-API-011 / FR-API-012: idempotency.
-- The unique constraint is the concurrency gate for checkout — a duplicate
-- INSERT blocks until the first transaction commits, then reads its response.
-- ---------------------------------------------------------------------------
CREATE TABLE app.idempotency_record (
    id              uuid PRIMARY KEY,
    org_id          uuid NOT NULL,
    terminal_id     text NOT NULL,
    endpoint        text NOT NULL,
    idem_key        text NOT NULL,
    request_hash    text NOT NULL,
    response_status integer,
    response_body   jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    CONSTRAINT idempotency_scope_unique UNIQUE (org_id, terminal_id, endpoint, idem_key)
);
CREATE INDEX idempotency_expiry_idx ON app.idempotency_record (expires_at);

-- ---------------------------------------------------------------------------
-- Row-level security for every kernel table.
-- ---------------------------------------------------------------------------
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['audit_log', 'outbox_event', 'idempotency_record'] LOOP
        EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I ON app.%I USING (org_id = app.current_org()) '
            'WITH CHECK (org_id = app.current_org())', t || '_tenant', t);
    END LOOP;
END
$$;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA app TO erp_app;

-- AUD-006: audit entries are append-only through every application path.
-- Enforced by grant, not by convention.
REVOKE UPDATE, DELETE ON app.audit_log FROM erp_app;

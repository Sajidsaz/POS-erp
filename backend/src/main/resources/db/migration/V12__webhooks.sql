-- V12 — Webhook subscriptions and delivery tracking (Section 14.3).
--
-- FR-API-002: delivery is signed, and the signature covers the payload AND a timestamp so a
--   captured request cannot be replayed later.
-- FR-API-003: transient failures retry with bounded exponential backoff and exhausted
--   deliveries land in a visible dead-letter state. The existing outbox worker already
--   supplies the backoff; a per-subscription delivery row makes the state visible and lets
--   a retry skip the subscribers that already succeeded.

CREATE TABLE app.webhook_subscription (
    id          uuid PRIMARY KEY,
    org_id      uuid NOT NULL,
    url         text NOT NULL,
    -- The shared secret the HMAC signature is keyed with. Shown once at creation.
    secret      text NOT NULL,
    -- Event types this endpoint wants, e.g. {'pos.sale_completed'}. '*' means every event.
    event_types text[] NOT NULL DEFAULT '{}',
    description text,
    active      boolean NOT NULL DEFAULT true,
    created_by  uuid REFERENCES platform.app_user (id),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX webhook_subscription_org_idx ON app.webhook_subscription (org_id) WHERE active;

CREATE TABLE app.webhook_delivery (
    id              uuid PRIMARY KEY,
    org_id          uuid NOT NULL,
    subscription_id uuid NOT NULL REFERENCES app.webhook_subscription (id) ON DELETE CASCADE,
    outbox_event_id uuid NOT NULL REFERENCES app.outbox_event (id) ON DELETE CASCADE,
    event_type      text NOT NULL,
    url             text NOT NULL,
    status          text NOT NULL CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED', 'DEAD')),
    attempts        integer NOT NULL DEFAULT 0,
    response_status integer,
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    delivered_at    timestamptz,
    -- One delivery record per (subscription, event): a retry updates it in place, so a
    -- subscriber that already succeeded is never sent the same event twice.
    CONSTRAINT webhook_delivery_unique UNIQUE (subscription_id, outbox_event_id)
);

CREATE INDEX webhook_delivery_sub_idx ON app.webhook_delivery (org_id, subscription_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['webhook_subscription', 'webhook_delivery'] LOOP
        EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I ON app.%I USING (org_id = app.current_org()) '
            'WITH CHECK (org_id = app.current_org())', t || '_tenant', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON app.%I TO erp_app', t);
    END LOOP;
END
$$;

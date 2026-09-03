-- V11 — Customers and store credit (Section 9).
--
-- A customer's outstanding balance is a running figure kept on the row for fast reads AND
-- an append-only ledger it can always be reconstructed from (the same discipline as stock
-- balances vs. stock movements — invariant B12). Charging on account and taking a payment
-- both lock the customer row FOR UPDATE inside the business transaction, so two concurrent
-- sales can never push a customer past their limit (decision D6 applied to credit).

CREATE TABLE app.customer (
    id             uuid PRIMARY KEY,
    org_id         uuid NOT NULL,
    code           text NOT NULL,
    name           text NOT NULL,
    phone          text,
    email          text,
    -- Maximum outstanding the customer may owe. Zero means cash-only, no account credit.
    credit_limit   numeric(19, 4) NOT NULL DEFAULT 0 CHECK (credit_limit >= 0),
    -- Current outstanding owed. Positive is owed to the shop; negative is store credit held.
    credit_balance numeric(19, 4) NOT NULL DEFAULT 0,
    active         boolean NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT customer_code_unique UNIQUE (org_id, code)
);

CREATE INDEX customer_name_idx ON app.customer (org_id, name);

-- Append-only. `amount` is signed: a CHARGE is positive (raises what is owed), a PAYMENT is
-- negative (settles it). `balance_after` is the running balance the entry produced.
CREATE TABLE app.customer_ledger (
    id             uuid PRIMARY KEY,
    org_id         uuid NOT NULL,
    customer_id    uuid NOT NULL REFERENCES app.customer (id),
    entry_type     text NOT NULL CHECK (entry_type IN ('CHARGE', 'PAYMENT', 'ADJUSTMENT')),
    amount         numeric(19, 4) NOT NULL,
    reference_type text,
    reference_id   text,
    balance_after  numeric(19, 4) NOT NULL,
    actor_user_id  uuid REFERENCES platform.app_user (id),
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX customer_ledger_customer_idx ON app.customer_ledger (org_id, customer_id, created_at);

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['customer', 'customer_ledger'] LOOP
        EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I ON app.%I USING (org_id = app.current_org()) '
            'WITH CHECK (org_id = app.current_org())', t || '_tenant', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON app.%I TO erp_app', t);
    END LOOP;
END
$$;

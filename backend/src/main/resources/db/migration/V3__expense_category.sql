-- V3 — the first real tenant-scoped business table.
--
-- ExpenseCategory (Section 15.1, Finance) is deliberately the M0 vertical slice:
-- it is a genuine R1 entity rather than throwaway scaffolding, it is small enough
-- to keep the milestone honest, and its monthly_budget column exercises the
-- NUMERIC(19,4) money rule from DB-004 end to end.

CREATE TABLE app.expense_category (
    id             uuid PRIMARY KEY,
    org_id         uuid NOT NULL,
    code           text NOT NULL,
    name           text NOT NULL,
    monthly_budget numeric(19, 4),
    active         boolean NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    -- DB-003: tenant-scoped business identifiers are unique *within* the org.
    CONSTRAINT expense_category_code_unique UNIQUE (org_id, code)
);

CREATE INDEX expense_category_org_idx ON app.expense_category (org_id) WHERE active;

ALTER TABLE app.expense_category ENABLE ROW LEVEL SECURITY;

CREATE POLICY expense_category_tenant ON app.expense_category
    USING (org_id = app.current_org())
    WITH CHECK (org_id = app.current_org());

GRANT SELECT, INSERT, UPDATE, DELETE ON app.expense_category TO erp_app;

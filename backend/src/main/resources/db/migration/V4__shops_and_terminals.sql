-- V4 — shops and terminals.
--
-- FR-ORG-008: a warehouse is a shop with selling disabled, so stock, transfers and counts
-- have one code path rather than two that drift apart.

CREATE TABLE app.shop (
    id               uuid PRIMARY KEY,
    org_id           uuid NOT NULL,
    code             text NOT NULL,
    name             text NOT NULL,
    -- FR-ORG-007
    address_line1    text,
    address_line2    text,
    city             text,
    phone            text,
    email            text,
    currency         text NOT NULL DEFAULT 'LKR',
    -- DB-005: business-day boundaries are resolved in the shop's own zone, not the server's.
    timezone         text NOT NULL DEFAULT 'Asia/Colombo',
    -- Decision D5: the SHOP segment of the invoice number, e.g. COL01-26-000042.
    document_prefix  text NOT NULL,
    default_tax_class_id uuid,
    receipt_header   text,
    receipt_footer   text,
    tax_registration_no text,
    selling_enabled  boolean NOT NULL DEFAULT true,
    -- FR-INV-004: negative stock policy is per organization, overridable per shop.
    -- NULL means "inherit the organization setting".
    allow_negative_stock boolean,
    active           boolean NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT shop_code_unique UNIQUE (org_id, code),
    CONSTRAINT shop_document_prefix_unique UNIQUE (org_id, document_prefix)
);

CREATE TABLE app.terminal (
    id               uuid PRIMARY KEY,
    org_id           uuid NOT NULL,
    -- FR-TERM-002: exactly one shop.
    shop_id          uuid NOT NULL REFERENCES app.shop (id),
    code             text NOT NULL,
    name             text NOT NULL,
    -- FR-TERM-005
    printer_type     text NOT NULL DEFAULT 'ESCPOS_USB'
        CHECK (printer_type IN ('ESCPOS_USB', 'ESCPOS_NETWORK', 'NONE')),
    printer_address  text,
    paper_width_mm   integer NOT NULL DEFAULT 80 CHECK (paper_width_mm IN (58, 80)),
    cash_drawer_enabled boolean NOT NULL DEFAULT true,
    customer_display_enabled boolean NOT NULL DEFAULT false,
    -- FR-TERM-004: disabling is a state on the terminal; the credential is revoked
    -- alongside it in platform.terminal_credential.
    disabled_at      timestamptz,
    last_seen_at     timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    -- FR-TERM-001: unique within the organization.
    CONSTRAINT terminal_code_unique UNIQUE (org_id, code)
);

CREATE INDEX terminal_shop_idx ON app.terminal (org_id, shop_id);

-- Decision D5. One row per (shop, document type, fiscal year); allocation locks this row
-- FOR UPDATE inside the checkout transaction, and the number is consumed only by a commit,
-- which is what keeps the series gapless.
CREATE TABLE app.document_sequence (
    id            uuid PRIMARY KEY,
    org_id        uuid NOT NULL,
    shop_id       uuid NOT NULL REFERENCES app.shop (id),
    document_type text NOT NULL CHECK (document_type IN
        ('SALE', 'RETURN', 'PURCHASE_ORDER', 'GOODS_RECEIPT', 'TRANSFER', 'PAYMENT')),
    fiscal_year   integer NOT NULL,
    next_value    bigint NOT NULL DEFAULT 1,
    CONSTRAINT document_sequence_unique UNIQUE (org_id, shop_id, document_type, fiscal_year)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['shop', 'terminal', 'document_sequence'] LOOP
        EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I ON app.%I USING (org_id = app.current_org()) '
            'WITH CHECK (org_id = app.current_org())', t || '_tenant', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON app.%I TO erp_app', t);
    END LOOP;
END
$$;

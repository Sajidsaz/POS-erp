-- V10 — Purchasing: suppliers, purchase orders and goods receipts.
--
-- Decision D2: a goods receipt is where new stock enters at a known unit cost, so it is the
--   event that moves the per-variant moving weighted average. Receiving posts a RECEIPT
--   stock movement through the inventory module, exactly as adjustments and transfers do.
-- Decision D5: PO and GRN numbers come from the same gapless per-shop sequence as SALE,
--   RETURN and TRANSFER (document_sequence already permits PURCHASE_ORDER and GOODS_RECEIPT).

-- ---------------------------------------------------------------------------
-- Suppliers (Section 8)
-- ---------------------------------------------------------------------------
CREATE TABLE app.supplier (
    id                  uuid PRIMARY KEY,
    org_id              uuid NOT NULL,
    code                text NOT NULL,
    name                text NOT NULL,
    contact_name        text,
    phone               text,
    email               text,
    tax_registration_no text,
    active              boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT supplier_code_unique UNIQUE (org_id, code)
);

-- ---------------------------------------------------------------------------
-- Purchase orders (Section 8.2)
-- ---------------------------------------------------------------------------
CREATE TABLE app.purchase_order (
    id            uuid PRIMARY KEY,
    org_id        uuid NOT NULL,
    -- Decision D5: gapless sequence number, e.g. COL01-26-000004.
    po_number     text NOT NULL,
    supplier_id   uuid NOT NULL REFERENCES app.supplier (id),
    -- The destination shop the goods are ordered into.
    shop_id       uuid NOT NULL REFERENCES app.shop (id),
    status        text NOT NULL CHECK (status IN
                      ('DRAFT', 'APPROVED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED')),
    ordered_total numeric(19, 4) NOT NULL DEFAULT 0,
    notes         text,
    created_by    uuid NOT NULL REFERENCES platform.app_user (id),
    approved_by   uuid REFERENCES platform.app_user (id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT purchase_order_number_unique UNIQUE (org_id, po_number)
);

CREATE INDEX purchase_order_supplier_idx ON app.purchase_order (org_id, supplier_id);
CREATE INDEX purchase_order_shop_idx ON app.purchase_order (org_id, shop_id, created_at DESC);

CREATE TABLE app.purchase_order_line (
    id                uuid PRIMARY KEY,
    org_id            uuid NOT NULL,
    po_id             uuid NOT NULL REFERENCES app.purchase_order (id) ON DELETE CASCADE,
    variant_id        uuid NOT NULL REFERENCES app.variant (id),
    ordered_quantity  numeric(19, 4) NOT NULL CHECK (ordered_quantity > 0),
    unit_cost         numeric(19, 4) NOT NULL CHECK (unit_cost >= 0),
    received_quantity numeric(19, 4) NOT NULL DEFAULT 0
);

CREATE INDEX purchase_order_line_po_idx ON app.purchase_order_line (po_id);

-- ---------------------------------------------------------------------------
-- Goods receipts (Section 8.3). A receipt may reference a PO or stand alone
-- (a direct/blind receipt), but always lands stock at a shop with a unit cost.
-- ---------------------------------------------------------------------------
CREATE TABLE app.goods_receipt (
    id          uuid PRIMARY KEY,
    org_id      uuid NOT NULL,
    grn_number  text NOT NULL,
    po_id       uuid REFERENCES app.purchase_order (id),
    supplier_id uuid REFERENCES app.supplier (id),
    shop_id     uuid NOT NULL REFERENCES app.shop (id),
    notes       text,
    received_by uuid NOT NULL REFERENCES platform.app_user (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT goods_receipt_number_unique UNIQUE (org_id, grn_number)
);

CREATE INDEX goods_receipt_po_idx ON app.goods_receipt (org_id, po_id);
CREATE INDEX goods_receipt_shop_idx ON app.goods_receipt (org_id, shop_id, created_at DESC);

CREATE TABLE app.goods_receipt_line (
    id                uuid PRIMARY KEY,
    org_id            uuid NOT NULL,
    grn_id            uuid NOT NULL REFERENCES app.goods_receipt (id) ON DELETE CASCADE,
    po_line_id        uuid REFERENCES app.purchase_order_line (id),
    variant_id        uuid NOT NULL REFERENCES app.variant (id),
    received_quantity numeric(19, 4) NOT NULL CHECK (received_quantity > 0),
    -- Decision D2 / invariant B7: the unit cost that this receipt feeds into the average.
    unit_cost         numeric(19, 4) NOT NULL CHECK (unit_cost >= 0)
);

CREATE INDEX goods_receipt_line_grn_idx ON app.goods_receipt_line (grn_id);

-- ---------------------------------------------------------------------------
-- Row-level security for all purchasing tables
-- ---------------------------------------------------------------------------
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'supplier', 'purchase_order', 'purchase_order_line',
        'goods_receipt', 'goods_receipt_line'
    ] LOOP
        EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I ON app.%I USING (org_id = app.current_org()) '
            'WITH CHECK (org_id = app.current_org())', t || '_tenant', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON app.%I TO erp_app', t);
    END LOOP;
END
$$;

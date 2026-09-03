-- V9 — Point of Sale (POS), checkout, shifts, held carts, receipts and returns.
--
-- Appendix D, decision D4: money precision, line tax rounding, cash rounding (FR-POS-014).
-- Appendix D, decision D5: gapless document sequences for SALE and RETURN.
-- Appendix D, decision D6: pessimistic locking on stock_balance during checkout.
-- Appendix B invariants B1, B2, B4, B5, B6, B7, B9, B10, B14.

-- ---------------------------------------------------------------------------
-- Cashier shifts & cash drawer tracking (Section 7.3)
-- ---------------------------------------------------------------------------
CREATE TABLE app.shift (
    id              uuid PRIMARY KEY,
    org_id          uuid NOT NULL,
    shop_id         uuid NOT NULL REFERENCES app.shop (id),
    terminal_id     uuid NOT NULL REFERENCES app.terminal (id),
    cashier_user_id uuid NOT NULL REFERENCES platform.app_user (id),
    status          text NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    opening_cash    numeric(19, 4) NOT NULL CHECK (opening_cash >= 0),
    closing_cash    numeric(19, 4),
    expected_cash   numeric(19, 4),
    cash_variance   numeric(19, 4),
    opened_at       timestamptz NOT NULL DEFAULT now(),
    closed_at       timestamptz,
    closed_by       uuid REFERENCES platform.app_user (id),
    notes           text
);

CREATE INDEX shift_shop_terminal_idx ON app.shift (org_id, shop_id, terminal_id);
-- FR-SHIFT-002: at most one open shift per terminal, and at most one open shift per cashier
CREATE UNIQUE INDEX shift_one_open_per_terminal ON app.shift (org_id, terminal_id) WHERE status = 'OPEN';
CREATE UNIQUE INDEX shift_one_open_per_cashier ON app.shift (org_id, cashier_user_id) WHERE status = 'OPEN';

CREATE TABLE app.shift_movement (
    id             uuid PRIMARY KEY,
    org_id         uuid NOT NULL,
    shift_id       uuid NOT NULL REFERENCES app.shift (id) ON DELETE CASCADE,
    movement_type  text NOT NULL CHECK (movement_type IN ('CASH_IN', 'CASH_OUT', 'DRAWER_OPEN', 'EXPENSE')),
    amount         numeric(19, 4) NOT NULL DEFAULT 0,
    reason         text NOT NULL,
    actor_user_id  uuid NOT NULL REFERENCES platform.app_user (id),
    occurred_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX shift_movement_shift_idx ON app.shift_movement (org_id, shift_id);

-- ---------------------------------------------------------------------------
-- Held carts (FR-POS-009)
-- ---------------------------------------------------------------------------
CREATE TABLE app.held_cart (
    id              uuid PRIMARY KEY,
    org_id          uuid NOT NULL,
    shop_id         uuid NOT NULL REFERENCES app.shop (id),
    terminal_id     uuid NOT NULL REFERENCES app.terminal (id),
    cashier_user_id uuid NOT NULL REFERENCES platform.app_user (id),
    reference       text,
    cart_payload    jsonb NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX held_cart_shop_idx ON app.held_cart (org_id, shop_id);

-- ---------------------------------------------------------------------------
-- Sales, lines and multi-tender payments (Section 7.2)
-- ---------------------------------------------------------------------------
CREATE TABLE app.sale (
    id              uuid PRIMARY KEY,
    org_id          uuid NOT NULL,
    shop_id         uuid NOT NULL REFERENCES app.shop (id),
    terminal_id     uuid NOT NULL REFERENCES app.terminal (id),
    shift_id        uuid REFERENCES app.shift (id),
    cashier_user_id uuid NOT NULL REFERENCES platform.app_user (id),
    -- Decision D5: Gapless sequence number (e.g. SHOP-YY-000001)
    invoice_number  text NOT NULL,
    status          text NOT NULL CHECK (status IN ('COMPLETED', 'VOIDED', 'RETURNED', 'PARTIALLY_RETURNED')),
    subtotal        numeric(19, 4) NOT NULL,
    discount_total  numeric(19, 4) NOT NULL DEFAULT 0,
    tax_total       numeric(19, 4) NOT NULL DEFAULT 0,
    grand_total     numeric(19, 4) NOT NULL,
    -- Decision D4 / FR-POS-014: Cash rounding adjustment line
    cash_rounding   numeric(19, 4) NOT NULL DEFAULT 0,
    total_tendered  numeric(19, 4) NOT NULL,
    change_given    numeric(19, 4) NOT NULL DEFAULT 0,
    customer_id     uuid,
    notes           text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT sale_invoice_number_unique UNIQUE (org_id, invoice_number)
);

CREATE INDEX sale_shop_time_idx ON app.sale (org_id, shop_id, created_at DESC);
CREATE INDEX sale_shift_idx ON app.sale (org_id, shift_id);

CREATE TABLE app.sale_line (
    id                uuid PRIMARY KEY,
    org_id            uuid NOT NULL,
    sale_id           uuid NOT NULL REFERENCES app.sale (id) ON DELETE CASCADE,
    variant_id        uuid NOT NULL REFERENCES app.variant (id),
    quantity          numeric(19, 4) NOT NULL CHECK (quantity > 0),
    unit_price        numeric(19, 4) NOT NULL,
    discount_amount   numeric(19, 4) NOT NULL DEFAULT 0,
    tax_class_id      uuid REFERENCES app.tax_class (id),
    tax_rate          numeric(9, 4) NOT NULL DEFAULT 0,
    tax_amount        numeric(19, 4) NOT NULL DEFAULT 0,
    line_total        numeric(19, 4) NOT NULL,
    -- Decision D2 / Invariant B7: Cost snapshot captured at the moment of sale
    cost_snapshot     numeric(19, 4) NOT NULL DEFAULT 0,
    returned_quantity numeric(19, 4) NOT NULL DEFAULT 0
);

CREATE INDEX sale_line_sale_idx ON app.sale_line (sale_id);
CREATE INDEX sale_line_variant_idx ON app.sale_line (org_id, variant_id);

CREATE TABLE app.sale_payment (
    id              uuid PRIMARY KEY,
    org_id          uuid NOT NULL,
    sale_id         uuid NOT NULL REFERENCES app.sale (id) ON DELETE CASCADE,
    payment_method  text NOT NULL CHECK (payment_method IN ('CASH', 'CARD', 'CREDIT', 'VOUCHER', 'OTHER')),
    amount          numeric(19, 4) NOT NULL CHECK (amount > 0),
    reference       text,
    tendered_amount numeric(19, 4),
    change_amount   numeric(19, 4)
);

CREATE INDEX sale_payment_sale_idx ON app.sale_payment (sale_id);

-- ---------------------------------------------------------------------------
-- Returns, return lines and refunds (Section 10)
-- ---------------------------------------------------------------------------
CREATE TABLE app.sale_return (
    id               uuid PRIMARY KEY,
    org_id           uuid NOT NULL,
    return_number    text NOT NULL,
    original_sale_id uuid REFERENCES app.sale (id),
    shop_id          uuid NOT NULL REFERENCES app.shop (id),
    terminal_id      uuid NOT NULL REFERENCES app.terminal (id),
    shift_id         uuid REFERENCES app.shift (id),
    cashier_user_id  uuid NOT NULL REFERENCES platform.app_user (id),
    approved_by      uuid REFERENCES platform.app_user (id),
    refund_total     numeric(19, 4) NOT NULL,
    reason           text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT sale_return_number_unique UNIQUE (org_id, return_number)
);

CREATE INDEX sale_return_original_sale_idx ON app.sale_return (org_id, original_sale_id);

CREATE TABLE app.sale_return_line (
    id                    uuid PRIMARY KEY,
    org_id                uuid NOT NULL,
    return_id             uuid NOT NULL REFERENCES app.sale_return (id) ON DELETE CASCADE,
    original_sale_line_id uuid REFERENCES app.sale_line (id),
    variant_id            uuid NOT NULL REFERENCES app.variant (id),
    quantity              numeric(19, 4) NOT NULL CHECK (quantity > 0),
    refund_amount         numeric(19, 4) NOT NULL,
    -- FR-RET-003: Restockable vs damaged/defective
    condition             text NOT NULL CHECK (condition IN ('RESTOCKABLE', 'DAMAGED', 'DEFECTIVE')),
    restocked             boolean NOT NULL DEFAULT true
);

CREATE INDEX sale_return_line_return_idx ON app.sale_return_line (return_id);

CREATE TABLE app.sale_return_refund (
    id             uuid PRIMARY KEY,
    org_id         uuid NOT NULL,
    return_id      uuid NOT NULL REFERENCES app.sale_return (id) ON DELETE CASCADE,
    payment_method text NOT NULL,
    amount         numeric(19, 4) NOT NULL CHECK (amount > 0)
);

CREATE INDEX sale_return_refund_return_idx ON app.sale_return_refund (return_id);

-- ---------------------------------------------------------------------------
-- Receipt reprint audit log (FR-POS-010)
-- ---------------------------------------------------------------------------
CREATE TABLE app.receipt_reprint_log (
    id            uuid PRIMARY KEY,
    org_id        uuid NOT NULL,
    sale_id       uuid NOT NULL REFERENCES app.sale (id),
    actor_user_id uuid NOT NULL REFERENCES platform.app_user (id),
    reason        text NOT NULL,
    reprinted_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX receipt_reprint_sale_idx ON app.receipt_reprint_log (org_id, sale_id);

-- ---------------------------------------------------------------------------
-- Row-level security for all POS tables
-- ---------------------------------------------------------------------------
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'shift', 'shift_movement', 'held_cart', 'sale', 'sale_line',
        'sale_payment', 'sale_return', 'sale_return_line', 'sale_return_refund',
        'receipt_reprint_log'
    ] LOOP
        EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I ON app.%I USING (org_id = app.current_org()) '
            'WITH CHECK (org_id = app.current_org())', t || '_tenant', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON app.%I TO erp_app', t);
    END LOOP;
END
$$;

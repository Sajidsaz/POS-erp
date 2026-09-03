-- V8 — inventory, stock balances, append-only movements, adjustments, counts and transfers.
--
-- Appendix D, decision D2: moving weighted average cost per variant per shop.
-- Appendix D, decision D6: pessimistic locking on stock_balance ordered by variant_id.
-- Appendix B invariants B3, B11, B12.

-- ---------------------------------------------------------------------------
-- Stock balances: quantity on hand, reserved, in transit, and average cost.
-- ---------------------------------------------------------------------------
CREATE TABLE app.stock_balance (
    id                   uuid PRIMARY KEY,
    org_id               uuid NOT NULL,
    shop_id              uuid NOT NULL REFERENCES app.shop (id) ON DELETE CASCADE,
    variant_id           uuid NOT NULL REFERENCES app.variant (id) ON DELETE CASCADE,
    quantity_on_hand     numeric(19, 4) NOT NULL DEFAULT 0,
    quantity_reserved    numeric(19, 4) NOT NULL DEFAULT 0,
    quantity_in_transit  numeric(19, 4) NOT NULL DEFAULT 0,
    -- Decision D2: moving weighted average cost at this shop.
    average_cost         numeric(19, 4) NOT NULL DEFAULT 0,
    reorder_point        numeric(19, 4),
    reorder_quantity     numeric(19, 4),
    low_stock_threshold  numeric(19, 4),
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT stock_balance_shop_variant_unique UNIQUE (org_id, shop_id, variant_id)
);

CREATE INDEX stock_balance_shop_idx ON app.stock_balance (org_id, shop_id);
CREATE INDEX stock_balance_variant_idx ON app.stock_balance (org_id, variant_id);

-- ---------------------------------------------------------------------------
-- Stock movements: append-only ledger for all physical stock alterations.
-- FR-INV-002: stock movements MUST NOT be updated or deleted.
-- Invariant B3: stock never changes without a corresponding stock movement.
-- Invariant B12: balance always equals sum of movements.
-- ---------------------------------------------------------------------------
CREATE TABLE app.stock_movement (
    id                     uuid PRIMARY KEY,
    org_id                 uuid NOT NULL,
    shop_id                uuid NOT NULL REFERENCES app.shop (id),
    variant_id             uuid NOT NULL REFERENCES app.variant (id),
    movement_type          text NOT NULL CHECK (movement_type IN
        ('RECEIPT', 'SALE', 'RETURN', 'ADJUSTMENT', 'COUNT_CORRECTION', 'TRANSFER_OUT', 'TRANSFER_IN', 'WRITE_OFF')),
    quantity               numeric(19, 4) NOT NULL,
    unit_cost              numeric(19, 4) NOT NULL DEFAULT 0,
    total_cost             numeric(19, 4) NOT NULL DEFAULT 0,
    resulting_balance      numeric(19, 4) NOT NULL,
    resulting_average_cost numeric(19, 4) NOT NULL DEFAULT 0,
    reference_type         text NOT NULL,
    reference_id           text,
    reason                 text,
    actor_user_id          uuid REFERENCES platform.app_user (id),
    created_at             timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX stock_movement_shop_variant_time_idx
    ON app.stock_movement (org_id, shop_id, variant_id, created_at DESC);
CREATE INDEX stock_movement_reference_idx
    ON app.stock_movement (org_id, reference_type, reference_id);

-- ---------------------------------------------------------------------------
-- Stock adjustments: manual changes, write-offs, or ad-hoc corrections.
-- ---------------------------------------------------------------------------
CREATE TABLE app.stock_adjustment (
    id            uuid PRIMARY KEY,
    org_id        uuid NOT NULL,
    shop_id       uuid NOT NULL REFERENCES app.shop (id),
    reason        text NOT NULL,
    notes         text,
    created_by    uuid NOT NULL REFERENCES platform.app_user (id),
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app.stock_adjustment_line (
    id             uuid PRIMARY KEY,
    org_id         uuid NOT NULL,
    adjustment_id  uuid NOT NULL REFERENCES app.stock_adjustment (id) ON DELETE CASCADE,
    variant_id     uuid NOT NULL REFERENCES app.variant (id),
    quantity_delta numeric(19, 4) NOT NULL,
    unit_cost      numeric(19, 4) NOT NULL DEFAULT 0,
    reason         text
);

CREATE INDEX stock_adjustment_shop_idx ON app.stock_adjustment (org_id, shop_id);
CREATE INDEX stock_adjustment_line_adj_idx ON app.stock_adjustment_line (adjustment_id);

-- ---------------------------------------------------------------------------
-- Stock counts: physical stock audit and count variance resolution.
-- FR-INV-006: records counted, system qty at moment of count, variance & approving user.
-- ---------------------------------------------------------------------------
CREATE TABLE app.stock_count (
    id            uuid PRIMARY KEY,
    org_id        uuid NOT NULL,
    shop_id       uuid NOT NULL REFERENCES app.shop (id),
    status        text NOT NULL CHECK (status IN ('DRAFT', 'COMPLETED', 'APPROVED', 'CANCELLED')),
    notes         text,
    created_by    uuid NOT NULL REFERENCES platform.app_user (id),
    approved_by   uuid REFERENCES platform.app_user (id),
    started_at    timestamptz NOT NULL DEFAULT now(),
    completed_at  timestamptz,
    approved_at   timestamptz
);

CREATE TABLE app.stock_count_line (
    id               uuid PRIMARY KEY,
    org_id           uuid NOT NULL,
    count_id         uuid NOT NULL REFERENCES app.stock_count (id) ON DELETE CASCADE,
    variant_id       uuid NOT NULL REFERENCES app.variant (id),
    system_quantity  numeric(19, 4) NOT NULL,
    counted_quantity numeric(19, 4) NOT NULL,
    variance         numeric(19, 4) NOT NULL,
    unit_cost        numeric(19, 4) NOT NULL DEFAULT 0,
    notes            text
);

CREATE INDEX stock_count_shop_idx ON app.stock_count (org_id, shop_id);
CREATE INDEX stock_count_line_count_idx ON app.stock_count_line (count_id);

-- ---------------------------------------------------------------------------
-- Inter-shop transfers.
-- FR-INV-009 / FR-INV-010 / FR-INV-011 / FR-INV-012.
-- ---------------------------------------------------------------------------
CREATE TABLE app.stock_transfer (
    id                  uuid PRIMARY KEY,
    org_id              uuid NOT NULL,
    transfer_number     text NOT NULL,
    source_shop_id      uuid NOT NULL REFERENCES app.shop (id),
    destination_shop_id uuid NOT NULL REFERENCES app.shop (id),
    status              text NOT NULL CHECK (status IN
        ('DRAFT', 'DISPATCHED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED')),
    dispatched_by       uuid REFERENCES platform.app_user (id),
    dispatched_at       timestamptz,
    received_by         uuid REFERENCES platform.app_user (id),
    received_at         timestamptz,
    notes               text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT stock_transfer_number_unique UNIQUE (org_id, transfer_number),
    CONSTRAINT stock_transfer_shops_different CHECK (source_shop_id <> destination_shop_id)
);

CREATE TABLE app.stock_transfer_line (
    id                  uuid PRIMARY KEY,
    org_id              uuid NOT NULL,
    transfer_id         uuid NOT NULL REFERENCES app.stock_transfer (id) ON DELETE CASCADE,
    variant_id          uuid NOT NULL REFERENCES app.variant (id),
    dispatched_quantity numeric(19, 4) NOT NULL CHECK (dispatched_quantity > 0),
    received_quantity   numeric(19, 4) NOT NULL DEFAULT 0 CHECK (received_quantity >= 0),
    unit_cost           numeric(19, 4) NOT NULL DEFAULT 0,
    discrepancy_reason  text
);

CREATE INDEX stock_transfer_source_idx ON app.stock_transfer (org_id, source_shop_id);
CREATE INDEX stock_transfer_dest_idx ON app.stock_transfer (org_id, destination_shop_id);
CREATE INDEX stock_transfer_line_transfer_idx ON app.stock_transfer_line (transfer_id);

-- ---------------------------------------------------------------------------
-- Row-level security for all inventory tables.
-- ---------------------------------------------------------------------------
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'stock_balance', 'stock_movement', 'stock_adjustment', 'stock_adjustment_line',
        'stock_count', 'stock_count_line', 'stock_transfer', 'stock_transfer_line'
    ] LOOP
        EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I ON app.%I USING (org_id = app.current_org()) '
            'WITH CHECK (org_id = app.current_org())', t || '_tenant', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON app.%I TO erp_app', t);
    END LOOP;
END
$$;

-- AUD-006 / FR-INV-002: stock movements are append-only.
REVOKE UPDATE, DELETE ON app.stock_movement FROM erp_app;

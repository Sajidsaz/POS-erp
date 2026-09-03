-- V6 — catalog: categories, units, products, variants and barcodes.
--
-- The shape here is set by FR-CAT-008/009/010. The variant, not the product, is the
-- stock-keeping entity, and a product declared without variants still gets exactly one
-- implicit variant row. Everything downstream — stock balances, movements, sale lines,
-- purchase lines, transfers, counts — references variant_id and never product_id, so no
-- code path has to ask "does this product have variants?".

CREATE TABLE app.category (
    id          uuid PRIMARY KEY,
    org_id      uuid NOT NULL,
    parent_id   uuid REFERENCES app.category (id),
    code        text NOT NULL,
    name        text NOT NULL,
    active      boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT category_code_unique UNIQUE (org_id, code)
);

CREATE TABLE app.unit (
    id          uuid PRIMARY KEY,
    org_id      uuid NOT NULL,
    code        text NOT NULL,
    name        text NOT NULL,
    -- Whole units only: 'each', 'box'. Bars this unit from fractional quantities.
    integral    boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT unit_code_unique UNIQUE (org_id, code)
);

CREATE TABLE app.product (
    id                uuid PRIMARY KEY,
    org_id            uuid NOT NULL,
    sku               text NOT NULL,
    name              text NOT NULL,
    description       text,
    category_id       uuid REFERENCES app.category (id),
    -- FR-CAT-011: the unit stock is held in. Conversions are defined against this.
    stocking_unit_id  uuid NOT NULL REFERENCES app.unit (id),
    tax_class_id      uuid,                       -- FK added in V7, once tax_class exists
    -- FR-CAT-004: a service or non-stock item must never produce a stock movement.
    stocked           boolean NOT NULL DEFAULT true,
    -- False when the product was created without explicit variants. The implicit variant
    -- still exists; this only tells the UI whether to show a variant picker.
    has_variants      boolean NOT NULL DEFAULT false,
    -- FR-CAT-005
    status            text NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    -- FR-CAT-006, DB-003
    CONSTRAINT product_sku_unique UNIQUE (org_id, sku)
);

-- The axes a product varies along: size, colour, pack. Ordered for display.
CREATE TABLE app.variant_axis (
    id          uuid PRIMARY KEY,
    org_id      uuid NOT NULL,
    product_id  uuid NOT NULL REFERENCES app.product (id) ON DELETE CASCADE,
    name        text NOT NULL,
    position    integer NOT NULL DEFAULT 1,
    CONSTRAINT variant_axis_unique UNIQUE (product_id, name)
);

CREATE TABLE app.variant (
    id             uuid PRIMARY KEY,
    org_id         uuid NOT NULL,
    product_id     uuid NOT NULL REFERENCES app.product (id) ON DELETE CASCADE,
    -- FR-CAT-010: its own SKU, cost and price, inherited from the product where null.
    sku            text NOT NULL,
    name           text,
    -- {"Size": "M", "Colour": "Red"}; empty for the implicit variant.
    axis_values    jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- Decision D2: moving weighted average, recalculated on each receipt. Held per
    -- variant here as the organization-wide default; the per-shop figure lives on the
    -- stock balance in M2.
    average_cost   numeric(19, 4) NOT NULL DEFAULT 0,
    base_price     numeric(19, 4),
    -- True for the single variant auto-created for a product with no declared axes.
    is_default     boolean NOT NULL DEFAULT false,
    active         boolean NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT variant_sku_unique UNIQUE (org_id, sku)
);

CREATE INDEX variant_product_idx ON app.variant (org_id, product_id);
-- Exactly one implicit variant per product; explicit variants are unconstrained in number.
CREATE UNIQUE INDEX variant_one_default_per_product
    ON app.variant (product_id) WHERE is_default;

-- FR-CAT-003: several barcodes per variant is normal — a retail pack and its inner unit
-- often scan differently, and old stock keeps circulating after a barcode changes.
CREATE TABLE app.barcode (
    id          uuid PRIMARY KEY,
    org_id      uuid NOT NULL,
    variant_id  uuid NOT NULL REFERENCES app.variant (id) ON DELETE CASCADE,
    code        text NOT NULL,
    is_primary  boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- FR-CAT-006: a barcode must resolve to exactly one variant within the organization,
    -- or scanning at the till is ambiguous.
    CONSTRAINT barcode_unique UNIQUE (org_id, code)
);

CREATE INDEX barcode_variant_idx ON app.barcode (org_id, variant_id);

-- FR-CAT-011. factor_to_stocking is how many stocking units one of this unit contains:
-- a CASE of 24 EACH has factor 24. Receiving 3 CASE writes a movement of 72 EACH, and
-- records both figures so the document still reads the way it was entered.
CREATE TABLE app.unit_conversion (
    id                  uuid PRIMARY KEY,
    org_id              uuid NOT NULL,
    product_id          uuid NOT NULL REFERENCES app.product (id) ON DELETE CASCADE,
    unit_id             uuid NOT NULL REFERENCES app.unit (id),
    factor_to_stocking  numeric(19, 6) NOT NULL CHECK (factor_to_stocking > 0),
    purchasable         boolean NOT NULL DEFAULT true,
    sellable            boolean NOT NULL DEFAULT false,
    CONSTRAINT unit_conversion_unique UNIQUE (product_id, unit_id)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['category', 'unit', 'product', 'variant_axis', 'variant',
                             'barcode', 'unit_conversion'] LOOP
        EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I ON app.%I USING (org_id = app.current_org()) '
            'WITH CHECK (org_id = app.current_org())', t || '_tenant', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON app.%I TO erp_app', t);
    END LOOP;
END
$$;

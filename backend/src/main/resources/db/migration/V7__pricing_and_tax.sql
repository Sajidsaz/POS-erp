-- V7 — tax classes, tax rates and price lists.
--
-- FR-PRICE-005 is the requirement that shapes this file: a completed transaction must
-- always reproduce the rate that applied when it completed. So a rate is never edited in
-- place; it is superseded by a new row with a later effective_from, and the old row keeps
-- its history. Acceptance scenario 17 is the assertion that this works.

CREATE TABLE app.tax_class (
    id          uuid PRIMARY KEY,
    org_id      uuid NOT NULL,
    code        text NOT NULL,
    name        text NOT NULL,
    active      boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tax_class_code_unique UNIQUE (org_id, code)
);

CREATE TABLE app.tax_rate (
    id             uuid PRIMARY KEY,
    org_id         uuid NOT NULL,
    tax_class_id   uuid NOT NULL REFERENCES app.tax_class (id) ON DELETE CASCADE,
    -- 0.1800 is 18%. Four decimals covers rates quoted to two decimal places.
    rate           numeric(9, 4) NOT NULL CHECK (rate >= 0),
    effective_from date NOT NULL,
    -- NULL means "still in force". Closed off when a successor is added.
    effective_to   date,
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid REFERENCES platform.app_user (id),
    CONSTRAINT tax_rate_period_valid CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- Two rates for the same class must not overlap in time, or the rate on a given date is
-- ambiguous and FR-PRICE-005 becomes unenforceable. An exclusion constraint says this
-- once, in the database, instead of in every code path that inserts a rate.
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE app.tax_rate ADD CONSTRAINT tax_rate_no_overlap
    EXCLUDE USING gist (
        tax_class_id WITH =,
        daterange(effective_from, effective_to, '[)') WITH &&
    );

CREATE INDEX tax_rate_lookup_idx ON app.tax_rate (org_id, tax_class_id, effective_from DESC);

-- Deferred from V6: products could not reference a tax class before it existed.
ALTER TABLE app.product
    ADD CONSTRAINT product_tax_class_fk FOREIGN KEY (tax_class_id) REFERENCES app.tax_class (id);
ALTER TABLE app.shop
    ADD CONSTRAINT shop_default_tax_class_fk FOREIGN KEY (default_tax_class_id) REFERENCES app.tax_class (id);

-- ---------------------------------------------------------------------------
-- Price lists. FR-PRICE-007 fixes the resolution order:
--   customer-specific price -> customer-group price list -> shop price list -> base price
-- M1 builds the last two; the customer legs arrive in M4 with customers and groups.
-- ---------------------------------------------------------------------------
CREATE TABLE app.price_list (
    id           uuid PRIMARY KEY,
    org_id       uuid NOT NULL,
    code         text NOT NULL,
    name         text NOT NULL,
    -- Decision D4: base prices are tax-exclusive, with an inclusive flag per price list.
    -- Where this is true, the exclusive base is derived on entry and stored, so downstream
    -- tax arithmetic has exactly one shape regardless of how the price was quoted.
    tax_inclusive boolean NOT NULL DEFAULT false,
    currency     text NOT NULL DEFAULT 'LKR',
    active       boolean NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT price_list_code_unique UNIQUE (org_id, code)
);

CREATE TABLE app.price (
    id             uuid PRIMARY KEY,
    org_id         uuid NOT NULL,
    price_list_id  uuid NOT NULL REFERENCES app.price_list (id) ON DELETE CASCADE,
    variant_id     uuid NOT NULL REFERENCES app.variant (id) ON DELETE CASCADE,
    -- Always tax-exclusive at rest, whatever the list's quoting convention.
    amount         numeric(19, 4) NOT NULL CHECK (amount >= 0),
    effective_from date NOT NULL DEFAULT CURRENT_DATE,
    effective_to   date,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT price_period_valid CHECK (effective_to IS NULL OR effective_to > effective_from)
);

ALTER TABLE app.price ADD CONSTRAINT price_no_overlap
    EXCLUDE USING gist (
        price_list_id WITH =,
        variant_id WITH =,
        daterange(effective_from, effective_to, '[)') WITH &&
    );

CREATE INDEX price_lookup_idx ON app.price (org_id, price_list_id, variant_id);

CREATE TABLE app.shop_price_list (
    org_id        uuid NOT NULL,
    shop_id       uuid NOT NULL REFERENCES app.shop (id) ON DELETE CASCADE,
    price_list_id uuid NOT NULL REFERENCES app.price_list (id) ON DELETE CASCADE,
    PRIMARY KEY (shop_id)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['tax_class', 'tax_rate', 'price_list', 'price', 'shop_price_list'] LOOP
        EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I ON app.%I USING (org_id = app.current_org()) '
            'WITH CHECK (org_id = app.current_org())', t || '_tenant', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON app.%I TO erp_app', t);
    END LOOP;
END
$$;

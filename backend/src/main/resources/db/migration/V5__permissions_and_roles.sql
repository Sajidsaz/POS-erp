-- V5 — the permission catalogue and the Section 5.2 capability matrix, as data.
--
-- The matrix in the SRS stops being a table someone reads and starts being the thing the
-- server enforces. FR-USER-002 requires roles to be configurable from a permission
-- catalogue; this is that catalogue, plus the eight standard roles seeded from the matrix.
--
-- Platform Operator and Integration Client are absent on purpose. Neither is a tenant role:
-- the first is a platform principal with no tenant context (FR-PLAT-001), the second is
-- authorised by token scopes (Section 14.4, FR-PERM-003).

-- Reference data, identical for every tenant, so it lives outside the RLS schema.
CREATE TABLE platform.permission (
    key         text PRIMARY KEY,
    name        text NOT NULL,
    description text NOT NULL
);

INSERT INTO platform.permission (key, name, description) VALUES
 ('dashboard',               'Dashboard',                'Operational dashboard and summary figures.'),
 ('pos.sales',               'POS sales',                'Ring up and complete sales at a terminal.'),
 ('returns',                 'Returns and refunds',      'Raise, approve and refund returns and exchanges.'),
 ('products',                'Products and prices',      'Catalog, variants, barcodes and selling prices.'),
 ('product.cost',            'Product cost visibility',  'See product cost. FR-PERM-004 keeps this separate from price.'),
 ('price.override',          'Price override at POS',    'Discount or override a price during checkout.'),
 ('inventory.adjustments',   'Inventory adjustments',    'Stock adjustments and counts.'),
 ('stock.transfers',         'Stock transfers',          'Inter-shop transfers, dispatch and receipt.'),
 ('negative_stock.override', 'Negative-stock override',  'Authorise a sale that takes stock below zero.'),
 ('purchasing',              'Purchasing',               'Suppliers, purchase orders and receiving.'),
 ('customers',               'Customers',                'Customer profiles and groups.'),
 ('customer.credit_limits',  'Customer credit limits',   'Set or override a credit limit.'),
 ('expenses',                'Expenses',                 'Expense records and categories.'),
 ('employees',               'Employees and HR',         'Employee records, attendance and leave.'),
 ('payroll',                 'Payroll',                  'Payroll runs and payslips.'),
 ('users_roles',             'Users and roles',          'User accounts, roles and permission grants.'),
 ('settings',                'Settings',                 'Organization, shop and terminal configuration.'),
 ('reports',                 'Reports',                  'Operational and financial reports and exports.'),
 ('audit_log',               'Audit log',                'Read the audit trail.'),
 ('api_tokens',              'API tokens',               'Issue, scope and revoke integration tokens.'),
 ('service.health',          'Service health',           'Operational health without customer data.');

-- Grant levels, straight from the Section 5.1 legend.
CREATE TYPE platform.grant_level AS ENUM ('RW', 'R', 'LTD', 'APV');

CREATE TABLE platform.role_template (
    code        text PRIMARY KEY,
    name        text NOT NULL,
    sort_order  integer NOT NULL
);

INSERT INTO platform.role_template (code, name, sort_order) VALUES
 ('OWNER',   'Tenant Owner',         1),
 ('ADMIN',   'Tenant Administrator', 2),
 ('MANAGER', 'Shop Manager',         3),
 ('CASHIER', 'Cashier',              4),
 ('STOCK',   'Stock Manager',        5),
 ('FINANCE', 'Finance User',         6),
 ('HR',      'HR User',              7),
 ('VIEWER',  'Auditor/Viewer',       8);

CREATE TABLE platform.role_template_permission (
    role_code      text NOT NULL REFERENCES platform.role_template (code),
    permission_key text NOT NULL REFERENCES platform.permission (key),
    level          platform.grant_level NOT NULL,
    PRIMARY KEY (role_code, permission_key)
);

-- Section 5.2, transcribed. A cell that is '–' in the matrix is simply absent here:
-- no row means no grant, which is how FR-PERM-001 fails closed.
INSERT INTO platform.role_template_permission (role_code, permission_key, level) VALUES
 -- Owner
 ('OWNER','dashboard','RW'),('OWNER','pos.sales','RW'),('OWNER','returns','RW'),
 ('OWNER','products','RW'),('OWNER','product.cost','RW'),('OWNER','price.override','RW'),
 ('OWNER','inventory.adjustments','RW'),('OWNER','stock.transfers','RW'),
 ('OWNER','negative_stock.override','RW'),('OWNER','purchasing','RW'),
 ('OWNER','customers','RW'),('OWNER','customer.credit_limits','RW'),('OWNER','expenses','RW'),
 ('OWNER','employees','RW'),('OWNER','payroll','RW'),('OWNER','users_roles','RW'),
 ('OWNER','settings','RW'),('OWNER','reports','RW'),('OWNER','audit_log','R'),
 ('OWNER','api_tokens','RW'),('OWNER','service.health','R'),
 -- Administrator
 ('ADMIN','dashboard','RW'),('ADMIN','pos.sales','RW'),('ADMIN','returns','RW'),
 ('ADMIN','products','RW'),('ADMIN','product.cost','RW'),('ADMIN','price.override','RW'),
 ('ADMIN','inventory.adjustments','RW'),('ADMIN','stock.transfers','RW'),
 ('ADMIN','negative_stock.override','RW'),('ADMIN','purchasing','RW'),
 ('ADMIN','customers','RW'),('ADMIN','customer.credit_limits','RW'),('ADMIN','expenses','RW'),
 ('ADMIN','employees','RW'),('ADMIN','payroll','LTD'),('ADMIN','users_roles','RW'),
 ('ADMIN','settings','RW'),('ADMIN','reports','RW'),('ADMIN','audit_log','R'),
 ('ADMIN','api_tokens','RW'),('ADMIN','service.health','R'),
 -- Shop Manager
 ('MANAGER','dashboard','RW'),('MANAGER','pos.sales','RW'),('MANAGER','returns','APV'),
 ('MANAGER','products','RW'),('MANAGER','product.cost','R'),('MANAGER','price.override','RW'),
 ('MANAGER','inventory.adjustments','APV'),('MANAGER','stock.transfers','APV'),
 ('MANAGER','negative_stock.override','RW'),('MANAGER','purchasing','RW'),
 ('MANAGER','customers','RW'),('MANAGER','customer.credit_limits','APV'),
 ('MANAGER','expenses','APV'),('MANAGER','employees','R'),('MANAGER','users_roles','R'),
 ('MANAGER','settings','R'),('MANAGER','reports','RW'),('MANAGER','audit_log','LTD'),
 -- Cashier
 ('CASHIER','dashboard','R'),('CASHIER','pos.sales','RW'),('CASHIER','returns','LTD'),
 ('CASHIER','products','R'),('CASHIER','price.override','LTD'),
 ('CASHIER','customers','RW'),('CASHIER','customer.credit_limits','R'),
 ('CASHIER','reports','LTD'),
 -- Stock Manager
 ('STOCK','dashboard','RW'),('STOCK','pos.sales','R'),('STOCK','products','RW'),
 ('STOCK','product.cost','RW'),('STOCK','inventory.adjustments','RW'),
 ('STOCK','stock.transfers','RW'),('STOCK','negative_stock.override','RW'),
 ('STOCK','purchasing','RW'),('STOCK','customers','R'),('STOCK','reports','RW'),
 -- Finance User
 ('FINANCE','dashboard','RW'),('FINANCE','pos.sales','R'),('FINANCE','returns','APV'),
 ('FINANCE','products','R'),('FINANCE','product.cost','R'),
 ('FINANCE','inventory.adjustments','R'),('FINANCE','stock.transfers','R'),
 ('FINANCE','purchasing','RW'),('FINANCE','customers','RW'),
 ('FINANCE','customer.credit_limits','RW'),('FINANCE','expenses','RW'),
 ('FINANCE','settings','R'),('FINANCE','reports','RW'),('FINANCE','audit_log','R'),
 -- HR User
 ('HR','dashboard','R'),('HR','employees','RW'),('HR','payroll','RW'),
 ('HR','settings','R'),('HR','reports','RW'),
 -- Auditor/Viewer
 ('VIEWER','dashboard','R'),('VIEWER','pos.sales','R'),('VIEWER','returns','R'),
 ('VIEWER','products','R'),('VIEWER','product.cost','R'),
 ('VIEWER','inventory.adjustments','R'),('VIEWER','stock.transfers','R'),
 ('VIEWER','purchasing','R'),('VIEWER','customers','R'),
 ('VIEWER','customer.credit_limits','R'),('VIEWER','expenses','R'),
 ('VIEWER','employees','R'),('VIEWER','users_roles','R'),('VIEWER','settings','R'),
 ('VIEWER','reports','R'),('VIEWER','audit_log','R'),('VIEWER','api_tokens','R');

GRANT SELECT ON platform.permission, platform.role_template,
                platform.role_template_permission TO erp_app;

-- ---------------------------------------------------------------------------
-- Per-organization roles. Seeded from the templates when an organization is
-- provisioned, then editable per FR-USER-002 without disturbing other tenants.
-- ---------------------------------------------------------------------------
CREATE TABLE app.role (
    id          uuid PRIMARY KEY,
    org_id      uuid NOT NULL,
    code        text NOT NULL,
    name        text NOT NULL,
    -- A system role may be granted but not deleted; renaming is allowed.
    is_system   boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT role_code_unique UNIQUE (org_id, code)
);

CREATE TABLE app.role_permission (
    org_id         uuid NOT NULL,
    role_id        uuid NOT NULL REFERENCES app.role (id) ON DELETE CASCADE,
    permission_key text NOT NULL REFERENCES platform.permission (key),
    level          platform.grant_level NOT NULL,
    PRIMARY KEY (role_id, permission_key)
);

CREATE TABLE app.user_role (
    org_id      uuid NOT NULL,
    user_id     uuid NOT NULL REFERENCES platform.app_user (id) ON DELETE CASCADE,
    role_id     uuid NOT NULL REFERENCES app.role (id) ON DELETE CASCADE,
    granted_at  timestamptz NOT NULL DEFAULT now(),
    granted_by  uuid REFERENCES platform.app_user (id),
    PRIMARY KEY (user_id, role_id)
);

-- FR-ORG-003. A user with no row here sees no shop-scoped data at all.
CREATE TABLE app.user_shop (
    org_id     uuid NOT NULL,
    user_id    uuid NOT NULL REFERENCES platform.app_user (id) ON DELETE CASCADE,
    shop_id    uuid NOT NULL REFERENCES app.shop (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, shop_id)
);

CREATE INDEX role_permission_role_idx ON app.role_permission (role_id);
CREATE INDEX user_role_user_idx ON app.user_role (org_id, user_id);
CREATE INDEX user_shop_user_idx ON app.user_shop (org_id, user_id);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['role', 'role_permission', 'user_role', 'user_shop'] LOOP
        EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I ON app.%I USING (org_id = app.current_org()) '
            'WITH CHECK (org_id = app.current_org())', t || '_tenant', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON app.%I TO erp_app', t);
    END LOOP;
END
$$;

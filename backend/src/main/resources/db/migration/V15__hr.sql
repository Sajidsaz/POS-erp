-- V15 — Human resources: employees, attendance and leave (Section 11, FR-HR-001..003, 006).
--
-- Payroll (FR-HR-004) is a MAY and not an R1 blocker, so it is deliberately absent here.
-- FR-HR-006: an employee record links to at most one user account and vice versa, so shift
-- and till activity resolve to a single person rather than tracking them twice.

CREATE TABLE app.employee (
    id              uuid PRIMARY KEY,
    org_id          uuid NOT NULL,
    code            text NOT NULL,
    full_name       text NOT NULL,
    title           text,
    employment_type text,
    phone           text,
    email           text,
    -- Optional link to the login this person uses (FR-HR-006).
    user_id         uuid REFERENCES platform.app_user (id),
    hired_on        date,
    active          boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT employee_code_unique UNIQUE (org_id, code)
);

-- FR-HR-006: a user account maps to at most one employee.
CREATE UNIQUE INDEX employee_user_unique ON app.employee (org_id, user_id) WHERE user_id IS NOT NULL;

CREATE TABLE app.attendance (
    id           uuid PRIMARY KEY,
    org_id       uuid NOT NULL,
    employee_id  uuid NOT NULL REFERENCES app.employee (id) ON DELETE CASCADE,
    work_date    date NOT NULL,
    check_in_at  timestamptz,
    check_out_at timestamptz,
    -- SELF is a live check-in/out; MANUAL is an after-the-fact entry that needs approval.
    source       text NOT NULL CHECK (source IN ('SELF', 'MANUAL')),
    status       text NOT NULL CHECK (status IN ('RECORDED', 'PENDING', 'APPROVED', 'REJECTED')),
    approved_by  uuid REFERENCES platform.app_user (id),
    notes        text,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX attendance_employee_idx ON app.attendance (org_id, employee_id, work_date DESC);

CREATE TABLE app.leave_entitlement (
    id            uuid PRIMARY KEY,
    org_id        uuid NOT NULL,
    employee_id   uuid NOT NULL REFERENCES app.employee (id) ON DELETE CASCADE,
    leave_type    text NOT NULL,
    entitled_days numeric(5, 1) NOT NULL DEFAULT 0 CHECK (entitled_days >= 0),
    CONSTRAINT leave_entitlement_unique UNIQUE (org_id, employee_id, leave_type)
);

CREATE TABLE app.leave_request (
    id          uuid PRIMARY KEY,
    org_id      uuid NOT NULL,
    employee_id uuid NOT NULL REFERENCES app.employee (id) ON DELETE CASCADE,
    leave_type  text NOT NULL,
    start_date  date NOT NULL,
    end_date    date NOT NULL,
    days        numeric(5, 1) NOT NULL CHECK (days > 0),
    reason      text,
    status      text NOT NULL CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    decided_by  uuid REFERENCES platform.app_user (id),
    decided_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT leave_dates_valid CHECK (end_date >= start_date)
);

CREATE INDEX leave_request_employee_idx ON app.leave_request (org_id, employee_id, start_date DESC);

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['employee', 'attendance', 'leave_entitlement', 'leave_request'] LOOP
        EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I ON app.%I USING (org_id = app.current_org()) '
            'WITH CHECK (org_id = app.current_org())', t || '_tenant', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON app.%I TO erp_app', t);
    END LOOP;
END
$$;

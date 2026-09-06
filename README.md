# HeySaz Commercial ERP

Multi-tenant retail ERP and POS. Built against [`docs/HeySaz_ERP_SRS_v3.1.docx`](docs/) —
requirement IDs in code comments (`FR-…`, `SEC-…`, `DB-…`) refer to that document, and
`Appendix D` decisions are referenced as `D1`–`D8`.

## Status

**HR — employees, attendance, leave** (complete). Employee records with an optional link to
exactly one user account (FR-HR-006, so till and shift activity resolve to one person),
live check-in/out with after-the-fact manual entries held for approval (FR-HR-002), and
leave requests approved or rejected against a per-type balance (FR-HR-003), under
`/api/v1/hr`. Payroll (FR-HR-004) is a MAY and out of R1, so it is deliberately not built.

**M6 — hardening** (in progress). Multi-factor authentication (FR-AUTH-005 / FR-AUTH-007) is
done: TOTP (RFC 6238) enrollment in two steps — a secret an authenticator app imports, then a
confirming code before MFA turns on, so a mistyped setup can't lock an account out — with
single-use recovery codes stored only as bcrypt hashes. Login gains a second-factor gate:
once MFA is enabled, a correct password returns `MFA_REQUIRED` until a valid TOTP or recovery
code is supplied, and an organization can make MFA mandatory for its privileged (Owner /
Administrator) accounts. Managed under `/api/v1/auth/mfa`. Still to come in M6: load tests,
backup/restore drills, and the R1 exit gate (largely operational rather than application code).

**M5 — downstream** (complete).

*Reporting and exports* — sales summary (with gross profit read from each sale line's cost
snapshot, decision D2, never recomputed from today's average), sales-by-day, top products,
payment mix, and inventory valuation at moving-average cost, plus a CSV export of
sales-by-day. All read-only aggregates over the same RLS-bound tables, under
`/api/v1/reports`. (Day boundaries fall on the UTC date for now; per-shop timezone
boundaries per DB-005 are a tracked refinement.)

*Signed webhook delivery* (FR-API-002 / FR-API-003) — a tenant subscribes an endpoint to
event types, and the outbox worker delivers each committed event signed with HMAC-SHA256
over `<timestamp>.<body>` (timestamp inside the signature defeats replay). Delivery reuses
the worker's bounded exponential backoff; every `(subscription, event)` pair gets one
delivery record, so a retry skips subscribers that already succeeded and never
double-delivers, and the final attempt moves a still-failing delivery to a visible `DEAD`
state. Managed under `/api/v1/integrations/webhooks`.

*Notifications and scheduled jobs* — in-app notifications with an email seam that activates
only where a provider is configured (FR-NOT-002) and per-user, per-type, per-channel
preferences (FR-NOT-003), under `/api/v1/notifications`. A reorder-alert sweep (FR-INV-013)
raises one alert per variant that has fallen to or below its reorder point, de-duplicated
against still-unread alerts; a cross-tenant scheduler runs it per tenant through
`runAsOrg` on the RLS-bound pool, listing tenants via a small platform directory that is the
only new use of the elevated connection.

M5 backend is essentially complete.

**M4 — commerce** (backend complete; POS Tauri client still to come).

*Returns, refunds and exchanges* — a restockable return goes back into stock with its own
`RETURN` movement (FR-RET-003) while damaged and defective goods are recorded but not
restocked; a return against a known sale is capped at what was sold and moves the sale to
`PARTIALLY_RETURNED` or `RETURNED`; refund tenders must reconcile to the line refund total;
and an exchange is a return and a sale in one transaction, reporting the net the customer
settles.

*Purchasing and receiving* — suppliers, purchase orders, and goods receipts. A receipt
lands stock through the inventory module's `RECEIPT` movement, so the moving weighted
average (decision D2) is maintained in one place; a purchase order tracks received quantity
and moves to `PARTIALLY_RECEIVED` / `RECEIVED`, over-receipt is refused, and direct (blind)
receipts without an order are supported. Gapless PO and GRN numbers (D5) and idempotent
retries (FR-API-012) throughout.

*Customers and store credit* — customer accounts with a credit limit and an append-only
ledger the on-row balance can be reconstructed from. An on-account `CREDIT` tender at the
till charges the customer inside the checkout transaction and refuses to breach the limit,
so a rejected charge unwinds the whole sale; payments settle the balance. Charges and
payments lock the customer row (decision D6 applied to credit) so concurrent sales cannot
push a customer past their limit.

Still to come in M4: the Tauri POS desktop client.

**M3 — POS & checkout** (complete). Cart pricing and checkout with per-line tax rounding, tax-inclusive base derivation and nearest-rupee cash rounding on its own line (decision D4 / FR-POS-014); gapless SALE numbers (decision D5); stock decremented under a pessimistic lock taken in variant order (decision D6) with a cost snapshot on every sale line (decision D2 / invariant B7); multi-tender payments, held carts, receipts with a per-rate tax breakdown, and audited reprints. Cashier shifts with cash-drawer movements and derived X/Z cash reconciliation (Section 7.3). Retried checkouts replay rather than ringing a second sale (FR-API-012). The gapless-sequence service moved into the platform kernel (`platform/sequence`) so POS, transfers and future purchasing share one implementation. The Tauri POS shell is deferred to M4 alongside its first offline concerns.

**M2 — inventory** (complete). Stock balances per shop and variant, append-only stock movements, adjustments, physical stock counts with reconciliation, inter-shop transfers with in-transit tracking and discrepancy handling, gapless document sequence numbers (decision D5), and moving weighted average costing (decision D2). Eight migrations, five modules.

**M1 — master data.** Shops and terminals, the Section 5.2 permission matrix as enforced
data, and the catalog with variants, units, barcodes, price lists and effective-dated tax
rates. Seven migrations, four modules, 28 tests green.

**M0 — walking skeleton** (complete). No user-facing feature shipped in M0, by design. It
exists to make the four decisions that are ruinous to retrofit true and enforced before
any module is written on top of them:

| Decision | What M0 establishes |
|---|---|
| D1 Tenant isolation | Shared schema, `org_id` on every tenant table, PostgreSQL RLS, GUC bound per transaction |
| D3 Authentication | Opaque server-side sessions and tokens; all three principal types resolve to one `Principal` |
| D4 Money | `Money` at scale 4, `numeric(19,4)` storage, string on the wire |
| FR-API-011/012 | Idempotency keyed and stored in the same transaction as the effect |

Plus the transactional outbox (FR-API-005), append-only audit (AUD-006), the error
envelope (API-STD-002), and the tests that keep all of it honest.

## Running it

```bash
docker compose up -d
```

```bash
cd backend && ./gradlew bootRun
```

Flyway migrates on startup. The app listens on `:8080`; health is at
`/actuator/health`.

## Tests

```bash
cd backend && ./gradlew test
```

Integration tests use Testcontainers, so **Docker must be running**. They start a real
PostgreSQL because RLS, `SET LOCAL`, `ON CONFLICT DO NOTHING` and `FOR UPDATE SKIP LOCKED`
are the load-bearing mechanics here and none of them exists in an in-memory database
(TEST-002).

If the host Gradle fails with `Unable to establish loopback connection` — a Windows
firewall interaction with the Gradle daemon, not a project problem — run the build in a
container instead. Mounting the Docker socket lets Testcontainers start sibling containers,
so the full suite still runs:

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W)/backend:/app" -w /app -v heysaz-gradle-cache:/home/gradle/.gradle -v //var/run/docker.sock:/var/run/docker.sock --add-host=host.docker.internal:host-gateway -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true gradle:8.12-jdk21 gradle test --no-daemon --console=plain
```

The four that matter most:

- **`ConnectionGucLeakTest`** — proves `app.org_id` is bound inside the transaction and
  does not survive into the next borrower of a pooled connection. If this regresses,
  every RLS policy in the schema quietly stops protecting anything.
- **`TenantIsolationTest`** — SEC-015. Note that no SQL in `ExpenseCategoryRepository`
  carries an org predicate; isolation is the database's job, and this is the proof.
- **`EndpointAuthenticationTest`** — walks Spring's mapping registry rather than a
  hand-maintained list, so a new controller is covered the moment it exists.
- **`ModuleBoundaryTest`** — ArchUnit. Module internals stay private, the kernel depends
  on no business module, and the RLS escape hatch stays confined to the two places that
  justify it.

## Layout

```
backend/          Spring Boot, Java 21. One deployable, package-per-module.
  platform/       Shared kernel — tenant, money, error, audit, outbox, idempotency, sequence, security
  identity/       Authentication, MFA (TOTP + recovery codes), roles, permission matrix, shop scope
  organization/   Shops, terminals, document sequences
  catalog/        Products, variants, units, barcodes, price lists, tax classes
  inventory/      Stock balances, movements, adjustments, counts, inter-shop transfers
  pos/            Checkout, sales, payments, held carts, receipts, shifts, returns, exchanges
  purchasing/     Suppliers, purchase orders, goods receipts (feeds moving-average cost)
  customer/       Customer accounts, credit limits, store-credit ledger, on-account tender
  integration/    Webhook subscriptions and delivery logs (signed delivery lives in platform)
  reporting/      Read-only sales, margin, payment-mix and valuation reports; CSV export
  notifications/  In-app notifications, per-user preferences, reorder-alert sweep + scheduler
  hr/             Employees (with the user link), attendance, leave requests and balances
  finance/        The M0 vertical slice; the template every later module copies
  resources/db/migration/   Flyway — the schema source of truth
docs/             SRS v3.1
```

Each business module is laid out as `api` / `domain` / `internal` / `web`. Only `api` is
visible to other modules; `ModuleBoundaryTest` enforces that, and adding a module means
adding its name to that test's `MODULES` list. That friction is deliberate — a module
without a boundary rule is a module without a boundary.

## Two connection pools

The application connects as `erp_app`, which is **subject to** row-level security and has
`NOBYPASSRLS`. A second, small pool connects as the owner and bypasses RLS. It is reserved
for the few things that genuinely have no single tenant: authentication, which runs before a
tenant is known; the outbox worker and its webhook dispatcher, which span all tenants; and
the tenant directory that periodic schedulers use to enumerate tenants before doing each
one's work back under RLS via `runAsOrg`. Everything else uses the primary pool (DB-011),
and `ModuleBoundaryTest` fails the build if that spreads.

## Not yet done

Known gaps rather than oversights:

- **jOOQ** — still deferred, and this is a judgement call worth stating plainly. Codegen
  needs a migrated database at build time, which would put Docker on the critical path for
  every compile. With the host Gradle currently unusable (see Tests above), moving the whole
  data-access layer onto a toolchain that cannot be exercised locally would be swapping
  working code for unverifiable code. `JdbcClient` has carried M1 through M5's reporting
  aggregates fine; jOOQ earns its keep if the reporting queries grow beyond what hand-written
  SQL keeps legible.
- **Frontend** — no `frontend/` yet. The checkout, shift, receipt, return and on-account
  tender APIs all exist; the POS Tauri shell that drives them is the outstanding M4 item.
- **Event naming** — the outbox emits internal names like `pos.sale_completed`, whereas
  FR-API-001 lists canonical names such as `sale.completed`. Subscriptions match on the
  emitted names today; reconciling the two (an event-name mapping, or renaming at the
  publish sites) is a small, tracked follow-up.

## Next

Finish M6 — load tests against the Section 17 capacity targets, backup/restore drills, and the
R1 exit gate. These are largely operational (scripts, infrastructure, runbooks) rather than
application code, and are not exercised by the container test suite. Still open from M4: the
**Tauri POS desktop client** — the offline-capable till UI with ESC/POS receipt printing.
Optional follow-ups: scheduled report generation and wiring the remaining FR-NOT-001
notification producers.

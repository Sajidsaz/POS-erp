# HeySaz POS — desktop till

A Tauri v2 + React + TypeScript point-of-sale shell for the HeySaz ERP backend. It signs a
cashier in against a provisioned terminal, opens a cash shift, rings up items (barcode scan or
catalog search), takes split tenders, checks out, and prints a receipt — all against the
existing `/api/v1/pos`, `/api/v1/pos/shifts`, and catalog endpoints.

## Running

Prerequisites: Node 18+, pnpm, the Rust toolchain, and a running backend (`docker compose up`
from the repo root, then the Spring app on `:8080`).

```bash
pnpm install
pnpm tauri dev      # desktop app (recommended — talks to the API through Rust, no CORS)
pnpm dev            # plain browser at :1420, proxied to the backend by Vite
```

Build a distributable: `pnpm tauri build`.

## Provisioning a terminal

On first launch the app asks for four values, held on the device (localStorage):

| Field | Where it comes from |
| --- | --- |
| API address | e.g. `http://localhost:8080` |
| Terminal code | the code the login is bound to (FR-TERM-003) |
| Terminal ID (UUID) | the `id` of the terminal record |
| Shop ID (UUID) | the terminal's `shopId` |

Login authenticates by `terminalCode`, but checkout and shift APIs address the terminal by its
UUID, so both are stored. An administrator creates the terminal in the back office
(`POST /api/v1/terminals`, needs `settings.write`) and reads the UUIDs back from
`GET /api/v1/terminals` — a cashier account (`pos.sales.*`) can't list them, so this is an
admin setup step. "Reconfigure terminal" on the sign-in screen clears it.

## Architecture

```
src/
  api/        types.ts (DTO mirrors) · money.ts (exact decimal) · client.ts (transport+auth) · endpoints.ts
  state/      AppContext.tsx (session/shift) · useCart.ts (server-priced cart)
  screens/    SetupScreen · LoginScreen · ShiftGate · CheckoutScreen
  components/ ProductSearch · TenderDialog · ReceiptModal · TopBar · Modal
```

Decisions worth knowing:

- **Transport goes through Rust.** The backend sends no CORS headers, so under Tauri all HTTP
  goes through `@tauri-apps/plugin-http` (the Rust `reqwest` client) — no browser CORS, no
  mixed-content rule. Plain browser dev falls back to `window.fetch` via the Vite proxy.
- **`Money` stays a string.** The API serialises `numeric(19,4)` as text so cents survive
  JavaScript's floats (D4). `src/api/money.ts` does exact decimal math on it (scaled BigInt)
  for the running "amount due"/change; the server owns every persisted total.
- **The cart is priced by the server.** Every change re-POSTs to `/pos/carts/calculate`, so tax
  per line and cash rounding (D4) are never recomputed on the client.
- **Checkout is idempotent.** One `Idempotency-Key` is minted per payment attempt and reused
  across retries, so a flaky network can't double-ring a sale (FR-API-012).
- **Bearer session with silent refresh.** A POS login returns a token pair; a 401 triggers one
  refresh + retry before the session is dropped. The pair is persisted so a restart stays
  signed in.

## Not yet done

- Held-cart **resume** (holding a cart works; a resume/list picker is still to build).
- Customer lookup / on-account (CREDIT) selection — the button is stubbed.
- Returns/exchanges UI (the `/api/v1/pos/returns` backend exists).
- X-report view, cash in/out movements from the till, receipt reprint UI.
- Offline queueing — the shell currently assumes the backend is reachable.

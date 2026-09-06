import { useState } from "react";
import { ApiClientError } from "../api/client";
import { newIdempotencyKey } from "../api/endpoints";
import { money } from "../api/money";
import { useApp } from "../state/AppContext";

/** No selling without an open shift (cash accountability). Opens one with a counted float. */
export function ShiftGate() {
  const { api, config, setShift, principal, logout } = useApp();
  const [openingCash, setOpeningCash] = useState("0");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const open = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!config) return;
    setBusy(true);
    setError(null);
    try {
      const shift = await api.openShift(
        {
          shopId: config.shopId,
          terminalId: config.terminalId,
          openingCash: money(openingCash),
        },
        newIdempotencyKey(),
      );
      setShift(shift);
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : "Could not open the shift.");
      setBusy(false);
    }
  };

  return (
    <div className="centered">
      <form className="card" onSubmit={open}>
        <h1>Open shift</h1>
        <p className="sub">
          {principal?.displayName} · Terminal {config?.terminalCode}
        </p>
        {error && <div className="error-banner">{error}</div>}
        <label className="field">
          <label>Opening cash float</label>
          <input
            autoFocus
            inputMode="decimal"
            value={openingCash}
            onChange={(e) => setOpeningCash(e.target.value)}
          />
        </label>
        <button className="btn btn-primary btn-block btn-lg" type="submit" disabled={busy}>
          {busy ? <span className="spin" /> : "Open shift"}
        </button>
        <button
          type="button"
          className="btn btn-ghost btn-block"
          style={{ marginTop: 12 }}
          onClick={logout}
        >
          Sign out
        </button>
      </form>
    </div>
  );
}

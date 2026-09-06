import { useState } from "react";
import { useApp } from "../state/AppContext";
import type { DeviceConfig } from "../config";

/**
 * One-time terminal provisioning. The shop/terminal UUIDs come from the terminal record an
 * administrator created in the back office; the terminal code is what the cashier's login is
 * bound to. Held on-device so this screen only appears until the till is set up.
 */
export function SetupScreen() {
  const { provision, config } = useApp();
  const [form, setForm] = useState<DeviceConfig>(
    config ?? {
      apiBaseUrl: "http://localhost:8080",
      shopId: "",
      terminalId: "",
      terminalCode: "",
    },
  );
  const [error, setError] = useState<string | null>(null);

  const set = (k: keyof DeviceConfig) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm({ ...form, [k]: e.target.value });

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.apiBaseUrl || !form.shopId || !form.terminalId || !form.terminalCode) {
      setError("All fields are required.");
      return;
    }
    if (!/^https?:\/\//.test(form.apiBaseUrl)) {
      setError("The API address must start with http:// or https://");
      return;
    }
    provision({ ...form, apiBaseUrl: form.apiBaseUrl.trim().replace(/\/+$/, "") });
  };

  return (
    <div className="centered">
      <form className="card" onSubmit={submit}>
        <h1>Set up this terminal</h1>
        <p className="sub">Provision the till once. An administrator supplies these values.</p>
        {error && <div className="error-banner">{error}</div>}

        <label className="field">
          <label>API address</label>
          <input value={form.apiBaseUrl} onChange={set("apiBaseUrl")} placeholder="http://localhost:8080" />
        </label>
        <label className="field">
          <label>Terminal code</label>
          <input value={form.terminalCode} onChange={set("terminalCode")} placeholder="TILL-01" />
        </label>
        <label className="field">
          <label>Terminal ID (UUID)</label>
          <input value={form.terminalId} onChange={set("terminalId")} placeholder="00000000-0000-…" />
        </label>
        <label className="field">
          <label>Shop ID (UUID)</label>
          <input value={form.shopId} onChange={set("shopId")} placeholder="00000000-0000-…" />
        </label>

        <button className="btn btn-primary btn-block btn-lg" type="submit">
          Save &amp; continue
        </button>
      </form>
    </div>
  );
}

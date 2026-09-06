import { useState } from "react";
import { ApiClientError } from "../api/client";
import { useApp } from "../state/AppContext";

export function LoginScreen() {
  const { login, config, deprovision } = useApp();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [mfaCode, setMfaCode] = useState("");
  const [needMfa, setNeedMfa] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email.trim(), password, needMfa ? mfaCode.trim() : undefined);
    } catch (err) {
      if (err instanceof ApiClientError && err.mfaRequired) {
        setNeedMfa(true);
        setError("Enter your authenticator code to finish signing in.");
      } else {
        setError(err instanceof ApiClientError ? err.message : "Sign-in failed.");
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="centered">
      <form className="card" onSubmit={submit}>
        <h1>Sign in</h1>
        <p className="sub">
          Terminal <strong>{config?.terminalCode}</strong>
        </p>
        {error && <div className="error-banner">{error}</div>}

        <label className="field">
          <label>Email</label>
          <input
            type="email"
            autoFocus
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={needMfa}
          />
        </label>
        <label className="field">
          <label>Password</label>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={needMfa}
          />
        </label>
        {needMfa && (
          <label className="field">
            <label>Authenticator code</label>
            <input
              autoFocus
              inputMode="numeric"
              value={mfaCode}
              onChange={(e) => setMfaCode(e.target.value)}
              placeholder="123456"
            />
          </label>
        )}

        <button className="btn btn-primary btn-block btn-lg" type="submit" disabled={busy}>
          {busy ? <span className="spin" /> : needMfa ? "Verify" : "Sign in"}
        </button>

        <button
          type="button"
          className="btn btn-ghost btn-block"
          style={{ marginTop: 12 }}
          onClick={deprovision}
        >
          Reconfigure terminal
        </button>
      </form>
    </div>
  );
}

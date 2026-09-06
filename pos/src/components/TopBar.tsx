import { useState } from "react";
import { ApiClientError } from "../api/client";
import { newIdempotencyKey } from "../api/endpoints";
import { formatMoney, money } from "../api/money";
import type { ZReportView } from "../api/types";
import { useApp } from "../state/AppContext";
import { Modal } from "./Modal";

export function TopBar() {
  const { principal, shift, config, api, setShift, logout } = useApp();
  const [closing, setClosing] = useState(false);
  const [zReport, setZReport] = useState<ZReportView | null>(null);

  return (
    <div className="topbar">
      <div className="brand">
        Hey<span>Saz</span> POS
      </div>
      <div className="spacer" />
      {shift ? (
        <span className="pill ok">
          <span className="dot" /> Shift open
        </span>
      ) : (
        <span className="pill warn">
          <span className="dot" /> No shift
        </span>
      )}
      <span className="pill">{config?.terminalCode}</span>
      <span className="pill">{principal?.displayName}</span>
      {shift && (
        <button className="btn btn-ghost" onClick={() => setClosing(true)} style={{ padding: "8px 14px" }}>
          Close shift
        </button>
      )}
      <button className="btn btn-ghost" onClick={logout} style={{ padding: "8px 14px" }}>
        Sign out
      </button>

      {closing && shift && (
        <CloseShiftDialog
          onCancel={() => setClosing(false)}
          onClosed={(z) => {
            setClosing(false);
            setZReport(z);
            setShift(null);
          }}
          run={async (countedCash) => {
            return api.closeShift(shift.id, { closingCash: money(countedCash) }, newIdempotencyKey());
          }}
        />
      )}

      {zReport && <ZReportModal report={zReport} onClose={() => setZReport(null)} />}
    </div>
  );
}

function CloseShiftDialog({
  run,
  onClosed,
  onCancel,
}: {
  run: (countedCash: string) => Promise<ZReportView>;
  onClosed: (z: ZReportView) => void;
  onCancel: () => void;
}) {
  const { shift } = useApp();
  const [counted, setCounted] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onClosed(await run(counted || "0"));
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : "Could not close the shift.");
      setBusy(false);
    }
  };

  return (
    <Modal onClose={busy ? undefined : onCancel}>
      <form onSubmit={submit}>
        <h2>Close shift</h2>
        {error && <div className="error-banner">{error}</div>}
        <div className="due">
          <div className="label">Expected cash in drawer</div>
          <div className="amount">{formatMoney(shift?.expectedCash ?? "0")}</div>
        </div>
        <label className="field">
          <label>Counted cash</label>
          <input
            autoFocus
            inputMode="decimal"
            value={counted}
            onChange={(e) => setCounted(e.target.value)}
            placeholder="0.00"
          />
        </label>
        <div className="modal-actions">
          <button type="button" className="btn btn-ghost" onClick={onCancel} disabled={busy}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <span className="spin" /> : "Close & print Z"}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function ZReportModal({ report, onClose }: { report: ZReportView; onClose: () => void }) {
  const rows: [string, string][] = [
    ["Opening float", formatMoney(report.openingCash)],
    ["Cash sales", formatMoney(report.cashSales)],
    ["Card sales", formatMoney(report.cardSales)],
    ["Other sales", formatMoney(report.otherSales)],
    ["Total sales", formatMoney(report.totalSales)],
    ["Cash refunds", formatMoney(report.cashRefunds)],
    ["Change given", formatMoney(report.changeGiven)],
    ["Cash in / out", `${formatMoney(report.cashIn)} / ${formatMoney(report.cashOut)}`],
    ["Expected cash", formatMoney(report.expectedCash)],
    ["Counted cash", formatMoney(report.countedCash)],
    ["Variance", formatMoney(report.cashVariance)],
    ["Sales / returns", `${report.salesCount} / ${report.returnsCount}`],
  ];
  return (
    <Modal onClose={onClose}>
      <h2>Z report</h2>
      <p className="text-muted" style={{ marginTop: -8 }}>
        {report.shopName} · {report.terminalCode} · {report.cashierName}
      </p>
      <div className="tender-list">
        {rows.map(([k, v]) => (
          <div className="tender-row" key={k}>
            <span className="text-muted">{k}</span>
            <span>{v}</span>
          </div>
        ))}
      </div>
      <div className="modal-actions">
        <button className="btn btn-ghost" onClick={() => window.print()}>
          Print
        </button>
        <button className="btn btn-primary" onClick={onClose}>
          Done
        </button>
      </div>
    </Modal>
  );
}

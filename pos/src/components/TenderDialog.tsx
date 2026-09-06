import { useMemo, useState } from "react";
import { add, cmp, formatMoney, isNegative, money, sub, ZERO } from "../api/money";
import type { Money, PaymentInput, PaymentMethod } from "../api/types";
import { Modal } from "./Modal";

const METHODS: { method: PaymentMethod; label: string; hint: string }[] = [
  { method: "CASH", label: "Cash", hint: "F1" },
  { method: "CARD", label: "Card", hint: "F2" },
  { method: "CREDIT", label: "On account", hint: "F3" },
  { method: "VOUCHER", label: "Voucher", hint: "F4" },
  { method: "OTHER", label: "Other", hint: "F5" },
];

const QUICK_CASH = ["100", "500", "1000", "5000"];

/**
 * Collects tenders against the (already cash-rounded) payable total and hands the parent a
 * list of PaymentInput to check out. Amounts applied are capped at the running due so they
 * sum exactly to the total; for cash, the full amount given is recorded as `tenderedAmount`
 * and the server derives change.
 */
export function TenderDialog({
  payable,
  currency,
  busy,
  onConfirm,
  onClose,
}: {
  payable: Money;
  currency?: string;
  busy: boolean;
  onConfirm: (payments: PaymentInput[]) => void;
  onClose: () => void;
}) {
  const [tenders, setTenders] = useState<PaymentInput[]>([]);
  const [amountText, setAmountText] = useState("");

  const paid = useMemo(
    () => tenders.reduce((acc, t) => add(acc, t.amount), ZERO),
    [tenders],
  );
  const due = useMemo(() => sub(payable, paid), [payable, paid]);
  const fullyPaid = cmp(due, ZERO) <= 0;

  // Change: for the last cash tender, tendered - amount, plus any residual overpayment.
  const change = useMemo(() => {
    const overCash = tenders.reduce((acc, t) => {
      if (t.method === "CASH" && t.tenderedAmount) {
        const over = sub(t.tenderedAmount, t.amount);
        return isNegative(over) ? acc : add(acc, over);
      }
      return acc;
    }, ZERO);
    return overCash;
  }, [tenders]);

  const addTender = (method: PaymentMethod) => {
    const entered = amountText.trim() ? money(amountText) : due;
    if (cmp(entered, ZERO) <= 0) return;
    // Applied amount can't exceed what's still owed.
    const applied = cmp(entered, due) > 0 ? due : entered;
    if (cmp(applied, ZERO) <= 0) return;
    const tender: PaymentInput =
      method === "CASH"
        ? { method, amount: applied, tenderedAmount: entered }
        : { method, amount: applied };
    setTenders((prev) => [...prev, tender]);
    setAmountText("");
  };

  const removeTender = (i: number) =>
    setTenders((prev) => prev.filter((_, idx) => idx !== i));

  return (
    <Modal onClose={busy ? undefined : onClose}>
      <h2>Payment</h2>

      <div className="due">
        {fullyPaid ? (
          <>
            <div className="label">Change due</div>
            <div className="amount change">{formatMoney(change, currency)}</div>
          </>
        ) : (
          <>
            <div className="label">Amount due</div>
            <div className="amount">{formatMoney(due, currency)}</div>
          </>
        )}
      </div>

      {!fullyPaid && (
        <>
          <div className="field" style={{ marginBottom: 12 }}>
            <label>Amount (blank = full remaining)</label>
            <input
              autoFocus
              inputMode="decimal"
              value={amountText}
              onChange={(e) => setAmountText(e.target.value)}
              placeholder={formatMoney(due)}
            />
          </div>
          <div className="quick-cash">
            {QUICK_CASH.map((c) => (
              <button key={c} className="btn" type="button" onClick={() => setAmountText(c)}>
                {c}
              </button>
            ))}
          </div>
          <div className="tender-methods">
            {METHODS.map((m) => (
              <button
                key={m.method}
                className="btn"
                type="button"
                onClick={() => addTender(m.method)}
              >
                <strong>{m.label}</strong>
              </button>
            ))}
          </div>
        </>
      )}

      {tenders.length > 0 && (
        <div className="tender-list">
          {tenders.map((t, i) => (
            <div className="tender-row" key={i}>
              <span>{t.method}</span>
              <span className="row-inline">
                {formatMoney(t.amount, currency)}
                {t.method === "CASH" && t.tenderedAmount && cmp(t.tenderedAmount, t.amount) > 0 && (
                  <span className="text-muted"> (given {formatMoney(t.tenderedAmount)})</span>
                )}
                {!busy && (
                  <button className="rm" type="button" onClick={() => removeTender(i)}>
                    ✕
                  </button>
                )}
              </span>
            </div>
          ))}
        </div>
      )}

      <div className="modal-actions">
        <button className="btn btn-ghost" type="button" onClick={onClose} disabled={busy}>
          Cancel
        </button>
        <button
          className="btn btn-accent"
          type="button"
          disabled={!fullyPaid || busy}
          onClick={() => onConfirm(tenders)}
        >
          {busy ? <span className="spin" /> : "Complete sale"}
        </button>
      </div>
    </Modal>
  );
}

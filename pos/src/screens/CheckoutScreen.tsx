import { useMemo, useState } from "react";
import { ApiClientError } from "../api/client";
import { newIdempotencyKey } from "../api/endpoints";
import { formatMoney } from "../api/money";
import type { CalculatedLineView, PaymentInput, ReceiptView } from "../api/types";
import { ProductSearch } from "../components/ProductSearch";
import { ReceiptModal } from "../components/ReceiptModal";
import { TenderDialog } from "../components/TenderDialog";
import { useApp } from "../state/AppContext";
import { useCart } from "../state/useCart";

export function CheckoutScreen() {
  const { api, config, shift, reloadShift } = useApp();
  const cart = useCart(api, config!.shopId);
  const [showTender, setShowTender] = useState(false);
  const [busy, setBusy] = useState(false);
  const [receipt, setReceipt] = useState<ReceiptView | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Stable across retries of one checkout so the server dedupes (FR-API-012).
  const [idemKey, setIdemKey] = useState<string>(() => newIdempotencyKey());

  const calc = cart.calculated;
  const calcByVariant = useMemo(() => {
    const map = new Map<string, CalculatedLineView>();
    calc?.lines.forEach((l) => map.set(l.variantId, l));
    return map;
  }, [calc]);

  const canPay = !!calc && cart.lines.length > 0 && !cart.calculating && !cart.error;

  const openTender = () => {
    setIdemKey(newIdempotencyKey());
    setShowTender(true);
  };

  const confirm = async (payments: PaymentInput[]) => {
    if (!config) return;
    setBusy(true);
    setError(null);
    try {
      const sale = await api.checkout(
        {
          shopId: config.shopId,
          terminalId: config.terminalId,
          shiftId: shift?.id ?? null,
          lines: cart.toInputs(),
          payments,
        },
        idemKey,
      );
      const r = await api.getReceipt(sale.id);
      setReceipt(r);
      setShowTender(false);
      cart.clear();
      void reloadShift();
    } catch (e) {
      setError(e instanceof ApiClientError ? e.message : "Checkout failed.");
    } finally {
      setBusy(false);
    }
  };

  const hold = async () => {
    if (!config || cart.lines.length === 0) return;
    setError(null);
    try {
      await api.holdCart(
        {
          shopId: config.shopId,
          terminalId: config.terminalId,
          lines: cart.toInputs(),
        },
        newIdempotencyKey(),
      );
      cart.clear();
    } catch (e) {
      setError(e instanceof ApiClientError ? e.message : "Could not hold the cart.");
    }
  };

  return (
    <div className="checkout">
      <div className="pane-left">
        <ProductSearch api={api} onPick={cart.addVariant} />
      </div>

      <div className="pane-right">
        <div className="cart-head">
          <h2>Cart</h2>
          {cart.lines.length > 0 && (
            <button className="btn btn-ghost" onClick={cart.clear} style={{ padding: "6px 10px" }}>
              Clear
            </button>
          )}
        </div>

        <div className="cart-lines">
          {cart.lines.length === 0 && <div className="cart-empty">No items yet</div>}
          {cart.lines.map((l) => {
            const c = calcByVariant.get(l.variantId);
            return (
              <div className="cart-line" key={l.variantId}>
                <div className="name">{l.productName}</div>
                <div className="total">
                  {c ? formatMoney(c.lineTotal) : formatMoney(l.catalogPrice)}
                </div>
                <div className="meta">
                  {l.sku} · {formatMoney(c?.unitPrice ?? l.catalogPrice)} ea
                  {c && Number(c.taxAmount) > 0 && ` · tax ${formatMoney(c.taxAmount)}`}
                </div>
                <div />
                <div className="qty">
                  <button onClick={() => cart.bumpQuantity(l.variantId, -1)}>−</button>
                  <span className="val">{Number(l.quantity)}</span>
                  <button onClick={() => cart.bumpQuantity(l.variantId, +1)}>+</button>
                </div>
                <button className="line-remove" onClick={() => cart.removeLine(l.variantId)}>
                  Remove
                </button>
              </div>
            );
          })}
        </div>

        {error && <div className="error-banner" style={{ margin: "0 16px" }}>{error}</div>}
        {cart.error && (
          <div className="error-banner" style={{ margin: "0 16px" }}>
            {cart.error}
          </div>
        )}

        <div className="totals">
          <div className="row">
            <span>Subtotal</span>
            <span>{formatMoney(calc?.subtotal ?? "0")}</span>
          </div>
          {calc && Number(calc.discountTotal) > 0 && (
            <div className="row">
              <span>Discount</span>
              <span>−{formatMoney(calc.discountTotal)}</span>
            </div>
          )}
          <div className="row">
            <span>Tax</span>
            <span>{formatMoney(calc?.taxTotal ?? "0")}</span>
          </div>
          {calc && Number(calc.cashRounding) !== 0 && (
            <div className="row">
              <span>Rounding</span>
              <span>{formatMoney(calc.cashRounding)}</span>
            </div>
          )}
          <div className="row grand">
            <span>Total</span>
            <span>
              {cart.calculating ? <span className="spin" /> : formatMoney(calc?.payableTotal ?? "0")}
            </span>
          </div>

          <div className="actions">
            <button className="btn" onClick={hold} disabled={cart.lines.length === 0}>
              Hold
            </button>
            <button className="btn" disabled>
              Customer
            </button>
            <button className="btn btn-accent btn-lg pay" onClick={openTender} disabled={!canPay}>
              Pay {calc ? formatMoney(calc.payableTotal) : ""}
            </button>
          </div>
        </div>
      </div>

      {showTender && calc && (
        <TenderDialog
          payable={calc.payableTotal}
          busy={busy}
          onConfirm={confirm}
          onClose={() => setShowTender(false)}
        />
      )}

      {receipt && (
        <ReceiptModal
          receipt={receipt}
          onClose={() => setReceipt(null)}
          onNewSale={() => setReceipt(null)}
        />
      )}
    </div>
  );
}

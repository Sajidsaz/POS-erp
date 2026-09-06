import { formatMoney } from "../api/money";
import type { ReceiptView } from "../api/types";
import { Modal } from "./Modal";

export function ReceiptModal({
  receipt,
  onClose,
  onNewSale,
}: {
  receipt: ReceiptView;
  onClose: () => void;
  onNewSale?: () => void;
}) {
  return (
    <Modal onClose={onClose}>
      <div className="receipt">
        <div className="center bold">{receipt.shopName}</div>
        {receipt.shopAddress && <div className="center">{receipt.shopAddress}</div>}
        {receipt.taxRegistrationNo && (
          <div className="center">Tax No: {receipt.taxRegistrationNo}</div>
        )}
        {receipt.receiptHeader && <div className="center">{receipt.receiptHeader}</div>}
        {receipt.isReprint && <div className="center reprint">*** REPRINT ***</div>}
        <hr />
        <div>Invoice: {receipt.invoiceNumber}</div>
        <div>{new Date(receipt.serverTime).toLocaleString()}</div>
        <div>
          Cashier: {receipt.cashierName} · {receipt.terminalCode}
        </div>
        <hr />
        <table>
          <tbody>
            {receipt.lines.map((l, i) => (
              <tr key={i}>
                <td>
                  {l.description}
                  <br />
                  <span className="text-muted">
                    {Number(l.quantity)} × {formatMoney(l.unitPrice)}
                    {Number(l.discountAmount) > 0 && ` − ${formatMoney(l.discountAmount)}`}
                  </span>
                </td>
                <td className="r">{formatMoney(l.lineTotal)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <hr />
        <table>
          <tbody>
            <tr>
              <td>Subtotal</td>
              <td className="r">{formatMoney(receipt.subtotal)}</td>
            </tr>
            {Number(receipt.discountTotal) > 0 && (
              <tr>
                <td>Discount</td>
                <td className="r">−{formatMoney(receipt.discountTotal)}</td>
              </tr>
            )}
            {receipt.taxBreakdown.map((t, i) => (
              <tr key={i}>
                <td>Tax {Number(t.rate) * 100}%</td>
                <td className="r">{formatMoney(t.taxAmount)}</td>
              </tr>
            ))}
            {Number(receipt.cashRounding) !== 0 && (
              <tr>
                <td>Rounding</td>
                <td className="r">{formatMoney(receipt.cashRounding)}</td>
              </tr>
            )}
            <tr className="bold">
              <td>TOTAL</td>
              <td className="r">{formatMoney(receipt.grandTotal)}</td>
            </tr>
          </tbody>
        </table>
        <hr />
        <table>
          <tbody>
            {receipt.payments.map((p, i) => (
              <tr key={i}>
                <td>{p.method}</td>
                <td className="r">{formatMoney(p.amount)}</td>
              </tr>
            ))}
            <tr>
              <td>Tendered</td>
              <td className="r">{formatMoney(receipt.tenderedAmount)}</td>
            </tr>
            {Number(receipt.changeGiven) > 0 && (
              <tr>
                <td>Change</td>
                <td className="r">{formatMoney(receipt.changeGiven)}</td>
              </tr>
            )}
          </tbody>
        </table>
        {receipt.receiptFooter && (
          <>
            <hr />
            <div className="center">{receipt.receiptFooter}</div>
          </>
        )}
      </div>

      <div className="modal-actions">
        <button className="btn btn-ghost" type="button" onClick={() => window.print()}>
          Print
        </button>
        {onNewSale ? (
          <button className="btn btn-primary" type="button" onClick={onNewSale}>
            New sale
          </button>
        ) : (
          <button className="btn btn-primary" type="button" onClick={onClose}>
            Close
          </button>
        )}
      </div>
    </Modal>
  );
}

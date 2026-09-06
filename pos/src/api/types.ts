/**
 * TypeScript mirrors of the backend DTOs (com.heysaz.erp.pos.api, .catalog.api, .identity.web).
 *
 * IMPORTANT: `Money` is a *string* on the wire, never a JSON number — the backend serialises
 * numeric(19,4) as text on purpose so cents survive JavaScript's IEEE-754 doubles. Keep it a
 * string end-to-end; only ever parse for display, never for re-summing a total the server owns.
 */
export type Money = string;

export type PaymentMethod = "CASH" | "CARD" | "CREDIT" | "VOUCHER" | "OTHER";

export type ClientType = "ADMIN" | "POS";

// ---- auth ----------------------------------------------------------------

export interface SessionResponse {
  userId: string;
  orgId: string;
  displayName: string;
  principalType: string;
  authorities: string[];
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  expiresInSeconds: number;
  principal: SessionResponse | null;
}

// ---- catalog -------------------------------------------------------------

export interface VariantView {
  id: string;
  sku: string;
  name: string;
  axisValues: Record<string, string>;
  price: Money;
  averageCost: Money;
  isDefault: boolean;
  active: boolean;
  barcodes: string[];
}

export interface ProductView {
  id: string;
  sku: string;
  name: string;
  categoryId: string | null;
  stockingUnitId: string | null;
  taxClassId: string | null;
  stocked: boolean;
  hasVariants: boolean;
  status: string;
  variants: VariantView[];
}

// ---- cart / checkout -----------------------------------------------------

export interface CartLineInput {
  variantId: string;
  quantity: string; // BigDecimal, min 0.0001
  unitPrice?: Money | null;
  discountAmount?: Money | null;
}

export interface CalculateCartCommand {
  shopId: string;
  lines: CartLineInput[];
  primaryPaymentMethod?: PaymentMethod | null;
}

export interface CalculatedLineView {
  variantId: string;
  variantSku: string;
  productName: string;
  quantity: string;
  unitPrice: Money;
  discountAmount: Money;
  taxRate: string;
  taxAmount: Money;
  lineTotal: Money;
}

export interface CalculatedCartView {
  lines: CalculatedLineView[];
  subtotal: Money;
  discountTotal: Money;
  taxTotal: Money;
  grandTotal: Money;
  cashRounding: Money;
  payableTotal: Money;
}

export interface PaymentInput {
  method: PaymentMethod;
  amount: Money;
  reference?: string | null;
  tenderedAmount?: Money | null;
}

export interface CheckoutCommand {
  shopId: string;
  terminalId: string;
  shiftId?: string | null;
  lines: CartLineInput[];
  payments: PaymentInput[];
  customerId?: string | null;
  notes?: string | null;
}

export interface SaleLineView {
  id: string;
  variantId: string;
  variantSku: string;
  productName: string;
  quantity: string;
  unitPrice: Money;
  discountAmount: Money;
  taxRate: string;
  taxAmount: Money;
  lineTotal: Money;
  costSnapshot: Money;
  returnedQuantity: string;
}

export interface SalePaymentView {
  id: string;
  paymentMethod: PaymentMethod;
  amount: Money;
  reference: string | null;
  tenderedAmount: Money | null;
  changeAmount: Money | null;
}

export interface SaleView {
  id: string;
  invoiceNumber: string;
  shopId: string;
  terminalId: string;
  shiftId: string | null;
  cashierUserId: string;
  status: string;
  subtotal: Money;
  discountTotal: Money;
  taxTotal: Money;
  grandTotal: Money;
  cashRounding: Money;
  totalTendered: Money;
  changeGiven: Money;
  customerId: string | null;
  notes: string | null;
  createdAt: string;
  lines: SaleLineView[];
  payments: SalePaymentView[];
}

// ---- held carts ----------------------------------------------------------

export interface HoldCartCommand {
  shopId: string;
  terminalId: string;
  reference?: string | null;
  lines: CartLineInput[];
}

export interface HeldCartView {
  id: string;
  shopId: string;
  terminalId: string;
  cashierUserId: string;
  reference: string | null;
  lines: CartLineInput[];
  createdAt: string;
}

// ---- receipt -------------------------------------------------------------

export interface ReceiptLine {
  description: string;
  quantity: string;
  unitPrice: Money;
  discountAmount: Money;
  lineTotal: Money;
}

export interface ReceiptTaxLine {
  rate: string;
  taxableAmount: Money;
  taxAmount: Money;
}

export interface ReceiptPaymentLine {
  method: PaymentMethod;
  amount: Money;
  reference: string | null;
}

export interface ReceiptView {
  saleId: string;
  invoiceNumber: string;
  shopName: string;
  shopAddress: string;
  taxRegistrationNo: string;
  serverTime: string;
  cashierName: string;
  terminalCode: string;
  lines: ReceiptLine[];
  subtotal: Money;
  discountTotal: Money;
  taxBreakdown: ReceiptTaxLine[];
  grandTotal: Money;
  cashRounding: Money;
  payments: ReceiptPaymentLine[];
  tenderedAmount: Money;
  changeGiven: Money;
  receiptHeader: string | null;
  receiptFooter: string | null;
  isReprint: boolean;
}

// ---- shifts --------------------------------------------------------------

export type ShiftStatus = "OPEN" | "CLOSED";

export interface OpenShiftCommand {
  shopId: string;
  terminalId: string;
  openingCash: Money;
  notes?: string | null;
}

export interface CloseShiftCommand {
  closingCash: Money;
  notes?: string | null;
}

export interface RecordShiftMovementCommand {
  movementType: "CASH_IN" | "CASH_OUT" | "DRAWER_OPEN" | "EXPENSE";
  amount: Money;
  reason: string;
}

export interface ShiftMovementView {
  id: string;
  shiftId: string;
  movementType: string;
  amount: Money;
  reason: string;
  actorUserId: string;
  occurredAt: string;
}

export interface ShiftSummaryView {
  id: string;
  shopId: string;
  terminalId: string;
  cashierUserId: string;
  status: ShiftStatus;
  openingCash: Money;
  cashSales: Money;
  changeGiven: Money;
  cashRefunds: Money;
  cashIn: Money;
  cashOut: Money;
  cashExpenses: Money;
  cashRounding: Money;
  expectedCash: Money;
  closingCash: Money | null;
  cashVariance: Money | null;
  openedAt: string;
  closedAt: string | null;
  notes: string | null;
  movements: ShiftMovementView[];
}

export interface XReportView {
  shiftId: string;
  shopName: string;
  terminalCode: string;
  cashierName: string;
  openedAt: string;
  reportTime: string;
  openingCash: Money;
  cashSales: Money;
  cardSales: Money;
  otherSales: Money;
  totalSales: Money;
  cashRefunds: Money;
  changeGiven: Money;
  cashIn: Money;
  cashOut: Money;
  cashExpenses: Money;
  cashRounding: Money;
  expectedCash: Money;
  salesCount: number;
  returnsCount: number;
}

export interface ZReportView extends XReportView {
  closedAt: string;
  countedCash: Money;
  cashVariance: Money;
  notes: string | null;
}

// ---- error envelope (API-STD-002) ---------------------------------------

export interface ApiError {
  code: string;
  message: string;
  details: string[];
  path?: string;
  timestamp?: string;
}

/** Named, typed wrappers over the raw client — one function per backend endpoint the POS uses. */
import { ApiClient } from "./client";
import type {
  CalculateCartCommand,
  CalculatedCartView,
  CheckoutCommand,
  HeldCartView,
  HoldCartCommand,
  OpenShiftCommand,
  CloseShiftCommand,
  ProductView,
  ReceiptView,
  SaleView,
  ShiftSummaryView,
  TokenResponse,
  VariantView,
  XReportView,
  ZReportView,
} from "./types";

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

export class Api {
  constructor(private readonly client: ApiClient) {}

  // ---- auth ----
  login(body: {
    email: string;
    password: string;
    terminalCode: string;
    mfaCode?: string;
  }): Promise<TokenResponse> {
    return this.client.post<TokenResponse>("/api/v1/auth/login", {
      ...body,
      clientType: "POS",
    });
  }

  // ---- catalog ----
  searchProducts(q: string, limit = 25): Promise<ProductView[]> {
    const query = `?q=${encodeURIComponent(q)}&limit=${limit}`;
    return this.client.get<ProductView[]>(`/api/v1/products${query}`);
  }

  findByBarcode(barcode: string): Promise<VariantView> {
    return this.client.get<VariantView>(`/api/v1/products/by-barcode/${encodeURIComponent(barcode)}`);
  }

  // ---- cart / checkout ----
  calculateCart(command: CalculateCartCommand): Promise<CalculatedCartView> {
    return this.client.post<CalculatedCartView>("/api/v1/pos/carts/calculate", command);
  }

  checkout(command: CheckoutCommand, idempotencyKey: string): Promise<SaleView> {
    return this.client.post<SaleView>("/api/v1/pos/sales", command, idempotencyKey);
  }

  getSale(saleId: string): Promise<SaleView> {
    return this.client.get<SaleView>(`/api/v1/pos/sales/${saleId}`);
  }

  getReceipt(saleId: string): Promise<ReceiptView> {
    return this.client.get<ReceiptView>(`/api/v1/pos/sales/${saleId}/receipt`);
  }

  reprintReceipt(saleId: string, reason?: string): Promise<ReceiptView> {
    const q = reason ? `?reason=${encodeURIComponent(reason)}` : "";
    return this.client.post<ReceiptView>(`/api/v1/pos/sales/${saleId}/receipt/reprint${q}`);
  }

  // ---- held carts ----
  holdCart(command: HoldCartCommand, idempotencyKey: string): Promise<HeldCartView> {
    return this.client.post<HeldCartView>("/api/v1/pos/held-carts", command, idempotencyKey);
  }

  listHeldCarts(shopId: string): Promise<HeldCartView[]> {
    return this.client.get<HeldCartView[]>(`/api/v1/pos/held-carts?shopId=${shopId}`);
  }

  deleteHeldCart(cartId: string): Promise<void> {
    return this.client.del<void>(`/api/v1/pos/held-carts/${cartId}`);
  }

  // ---- shifts ----
  currentShift(terminalId: string): Promise<ShiftSummaryView | null> {
    // The endpoint returns 204 (-> undefined here) when no shift is open.
    return this.client
      .get<ShiftSummaryView | undefined>(`/api/v1/pos/shifts/current?terminalId=${terminalId}`)
      .then((s) => s ?? null);
  }

  openShift(command: OpenShiftCommand, idempotencyKey: string): Promise<ShiftSummaryView> {
    return this.client.post<ShiftSummaryView>("/api/v1/pos/shifts", command, idempotencyKey);
  }

  closeShift(
    shiftId: string,
    command: CloseShiftCommand,
    idempotencyKey: string,
  ): Promise<ZReportView> {
    return this.client.post<ZReportView>(`/api/v1/pos/shifts/${shiftId}/close`, command, idempotencyKey);
  }

  xReport(shiftId: string): Promise<XReportView> {
    return this.client.get<XReportView>(`/api/v1/pos/shifts/${shiftId}/x-report`);
  }
}

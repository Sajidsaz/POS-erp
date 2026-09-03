package com.heysaz.erp.purchasing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.heysaz.erp.platform.money.Money;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Suppliers, purchase orders and goods receipts (Section 8).
 *
 * <p>Receiving is the only stock-affecting operation here, and it always lands stock through
 * the inventory module's {@code RECEIPT} movement so the moving weighted average (decision
 * D2) is maintained in exactly one place. Purchase orders and receipts draw gapless numbers
 * from the same per-shop sequence as sales and transfers (decision D5).
 */
public interface PurchasingService {

    // ------------------------------------------------------------- Suppliers

    record CreateSupplierCommand(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 200) String name,
            String contactName,
            String phone,
            String email,
            String taxRegistrationNo) {
    }

    record SupplierView(UUID id, String code, String name, String contactName,
                        String phone, String email, String taxRegistrationNo, boolean active) {
    }

    // -------------------------------------------------------- Purchase orders

    record PurchaseOrderLineInput(
            @NotNull UUID variantId,
            @NotNull @DecimalMin("0.0001") BigDecimal quantity,
            @NotNull Money unitCost) {
    }

    record CreatePurchaseOrderCommand(
            @NotNull UUID supplierId,
            @NotNull UUID shopId,
            String notes,
            @NotEmpty @Valid List<PurchaseOrderLineInput> lines) {
    }

    record PurchaseOrderLineView(
            UUID id,
            UUID variantId,
            String variantSku,
            String productName,
            BigDecimal orderedQuantity,
            Money unitCost,
            BigDecimal receivedQuantity) {
    }

    record PurchaseOrderView(
            UUID id,
            String poNumber,
            UUID supplierId,
            String supplierName,
            UUID shopId,
            PurchaseOrderStatus status,
            Money orderedTotal,
            String notes,
            UUID createdBy,
            UUID approvedBy,
            Instant createdAt,
            List<PurchaseOrderLineView> lines) {
    }

    // --------------------------------------------------------- Goods receipts

    record ReceiveLineInput(
            /** Links this receipt line to a PO line; null for a direct/blind receipt. */
            UUID poLineId,
            @NotNull UUID variantId,
            @NotNull @DecimalMin("0.0001") BigDecimal receivedQuantity,
            /** Overrides the PO unit cost; required when there is no PO line to inherit from. */
            Money unitCost) {
    }

    record ReceiveGoodsCommand(
            /** The purchase order being received against; null for a direct receipt. */
            UUID purchaseOrderId,
            /** Required only for a direct receipt; otherwise taken from the purchase order. */
            UUID supplierId,
            @NotNull UUID shopId,
            String notes,
            @NotEmpty @Valid List<ReceiveLineInput> lines) {
    }

    record GoodsReceiptLineView(
            UUID id,
            UUID purchaseOrderLineId,
            UUID variantId,
            String variantSku,
            String productName,
            BigDecimal receivedQuantity,
            Money unitCost) {
    }

    record GoodsReceiptView(
            UUID id,
            String grnNumber,
            UUID purchaseOrderId,
            UUID supplierId,
            UUID shopId,
            String notes,
            UUID receivedBy,
            Instant createdAt,
            List<GoodsReceiptLineView> lines) {
    }

    SupplierView createSupplier(CreateSupplierCommand command);

    List<SupplierView> listSuppliers();

    PurchaseOrderView createPurchaseOrder(CreatePurchaseOrderCommand command, String idempotencyKey);

    PurchaseOrderView approvePurchaseOrder(UUID purchaseOrderId, String idempotencyKey);

    void cancelPurchaseOrder(UUID purchaseOrderId, String reason);

    PurchaseOrderView getPurchaseOrder(UUID purchaseOrderId);

    List<PurchaseOrderView> listPurchaseOrders(UUID shopId, PurchaseOrderStatus status);

    GoodsReceiptView receiveGoods(ReceiveGoodsCommand command, String idempotencyKey);

    GoodsReceiptView getGoodsReceipt(UUID goodsReceiptId);

    List<GoodsReceiptView> listGoodsReceipts(UUID shopId);
}

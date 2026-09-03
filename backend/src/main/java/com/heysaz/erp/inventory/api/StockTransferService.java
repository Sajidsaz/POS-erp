package com.heysaz.erp.inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.heysaz.erp.platform.money.Money;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * FR-INV-009 / FR-INV-010 / FR-INV-011 / FR-INV-012: Inter-shop transfers.
 */
public interface StockTransferService {

    record TransferLineInput(
            @NotNull UUID variantId,
            @NotNull @DecimalMin("0.0001") BigDecimal dispatchedQuantity) {
    }

    record CreateTransferCommand(
            @NotNull UUID sourceShopId,
            @NotNull UUID destinationShopId,
            String notes,
            @NotEmpty @Valid List<TransferLineInput> lines) {
    }

    record ReceiveLineInput(
            @NotNull UUID lineId,
            @NotNull @DecimalMin("0") BigDecimal receivedQuantity,
            String discrepancyReason) {
    }

    record ReceiveTransferCommand(
            @NotEmpty @Valid List<ReceiveLineInput> lines,
            String notes) {
    }

    record TransferLineView(
            UUID id,
            UUID variantId,
            String variantSku,
            String productName,
            BigDecimal dispatchedQuantity,
            BigDecimal receivedQuantity,
            Money unitCost,
            String discrepancyReason) {
    }

    record StockTransferView(
            UUID id,
            String transferNumber,
            UUID sourceShopId,
            UUID destinationShopId,
            TransferStatus status,
            UUID dispatchedBy,
            Instant dispatchedAt,
            UUID receivedBy,
            Instant receivedAt,
            String notes,
            Instant createdAt,
            Instant updatedAt,
            List<TransferLineView> lines) {
    }

    StockTransferView createTransfer(CreateTransferCommand command, String idempotencyKey);

    StockTransferView dispatchTransfer(UUID transferId, String idempotencyKey);

    StockTransferView receiveTransfer(UUID transferId, ReceiveTransferCommand command, String idempotencyKey);

    void cancelTransfer(UUID transferId, String reason);

    StockTransferView getTransfer(UUID transferId);

    List<StockTransferView> listTransfers(UUID shopId, TransferStatus status);
}

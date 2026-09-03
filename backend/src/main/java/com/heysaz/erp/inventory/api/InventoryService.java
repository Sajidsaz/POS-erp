package com.heysaz.erp.inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.heysaz.erp.platform.money.Money;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Inventory management: stock balances, append-only movements, adjustments, and threshold alerts.
 */
public interface InventoryService {

    record StockBalanceView(
            UUID id,
            UUID shopId,
            UUID variantId,
            String variantSku,
            String productName,
            BigDecimal quantityOnHand,
            BigDecimal quantityReserved,
            BigDecimal quantityInTransit,
            BigDecimal availableQuantity,
            Money averageCost,
            BigDecimal reorderPoint,
            BigDecimal reorderQuantity,
            BigDecimal lowStockThreshold,
            Instant updatedAt) {
    }

    record StockMovementView(
            UUID id,
            UUID shopId,
            UUID variantId,
            String variantSku,
            MovementType movementType,
            BigDecimal quantity,
            Money unitCost,
            Money totalCost,
            BigDecimal resultingBalance,
            Money resultingAverageCost,
            String referenceType,
            String referenceId,
            String reason,
            UUID actorUserId,
            Instant createdAt) {
    }

    record StockAdjustmentLineInput(
            @NotNull UUID variantId,
            @NotNull BigDecimal quantityDelta,
            Money unitCost,
            String reason) {
    }

    record AdjustStockCommand(
            @NotNull UUID shopId,
            @NotBlank String reason,
            String notes,
            @NotEmpty @Valid List<StockAdjustmentLineInput> lines) {
    }

    record StockAdjustmentLineView(
            UUID id,
            UUID variantId,
            String variantSku,
            BigDecimal quantityDelta,
            Money unitCost,
            String reason) {
    }

    record StockAdjustmentView(
            UUID id,
            UUID shopId,
            String reason,
            String notes,
            UUID createdBy,
            Instant createdAt,
            List<StockAdjustmentLineView> lines) {
    }

    record SetThresholdsCommand(
            BigDecimal reorderPoint,
            BigDecimal reorderQuantity,
            BigDecimal lowStockThreshold) {
    }

    record LowStockAlertView(
            UUID shopId,
            UUID variantId,
            String variantSku,
            String productName,
            BigDecimal quantityOnHand,
            BigDecimal lowStockThreshold,
            BigDecimal reorderPoint,
            BigDecimal reorderQuantity) {
    }

    StockBalanceView getStockBalance(UUID shopId, UUID variantId);

    List<StockBalanceView> listStockBalances(UUID shopId, UUID categoryId, String search, Boolean lowStockOnly);

    List<StockMovementView> listStockMovements(UUID shopId, UUID variantId, int limit);

    /**
     * FR-INV-008: Reconstructs balance by summing historical movements from the ledger.
     */
    BigDecimal reconstructBalance(UUID shopId, UUID variantId);

    List<LowStockAlertView> getLowStockAlerts(UUID shopId);

    StockAdjustmentView adjustStock(AdjustStockCommand command, String idempotencyKey);

    void setThresholds(UUID shopId, UUID variantId, SetThresholdsCommand command);

    /**
     * Internal/cross-module entry point to record atomic movements and update balances.
     */
    StockMovementView recordMovement(
            UUID shopId,
            UUID variantId,
            MovementType type,
            BigDecimal quantity,
            Money unitCost,
            String referenceType,
            String referenceId,
            String reason);
}

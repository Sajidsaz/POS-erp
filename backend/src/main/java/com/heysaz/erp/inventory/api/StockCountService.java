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
 * FR-INV-006: Physical stock audit and reconciliation.
 */
public interface StockCountService {

    record CountLineInput(
            @NotNull UUID variantId,
            @NotNull @DecimalMin("0") BigDecimal countedQuantity,
            String notes) {
    }

    record CreateStockCountCommand(
            @NotNull UUID shopId,
            String notes) {
    }

    record RecordCountsCommand(
            @NotEmpty @Valid List<CountLineInput> lines) {
    }

    record CountLineView(
            UUID id,
            UUID variantId,
            String variantSku,
            String productName,
            BigDecimal systemQuantity,
            BigDecimal countedQuantity,
            BigDecimal variance,
            Money unitCost,
            String notes) {
    }

    record StockCountView(
            UUID id,
            UUID shopId,
            StockCountStatus status,
            String notes,
            UUID createdBy,
            UUID approvedBy,
            Instant startedAt,
            Instant completedAt,
            Instant approvedAt,
            List<CountLineView> lines) {
    }

    StockCountView createCount(CreateStockCountCommand command, String idempotencyKey);

    StockCountView recordCounts(UUID countId, RecordCountsCommand command);

    StockCountView approveCount(UUID countId, String idempotencyKey);

    void cancelCount(UUID countId, String reason);

    StockCountView getCount(UUID countId);

    List<StockCountView> listCounts(UUID shopId);
}

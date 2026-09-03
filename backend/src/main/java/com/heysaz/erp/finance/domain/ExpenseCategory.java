package com.heysaz.erp.finance.domain;

import java.time.Instant;
import java.util.UUID;

import com.heysaz.erp.platform.money.Money;

/**
 * FR-FIN-001 supporting data. Kept in {@code domain} so that no other module can reach
 * it — cross-module callers get the view type from {@code finance.api} instead.
 */
public record ExpenseCategory(
        UUID id,
        UUID orgId,
        String code,
        String name,
        Money monthlyBudget,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {
}

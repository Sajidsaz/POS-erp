package com.heysaz.erp.finance.api;

import java.util.List;
import java.util.UUID;

import com.heysaz.erp.platform.money.Money;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The finance module's public surface. Other modules depend on this interface and on the
 * types declared here; {@code finance.domain} and {@code finance.internal} are off limits
 * to them, and {@code ModuleBoundaryTest} enforces it.
 */
public interface ExpenseCategoryService {

    record CreateCommand(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 120) String name,
            Money monthlyBudget) {
    }

    record View(UUID id, String code, String name, Money monthlyBudget, boolean active) {
    }

    /**
     * @param idempotencyKey when present, a repeat of the same key returns the original
     *                       result rather than creating a second category (FR-API-011).
     */
    View create(CreateCommand command, String idempotencyKey);

    List<View> listActive();

    View get(UUID id);
}

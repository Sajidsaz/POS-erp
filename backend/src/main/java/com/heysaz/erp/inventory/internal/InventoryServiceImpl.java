package com.heysaz.erp.inventory.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heysaz.erp.inventory.api.InventoryService;
import com.heysaz.erp.inventory.api.MovementType;
import com.heysaz.erp.platform.audit.AuditService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.idempotency.IdempotencyService;
import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.platform.outbox.OutboxPublisher;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;

@Service
class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository repository;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final OutboxPublisher outbox;

    InventoryServiceImpl(InventoryRepository repository, IdempotencyService idempotency,
                         AuditService audit, OutboxPublisher outbox) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Override
    @Transactional(readOnly = true)
    public StockBalanceView getStockBalance(UUID shopId, UUID variantId) {
        checkShopAccess(shopId);
        return repository.findBalanceView(shopId, variantId)
                .orElseGet(() -> new StockBalanceView(
                        UUID.randomUUID(), shopId, variantId, "", "",
                        BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                        Money.ZERO, null, null, null, java.time.Instant.now()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockBalanceView> listStockBalances(UUID shopId, UUID categoryId, String search, Boolean lowStockOnly) {
        checkShopAccess(shopId);
        return repository.listBalances(shopId, categoryId, search, lowStockOnly);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockMovementView> listStockMovements(UUID shopId, UUID variantId, int limit) {
        checkShopAccess(shopId);
        int capped = Math.clamp(limit, 1, 500);
        return repository.listMovements(shopId, variantId, capped);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal reconstructBalance(UUID shopId, UUID variantId) {
        checkShopAccess(shopId);
        return repository.sumMovements(shopId, variantId).setScale(4, RoundingMode.HALF_UP);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LowStockAlertView> getLowStockAlerts(UUID shopId) {
        checkShopAccess(shopId);
        return repository.getLowStockAlerts(shopId);
    }

    @Override
    @Transactional
    public StockAdjustmentView adjustStock(AdjustStockCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doAdjustStock(command);
        }
        return idempotency.execute("POST /api/v1/inventory/adjustments", idempotencyKey, command,
                StockAdjustmentView.class, () -> doAdjustStock(command)).value();
    }

    private StockAdjustmentView doAdjustStock(AdjustStockCommand command) {
        checkShopAccess(command.shopId());
        UUID orgId = TenantContext.requireOrgId();
        Principal principal = TenantContext.requirePrincipal();

        List<UUID> variantIds = command.lines().stream()
                .map(StockAdjustmentLineInput::variantId)
                .distinct()
                .toList();

        // Lock balances in deterministic order (Decision D6)
        Map<UUID, InventoryRepository.BalanceRow> lockedMap = repository
                .lockBalances(command.shopId(), variantIds)
                .stream()
                .collect(Collectors.toMap(InventoryRepository.BalanceRow::variantId, b -> b));

        boolean allowNegative = repository.isNegativeStockAllowed(command.shopId())
                || principal.hasAuthority("negative_stock.override");

        UUID adjustmentId = UUID.randomUUID();
        repository.insertAdjustment(adjustmentId, orgId, command.shopId(),
                command.reason(), command.notes(), principal.userId());

        for (StockAdjustmentLineInput line : command.lines()) {
            InventoryRepository.BalanceRow balance = lockedMap.get(line.variantId());
            if (balance == null) {
                throw ApiException.notFound("Variant " + line.variantId());
            }

            BigDecimal delta = line.quantityDelta().setScale(4, RoundingMode.HALF_UP);
            if (delta.signum() == 0) {
                continue;
            }

            BigDecimal currentOnHand = balance.quantityOnHand();
            BigDecimal newOnHand = currentOnHand.add(delta).setScale(4, RoundingMode.HALF_UP);

            if (newOnHand.signum() < 0 && !allowNegative) {
                throw ApiException.conflict("Negative stock not permitted for variant " + line.variantId());
            }

            BigDecimal currentAvgCost = balance.averageCost() == null ? BigDecimal.ZERO : balance.averageCost();
            BigDecimal unitCostAmount;
            BigDecimal newAvgCost;

            if (delta.signum() > 0) {
                // Inbound adjustment: update moving average cost (Decision D2)
                unitCostAmount = (line.unitCost() != null)
                        ? line.unitCost().amount()
                        : currentAvgCost;
                if (currentOnHand.signum() <= 0) {
                    newAvgCost = unitCostAmount;
                } else {
                    BigDecimal currentTotalVal = currentOnHand.multiply(currentAvgCost);
                    BigDecimal incomingVal = delta.multiply(unitCostAmount);
                    newAvgCost = currentTotalVal.add(incomingVal)
                            .divide(newOnHand, 4, RoundingMode.HALF_UP);
                }
            } else {
                // Outbound adjustment / write-off: issues occur at current moving average cost
                unitCostAmount = currentAvgCost;
                newAvgCost = currentAvgCost;
            }

            BigDecimal totalCost = delta.abs().multiply(unitCostAmount).setScale(4, RoundingMode.HALF_UP);

            repository.updateBalance(command.shopId(), line.variantId(), newOnHand,
                    balance.quantityReserved(), balance.quantityInTransit(), newAvgCost);

            MovementType movType = delta.signum() > 0 ? MovementType.ADJUSTMENT : MovementType.WRITE_OFF;

            repository.insertMovement(
                    UUID.randomUUID(), orgId, command.shopId(), line.variantId(),
                    movType, delta, unitCostAmount, totalCost, newOnHand, newAvgCost,
                    "STOCK_ADJUSTMENT", adjustmentId.toString(),
                    line.reason() != null ? line.reason() : command.reason(),
                    principal.userId());

            repository.insertAdjustmentLine(UUID.randomUUID(), orgId, adjustmentId,
                    line.variantId(), delta, unitCostAmount, line.reason());
        }

        StockAdjustmentView view = repository.findAdjustment(adjustmentId).orElseThrow();
        audit.record("inventory.stock_adjusted", "StockAdjustment", adjustmentId.toString(),
                null, view, AuditService.Outcome.SUCCESS);
        outbox.publish("inventory.stock_adjusted", "StockAdjustment", adjustmentId.toString(), view);
        return view;
    }

    @Override
    @Transactional
    public void setThresholds(UUID shopId, UUID variantId, SetThresholdsCommand command) {
        checkShopAccess(shopId);
        // Ensure balance record exists
        repository.lockBalances(shopId, List.of(variantId));
        repository.setThresholds(shopId, variantId, command.reorderPoint(),
                command.reorderQuantity(), command.lowStockThreshold());
    }

    @Override
    @Transactional
    public StockMovementView recordMovement(
            UUID shopId, UUID variantId, MovementType type, BigDecimal quantity,
            Money unitCost, String referenceType, String referenceId, String reason) {
        UUID orgId = TenantContext.requireOrgId();
        Principal principal = TenantContext.principal().orElse(null);
        UUID actor = principal != null ? principal.userId() : null;

        List<InventoryRepository.BalanceRow> locked = repository.lockBalances(shopId, List.of(variantId));
        if (locked.isEmpty()) {
            throw ApiException.notFound("Variant " + variantId);
        }
        InventoryRepository.BalanceRow balance = locked.getFirst();

        BigDecimal qty = quantity.setScale(4, RoundingMode.HALF_UP);
        BigDecimal currentOnHand = balance.quantityOnHand();
        BigDecimal newOnHand = currentOnHand.add(qty).setScale(4, RoundingMode.HALF_UP);

        boolean allowNegative = repository.isNegativeStockAllowed(shopId)
                || (principal != null && principal.hasAuthority("negative_stock.override"));

        if (newOnHand.signum() < 0 && !allowNegative) {
            throw ApiException.conflict("Negative stock not permitted for variant " + variantId);
        }

        BigDecimal currentAvgCost = balance.averageCost() == null ? BigDecimal.ZERO : balance.averageCost();
        BigDecimal unitCostAmount;
        BigDecimal newAvgCost;

        if (qty.signum() > 0) {
            unitCostAmount = (unitCost != null) ? unitCost.amount() : currentAvgCost;
            if (currentOnHand.signum() <= 0) {
                newAvgCost = unitCostAmount;
            } else {
                BigDecimal currentVal = currentOnHand.multiply(currentAvgCost);
                BigDecimal incomingVal = qty.multiply(unitCostAmount);
                newAvgCost = currentVal.add(incomingVal).divide(newOnHand, 4, RoundingMode.HALF_UP);
            }
        } else {
            unitCostAmount = currentAvgCost;
            newAvgCost = currentAvgCost;
        }

        BigDecimal totalCost = qty.abs().multiply(unitCostAmount).setScale(4, RoundingMode.HALF_UP);

        repository.updateBalance(shopId, variantId, newOnHand,
                balance.quantityReserved(), balance.quantityInTransit(), newAvgCost);

        UUID movementId = UUID.randomUUID();
        repository.insertMovement(
                movementId, orgId, shopId, variantId, type, qty, unitCostAmount, totalCost,
                newOnHand, newAvgCost, referenceType, referenceId, reason, actor);

        return new StockMovementView(
                movementId, shopId, variantId, "", type, qty,
                Money.of(unitCostAmount), Money.of(totalCost), newOnHand,
                Money.of(newAvgCost), referenceType, referenceId, reason, actor,
                java.time.Instant.now());
    }

    private void checkShopAccess(UUID shopId) {
        Principal principal = TenantContext.principal().orElse(null);
        if (principal != null && !principal.coversShop(shopId)) {
            throw ApiException.forbidden("Not authorized for shop " + shopId);
        }
    }
}

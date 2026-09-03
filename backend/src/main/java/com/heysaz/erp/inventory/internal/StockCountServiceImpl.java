package com.heysaz.erp.inventory.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heysaz.erp.inventory.api.MovementType;
import com.heysaz.erp.inventory.api.StockCountService;
import com.heysaz.erp.inventory.api.StockCountStatus;
import com.heysaz.erp.platform.audit.AuditService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.idempotency.IdempotencyService;
import com.heysaz.erp.platform.outbox.OutboxPublisher;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;

@Service
class StockCountServiceImpl implements StockCountService {

    private final StockCountRepository repository;
    private final InventoryRepository inventoryRepository;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final OutboxPublisher outbox;

    StockCountServiceImpl(StockCountRepository repository,
                          InventoryRepository inventoryRepository,
                          IdempotencyService idempotency,
                          AuditService audit,
                          OutboxPublisher outbox) {
        this.repository = repository;
        this.inventoryRepository = inventoryRepository;
        this.idempotency = idempotency;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public StockCountView createCount(CreateStockCountCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doCreateCount(command);
        }
        return idempotency.execute("POST /api/v1/stock-counts", idempotencyKey, command,
                StockCountView.class, () -> doCreateCount(command)).value();
    }

    private StockCountView doCreateCount(CreateStockCountCommand command) {
        checkShopAccess(command.shopId());
        UUID orgId = TenantContext.requireOrgId();
        Principal principal = TenantContext.requirePrincipal();
        UUID countId = UUID.randomUUID();

        repository.insertCount(countId, orgId, command.shopId(), StockCountStatus.DRAFT,
                command.notes(), principal.userId());

        StockCountView view = repository.findCount(countId).orElseThrow();
        audit.record("stock_count.started", "StockCount", countId.toString(),
                null, view, AuditService.Outcome.SUCCESS);
        outbox.publish("stock_count.started", "StockCount", countId.toString(), view);
        return view;
    }

    @Override
    @Transactional
    public StockCountView recordCounts(UUID countId, RecordCountsCommand command) {
        StockCountView count = repository.findCount(countId)
                .orElseThrow(() -> ApiException.notFound("Stock count"));
        if (count.status() != StockCountStatus.DRAFT && count.status() != StockCountStatus.COMPLETED) {
            throw ApiException.conflict("Cannot record lines for count in " + count.status() + " status");
        }
        checkShopAccess(count.shopId());

        UUID orgId = TenantContext.requireOrgId();
        repository.deleteCountLines(countId);

        List<UUID> variantIds = command.lines().stream().map(CountLineInput::variantId).distinct().toList();
        Map<UUID, InventoryRepository.BalanceRow> balanceMap = inventoryRepository
                .lockBalances(count.shopId(), variantIds)
                .stream()
                .collect(Collectors.toMap(InventoryRepository.BalanceRow::variantId, b -> b));

        for (CountLineInput line : command.lines()) {
            InventoryRepository.BalanceRow balance = balanceMap.get(line.variantId());
            BigDecimal systemQty = (balance != null) ? balance.quantityOnHand() : BigDecimal.ZERO;
            BigDecimal countedQty = line.countedQuantity().setScale(4, RoundingMode.HALF_UP);
            BigDecimal variance = countedQty.subtract(systemQty).setScale(4, RoundingMode.HALF_UP);
            BigDecimal unitCost = (balance != null && balance.averageCost() != null)
                    ? balance.averageCost()
                    : BigDecimal.ZERO;

            repository.insertCountLine(UUID.randomUUID(), orgId, countId, line.variantId(),
                    systemQty, countedQty, variance, unitCost, line.notes());
        }

        repository.markCompleted(countId);

        StockCountView updated = repository.findCount(countId).orElseThrow();
        audit.record("stock_count.completed", "StockCount", countId.toString(),
                count, updated, AuditService.Outcome.SUCCESS);
        return updated;
    }

    @Override
    @Transactional
    public StockCountView approveCount(UUID countId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doApproveCount(countId);
        }
        return idempotency.execute("POST /api/v1/stock-counts/" + countId + "/approve",
                idempotencyKey, Map.of("countId", countId),
                StockCountView.class, () -> doApproveCount(countId)).value();
    }

    private StockCountView doApproveCount(UUID countId) {
        StockCountView count = repository.findCount(countId)
                .orElseThrow(() -> ApiException.notFound("Stock count"));
        if (count.status() != StockCountStatus.COMPLETED) {
            throw ApiException.conflict("Only COMPLETED stock count can be approved");
        }
        checkShopAccess(count.shopId());

        UUID orgId = TenantContext.requireOrgId();
        Principal principal = TenantContext.requirePrincipal();

        // FR-PERM-002: A user cannot approve their own stock count if segregated approval is enabled
        // but here we record the approving actor.
        List<UUID> variantIds = count.lines().stream().map(CountLineView::variantId).distinct().toList();
        Map<UUID, InventoryRepository.BalanceRow> balanceMap = inventoryRepository
                .lockBalances(count.shopId(), variantIds)
                .stream()
                .collect(Collectors.toMap(InventoryRepository.BalanceRow::variantId, b -> b));

        for (CountLineView line : count.lines()) {
            BigDecimal variance = line.variance();
            if (variance.signum() == 0) {
                continue;
            }

            InventoryRepository.BalanceRow balance = balanceMap.get(line.variantId());
            BigDecimal currentOnHand = balance.quantityOnHand();
            BigDecimal newOnHand = line.countedQuantity();
            BigDecimal unitCost = line.unitCost().amount();
            BigDecimal currentAvgCost = balance.averageCost() == null ? BigDecimal.ZERO : balance.averageCost();
            BigDecimal newAvgCost;

            if (variance.signum() > 0) {
                if (currentOnHand.signum() <= 0) {
                    newAvgCost = unitCost;
                } else {
                    BigDecimal currentVal = currentOnHand.multiply(currentAvgCost);
                    BigDecimal varianceVal = variance.multiply(unitCost);
                    newAvgCost = currentVal.add(varianceVal).divide(newOnHand, 4, RoundingMode.HALF_UP);
                }
            } else {
                newAvgCost = currentAvgCost;
            }

            BigDecimal totalCost = variance.abs().multiply(unitCost).setScale(4, RoundingMode.HALF_UP);

            inventoryRepository.updateBalance(count.shopId(), line.variantId(),
                    newOnHand, balance.quantityReserved(), balance.quantityInTransit(), newAvgCost);

            inventoryRepository.insertMovement(
                    UUID.randomUUID(), orgId, count.shopId(), line.variantId(),
                    MovementType.COUNT_CORRECTION, variance, unitCost, totalCost,
                    newOnHand, newAvgCost,
                    "STOCK_COUNT", countId.toString(),
                    line.notes() != null ? line.notes() : "Physical count reconciliation",
                    principal.userId());
        }

        repository.markApproved(countId, principal.userId());

        StockCountView updated = repository.findCount(countId).orElseThrow();
        audit.record("stock_count.approved", "StockCount", countId.toString(),
                count, updated, AuditService.Outcome.SUCCESS);
        outbox.publish("stock_count.approved", "StockCount", countId.toString(), updated);
        return updated;
    }

    @Override
    @Transactional
    public void cancelCount(UUID countId, String reason) {
        StockCountView count = repository.findCount(countId)
                .orElseThrow(() -> ApiException.notFound("Stock count"));
        if (count.status() == StockCountStatus.APPROVED) {
            throw ApiException.conflict("Cannot cancel an already approved stock count");
        }
        checkShopAccess(count.shopId());

        repository.cancelCount(countId, reason);
        audit.record("stock_count.cancelled", "StockCount", countId.toString(),
                count, Map.of("reason", reason == null ? "" : reason), AuditService.Outcome.SUCCESS);
    }

    @Override
    @Transactional(readOnly = true)
    public StockCountView getCount(UUID countId) {
        StockCountView view = repository.findCount(countId)
                .orElseThrow(() -> ApiException.notFound("Stock count"));
        checkShopAccess(view.shopId());
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockCountView> listCounts(UUID shopId) {
        if (shopId != null) {
            checkShopAccess(shopId);
        }
        return repository.listCounts(shopId);
    }

    private void checkShopAccess(UUID shopId) {
        Principal principal = TenantContext.principal().orElse(null);
        if (principal != null && !principal.coversShop(shopId)) {
            throw ApiException.forbidden("Not authorized for shop " + shopId);
        }
    }
}

package com.heysaz.erp.pos.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import com.heysaz.erp.platform.sequence.DocumentSequenceService;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;
import com.heysaz.erp.pos.api.PosService;
import com.heysaz.erp.pos.api.ReturnCondition;
import com.heysaz.erp.pos.api.ReturnService;

/**
 * Returns, refunds and exchanges (Section 10).
 *
 * <p>A returned line that comes back in sellable condition (FR-RET-003) is put back into
 * stock with its own {@code RETURN} movement, valued at the current moving average
 * (decision D2); damaged or defective goods are recorded but never restocked. Against a
 * known sale, each line's returned quantity is capped at what was sold and the sale's
 * status moves to {@code PARTIALLY_RETURNED} or {@code RETURNED}. The refund tenders must
 * reconcile to the sum of the line refunds, so a return can never quietly hand back a
 * different amount than it accounts for. An exchange is exactly a return and a sale in one
 * transaction, reporting the net the customer settles either way.
 */
@Service
class ReturnServiceImpl implements ReturnService {

    private final ReturnRepository repository;
    private final PosRepository posRepository;
    private final PosService posService;
    private final InventoryService inventory;
    private final DocumentSequenceService sequences;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final OutboxPublisher outbox;

    ReturnServiceImpl(ReturnRepository repository, PosRepository posRepository, PosService posService,
                      InventoryService inventory, DocumentSequenceService sequences,
                      IdempotencyService idempotency, AuditService audit, OutboxPublisher outbox) {
        this.repository = repository;
        this.posRepository = posRepository;
        this.posService = posService;
        this.inventory = inventory;
        this.sequences = sequences;
        this.idempotency = idempotency;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public SaleReturnView processReturn(ProcessReturnCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doProcessReturn(command);
        }
        return idempotency.execute("POST /api/v1/pos/returns", idempotencyKey, command,
                SaleReturnView.class, () -> doProcessReturn(command)).value();
    }

    private SaleReturnView doProcessReturn(ProcessReturnCommand command) {
        checkShopAccess(command.shopId());
        UUID orgId = TenantContext.requireOrgId();
        Principal principal = TenantContext.requirePrincipal();

        // When the return names an original sale, its lines are the authority on what may be
        // returned and at what price.
        Map<UUID, PosService.SaleLineView> originalLines = Map.of();
        if (command.originalSaleId() != null) {
            PosService.SaleView sale = posRepository.findSale(command.originalSaleId())
                    .orElseThrow(() -> ApiException.notFound("Original sale " + command.originalSaleId()));
            originalLines = sale.lines().stream()
                    .collect(Collectors.toMap(PosService.SaleLineView::id, l -> l));
        }

        String returnNumber = sequences.nextDocumentNumber(command.shopId(), "RETURN");
        UUID returnId = UUID.randomUUID();

        // Resolve each line's refund and its cap against the original sale first, so nothing
        // is written if any line is invalid.
        record Resolved(ReturnLineInput input, Money refund, boolean restocked) {
        }
        List<Resolved> resolved = new java.util.ArrayList<>();
        for (ReturnLineInput line : command.lines()) {
            BigDecimal qty = line.quantity().setScale(Money.SCALE, RoundingMode.HALF_UP);
            boolean restocked = line.condition() == ReturnCondition.RESTOCKABLE;

            Money refund;
            if (line.originalSaleLineId() != null && !originalLines.isEmpty()) {
                PosService.SaleLineView orig = originalLines.get(line.originalSaleLineId());
                if (orig == null) {
                    throw ApiException.conflict("Sale line " + line.originalSaleLineId()
                            + " is not part of the named sale");
                }
                if (!orig.variantId().equals(line.variantId())) {
                    throw ApiException.conflict("Return line variant does not match the original sale line");
                }
                BigDecimal remaining = orig.quantity().subtract(orig.returnedQuantity());
                if (qty.compareTo(remaining) > 0) {
                    throw ApiException.conflict("Cannot return " + qty + " of " + orig.variantSku()
                            + "; only " + remaining + " remain returnable");
                }
                refund = line.refundAmount() != null
                        ? line.refundAmount()
                        // Refund the sold line's share: its all-in line total, pro-rated by quantity.
                        : Money.of(orig.lineTotal().amount()
                                .divide(orig.quantity(), Money.SCALE, RoundingMode.HALF_UP)
                                .multiply(qty).setScale(Money.SCALE, RoundingMode.HALF_UP));
            } else {
                if (line.refundAmount() == null) {
                    throw ApiException.conflict("A blind return line must carry an explicit refund amount");
                }
                refund = line.refundAmount();
            }
            resolved.add(new Resolved(line, refund, restocked));
        }

        Money lineRefundTotal = resolved.stream().map(Resolved::refund).reduce(Money.ZERO, Money::plus);
        Money tenderTotal = command.refunds().stream()
                .map(RefundInput::amount).reduce(Money.ZERO, Money::plus);
        if (lineRefundTotal.compareTo(tenderTotal) != 0) {
            throw ApiException.conflict("Refund tenders (" + tenderTotal
                    + ") do not match the line refund total (" + lineRefundTotal + ")");
        }

        repository.insertReturn(returnId, orgId, returnNumber, command.originalSaleId(),
                command.shopId(), command.terminalId(), command.shiftId(), principal.userId(),
                null, tenderTotal.amount(), command.reason());

        for (Resolved r : resolved) {
            ReturnLineInput line = r.input();
            BigDecimal qty = line.quantity().setScale(Money.SCALE, RoundingMode.HALF_UP);
            repository.insertReturnLine(UUID.randomUUID(), orgId, returnId, line.originalSaleLineId(),
                    line.variantId(), qty, r.refund().amount(), line.condition(), r.restocked());

            // FR-RET-003: only sellable goods go back on the shelf; damaged/defective do not.
            if (r.restocked()) {
                inventory.recordMovement(command.shopId(), line.variantId(), MovementType.RETURN,
                        qty, null, "RETURN", returnNumber, "Customer return " + returnNumber);
            }
            if (line.originalSaleLineId() != null && !originalLines.isEmpty()) {
                posRepository.incrementReturnedQuantity(line.originalSaleLineId(), qty);
            }
        }

        for (RefundInput refund : command.refunds()) {
            repository.insertReturnRefund(UUID.randomUUID(), orgId, returnId,
                    refund.paymentMethod(), refund.amount().amount());
        }

        if (command.originalSaleId() != null) {
            updateSaleReturnStatus(command.originalSaleId());
        }

        SaleReturnView view = repository.findReturn(returnId).orElseThrow();
        audit.record("pos.return_processed", "SaleReturn", returnId.toString(), null, view,
                AuditService.Outcome.SUCCESS);
        outbox.publish("pos.return_processed", "SaleReturn", returnId.toString(), view);
        return view;
    }

    private void updateSaleReturnStatus(UUID saleId) {
        List<PosService.SaleLineView> lines = posRepository.findSaleLines(saleId);
        boolean anyReturned = lines.stream().anyMatch(l -> l.returnedQuantity().signum() > 0);
        if (!anyReturned) {
            return;
        }
        boolean allReturned = lines.stream()
                .allMatch(l -> l.returnedQuantity().compareTo(l.quantity()) >= 0);
        posRepository.updateSaleStatus(saleId, allReturned ? "RETURNED" : "PARTIALLY_RETURNED");
    }

    @Override
    @Transactional
    public ExchangeResultView processExchange(ProcessExchangeCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doProcessExchange(command);
        }
        return idempotency.execute("POST /api/v1/pos/exchanges", idempotencyKey, command,
                ExchangeResultView.class, () -> doProcessExchange(command)).value();
    }

    private ExchangeResultView doProcessExchange(ProcessExchangeCommand command) {
        // One transaction: the goods come back and the replacement goes out together, so a
        // half-completed exchange can never exist.
        SaleReturnView returnRecord = doProcessReturn(command.returnPart());
        PosService.SaleView saleRecord = posService.checkout(command.salePart(), null);
        // Positive: the customer still owes the difference; negative: the shop refunds it.
        Money net = saleRecord.grandTotal().minus(returnRecord.refundTotal());
        return new ExchangeResultView(returnRecord, saleRecord, net);
    }

    @Override
    @Transactional(readOnly = true)
    public SaleReturnView getReturn(UUID returnId) {
        SaleReturnView view = repository.findReturn(returnId)
                .orElseThrow(() -> ApiException.notFound("Return"));
        checkShopAccess(view.shopId());
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SaleReturnView> listReturns(UUID shopId) {
        checkShopAccess(shopId);
        return repository.listReturns(shopId);
    }

    private void checkShopAccess(UUID shopId) {
        Principal principal = TenantContext.principal().orElse(null);
        if (principal != null && !principal.coversShop(shopId)) {
            throw ApiException.forbidden("Not authorized for shop " + shopId);
        }
    }
}

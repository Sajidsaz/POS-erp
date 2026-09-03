package com.heysaz.erp.pos.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heysaz.erp.platform.audit.AuditService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.idempotency.IdempotencyService;
import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.platform.outbox.OutboxPublisher;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;
import com.heysaz.erp.pos.api.ShiftService;
import com.heysaz.erp.pos.api.ShiftStatus;

/**
 * Cashier shifts and the cash drawer (Section 7.3).
 *
 * <p>Expected cash is a derived figure, never stored as a running balance: opening float,
 * plus cash taken in sales, less cash refunded, plus and minus recorded cash movements.
 * Each cash sale's payment amount is already net of change and of any FR-POS-014 rounding,
 * so those appear on the report for the cashier's eyes but are not added again. The X-report
 * is this same computation without closing the shift; the Z-report closes it and records the
 * counted cash and the variance (FR-SHIFT-005).
 */
@Service
class ShiftServiceImpl implements ShiftService {

    private final ShiftRepository repository;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final OutboxPublisher outbox;

    ShiftServiceImpl(ShiftRepository repository, IdempotencyService idempotency,
                     AuditService audit, OutboxPublisher outbox) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public ShiftSummaryView openShift(OpenShiftCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doOpenShift(command);
        }
        return idempotency.execute("POST /api/v1/pos/shifts", idempotencyKey, command,
                ShiftSummaryView.class, () -> doOpenShift(command)).value();
    }

    private ShiftSummaryView doOpenShift(OpenShiftCommand command) {
        checkShopAccess(command.shopId());
        UUID orgId = TenantContext.requireOrgId();
        Principal principal = TenantContext.requirePrincipal();

        // FR-SHIFT-002: one open shift per terminal and per cashier. The partial unique
        // indexes are the real guard against a race; these checks turn the common case into
        // a clear 409 rather than a constraint-violation surprise.
        if (repository.hasOpenShiftForTerminal(command.terminalId())) {
            throw ApiException.conflict("A shift is already open on this terminal");
        }
        if (repository.hasOpenShiftForCashier(principal.userId())) {
            throw ApiException.conflict("This cashier already has an open shift");
        }

        UUID shiftId = UUID.randomUUID();
        repository.insertShift(shiftId, orgId, command.shopId(), command.terminalId(),
                principal.userId(), command.openingCash().amount(), command.notes());

        ShiftSummaryView view = buildSummary(shiftId);
        audit.record("pos.shift_opened", "Shift", shiftId.toString(), null, view,
                AuditService.Outcome.SUCCESS);
        outbox.publish("pos.shift_opened", "Shift", shiftId.toString(), view);
        return view;
    }

    @Override
    @Transactional
    public ShiftMovementView recordMovement(UUID shiftId, RecordShiftMovementCommand command) {
        ShiftRepository.ShiftHeader header = requireOpenShift(shiftId);
        checkShopAccess(header.shopId());
        UUID orgId = TenantContext.requireOrgId();
        Principal principal = TenantContext.requirePrincipal();

        String type = command.movementType();
        if (!List.of("CASH_IN", "CASH_OUT", "DRAWER_OPEN", "EXPENSE").contains(type)) {
            throw ApiException.conflict("Unknown shift movement type: " + type);
        }

        UUID id = UUID.randomUUID();
        repository.insertMovement(id, orgId, shiftId, type, command.amount().amount(),
                command.reason(), principal.userId());
        audit.record("pos.shift_movement", "Shift", shiftId.toString(), null, command,
                AuditService.Outcome.SUCCESS);
        return repository.findMovements(shiftId).stream()
                .filter(m -> m.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public XReportView getXReport(UUID shiftId) {
        ShiftRepository.ShiftHeader header = requireShift(shiftId);
        checkShopAccess(header.shopId());
        ShiftRepository.CashTotals t = repository.totalsFor(shiftId);
        Money expected = expectedCash(header.openingCash(), t);
        Money totalSales = t.cashSales().plus(t.cardSales()).plus(t.otherSales());

        return new XReportView(
                shiftId, repository.shopName(header.shopId()),
                repository.terminalCode(header.terminalId()),
                repository.cashierName(header.cashierUserId()),
                header.openedAt(), Instant.now(),
                Money.of(header.openingCash()), t.cashSales(), t.cardSales(), t.otherSales(),
                totalSales, t.cashRefunds(), t.changeGiven(), t.cashIn(), t.cashOut(),
                t.cashExpenses(), t.cashRounding(), expected, t.salesCount(), t.returnsCount());
    }

    @Override
    @Transactional
    public ZReportView closeShift(UUID shiftId, CloseShiftCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doCloseShift(shiftId, command);
        }
        return idempotency.execute("POST /api/v1/pos/shifts/" + shiftId + "/close",
                idempotencyKey, command, ZReportView.class, () -> doCloseShift(shiftId, command)).value();
    }

    private ZReportView doCloseShift(UUID shiftId, CloseShiftCommand command) {
        ShiftRepository.ShiftHeader header = requireOpenShift(shiftId);
        checkShopAccess(header.shopId());
        Principal principal = TenantContext.requirePrincipal();

        ShiftRepository.CashTotals t = repository.totalsFor(shiftId);
        Money expected = expectedCash(header.openingCash(), t);
        Money counted = command.closingCash();
        Money variance = counted.minus(expected);

        repository.markClosed(shiftId, counted.amount(), expected.amount(), variance.amount(),
                command.notes(), principal.userId());

        Money totalSales = t.cashSales().plus(t.cardSales()).plus(t.otherSales());
        ShiftRepository.ShiftHeader closed = repository.findHeader(shiftId).orElseThrow();

        ZReportView z = new ZReportView(
                shiftId, repository.shopName(header.shopId()),
                repository.terminalCode(header.terminalId()),
                repository.cashierName(header.cashierUserId()),
                header.openedAt(), closed.closedAt(),
                Money.of(header.openingCash()), t.cashSales(), t.cardSales(), t.otherSales(),
                totalSales, t.cashRefunds(), t.changeGiven(), t.cashIn(), t.cashOut(),
                t.cashExpenses(), t.cashRounding(), expected, counted, variance,
                t.salesCount(), t.returnsCount(), closed.notes());

        audit.record("pos.shift_closed", "Shift", shiftId.toString(), null, z,
                AuditService.Outcome.SUCCESS);
        outbox.publish("pos.shift_closed", "Shift", shiftId.toString(), z);
        return z;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShiftSummaryView> getCurrentShift(UUID terminalId) {
        return repository.findOpenShiftIdForTerminal(terminalId).map(this::buildSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public ShiftSummaryView getShift(UUID shiftId) {
        ShiftRepository.ShiftHeader header = requireShift(shiftId);
        checkShopAccess(header.shopId());
        return buildSummary(shiftId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShiftSummaryView> listShifts(UUID shopId) {
        checkShopAccess(shopId);
        return repository.listForShop(shopId).stream().map(h -> buildSummary(h.id())).toList();
    }

    // ------------------------------------------------------------------ Helpers

    private Money expectedCash(java.math.BigDecimal openingCash, ShiftRepository.CashTotals t) {
        return Money.of(openingCash)
                .plus(t.cashSales())
                .minus(t.cashRefunds())
                .plus(t.cashIn())
                .minus(t.cashOut())
                .minus(t.cashExpenses());
    }

    private ShiftSummaryView buildSummary(UUID shiftId) {
        ShiftRepository.ShiftHeader h = repository.findHeader(shiftId)
                .orElseThrow(() -> ApiException.notFound("Shift"));
        ShiftRepository.CashTotals t = repository.totalsFor(shiftId);
        Money expected = expectedCash(h.openingCash(), t);
        return new ShiftSummaryView(
                h.id(), h.shopId(), h.terminalId(), h.cashierUserId(),
                ShiftStatus.valueOf(h.status()),
                Money.of(h.openingCash()), t.cashSales(), t.changeGiven(), t.cashRefunds(),
                t.cashIn(), t.cashOut(), t.cashExpenses(), t.cashRounding(), expected,
                Money.ofNullable(h.closingCash()), Money.ofNullable(h.cashVariance()),
                h.openedAt(), h.closedAt(), h.notes(), repository.findMovements(shiftId));
    }

    private ShiftRepository.ShiftHeader requireShift(UUID shiftId) {
        return repository.findHeader(shiftId)
                .orElseThrow(() -> ApiException.notFound("Shift " + shiftId));
    }

    private ShiftRepository.ShiftHeader requireOpenShift(UUID shiftId) {
        ShiftRepository.ShiftHeader header = requireShift(shiftId);
        if (!"OPEN".equals(header.status())) {
            throw ApiException.conflict("Shift " + shiftId + " is " + header.status() + ", not OPEN");
        }
        return header;
    }

    private void checkShopAccess(UUID shopId) {
        Principal principal = TenantContext.principal().orElse(null);
        if (principal != null && !principal.coversShop(shopId)) {
            throw ApiException.forbidden("Not authorized for shop " + shopId);
        }
    }
}

package com.heysaz.erp.pos.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heysaz.erp.catalog.api.PricingService;
import com.heysaz.erp.customer.api.CustomerService;
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
import com.heysaz.erp.pos.api.PaymentMethod;
import com.heysaz.erp.pos.api.PosService;

/**
 * Point-of-sale checkout (Section 7.2).
 *
 * <p>The money arithmetic follows Appendix D decision D4 exactly: base prices are
 * tax-exclusive (a tax-inclusive price list has its exclusive base derived here), tax is
 * computed and rounded <em>per line</em> and then summed, and a cash sale's payable total
 * is rounded to the nearest 1.00 LKR with the difference carried on its own line
 * (FR-POS-014). Stock is decremented under a pessimistic lock taken in variant order
 * (decision D6), and each sale line keeps the cost snapshot read at that moment (decision
 * D2, invariant B7). The whole checkout is one transaction wrapped by the idempotency
 * record (FR-API-012), so a retried request replays the first sale rather than ringing a
 * second.
 */
@Service
class PosServiceImpl implements PosService {

    /** Cash is rounded to whole rupees; nothing smaller circulates. Decision D4. */
    private static final BigDecimal CASH_ROUNDING_INCREMENT = BigDecimal.ONE;

    private final PosRepository repository;
    private final PricingService pricing;
    private final InventoryService inventory;
    private final CustomerService customers;
    private final DocumentSequenceService sequences;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final ObjectMapper mapper;

    PosServiceImpl(PosRepository repository, PricingService pricing, InventoryService inventory,
                   CustomerService customers, DocumentSequenceService sequences,
                   IdempotencyService idempotency, AuditService audit, OutboxPublisher outbox,
                   ObjectMapper mapper) {
        this.repository = repository;
        this.pricing = pricing;
        this.inventory = inventory;
        this.customers = customers;
        this.sequences = sequences;
        this.idempotency = idempotency;
        this.audit = audit;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------- Cart pricing

    /** Everything needed to price one variant: resolved unit price, tax rate, inclusivity. */
    private record PriceContext(String sku, String productName, Money unitPrice,
                                BigDecimal taxRate, boolean taxInclusive) {
    }

    private record LineMath(Money unitPriceExclusive, Money discount, BigDecimal taxRate,
                            Money taxAmount, Money lineGrossExclusive, Money lineTotal) {
    }

    @Override
    @Transactional(readOnly = true)
    public CalculatedCartView calculateCart(CalculateCartCommand command) {
        checkShopAccess(command.shopId());
        Map<UUID, PriceContext> contexts = priceContexts(command.shopId(),
                command.lines().stream().map(CartLineInput::variantId).toList());

        List<CalculatedLineView> lines = new ArrayList<>();
        Money subtotal = Money.ZERO;
        Money discountTotal = Money.ZERO;
        Money taxTotal = Money.ZERO;
        Money grandTotal = Money.ZERO;

        for (CartLineInput line : command.lines()) {
            PriceContext ctx = contexts.get(line.variantId());
            LineMath m = computeLine(line, ctx);
            lines.add(new CalculatedLineView(
                    line.variantId(), ctx.sku(), ctx.productName(), line.quantity(),
                    m.unitPriceExclusive(), m.discount(), m.taxRate(), m.taxAmount(), m.lineTotal()));
            subtotal = subtotal.plus(m.lineGrossExclusive());
            discountTotal = discountTotal.plus(m.discount());
            taxTotal = taxTotal.plus(m.taxAmount());
            grandTotal = grandTotal.plus(m.lineTotal());
        }

        boolean cash = command.primaryPaymentMethod() == PaymentMethod.CASH;
        Money payable = cash ? roundToCash(grandTotal) : grandTotal;
        Money cashRounding = payable.minus(grandTotal);

        return new CalculatedCartView(lines, subtotal, discountTotal, taxTotal, grandTotal,
                cashRounding, payable);
    }

    private Map<UUID, PriceContext> priceContexts(UUID shopId, List<UUID> variantIds) {
        List<UUID> distinct = variantIds.stream().distinct().toList();
        Map<UUID, PosRepository.VariantMeta> meta = repository.findVariantMeta(distinct);
        UUID shopDefaultTaxClass = repository.shopDefaultTaxClass(shopId);
        LocalDate today = LocalDate.now();

        Map<UUID, PriceContext> out = new LinkedHashMap<>();
        for (UUID variantId : distinct) {
            PosRepository.VariantMeta vm = meta.get(variantId);
            if (vm == null) {
                throw ApiException.notFound("Variant " + variantId);
            }
            PricingService.ResolvedPrice resolved = pricing.resolve(variantId, shopId, today);
            boolean inclusive = resolved.priceListId() != null
                    && repository.isPriceListInclusive(resolved.priceListId());

            UUID taxClassId = vm.taxClassId() != null ? vm.taxClassId() : shopDefaultTaxClass;
            BigDecimal rate = taxClassId == null
                    ? BigDecimal.ZERO
                    : pricing.rateOn(taxClassId, today).orElse(BigDecimal.ZERO);

            out.put(variantId, new PriceContext(vm.sku(), vm.productName(), resolved.amount(),
                    rate, inclusive));
        }
        return out;
    }

    private LineMath computeLine(CartLineInput line, PriceContext ctx) {
        BigDecimal qty = line.quantity().setScale(Money.SCALE, RoundingMode.HALF_UP);
        Money discount = line.discountAmount() == null ? Money.ZERO : line.discountAmount();

        // An explicit price at the till is always treated as tax-exclusive, matching the
        // stored convention (decision D4); a resolved price may be inclusive per its list.
        Money quotedUnit = line.unitPrice() != null ? line.unitPrice() : ctx.unitPrice();
        boolean inclusive = line.unitPrice() == null && ctx.taxInclusive();
        BigDecimal rate = ctx.taxRate();

        Money unitExclusive;
        if (inclusive && rate.signum() > 0) {
            unitExclusive = Money.of(quotedUnit.amount()
                    .divide(BigDecimal.ONE.add(rate), Money.SCALE, RoundingMode.HALF_UP));
        } else {
            unitExclusive = quotedUnit;
        }

        Money lineGross = unitExclusive.times(qty);           // exclusive, before discount
        Money net = lineGross.minus(discount);                // taxable base
        if (net.isNegative()) {
            throw ApiException.conflict("Discount exceeds line total for variant " + line.variantId());
        }
        // Decision D4: tax rounded per line, half-up, then summed to the document total.
        Money tax = Money.of(net.amount().multiply(rate).setScale(Money.SCALE, RoundingMode.HALF_UP));
        Money lineTotal = net.plus(tax);
        return new LineMath(unitExclusive, discount, rate, tax, lineGross, lineTotal);
    }

    private Money roundToCash(Money amount) {
        BigDecimal rounded = amount.amount()
                .divide(CASH_ROUNDING_INCREMENT, 0, RoundingMode.HALF_UP)
                .multiply(CASH_ROUNDING_INCREMENT);
        return Money.of(rounded);
    }

    // ---------------------------------------------------------------- Checkout

    @Override
    @Transactional
    public SaleView checkout(CheckoutCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doCheckout(command);
        }
        return idempotency.execute("POST /api/v1/pos/sales", idempotencyKey, command,
                SaleView.class, () -> doCheckout(command)).value();
    }

    private SaleView doCheckout(CheckoutCommand command) {
        checkShopAccess(command.shopId());
        if (!repository.isSellingEnabled(command.shopId())) {
            throw ApiException.conflict("Selling is not enabled for this shop");
        }
        UUID orgId = TenantContext.requireOrgId();
        Principal principal = TenantContext.requirePrincipal();

        boolean priceOverride = command.lines().stream()
                .anyMatch(l -> l.unitPrice() != null
                        || (l.discountAmount() != null && !l.discountAmount().isZero()));
        if (priceOverride && !principal.hasAuthority("price.override.limited")
                && !principal.hasAuthority("price.override.write")) {
            throw ApiException.forbidden("A price override at the till requires the price.override permission");
        }

        UUID shiftId = resolveShift(command.shiftId(), command.terminalId());

        Map<UUID, PriceContext> contexts = priceContexts(command.shopId(),
                command.lines().stream().map(CartLineInput::variantId).toList());

        // Price every line first so the money totals are known before anything is written.
        List<LineMath> lineMaths = command.lines().stream()
                .map(l -> computeLine(l, contexts.get(l.variantId())))
                .toList();

        Money subtotal = Money.ZERO;
        Money discountTotal = Money.ZERO;
        Money taxTotal = Money.ZERO;
        Money grandTotal = Money.ZERO;
        for (LineMath m : lineMaths) {
            subtotal = subtotal.plus(m.lineGrossExclusive());
            discountTotal = discountTotal.plus(m.discount());
            taxTotal = taxTotal.plus(m.taxAmount());
            grandTotal = grandTotal.plus(m.lineTotal());
        }

        boolean anyCash = command.payments().stream().anyMatch(p -> p.method() == PaymentMethod.CASH);
        Money payable = anyCash ? roundToCash(grandTotal) : grandTotal;
        Money cashRounding = payable.minus(grandTotal);

        Money paid = command.payments().stream()
                .map(PaymentInput::amount).reduce(Money.ZERO, Money::plus);
        if (paid.compareTo(payable) != 0) {
            throw ApiException.conflict("Payments (" + paid + ") do not settle the payable total (" + payable + ")");
        }

        Money changeGiven = Money.ZERO;
        for (PaymentInput p : command.payments()) {
            if (p.method() == PaymentMethod.CASH && p.tenderedAmount() != null) {
                Money change = p.tenderedAmount().minus(p.amount());
                if (change.isNegative()) {
                    throw ApiException.conflict("Tendered cash is less than the cash amount applied");
                }
                changeGiven = changeGiven.plus(change);
            }
        }
        Money totalTendered = payable.plus(changeGiven);

        // Decision D5: gapless SALE number, allocated under a row lock in this transaction.
        String invoiceNumber = sequences.nextDocumentNumber(command.shopId(), "SALE");
        UUID saleId = UUID.randomUUID();

        repository.insertSale(saleId, orgId, command.shopId(), command.terminalId(), shiftId,
                principal.userId(), invoiceNumber, "COMPLETED",
                subtotal.amount(), discountTotal.amount(), taxTotal.amount(), grandTotal.amount(),
                cashRounding.amount(), totalTendered.amount(), changeGiven.amount(),
                command.customerId(), command.notes());

        // Decrement stock under a deterministic lock order (decision D6). Aggregate by
        // variant so the same item on two lines locks once, and snapshot the moving-average
        // cost the movement reads back (decision D2 / invariant B7).
        Map<UUID, BigDecimal> quantityByVariant = new LinkedHashMap<>();
        for (CartLineInput line : command.lines()) {
            quantityByVariant.merge(line.variantId(),
                    line.quantity().setScale(Money.SCALE, RoundingMode.HALF_UP), BigDecimal::add);
        }
        Map<UUID, Money> costSnapshot = new LinkedHashMap<>();
        quantityByVariant.keySet().stream().sorted().forEach(variantId -> {
            BigDecimal qty = quantityByVariant.get(variantId);
            InventoryService.StockMovementView movement = inventory.recordMovement(
                    command.shopId(), variantId, MovementType.SALE, qty.negate(), null,
                    "SALE", invoiceNumber, "POS sale " + invoiceNumber);
            costSnapshot.put(variantId, movement.unitCost());
        });

        List<CartLineInput> commandLines = command.lines();
        for (int i = 0; i < commandLines.size(); i++) {
            CartLineInput line = commandLines.get(i);
            LineMath m = lineMaths.get(i);
            Money cost = costSnapshot.getOrDefault(line.variantId(), Money.ZERO);
            repository.insertSaleLine(UUID.randomUUID(), orgId, saleId, line.variantId(),
                    line.quantity().setScale(Money.SCALE, RoundingMode.HALF_UP),
                    m.unitPriceExclusive().amount(), m.discount().amount(),
                    null, m.taxRate(), m.taxAmount().amount(), m.lineTotal().amount(),
                    cost.amount());
        }

        for (PaymentInput p : command.payments()) {
            Money change = Money.ZERO;
            if (p.method() == PaymentMethod.CASH && p.tenderedAmount() != null) {
                change = p.tenderedAmount().minus(p.amount());
            }
            repository.insertSalePayment(UUID.randomUUID(), orgId, saleId, p.method(),
                    p.amount().amount(), p.reference(),
                    p.tenderedAmount() == null ? null : p.tenderedAmount().amount(),
                    change.amount());
        }

        // On-account tender charges the customer's credit; the charge refuses to breach the
        // limit, and because it shares this transaction a rejection unwinds the whole sale.
        Money creditTotal = command.payments().stream()
                .filter(p -> p.method() == PaymentMethod.CREDIT)
                .map(PaymentInput::amount)
                .reduce(Money.ZERO, Money::plus);
        if (!creditTotal.isZero()) {
            if (command.customerId() == null) {
                throw ApiException.conflict("A credit tender requires a customer");
            }
            customers.chargeCredit(command.customerId(), creditTotal, "SALE", invoiceNumber);
        }

        SaleView view = repository.findSale(saleId).orElseThrow();
        audit.record("pos.sale_completed", "Sale", saleId.toString(), null, view,
                AuditService.Outcome.SUCCESS);
        outbox.publish("pos.sale_completed", "Sale", saleId.toString(), view);
        return view;
    }

    private UUID resolveShift(UUID requestedShiftId, UUID terminalId) {
        if (requestedShiftId != null) {
            String status = repository.findShiftStatus(requestedShiftId)
                    .orElseThrow(() -> ApiException.notFound("Shift " + requestedShiftId));
            if (!"OPEN".equals(status)) {
                throw ApiException.conflict("Shift " + requestedShiftId + " is " + status + ", not OPEN");
            }
            return requestedShiftId;
        }
        // A sale outside a shift is permitted (the schema allows a null shift_id); when a
        // shift is open on the terminal, the sale attaches to it so it rolls into the Z-report.
        return repository.findOpenShiftForTerminal(terminalId).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public SaleView getSale(UUID saleId) {
        SaleView view = repository.findSale(saleId).orElseThrow(() -> ApiException.notFound("Sale"));
        checkShopAccess(view.shopId());
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public SaleView getSaleByInvoiceNumber(String invoiceNumber) {
        SaleView view = repository.findSaleByInvoice(invoiceNumber)
                .orElseThrow(() -> ApiException.notFound("Sale " + invoiceNumber));
        checkShopAccess(view.shopId());
        return view;
    }

    // -------------------------------------------------------------- Held carts

    @Override
    @Transactional
    public HeldCartView holdCart(HoldCartCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doHoldCart(command);
        }
        return idempotency.execute("POST /api/v1/pos/held-carts", idempotencyKey, command,
                HeldCartView.class, () -> doHoldCart(command)).value();
    }

    private HeldCartView doHoldCart(HoldCartCommand command) {
        checkShopAccess(command.shopId());
        UUID orgId = TenantContext.requireOrgId();
        Principal principal = TenantContext.requirePrincipal();
        UUID cartId = UUID.randomUUID();
        String payload = writeJson(command.lines());
        repository.insertHeldCart(cartId, orgId, command.shopId(), command.terminalId(),
                principal.userId(), command.reference(), payload);
        return repository.findHeldCart(cartId).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public HeldCartView getHeldCart(UUID cartId) {
        return repository.findHeldCart(cartId).orElseThrow(() -> ApiException.notFound("Held cart"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<HeldCartView> listHeldCarts(UUID shopId) {
        checkShopAccess(shopId);
        return repository.listHeldCarts(shopId);
    }

    @Override
    @Transactional
    public void deleteHeldCart(UUID cartId) {
        repository.findHeldCart(cartId).orElseThrow(() -> ApiException.notFound("Held cart"));
        repository.deleteHeldCart(cartId);
    }

    // ----------------------------------------------------------------- Receipts

    @Override
    @Transactional(readOnly = true)
    public ReceiptView getReceipt(UUID saleId) {
        SaleView sale = getSale(saleId);
        return buildReceipt(sale, false);
    }

    @Override
    @Transactional
    public ReceiptView reprintReceipt(UUID saleId, String reason) {
        SaleView sale = getSale(saleId);
        UUID orgId = TenantContext.requireOrgId();
        Principal principal = TenantContext.requirePrincipal();
        // FR-POS-010: a reprint is an audited event, not a free action.
        repository.insertReceiptReprint(UUID.randomUUID(), orgId, saleId, principal.userId(),
                reason == null ? "" : reason);
        audit.record("pos.receipt_reprinted", "Sale", saleId.toString(), null,
                Map.of("reason", reason == null ? "" : reason), AuditService.Outcome.SUCCESS);
        return buildReceipt(sale, true);
    }

    private ReceiptView buildReceipt(SaleView sale, boolean isReprint) {
        PosRepository.ShopReceiptInfo shop = repository.findShopReceiptInfo(sale.shopId());
        String terminalCode = repository.findTerminalCode(sale.terminalId());
        String cashierName = repository.findCashierName(sale.cashierUserId());

        List<ReceiptLine> lines = sale.lines().stream()
                .map(l -> new ReceiptLine(l.productName(), l.quantity(), l.unitPrice(),
                        l.discountAmount(), l.lineTotal()))
                .toList();

        // Tax breakdown grouped by rate — the receipt shows one row per rate, not per line.
        Map<BigDecimal, Money[]> byRate = new LinkedHashMap<>();
        for (SaleLineView l : sale.lines()) {
            Money net = l.lineTotal().minus(l.taxAmount());
            byRate.merge(l.taxRate(), new Money[] {net, l.taxAmount()}, (a, b) -> {
                a[0] = a[0].plus(b[0]);
                a[1] = a[1].plus(b[1]);
                return a;
            });
        }
        List<ReceiptTaxLine> taxBreakdown = byRate.entrySet().stream()
                .map(e -> new ReceiptTaxLine(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();

        List<ReceiptPaymentLine> payments = sale.payments().stream()
                .map(p -> new ReceiptPaymentLine(p.paymentMethod(), p.amount(), p.reference()))
                .toList();

        return new ReceiptView(
                sale.id(), sale.invoiceNumber(), shop.name(), shop.address(), shop.taxRegNo(),
                sale.createdAt(), cashierName, terminalCode, lines,
                sale.subtotal(), sale.discountTotal(), taxBreakdown, sale.grandTotal(),
                sale.cashRounding(), payments, sale.totalTendered(), sale.changeGiven(),
                shop.header(), shop.footer(), isReprint);
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise held cart", e);
        }
    }

    private void checkShopAccess(UUID shopId) {
        Principal principal = TenantContext.principal().orElse(null);
        if (principal != null && !principal.coversShop(shopId)) {
            throw ApiException.forbidden("Not authorized for shop " + shopId);
        }
    }
}

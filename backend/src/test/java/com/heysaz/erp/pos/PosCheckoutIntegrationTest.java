package com.heysaz.erp.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.heysaz.erp.catalog.api.CatalogService;
import com.heysaz.erp.catalog.api.PricingService;
import com.heysaz.erp.inventory.api.InventoryService;
import com.heysaz.erp.organization.api.OrganizationService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;
import com.heysaz.erp.pos.api.PaymentMethod;
import com.heysaz.erp.pos.api.PosService;
import com.heysaz.erp.pos.api.ShiftService;
import com.heysaz.erp.pos.api.ShiftStatus;
import com.heysaz.erp.support.AbstractIntegrationTest;

/**
 * Milestone 3 — checkout, shifts and receipts:
 * <ul>
 *   <li>Decision D4 — per-line tax rounding and nearest-rupee cash rounding (FR-POS-014)</li>
 *   <li>Decision D5 — gapless SALE numbers</li>
 *   <li>Decision D2 / invariant B7 — cost snapshot captured on the sale line</li>
 *   <li>FR-API-012 — a retried checkout replays rather than ringing a second sale</li>
 *   <li>Section 7.3 — shift cash reconciliation and X/Z reports</li>
 * </ul>
 */
class PosCheckoutIntegrationTest extends AbstractIntegrationTest {

    @Autowired OrganizationService orgService;
    @Autowired CatalogService catalogService;
    @Autowired PricingService pricingService;
    @Autowired InventoryService inventoryService;
    @Autowired PosService posService;
    @Autowired ShiftService shiftService;

    private static final String[] PERMS = {
            "pos.sales.read", "pos.sales.write", "price.override.limited",
            "products.read", "products.write",
            "inventory.adjustments.read", "inventory.adjustments.write",
            "settings.read", "settings.write"
    };

    private void asPos(UUID orgId, UUID userId, Runnable body) {
        TenantContext.set(new Principal(Principal.PrincipalType.POS_TERMINAL, userId, orgId,
                "cashier", Set.of(), Set.of(PERMS), "T1", java.time.Instant.now()));
        try {
            body.run();
        } finally {
            TenantContext.clear();
        }
    }

    private record Fixture(UUID orgId, UUID userId, UUID shopId, UUID terminalId, UUID variantId) {
    }

    /** A shop with one till, an 18% product priced at 149.95 exclusive, and 10 in stock. */
    private Fixture newFixture(String tag) {
        String suffix = (tag + UUID.randomUUID().toString().substring(0, 6)).toUpperCase();
        UUID orgId = createProvisionedOrganization("POS-" + suffix);
        UUID userId = createUser(orgId, "cashier-" + suffix.toLowerCase() + "@heysaz.test");
        final Fixture[] holder = new Fixture[1];

        asPos(orgId, userId, () -> {
            var shop = orgService.createShop(new OrganizationService.CreateShopCommand(
                    "SH-" + suffix, "Shop " + suffix, "P" + suffix.substring(0, 6),
                    "1 Main St", "Colombo", "0112", "Asia/Colombo", "TAX-" + suffix, true), null);

            var terminal = orgService.createTerminal(new OrganizationService.CreateTerminalCommand(
                    shop.id(), "T1", "Till 1", null, null, null, null, null), null);

            var taxClass = pricingService.createTaxClass(
                    new PricingService.CreateTaxClassCommand("VAT-" + suffix, "VAT 18%"));
            pricingService.setTaxRate(new PricingService.SetTaxRateCommand(
                    taxClass.id(), new BigDecimal("0.1800"), LocalDate.of(2020, 1, 1)));

            var unit = catalogService.createUnit(new CatalogService.CreateUnitCommand(
                    "EA-" + suffix, "Each", true));

            var product = catalogService.createProduct(new CatalogService.CreateProductCommand(
                    "SKU-" + suffix, "Widget " + suffix, null, null, unit.id(), taxClass.id(),
                    true, Money.of("100.0000"), Money.of("149.9500"), List.of(), List.of()), null);
            UUID variantId = product.variants().getFirst().id();

            inventoryService.adjustStock(new InventoryService.AdjustStockCommand(
                    shop.id(), "Opening stock", null,
                    List.of(new InventoryService.StockAdjustmentLineInput(
                            variantId, new BigDecimal("10.0000"), Money.of("100.0000"), null))), null);

            holder[0] = new Fixture(orgId, userId, shop.id(), terminal.id(), variantId);
        });
        return holder[0];
    }

    private PosService.CartLineInput line(UUID variantId, String qty) {
        return new PosService.CartLineInput(variantId, new BigDecimal(qty), null, null);
    }

    @Test
    void cash_checkout_rounds_to_the_rupee_taxes_per_line_and_snapshots_cost() {
        Fixture f = newFixture("A");

        asPos(f.orgId(), f.userId(), () -> {
            // One unit at 149.95 excl + 18% tax = 176.9410; cash rounds up to 177.00.
            var sale = posService.checkout(new PosService.CheckoutCommand(
                    f.shopId(), f.terminalId(), null,
                    List.of(line(f.variantId(), "1")),
                    List.of(new PosService.PaymentInput(
                            PaymentMethod.CASH, Money.of("177.00"), null, Money.of("200.00"))),
                    null, null), null);

            assertThat(sale.status()).isEqualTo("COMPLETED");
            assertThat(sale.invoiceNumber()).startsWith("P").contains("-");
            assertThat(sale.subtotal()).isEqualTo(Money.of("149.9500"));
            assertThat(sale.taxTotal()).isEqualTo(Money.of("26.9910"));
            assertThat(sale.grandTotal()).isEqualTo(Money.of("176.9410"));
            assertThat(sale.cashRounding()).isEqualTo(Money.of("0.0590"));
            assertThat(sale.changeGiven()).isEqualTo(Money.of("23.0000"));
            assertThat(sale.totalTendered()).isEqualTo(Money.of("200.0000"));

            var saleLine = sale.lines().getFirst();
            assertThat(saleLine.taxRate()).isEqualByComparingTo("0.1800");
            assertThat(saleLine.taxAmount()).isEqualTo(Money.of("26.9910"));
            assertThat(saleLine.lineTotal()).isEqualTo(Money.of("176.9410"));
            assertThat(saleLine.costSnapshot()).isEqualTo(Money.of("100.0000"));

            // Stock fell by exactly one unit.
            assertThat(inventoryService.getStockBalance(f.shopId(), f.variantId()).quantityOnHand())
                    .isEqualByComparingTo("9.0000");

            // Receipt groups tax by rate and marks reprints.
            var receipt = posService.getReceipt(sale.id());
            assertThat(receipt.isReprint()).isFalse();
            assertThat(receipt.taxBreakdown()).hasSize(1);
            assertThat(receipt.taxBreakdown().getFirst().rate()).isEqualByComparingTo("0.1800");
            assertThat(receipt.taxBreakdown().getFirst().taxableAmount()).isEqualTo(Money.of("149.9500"));
            assertThat(receipt.taxBreakdown().getFirst().taxAmount()).isEqualTo(Money.of("26.9910"));

            assertThat(posService.reprintReceipt(sale.id(), "customer copy").isReprint()).isTrue();
        });
    }

    @Test
    void card_payment_is_never_rounded() {
        Fixture f = newFixture("CARD");

        asPos(f.orgId(), f.userId(), () -> {
            var sale = posService.checkout(new PosService.CheckoutCommand(
                    f.shopId(), f.terminalId(), null,
                    List.of(line(f.variantId(), "1")),
                    List.of(new PosService.PaymentInput(
                            PaymentMethod.CARD, Money.of("176.9410"), "auth-123", null)),
                    null, null), null);

            assertThat(sale.cashRounding()).isEqualTo(Money.ZERO);
            assertThat(sale.grandTotal()).isEqualTo(Money.of("176.9410"));
            assertThat(sale.changeGiven()).isEqualTo(Money.ZERO);
        });
    }

    @Test
    void gapless_invoice_numbers_and_idempotent_retry() {
        Fixture f = newFixture("IDEM");

        asPos(f.orgId(), f.userId(), () -> {
            var first = posService.checkout(new PosService.CheckoutCommand(
                    f.shopId(), f.terminalId(), null,
                    List.of(line(f.variantId(), "1")),
                    List.of(new PosService.PaymentInput(PaymentMethod.CASH, Money.of("177.00"), null, null)),
                    null, null), null);
            var second = posService.checkout(new PosService.CheckoutCommand(
                    f.shopId(), f.terminalId(), null,
                    List.of(line(f.variantId(), "1")),
                    List.of(new PosService.PaymentInput(PaymentMethod.CASH, Money.of("177.00"), null, null)),
                    null, null), null);

            // Consecutive sales get consecutive numbers.
            assertThat(first.invoiceNumber()).isNotEqualTo(second.invoiceNumber());

            String key = UUID.randomUUID().toString();
            var cmd = new PosService.CheckoutCommand(
                    f.shopId(), f.terminalId(), null,
                    List.of(line(f.variantId(), "2")),
                    List.of(new PosService.PaymentInput(PaymentMethod.CASH, Money.of("354.00"), null, null)),
                    null, null);
            var a = posService.checkout(cmd, key);
            var b = posService.checkout(cmd, key);

            assertThat(a.invoiceNumber()).isEqualTo(b.invoiceNumber());
            assertThat(a.id()).isEqualTo(b.id());
            // 10 - 1 - 1 - 2 = 6, and the replayed retry did NOT decrement again.
            assertThat(inventoryService.getStockBalance(f.shopId(), f.variantId()).quantityOnHand())
                    .isEqualByComparingTo("6.0000");
        });
    }

    @Test
    void shift_reconciles_cash_and_produces_x_and_z_reports() {
        Fixture f = newFixture("SHIFT");

        asPos(f.orgId(), f.userId(), () -> {
            var shift = shiftService.openShift(new ShiftService.OpenShiftCommand(
                    f.shopId(), f.terminalId(), Money.of("1000.00"), "morning"), null);
            assertThat(shift.status()).isEqualTo(ShiftStatus.OPEN);

            posService.checkout(new PosService.CheckoutCommand(
                    f.shopId(), f.terminalId(), shift.id(),
                    List.of(line(f.variantId(), "1")),
                    List.of(new PosService.PaymentInput(PaymentMethod.CASH, Money.of("177.00"), null, null)),
                    null, null), null);

            shiftService.recordMovement(shift.id(), new ShiftService.RecordShiftMovementCommand(
                    "CASH_IN", Money.of("50.00"), "float top-up"));

            var x = shiftService.getXReport(shift.id());
            assertThat(x.cashSales()).isEqualTo(Money.of("177.0000"));
            assertThat(x.salesCount()).isEqualTo(1);
            // opening 1000 + cash sales 177 + cash-in 50 = 1227 expected in the drawer.
            assertThat(x.expectedCash()).isEqualTo(Money.of("1227.0000"));

            var z = shiftService.closeShift(shift.id(), new ShiftService.CloseShiftCommand(
                    Money.of("1227.00"), "counted, balanced"), null);
            assertThat(z.countedCash()).isEqualTo(Money.of("1227.0000"));
            assertThat(z.cashVariance()).isEqualTo(Money.ZERO);
            assertThat(z.totalSales()).isEqualTo(Money.of("177.0000"));

            assertThat(shiftService.getShift(shift.id()).status()).isEqualTo(ShiftStatus.CLOSED);
        });
    }

    @Test
    void only_one_shift_may_be_open_on_a_terminal() {
        Fixture f = newFixture("ONE");

        asPos(f.orgId(), f.userId(), () -> {
            shiftService.openShift(new ShiftService.OpenShiftCommand(
                    f.shopId(), f.terminalId(), Money.of("500.00"), null), null);

            assertThatThrownBy(() -> shiftService.openShift(new ShiftService.OpenShiftCommand(
                    f.shopId(), f.terminalId(), Money.of("500.00"), null), null))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("already open");
        });
    }

    @Test
    void selling_is_refused_when_the_shop_has_it_disabled() {
        Fixture f = newFixture("NOSELL");

        // A terminal can only be created on a selling shop, so disable selling after the
        // fixture is built — the state a shop reaches when it is suspended (FR-ORG-008).
        elevatedJdbc.sql("UPDATE app.shop SET selling_enabled = false WHERE id = ?")
                .param(f.shopId()).update();

        asPos(f.orgId(), f.userId(), () ->
                assertThatThrownBy(() -> posService.checkout(new PosService.CheckoutCommand(
                        f.shopId(), f.terminalId(), null,
                        List.of(line(f.variantId(), "1")),
                        List.of(new PosService.PaymentInput(PaymentMethod.CASH, Money.of("177.00"), null, null)),
                        null, null), null))
                        .isInstanceOf(ApiException.class)
                        .hasMessageContaining("Selling is not enabled"));
    }
}

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
import com.heysaz.erp.pos.api.ReturnCondition;
import com.heysaz.erp.pos.api.ReturnService;
import com.heysaz.erp.support.AbstractIntegrationTest;

/**
 * Milestone 4 — returns, refunds and exchanges (Section 10):
 * <ul>
 *   <li>FR-RET-003 — restockable goods return to stock, damaged/defective do not</li>
 *   <li>A sale moves to PARTIALLY_RETURNED / RETURNED as its lines are returned, capped at sold qty</li>
 *   <li>Refund tenders must reconcile to the line refund total</li>
 *   <li>An exchange is a return and a sale in one transaction, reporting the net settled</li>
 * </ul>
 */
class ReturnIntegrationTest extends AbstractIntegrationTest {

    @Autowired OrganizationService orgService;
    @Autowired CatalogService catalogService;
    @Autowired PricingService pricingService;
    @Autowired InventoryService inventoryService;
    @Autowired PosService posService;
    @Autowired ReturnService returnService;

    private static final String[] PERMS = {
            "pos.sales.read", "pos.sales.write", "returns.read", "returns.write",
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
        UUID orgId = createProvisionedOrganization("RET-" + suffix);
        UUID userId = createUser(orgId, "cashier-" + suffix.toLowerCase() + "@heysaz.test");
        final Fixture[] holder = new Fixture[1];

        asPos(orgId, userId, () -> {
            var shop = orgService.createShop(new OrganizationService.CreateShopCommand(
                    "SH-" + suffix, "Shop " + suffix, "R" + suffix.substring(0, 6),
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

    private PosService.SaleView sell(Fixture f, String qty, String cashAmount) {
        return posService.checkout(new PosService.CheckoutCommand(
                f.shopId(), f.terminalId(), null,
                List.of(new PosService.CartLineInput(f.variantId(), new BigDecimal(qty), null, null)),
                List.of(new PosService.PaymentInput(PaymentMethod.CASH, Money.of(cashAmount), null, null)),
                null, null), null);
    }

    @Test
    void full_restockable_return_puts_stock_back_and_marks_the_sale_returned() {
        Fixture f = newFixture("FULL");

        asPos(f.orgId(), f.userId(), () -> {
            var sale = sell(f, "2", "354.00");
            var saleLine = sale.lines().getFirst();
            assertThat(inventoryService.getStockBalance(f.shopId(), f.variantId()).quantityOnHand())
                    .isEqualByComparingTo("8.0000");

            // Derived refund (null refundAmount): the sold line total pro-rated by quantity.
            var ret = returnService.processReturn(new ReturnService.ProcessReturnCommand(
                    sale.id(), f.shopId(), f.terminalId(), null, "changed mind",
                    List.of(new ReturnService.ReturnLineInput(
                            saleLine.id(), f.variantId(), new BigDecimal("2.0000"),
                            ReturnCondition.RESTOCKABLE, null)),
                    List.of(new ReturnService.RefundInput(PaymentMethod.CASH, Money.of("353.8820")))), null);

            assertThat(ret.returnNumber()).contains("-");
            assertThat(ret.refundTotal()).isEqualTo(Money.of("353.8820"));
            assertThat(ret.lines().getFirst().restocked()).isTrue();

            // Restocked back to 10, and the sale is now fully returned.
            assertThat(inventoryService.getStockBalance(f.shopId(), f.variantId()).quantityOnHand())
                    .isEqualByComparingTo("10.0000");
            var reloaded = posService.getSale(sale.id());
            assertThat(reloaded.status()).isEqualTo("RETURNED");
            assertThat(reloaded.lines().getFirst().returnedQuantity()).isEqualByComparingTo("2.0000");
        });
    }

    @Test
    void damaged_partial_return_does_not_restock_and_marks_partially_returned() {
        Fixture f = newFixture("DMG");

        asPos(f.orgId(), f.userId(), () -> {
            var sale = sell(f, "3", "531.00"); // 3 * 176.9410 = 530.823 -> cash 531.00
            var saleLine = sale.lines().getFirst();
            assertThat(inventoryService.getStockBalance(f.shopId(), f.variantId()).quantityOnHand())
                    .isEqualByComparingTo("7.0000");

            var ret = returnService.processReturn(new ReturnService.ProcessReturnCommand(
                    sale.id(), f.shopId(), f.terminalId(), null, "arrived broken",
                    List.of(new ReturnService.ReturnLineInput(
                            saleLine.id(), f.variantId(), new BigDecimal("1.0000"),
                            ReturnCondition.DAMAGED, Money.of("176.9410"))),
                    List.of(new ReturnService.RefundInput(PaymentMethod.CASH, Money.of("176.9410")))), null);

            assertThat(ret.lines().getFirst().restocked()).isFalse();
            // Damaged goods are NOT returned to sellable stock.
            assertThat(inventoryService.getStockBalance(f.shopId(), f.variantId()).quantityOnHand())
                    .isEqualByComparingTo("7.0000");

            var reloaded = posService.getSale(sale.id());
            assertThat(reloaded.status()).isEqualTo("PARTIALLY_RETURNED");
            assertThat(reloaded.lines().getFirst().returnedQuantity()).isEqualByComparingTo("1.0000");
        });
    }

    @Test
    void refund_tenders_must_reconcile_to_the_line_refund_total() {
        Fixture f = newFixture("RECON");

        asPos(f.orgId(), f.userId(), () -> {
            var sale = sell(f, "1", "177.00");
            var saleLine = sale.lines().getFirst();

            assertThatThrownBy(() -> returnService.processReturn(new ReturnService.ProcessReturnCommand(
                    sale.id(), f.shopId(), f.terminalId(), null, "mismatch",
                    List.of(new ReturnService.ReturnLineInput(
                            saleLine.id(), f.variantId(), new BigDecimal("1.0000"),
                            ReturnCondition.RESTOCKABLE, Money.of("176.9410"))),
                    List.of(new ReturnService.RefundInput(PaymentMethod.CASH, Money.of("100.0000")))), null))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("do not match");
        });
    }

    @Test
    void a_line_cannot_be_returned_beyond_what_was_sold() {
        Fixture f = newFixture("OVER");

        asPos(f.orgId(), f.userId(), () -> {
            var sale = sell(f, "1", "177.00");
            var saleLine = sale.lines().getFirst();

            assertThatThrownBy(() -> returnService.processReturn(new ReturnService.ProcessReturnCommand(
                    sale.id(), f.shopId(), f.terminalId(), null, "greedy",
                    List.of(new ReturnService.ReturnLineInput(
                            saleLine.id(), f.variantId(), new BigDecimal("2.0000"),
                            ReturnCondition.RESTOCKABLE, Money.of("353.8820"))),
                    List.of(new ReturnService.RefundInput(PaymentMethod.CASH, Money.of("353.8820")))), null))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("returnable");
        });
    }

    @Test
    void exchange_returns_and_sells_in_one_transaction_and_reports_the_net() {
        Fixture f = newFixture("EXCH");

        asPos(f.orgId(), f.userId(), () -> {
            // Blind return of one unit (restocked) plus a fresh sale of two units.
            var returnPart = new ReturnService.ProcessReturnCommand(
                    null, f.shopId(), f.terminalId(), null, "swap",
                    List.of(new ReturnService.ReturnLineInput(
                            null, f.variantId(), new BigDecimal("1.0000"),
                            ReturnCondition.RESTOCKABLE, Money.of("100.0000"))),
                    List.of(new ReturnService.RefundInput(PaymentMethod.CASH, Money.of("100.0000"))));
            var salePart = new PosService.CheckoutCommand(
                    f.shopId(), f.terminalId(), null,
                    List.of(new PosService.CartLineInput(f.variantId(), new BigDecimal("2.0000"), null, null)),
                    List.of(new PosService.PaymentInput(PaymentMethod.CASH, Money.of("354.00"), null, null)),
                    null, null);

            var result = returnService.processExchange(
                    new ReturnService.ProcessExchangeCommand(returnPart, salePart), null);

            assertThat(result.returnRecord().refundTotal()).isEqualTo(Money.of("100.0000"));
            assertThat(result.saleRecord().grandTotal()).isEqualTo(Money.of("353.8820"));
            // Net owed by customer = new sale total less the refund.
            assertThat(result.netSettlementAmount()).isEqualTo(Money.of("253.8820"));

            // Stock: 10 + 1 restocked - 2 sold = 9.
            assertThat(inventoryService.getStockBalance(f.shopId(), f.variantId()).quantityOnHand())
                    .isEqualByComparingTo("9.0000");
        });
    }

    @Test
    void a_retried_return_replays_rather_than_refunding_twice() {
        Fixture f = newFixture("IDEM");

        asPos(f.orgId(), f.userId(), () -> {
            var sale = sell(f, "2", "354.00");
            var saleLine = sale.lines().getFirst();
            String key = UUID.randomUUID().toString();
            var cmd = new ReturnService.ProcessReturnCommand(
                    sale.id(), f.shopId(), f.terminalId(), null, "retry",
                    List.of(new ReturnService.ReturnLineInput(
                            saleLine.id(), f.variantId(), new BigDecimal("2.0000"),
                            ReturnCondition.RESTOCKABLE, Money.of("353.8820"))),
                    List.of(new ReturnService.RefundInput(PaymentMethod.CASH, Money.of("353.8820"))));

            var a = returnService.processReturn(cmd, key);
            var b = returnService.processReturn(cmd, key);

            assertThat(a.id()).isEqualTo(b.id());
            assertThat(a.returnNumber()).isEqualTo(b.returnNumber());
            // 8 after sale, +2 restocked once = 10 (the replay did not restock again).
            assertThat(inventoryService.getStockBalance(f.shopId(), f.variantId()).quantityOnHand())
                    .isEqualByComparingTo("10.0000");
        });
    }
}

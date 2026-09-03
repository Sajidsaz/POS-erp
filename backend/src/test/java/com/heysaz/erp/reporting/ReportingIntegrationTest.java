package com.heysaz.erp.reporting;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;
import com.heysaz.erp.pos.api.PaymentMethod;
import com.heysaz.erp.pos.api.PosService;
import com.heysaz.erp.pos.api.ReturnCondition;
import com.heysaz.erp.pos.api.ReturnService;
import com.heysaz.erp.reporting.api.ReportingService;
import com.heysaz.erp.support.AbstractIntegrationTest;

/**
 * Milestone 5 — reporting (Section 12): gross profit from cost snapshots (D2/B7), sales
 * summaries, top products, payment mix, inventory valuation, and CSV export.
 */
class ReportingIntegrationTest extends AbstractIntegrationTest {

    @Autowired OrganizationService orgService;
    @Autowired CatalogService catalogService;
    @Autowired PricingService pricingService;
    @Autowired InventoryService inventoryService;
    @Autowired PosService posService;
    @Autowired ReturnService returnService;
    @Autowired ReportingService reportingService;

    private static final String[] PERMS = {
            "pos.sales.read", "pos.sales.write", "returns.read", "returns.write", "reports.read",
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

    private Fixture newFixture(String tag) {
        String suffix = (tag + UUID.randomUUID().toString().substring(0, 6)).toUpperCase();
        UUID orgId = createProvisionedOrganization("REP-" + suffix);
        UUID userId = createUser(orgId, "cashier-" + suffix.toLowerCase() + "@heysaz.test");
        final Fixture[] holder = new Fixture[1];

        asPos(orgId, userId, () -> {
            var shop = orgService.createShop(new OrganizationService.CreateShopCommand(
                    "SH-" + suffix, "Shop " + suffix, "R" + suffix.substring(0, 6),
                    "1 Main St", "Colombo", "0112", "Asia/Colombo", null, true), null);
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

    private PosService.SaleView sell(Fixture f) {
        return posService.checkout(new PosService.CheckoutCommand(
                f.shopId(), f.terminalId(), null,
                List.of(new PosService.CartLineInput(f.variantId(), new BigDecimal("1"), null, null)),
                List.of(new PosService.PaymentInput(PaymentMethod.CASH, Money.of("177.00"), null, null)),
                null, null), null);
    }

    @Test
    void sales_summary_reads_gross_profit_from_cost_snapshots_and_nets_returns() {
        Fixture f = newFixture("SUM");
        LocalDate from = LocalDate.now().minusDays(1);
        LocalDate to = LocalDate.now().plusDays(1);

        asPos(f.orgId(), f.userId(), () -> {
            var first = sell(f);
            sell(f);
            // Return one unit from the first sale.
            returnService.processReturn(new ReturnService.ProcessReturnCommand(
                    first.id(), f.shopId(), f.terminalId(), null, "changed mind",
                    List.of(new ReturnService.ReturnLineInput(
                            first.lines().getFirst().id(), f.variantId(), new BigDecimal("1.0000"),
                            ReturnCondition.RESTOCKABLE, Money.of("176.9410"))),
                    List.of(new ReturnService.RefundInput(PaymentMethod.CASH, Money.of("176.9410")))), null);

            var summary = reportingService.salesSummary(f.shopId(), from, to);
            assertThat(summary.salesCount()).isEqualTo(2);
            assertThat(summary.grossSales()).isEqualTo(Money.of("353.8820"));
            assertThat(summary.totalTax()).isEqualTo(Money.of("53.9820"));
            assertThat(summary.cashRounding()).isEqualTo(Money.of("0.1180"));
            // COGS = 2 units * 100; gross profit = net revenue (299.90) − COGS (200).
            assertThat(summary.costOfGoodsSold()).isEqualTo(Money.of("200.0000"));
            assertThat(summary.grossProfit()).isEqualTo(Money.of("99.9000"));
            assertThat(summary.returnsCount()).isEqualTo(1);
            assertThat(summary.returnsTotal()).isEqualTo(Money.of("176.9410"));
            assertThat(summary.netSales()).isEqualTo(Money.of("176.9410"));
        });
    }

    @Test
    void top_products_payment_mix_and_valuation_reflect_the_sales() {
        Fixture f = newFixture("TOP");
        LocalDate from = LocalDate.now().minusDays(1);
        LocalDate to = LocalDate.now().plusDays(1);

        asPos(f.orgId(), f.userId(), () -> {
            sell(f);
            sell(f);

            var top = reportingService.topProducts(f.shopId(), from, to, 10);
            assertThat(top).hasSize(1);
            assertThat(top.getFirst().quantitySold()).isEqualByComparingTo("2.0000");
            assertThat(top.getFirst().revenue()).isEqualTo(Money.of("299.9000"));
            assertThat(top.getFirst().grossProfit()).isEqualTo(Money.of("99.9000"));

            var mix = reportingService.paymentMix(f.shopId(), from, to);
            assertThat(mix).hasSize(1);
            assertThat(mix.getFirst().paymentMethod()).isEqualTo("CASH");
            assertThat(mix.getFirst().count()).isEqualTo(2);
            assertThat(mix.getFirst().total()).isEqualTo(Money.of("354.0000"));

            // 10 opening − 2 sold = 8 on hand at cost 100 = 800.
            var valuation = reportingService.inventoryValuation(f.shopId());
            assertThat(valuation).hasSize(1);
            assertThat(valuation.getFirst().stockValue()).isEqualTo(Money.of("800.0000"));
            assertThat(valuation.getFirst().variantCount()).isEqualTo(1);
        });
    }

    @Test
    void sales_by_day_csv_has_a_header_and_a_row_per_day() {
        Fixture f = newFixture("CSV");
        LocalDate from = LocalDate.now().minusDays(1);
        LocalDate to = LocalDate.now().plusDays(1);

        asPos(f.orgId(), f.userId(), () -> {
            sell(f);
            sell(f);

            var byDay = reportingService.salesByDay(f.shopId(), from, to);
            assertThat(byDay).hasSize(1);
            assertThat(byDay.getFirst().salesCount()).isEqualTo(2);
            assertThat(byDay.getFirst().grossSales()).isEqualTo(Money.of("353.8820"));

            String csv = reportingService.exportSalesByDayCsv(f.shopId(), from, to);
            assertThat(csv).startsWith("date,sales_count,gross_sales\n");
            assertThat(csv).contains(",2,353.8820");
        });
    }
}

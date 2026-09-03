package com.heysaz.erp.customer;

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
import com.heysaz.erp.customer.api.CustomerService;
import com.heysaz.erp.inventory.api.InventoryService;
import com.heysaz.erp.organization.api.OrganizationService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;
import com.heysaz.erp.pos.api.PaymentMethod;
import com.heysaz.erp.pos.api.PosService;
import com.heysaz.erp.support.AbstractIntegrationTest;

/**
 * Milestone 4 — customers and store credit (Section 9):
 * <ul>
 *   <li>An on-account CREDIT tender at the till charges the customer and respects the limit</li>
 *   <li>A charge that would breach the limit is refused and unwinds the whole sale</li>
 *   <li>A credit tender with no customer is refused</li>
 *   <li>Payments settle the balance; the ledger records every movement</li>
 * </ul>
 */
class CustomerCreditIntegrationTest extends AbstractIntegrationTest {

    @Autowired OrganizationService orgService;
    @Autowired CatalogService catalogService;
    @Autowired PricingService pricingService;
    @Autowired InventoryService inventoryService;
    @Autowired PosService posService;
    @Autowired CustomerService customerService;

    private static final String[] PERMS = {
            "pos.sales.read", "pos.sales.write",
            "customers.read", "customers.write",
            "customer.credit_limits.read", "customer.credit_limits.write",
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
        UUID orgId = createProvisionedOrganization("CUS-" + suffix);
        UUID userId = createUser(orgId, "cashier-" + suffix.toLowerCase() + "@heysaz.test");
        final Fixture[] holder = new Fixture[1];

        asPos(orgId, userId, () -> {
            var shop = orgService.createShop(new OrganizationService.CreateShopCommand(
                    "SH-" + suffix, "Shop " + suffix, "C" + suffix.substring(0, 6),
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

    private CustomerService.CustomerView customer(String suffix, String limit) {
        return customerService.createCustomer(new CustomerService.CreateCustomerCommand(
                "CUST-" + suffix + UUID.randomUUID().toString().substring(0, 4),
                "Acme Buyer", "0771", "buyer@x.test", Money.of(limit)), null);
    }

    private PosService.SaleView creditSale(Fixture f, UUID customerId, String qty, String creditAmount) {
        return posService.checkout(new PosService.CheckoutCommand(
                f.shopId(), f.terminalId(), null,
                List.of(new PosService.CartLineInput(f.variantId(), new BigDecimal(qty), null, null)),
                List.of(new PosService.PaymentInput(PaymentMethod.CREDIT, Money.of(creditAmount), null, null)),
                customerId, null), null);
    }

    @Test
    void an_on_account_sale_charges_the_customer_and_records_the_ledger() {
        Fixture f = newFixture("CHG");

        asPos(f.orgId(), f.userId(), () -> {
            var cust = customer("CHG", "1000.00");
            var sale = creditSale(f, cust.id(), "1", "176.9410");

            var after = customerService.getCustomer(cust.id());
            assertThat(after.creditBalance()).isEqualTo(Money.of("176.9410"));
            assertThat(after.availableCredit()).isEqualTo(Money.of("823.0590"));

            var ledger = customerService.listLedger(cust.id(), 10);
            assertThat(ledger).hasSize(1);
            assertThat(ledger.getFirst().entryType()).isEqualTo("CHARGE");
            assertThat(ledger.getFirst().referenceId()).isEqualTo(sale.invoiceNumber());
            assertThat(ledger.getFirst().balanceAfter()).isEqualTo(Money.of("176.9410"));
        });
    }

    @Test
    void a_charge_beyond_the_limit_is_refused_and_unwinds_the_sale() {
        Fixture f = newFixture("LIM");

        asPos(f.orgId(), f.userId(), () -> {
            var cust = customer("LIM", "100.00");

            assertThatThrownBy(() -> creditSale(f, cust.id(), "1", "176.9410"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("exceeds available credit");

            // The whole checkout rolled back: no stock was taken, no charge landed.
            assertThat(inventoryService.getStockBalance(f.shopId(), f.variantId()).quantityOnHand())
                    .isEqualByComparingTo("10.0000");
            assertThat(customerService.getCustomer(cust.id()).creditBalance()).isEqualTo(Money.ZERO);
            assertThat(customerService.listLedger(cust.id(), 10)).isEmpty();
        });
    }

    @Test
    void a_credit_tender_requires_a_customer() {
        Fixture f = newFixture("NOCUST");

        asPos(f.orgId(), f.userId(), () ->
                assertThatThrownBy(() -> creditSale(f, null, "1", "176.9410"))
                        .isInstanceOf(ApiException.class)
                        .hasMessageContaining("requires a customer"));
    }

    @Test
    void a_payment_settles_the_balance_and_appends_to_the_ledger() {
        Fixture f = newFixture("PAY");

        asPos(f.orgId(), f.userId(), () -> {
            var cust = customer("PAY", "1000.00");
            creditSale(f, cust.id(), "1", "176.9410");

            var updated = customerService.recordPayment(new CustomerService.RecordPaymentCommand(
                    cust.id(), Money.of("100.00"), "CASH", "receipt-1"), null);

            assertThat(updated.creditBalance()).isEqualTo(Money.of("76.9410"));
            assertThat(updated.availableCredit()).isEqualTo(Money.of("923.0590"));

            var ledger = customerService.listLedger(cust.id(), 10);
            assertThat(ledger).hasSize(2);
            // Most recent first: the payment, signed negative.
            assertThat(ledger.getFirst().entryType()).isEqualTo("PAYMENT");
            assertThat(ledger.getFirst().amount()).isEqualTo(Money.of("-100.0000"));
            assertThat(ledger.getFirst().balanceAfter()).isEqualTo(Money.of("76.9410"));
        });
    }

    @Test
    void mixed_cash_and_credit_tender_rounds_cash_and_charges_the_remainder() {
        Fixture f = newFixture("MIX");

        asPos(f.orgId(), f.userId(), () -> {
            var cust = customer("MIX", "1000.00");
            // 1 unit = 176.9410; any cash present rounds the payable to 177.00.
            var sale = posService.checkout(new PosService.CheckoutCommand(
                    f.shopId(), f.terminalId(), null,
                    List.of(new PosService.CartLineInput(f.variantId(), new BigDecimal("1"), null, null)),
                    List.of(
                            new PosService.PaymentInput(PaymentMethod.CASH, Money.of("100.00"), null, Money.of("100.00")),
                            new PosService.PaymentInput(PaymentMethod.CREDIT, Money.of("77.00"), null, null)),
                    cust.id(), null), null);

            assertThat(sale.cashRounding()).isEqualTo(Money.of("0.0590"));
            assertThat(customerService.getCustomer(cust.id()).creditBalance()).isEqualTo(Money.of("77.0000"));
        });
    }

    @Test
    void raising_the_credit_limit_increases_available_credit() {
        Fixture f = newFixture("SETLIM");

        asPos(f.orgId(), f.userId(), () -> {
            var cust = customer("SETLIM", "100.00");
            assertThat(cust.availableCredit()).isEqualTo(Money.of("100.0000"));

            var raised = customerService.setCreditLimit(cust.id(), Money.of("500.00"));
            assertThat(raised.creditLimit()).isEqualTo(Money.of("500.0000"));
            assertThat(raised.availableCredit()).isEqualTo(Money.of("500.0000"));
        });
    }
}

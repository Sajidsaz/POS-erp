package com.heysaz.erp.purchasing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.heysaz.erp.catalog.api.CatalogService;
import com.heysaz.erp.inventory.api.InventoryService;
import com.heysaz.erp.organization.api.OrganizationService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;
import com.heysaz.erp.purchasing.api.PurchaseOrderStatus;
import com.heysaz.erp.purchasing.api.PurchasingService;
import com.heysaz.erp.support.AbstractIntegrationTest;

/**
 * Milestone 4 — purchasing and receiving (Section 8):
 * <ul>
 *   <li>Decision D2 — a goods receipt lands stock at a unit cost and moves the average</li>
 *   <li>Decision D5 — gapless PO and GRN numbers</li>
 *   <li>A purchase order tracks received quantity and moves to PARTIALLY_RECEIVED / RECEIVED</li>
 *   <li>Direct (blind) receipts, over-receipt rejection, and idempotent receiving</li>
 * </ul>
 */
class PurchasingIntegrationTest extends AbstractIntegrationTest {

    @Autowired OrganizationService orgService;
    @Autowired CatalogService catalogService;
    @Autowired InventoryService inventoryService;
    @Autowired PurchasingService purchasingService;

    private static final String[] PERMS = {
            "purchasing.read", "purchasing.write",
            "products.read", "products.write",
            "inventory.adjustments.read", "inventory.adjustments.write",
            "settings.read", "settings.write"
    };

    private void asBuyer(UUID orgId, UUID userId, Runnable body) {
        TenantContext.set(new Principal(Principal.PrincipalType.USER_SESSION, userId, orgId,
                "buyer", Set.of(), Set.of(PERMS), "T1", java.time.Instant.now()));
        try {
            body.run();
        } finally {
            TenantContext.clear();
        }
    }

    private record Fixture(UUID orgId, UUID userId, UUID shopId, UUID variantId, UUID supplierId) {
    }

    /** A shop with 10 units in stock at cost 100, and one supplier. */
    private Fixture newFixture(String tag) {
        String suffix = (tag + UUID.randomUUID().toString().substring(0, 6)).toUpperCase();
        UUID orgId = createProvisionedOrganization("PUR-" + suffix);
        UUID userId = createUser(orgId, "buyer-" + suffix.toLowerCase() + "@heysaz.test");
        final Fixture[] holder = new Fixture[1];

        asBuyer(orgId, userId, () -> {
            var shop = orgService.createShop(new OrganizationService.CreateShopCommand(
                    "SH-" + suffix, "Shop " + suffix, "U" + suffix.substring(0, 6),
                    "1 Main St", "Colombo", "0112", "Asia/Colombo", null, true), null);
            var unit = catalogService.createUnit(new CatalogService.CreateUnitCommand(
                    "EA-" + suffix, "Each", true));
            var product = catalogService.createProduct(new CatalogService.CreateProductCommand(
                    "SKU-" + suffix, "Widget " + suffix, null, null, unit.id(), null,
                    true, Money.of("100.0000"), Money.of("150.0000"), List.of(), List.of()), null);
            UUID variantId = product.variants().getFirst().id();
            inventoryService.adjustStock(new InventoryService.AdjustStockCommand(
                    shop.id(), "Opening stock", null,
                    List.of(new InventoryService.StockAdjustmentLineInput(
                            variantId, new BigDecimal("10.0000"), Money.of("100.0000"), null))), null);
            var supplier = purchasingService.createSupplier(new PurchasingService.CreateSupplierCommand(
                    "SUP-" + suffix, "Acme " + suffix, "Jane", "0111", "acme@x.test", null));
            holder[0] = new Fixture(orgId, userId, shop.id(), variantId, supplier.id());
        });
        return holder[0];
    }

    private PurchasingService.PurchaseOrderView order(Fixture f, String qty, String unitCost) {
        return purchasingService.createPurchaseOrder(new PurchasingService.CreatePurchaseOrderCommand(
                f.supplierId(), f.shopId(), "restock",
                List.of(new PurchasingService.PurchaseOrderLineInput(
                        f.variantId(), new BigDecimal(qty), Money.of(unitCost)))), null);
    }

    @Test
    void full_receipt_lands_stock_moves_the_average_and_completes_the_order() {
        Fixture f = newFixture("FULL");

        asBuyer(f.orgId(), f.userId(), () -> {
            var po = order(f, "10", "200.0000");
            assertThat(po.poNumber()).contains("-");
            assertThat(po.status()).isEqualTo(PurchaseOrderStatus.DRAFT);
            assertThat(po.orderedTotal()).isEqualTo(Money.of("2000.0000"));

            var approved = purchasingService.approvePurchaseOrder(po.id(), null);
            assertThat(approved.status()).isEqualTo(PurchaseOrderStatus.APPROVED);

            var poLineId = po.lines().getFirst().id();
            var grn = purchasingService.receiveGoods(new PurchasingService.ReceiveGoodsCommand(
                    po.id(), null, f.shopId(), "delivered",
                    List.of(new PurchasingService.ReceiveLineInput(
                            poLineId, f.variantId(), new BigDecimal("10.0000"), null))), null);

            assertThat(grn.grnNumber()).contains("-");
            assertThat(grn.purchaseOrderId()).isEqualTo(po.id());

            // Decision D2: (10*100 + 10*200) / 20 = 150.
            var balance = inventoryService.getStockBalance(f.shopId(), f.variantId());
            assertThat(balance.quantityOnHand()).isEqualByComparingTo("20.0000");
            assertThat(balance.averageCost()).isEqualTo(Money.of("150.0000"));

            assertThat(purchasingService.getPurchaseOrder(po.id()).status())
                    .isEqualTo(PurchaseOrderStatus.RECEIVED);
        });
    }

    @Test
    void a_partial_receipt_leaves_the_order_partially_received_until_completed() {
        Fixture f = newFixture("PART");

        asBuyer(f.orgId(), f.userId(), () -> {
            var po = order(f, "10", "100.0000");
            purchasingService.approvePurchaseOrder(po.id(), null);
            var poLineId = po.lines().getFirst().id();

            purchasingService.receiveGoods(new PurchasingService.ReceiveGoodsCommand(
                    po.id(), null, f.shopId(), null,
                    List.of(new PurchasingService.ReceiveLineInput(
                            poLineId, f.variantId(), new BigDecimal("4.0000"), null))), null);

            assertThat(inventoryService.getStockBalance(f.shopId(), f.variantId()).quantityOnHand())
                    .isEqualByComparingTo("14.0000");
            assertThat(purchasingService.getPurchaseOrder(po.id()).status())
                    .isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);

            purchasingService.receiveGoods(new PurchasingService.ReceiveGoodsCommand(
                    po.id(), null, f.shopId(), null,
                    List.of(new PurchasingService.ReceiveLineInput(
                            poLineId, f.variantId(), new BigDecimal("6.0000"), null))), null);

            assertThat(inventoryService.getStockBalance(f.shopId(), f.variantId()).quantityOnHand())
                    .isEqualByComparingTo("20.0000");
            assertThat(purchasingService.getPurchaseOrder(po.id()).status())
                    .isEqualTo(PurchaseOrderStatus.RECEIVED);
        });
    }

    @Test
    void receiving_more_than_was_ordered_is_refused() {
        Fixture f = newFixture("OVER");

        asBuyer(f.orgId(), f.userId(), () -> {
            var po = order(f, "10", "100.0000");
            purchasingService.approvePurchaseOrder(po.id(), null);
            var poLineId = po.lines().getFirst().id();

            assertThatThrownBy(() -> purchasingService.receiveGoods(
                    new PurchasingService.ReceiveGoodsCommand(po.id(), null, f.shopId(), null,
                            List.of(new PurchasingService.ReceiveLineInput(
                                    poLineId, f.variantId(), new BigDecimal("11.0000"), null))), null))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("outstanding");
        });
    }

    @Test
    void a_direct_receipt_without_an_order_still_lands_stock_at_its_cost() {
        Fixture f = newFixture("BLIND");

        asBuyer(f.orgId(), f.userId(), () -> {
            var grn = purchasingService.receiveGoods(new PurchasingService.ReceiveGoodsCommand(
                    null, f.supplierId(), f.shopId(), "walk-in delivery",
                    List.of(new PurchasingService.ReceiveLineInput(
                            null, f.variantId(), new BigDecimal("5.0000"), Money.of("100.0000")))), null);

            assertThat(grn.purchaseOrderId()).isNull();
            var balance = inventoryService.getStockBalance(f.shopId(), f.variantId());
            assertThat(balance.quantityOnHand()).isEqualByComparingTo("15.0000");
            assertThat(balance.averageCost()).isEqualTo(Money.of("100.0000"));
        });
    }

    @Test
    void a_direct_receipt_must_name_a_supplier() {
        Fixture f = newFixture("NOSUP");

        asBuyer(f.orgId(), f.userId(), () ->
                assertThatThrownBy(() -> purchasingService.receiveGoods(
                        new PurchasingService.ReceiveGoodsCommand(null, null, f.shopId(), null,
                                List.of(new PurchasingService.ReceiveLineInput(
                                        null, f.variantId(), new BigDecimal("1.0000"), Money.of("100.0000")))), null))
                        .isInstanceOf(ApiException.class)
                        .hasMessageContaining("supplier"));
    }

    @Test
    void a_retried_receipt_replays_rather_than_receiving_twice() {
        Fixture f = newFixture("IDEM");

        asBuyer(f.orgId(), f.userId(), () -> {
            var po = order(f, "10", "100.0000");
            purchasingService.approvePurchaseOrder(po.id(), null);
            var poLineId = po.lines().getFirst().id();
            String key = UUID.randomUUID().toString();
            var cmd = new PurchasingService.ReceiveGoodsCommand(po.id(), null, f.shopId(), null,
                    List.of(new PurchasingService.ReceiveLineInput(
                            poLineId, f.variantId(), new BigDecimal("10.0000"), null)));

            var a = purchasingService.receiveGoods(cmd, key);
            var b = purchasingService.receiveGoods(cmd, key);

            assertThat(a.id()).isEqualTo(b.id());
            assertThat(a.grnNumber()).isEqualTo(b.grnNumber());
            // 10 opening + 10 received once = 20 (the replay did not receive again).
            assertThat(inventoryService.getStockBalance(f.shopId(), f.variantId()).quantityOnHand())
                    .isEqualByComparingTo("20.0000");
        });
    }
}

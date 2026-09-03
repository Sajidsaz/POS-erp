package com.heysaz.erp.platform;

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
import com.heysaz.erp.inventory.api.StockCountService;
import com.heysaz.erp.inventory.api.StockTransferService;
import com.heysaz.erp.inventory.api.TransferStatus;
import com.heysaz.erp.organization.api.OrganizationService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;
import com.heysaz.erp.support.AbstractIntegrationTest;

/**
 * Milestone 2 Integration Tests:
 * - Decision D2 (Moving Weighted Average Cost)
 * - Invariant B3 & B12 (Stock Balance reconstructible from movements)
 * - Decision D5 (Gapless transfer numbers)
 * - Invariant B11 (Transfers conserve total inventory valuation)
 * - Discrepancy & Shrinkage (FR-INV-011)
 * - Physical Stock Counts (FR-INV-006)
 * - Multi-tenant RLS isolation
 */
class InventoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    OrganizationService orgService;

    @Autowired
    CatalogService catalogService;

    @Autowired
    InventoryService inventoryService;

    @Autowired
    StockTransferService transferService;

    @Autowired
    StockCountService countService;

    private static final String[] ALL_PERMS = {
            "products.read", "products.write",
            "inventory.adjustments.read", "inventory.adjustments.write", "inventory.adjustments.approve",
            "stock.transfers.read", "stock.transfers.write", "stock.transfers.approve",
            "settings.read", "settings.write"
    };

    private final java.util.Map<UUID, UUID> orgUsers = new java.util.HashMap<>();

    /** One real {@code app_user} per org, so acting-user foreign keys (e.g. created_by) resolve. */
    private UUID userFor(UUID orgId) {
        return orgUsers.computeIfAbsent(orgId,
                o -> createUser(o, "inv-" + o + "@heysaz.test"));
    }

    private void asInventoryTenant(UUID orgId, Runnable body) {
        TenantContext.set(new Principal(
                Principal.PrincipalType.USER_SESSION, userFor(orgId), orgId,
                "inventory user", Set.of(), Set.of(ALL_PERMS), "T1", java.time.Instant.now()));
        try {
            body.run();
        } finally {
            TenantContext.clear();
        }
    }

    private record TestFixture(UUID shopId, UUID variantId, UUID secondShopId) {
    }

    private TestFixture createFixture(UUID orgId, String suffix) {
        final TestFixture[] fixture = new TestFixture[1];
        asInventoryTenant(orgId, () -> {
            var shop1 = orgService.createShop(new OrganizationService.CreateShopCommand(
                    "SH-" + suffix + "1", "Shop 1 " + suffix, "S1" + suffix,
                    "Addr", "City", "123", "Asia/Colombo", null, true), null);

            var shop2 = orgService.createShop(new OrganizationService.CreateShopCommand(
                    "SH-" + suffix + "2", "Shop 2 " + suffix, "S2" + suffix,
                    "Addr", "City", "123", "Asia/Colombo", null, true), null);

            var unit = catalogService.createUnit(new CatalogService.CreateUnitCommand("U-" + suffix, "Each", true));

            var product = catalogService.createProduct(new CatalogService.CreateProductCommand(
                    "SKU-" + suffix, "Product " + suffix, null, null, unit.id(), null,
                    true, Money.of(100), Money.of(150), List.of(), List.of()), null);

            UUID variantId = product.variants().getFirst().id();
            fixture[0] = new TestFixture(shop1.id(), variantId, shop2.id());
        });
        return fixture[0];
    }

    @Test
    void moving_average_cost_recalculates_on_inbound_and_persists_on_outbound() {
        UUID orgId = createProvisionedOrganization("D2-" + UUID.randomUUID().toString().substring(0, 8));
        TestFixture f = createFixture(orgId, "D2");

        asInventoryTenant(orgId, () -> {
            // 1. Initial 10 units @ 100 LKR
            inventoryService.adjustStock(new InventoryService.AdjustStockCommand(
                    f.shopId(), "Initial Receipt", null,
                    List.of(new InventoryService.StockAdjustmentLineInput(
                            f.variantId(), new BigDecimal("10.0000"), Money.of("100.0000"), null))), null);

            var b1 = inventoryService.getStockBalance(f.shopId(), f.variantId());
            assertThat(b1.quantityOnHand()).isEqualByComparingTo(new BigDecimal("10.0000"));
            assertThat(b1.averageCost()).isEqualTo(Money.of("100.0000"));

            // 2. Second inbound: 10 units @ 200 LKR -> (10*100 + 10*200)/20 = 150 LKR
            inventoryService.adjustStock(new InventoryService.AdjustStockCommand(
                    f.shopId(), "Second Receipt", null,
                    List.of(new InventoryService.StockAdjustmentLineInput(
                            f.variantId(), new BigDecimal("10.0000"), Money.of("200.0000"), null))), null);

            var b2 = inventoryService.getStockBalance(f.shopId(), f.variantId());
            assertThat(b2.quantityOnHand()).isEqualByComparingTo(new BigDecimal("20.0000"));
            assertThat(b2.averageCost()).isEqualTo(Money.of("150.0000"));

            // 3. Outbound adjustment (e.g. -5 units): cost should remain 150 LKR
            inventoryService.adjustStock(new InventoryService.AdjustStockCommand(
                    f.shopId(), "Damaged write-off", null,
                    List.of(new InventoryService.StockAdjustmentLineInput(
                            f.variantId(), new BigDecimal("-5.0000"), null, "Damaged"))), null);

            var b3 = inventoryService.getStockBalance(f.shopId(), f.variantId());
            assertThat(b3.quantityOnHand()).isEqualByComparingTo(new BigDecimal("15.0000"));
            assertThat(b3.averageCost()).isEqualTo(Money.of("150.0000"));
        });
    }

    @Test
    void stock_balance_is_strictly_reconstructible_from_movements() {
        // Invariants B3 & B12
        UUID orgId = createProvisionedOrganization("B12-" + UUID.randomUUID().toString().substring(0, 8));
        TestFixture f = createFixture(orgId, "B12");

        asInventoryTenant(orgId, () -> {
            inventoryService.adjustStock(new InventoryService.AdjustStockCommand(
                    f.shopId(), "Receive 50", null,
                    List.of(new InventoryService.StockAdjustmentLineInput(
                            f.variantId(), new BigDecimal("50.0000"), Money.of("80.0000"), null))), null);

            inventoryService.adjustStock(new InventoryService.AdjustStockCommand(
                    f.shopId(), "Issue 15", null,
                    List.of(new InventoryService.StockAdjustmentLineInput(
                            f.variantId(), new BigDecimal("-15.0000"), null, null))), null);

            inventoryService.adjustStock(new InventoryService.AdjustStockCommand(
                    f.shopId(), "Receive 25", null,
                    List.of(new InventoryService.StockAdjustmentLineInput(
                            f.variantId(), new BigDecimal("25.0000"), Money.of("80.0000"), null))), null);

            var balance = inventoryService.getStockBalance(f.shopId(), f.variantId());
            BigDecimal reconstructed = inventoryService.reconstructBalance(f.shopId(), f.variantId());

            assertThat(balance.quantityOnHand()).isEqualByComparingTo(new BigDecimal("60.0000"));
            assertThat(reconstructed).isEqualByComparingTo(new BigDecimal("60.0000"));

            var movements = inventoryService.listStockMovements(f.shopId(), f.variantId(), 10);
            assertThat(movements).hasSize(3);
        });
    }

    @Test
    void transfer_moves_cost_and_conserves_total_organization_valuation() {
        // Invariant B11
        UUID orgId = createProvisionedOrganization("B11-" + UUID.randomUUID().toString().substring(0, 8));
        TestFixture f = createFixture(orgId, "B11");

        asInventoryTenant(orgId, () -> {
            // Source shop has 10 units @ 100 LKR (valuation = 1000 LKR)
            inventoryService.adjustStock(new InventoryService.AdjustStockCommand(
                    f.shopId(), "Initial stock", null,
                    List.of(new InventoryService.StockAdjustmentLineInput(
                            f.variantId(), new BigDecimal("10.0000"), Money.of("100.0000"), null))), null);

            // Create transfer for 4 units from shop1 to shop2
            var transfer = transferService.createTransfer(new StockTransferService.CreateTransferCommand(
                    f.shopId(), f.secondShopId(), "Transfer to Shop 2",
                    List.of(new StockTransferService.TransferLineInput(f.variantId(), new BigDecimal("4.0000")))), null);

            assertThat(transfer.transferNumber()).contains("-");
            assertThat(transfer.status()).isEqualTo(TransferStatus.DRAFT);

            // Dispatch
            var dispatched = transferService.dispatchTransfer(transfer.id(), null);
            assertThat(dispatched.status()).isEqualTo(TransferStatus.DISPATCHED);

            var srcAfterDispatch = inventoryService.getStockBalance(f.shopId(), f.variantId());
            assertThat(srcAfterDispatch.quantityOnHand()).isEqualByComparingTo(new BigDecimal("6.0000"));
            assertThat(srcAfterDispatch.quantityInTransit()).isEqualByComparingTo(new BigDecimal("4.0000"));

            // Receive at destination
            var lineId = dispatched.lines().getFirst().id();
            var received = transferService.receiveTransfer(transfer.id(), new StockTransferService.ReceiveTransferCommand(
                    List.of(new StockTransferService.ReceiveLineInput(lineId, new BigDecimal("4.0000"), null)), "All good"), null);

            assertThat(received.status()).isEqualTo(TransferStatus.RECEIVED);

            var srcFinal = inventoryService.getStockBalance(f.shopId(), f.variantId());
            var destFinal = inventoryService.getStockBalance(f.secondShopId(), f.variantId());

            assertThat(srcFinal.quantityOnHand()).isEqualByComparingTo(new BigDecimal("6.0000"));
            assertThat(srcFinal.quantityInTransit()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(srcFinal.averageCost()).isEqualTo(Money.of("100.0000"));

            assertThat(destFinal.quantityOnHand()).isEqualByComparingTo(new BigDecimal("4.0000"));
            assertThat(destFinal.averageCost()).isEqualTo(Money.of("100.0000"));

            // Total valuation = 6 * 100 + 4 * 100 = 1000 LKR (Conserved!)
            BigDecimal totalVal = srcFinal.quantityOnHand().multiply(srcFinal.averageCost().amount())
                    .add(destFinal.quantityOnHand().multiply(destFinal.averageCost().amount()));
            assertThat(totalVal).isEqualByComparingTo(new BigDecimal("1000.0000"));
        });
    }

    @Test
    void transfer_shortage_produces_shrinkage_write_off_with_recorded_reason() {
        // FR-INV-011
        UUID orgId = createProvisionedOrganization("SHRINK-" + UUID.randomUUID().toString().substring(0, 8));
        TestFixture f = createFixture(orgId, "SH");

        asInventoryTenant(orgId, () -> {
            inventoryService.adjustStock(new InventoryService.AdjustStockCommand(
                    f.shopId(), "Initial stock", null,
                    List.of(new InventoryService.StockAdjustmentLineInput(
                            f.variantId(), new BigDecimal("10.0000"), Money.of("100.0000"), null))), null);

            var transfer = transferService.createTransfer(new StockTransferService.CreateTransferCommand(
                    f.shopId(), f.secondShopId(), "Transfer 5",
                    List.of(new StockTransferService.TransferLineInput(f.variantId(), new BigDecimal("5.0000")))), null);

            transferService.dispatchTransfer(transfer.id(), null);
            var dispatched = transferService.getTransfer(transfer.id());
            var lineId = dispatched.lines().getFirst().id();

            // Missing reason when shortage exists must fail
            assertThatThrownBy(() -> transferService.receiveTransfer(transfer.id(),
                    new StockTransferService.ReceiveTransferCommand(
                            List.of(new StockTransferService.ReceiveLineInput(lineId, new BigDecimal("3.0000"), null)), null), null))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("mandatory");

            // Receive 3 with discrepancy reason
            var received = transferService.receiveTransfer(transfer.id(),
                    new StockTransferService.ReceiveTransferCommand(
                            List.of(new StockTransferService.ReceiveLineInput(
                                    lineId, new BigDecimal("3.0000"), "2 units damaged in transit")), "Received with damage"), null);

            assertThat(received.status()).isEqualTo(TransferStatus.PARTIALLY_RECEIVED);

            var destBalance = inventoryService.getStockBalance(f.secondShopId(), f.variantId());
            assertThat(destBalance.quantityOnHand()).isEqualByComparingTo(new BigDecimal("3.0000"));

            var srcBalance = inventoryService.getStockBalance(f.shopId(), f.variantId());
            assertThat(srcBalance.quantityInTransit()).isEqualByComparingTo(BigDecimal.ZERO);

            // Check that source shop has recorded shrinkage movement
            var srcMovements = inventoryService.listStockMovements(f.shopId(), f.variantId(), 10);
            boolean hasShrinkage = srcMovements.stream().anyMatch(m -> m.referenceType().equals("STOCK_TRANSFER_SHRINKAGE"));
            assertThat(hasShrinkage).isTrue();
        });
    }

    @Test
    void physical_stock_count_adjusts_balance_to_counted_quantity() {
        // FR-INV-006
        UUID orgId = createProvisionedOrganization("COUNT-" + UUID.randomUUID().toString().substring(0, 8));
        TestFixture f = createFixture(orgId, "CT");

        asInventoryTenant(orgId, () -> {
            inventoryService.adjustStock(new InventoryService.AdjustStockCommand(
                    f.shopId(), "Initial stock", null,
                    List.of(new InventoryService.StockAdjustmentLineInput(
                            f.variantId(), new BigDecimal("10.0000"), Money.of("50.0000"), null))), null);

            var count = countService.createCount(new StockCountService.CreateStockCountCommand(f.shopId(), "Annual Audit"), null);

            // Record physical count of 14 (+4 variance)
            countService.recordCounts(count.id(), new StockCountService.RecordCountsCommand(
                    List.of(new StockCountService.CountLineInput(f.variantId(), new BigDecimal("14.0000"), "Found extra box"))));

            var recorded = countService.getCount(count.id());
            assertThat(recorded.lines().getFirst().systemQuantity()).isEqualByComparingTo(new BigDecimal("10.0000"));
            assertThat(recorded.lines().getFirst().variance()).isEqualByComparingTo(new BigDecimal("4.0000"));

            // Approve count
            var approved = countService.approveCount(count.id(), null);
            assertThat(approved.status()).isEqualTo(com.heysaz.erp.inventory.api.StockCountStatus.APPROVED);

            var finalBalance = inventoryService.getStockBalance(f.shopId(), f.variantId());
            assertThat(finalBalance.quantityOnHand()).isEqualByComparingTo(new BigDecimal("14.0000"));

            // Verify count correction movement
            var movements = inventoryService.listStockMovements(f.shopId(), f.variantId(), 5);
            assertThat(movements.getFirst().movementType()).isEqualTo(com.heysaz.erp.inventory.api.MovementType.COUNT_CORRECTION);
            assertThat(movements.getFirst().quantity()).isEqualByComparingTo(new BigDecimal("4.0000"));
        });
    }

    @Test
    void stock_balance_and_movements_are_isolated_by_tenant_rls() {
        UUID orgA = createProvisionedOrganization("ORGA-" + UUID.randomUUID().toString().substring(0, 8));
        UUID orgB = createProvisionedOrganization("ORGB-" + UUID.randomUUID().toString().substring(0, 8));

        TestFixture fA = createFixture(orgA, "A");
        TestFixture fB = createFixture(orgB, "B");

        asInventoryTenant(orgA, () -> {
            inventoryService.adjustStock(new InventoryService.AdjustStockCommand(
                    fA.shopId(), "Tenant A stock", null,
                    List.of(new InventoryService.StockAdjustmentLineInput(
                            fA.variantId(), new BigDecimal("100.0000"), Money.of("10.0000"), null))), null);
        });

        // Tenant B querying Tenant A's shop / variant
        asInventoryTenant(orgB, () -> {
            // Balance query for Tenant A's shop returns zero or is isolated
            var balanceForA = inventoryService.getStockBalance(fA.shopId(), fA.variantId());
            assertThat(balanceForA.quantityOnHand()).isEqualByComparingTo(BigDecimal.ZERO);

            // Reconstruct returns 0
            assertThat(inventoryService.reconstructBalance(fA.shopId(), fA.variantId()))
                    .isEqualByComparingTo(BigDecimal.ZERO);

            // Listing movements for Shop A returns empty
            assertThat(inventoryService.listStockMovements(fA.shopId(), fA.variantId(), 10)).isEmpty();
        });
    }
}

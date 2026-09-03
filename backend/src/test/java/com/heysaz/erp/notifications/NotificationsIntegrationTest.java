package com.heysaz.erp.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.heysaz.erp.catalog.api.CatalogService;
import com.heysaz.erp.inventory.api.InventoryService;
import com.heysaz.erp.notifications.api.NotificationService;
import com.heysaz.erp.organization.api.OrganizationService;
import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;
import com.heysaz.erp.support.AbstractIntegrationTest;

/**
 * Milestone 5 — notifications (Section 13, FR-NOT-001..005) and the reorder-alert sweep
 * (FR-INV-013).
 */
class NotificationsIntegrationTest extends AbstractIntegrationTest {

    @Autowired OrganizationService orgService;
    @Autowired CatalogService catalogService;
    @Autowired InventoryService inventoryService;
    @Autowired NotificationService notificationService;

    private static final String[] PERMS = {
            "products.read", "products.write",
            "inventory.adjustments.read", "inventory.adjustments.write",
            "settings.read", "settings.write"
    };

    private void asUser(UUID orgId, UUID userId, Runnable body) {
        TenantContext.set(new Principal(Principal.PrincipalType.USER_SESSION, userId, orgId,
                "user", Set.of(), Set.of(PERMS), "T1", java.time.Instant.now()));
        try {
            body.run();
        } finally {
            TenantContext.clear();
        }
    }

    private record Fixture(UUID orgId, UUID userId, UUID shopId, UUID variantId) {
    }

    private Fixture newFixture(String tag) {
        String suffix = (tag + UUID.randomUUID().toString().substring(0, 6)).toUpperCase();
        UUID orgId = createProvisionedOrganization("NOT-" + suffix);
        UUID userId = createUser(orgId, "user-" + suffix.toLowerCase() + "@heysaz.test");
        final Fixture[] holder = new Fixture[1];

        asUser(orgId, userId, () -> {
            var shop = orgService.createShop(new OrganizationService.CreateShopCommand(
                    "SH-" + suffix, "Shop " + suffix, "N" + suffix.substring(0, 6),
                    "1 Main St", "Colombo", "0112", "Asia/Colombo", null, true), null);
            var unit = catalogService.createUnit(new CatalogService.CreateUnitCommand(
                    "EA-" + suffix, "Each", true));
            var product = catalogService.createProduct(new CatalogService.CreateProductCommand(
                    "SKU-" + suffix, "Widget " + suffix, null, null, unit.id(), null,
                    true, Money.of("100"), Money.of("150"), List.of(), List.of()), null);
            UUID variantId = product.variants().getFirst().id();
            holder[0] = new Fixture(orgId, userId, shop.id(), variantId);
        });
        return holder[0];
    }

    @Test
    void raise_list_and_read_track_the_unread_count() {
        Fixture f = newFixture("READ");

        asUser(f.orgId(), f.userId(), () -> {
            var raised = notificationService.raise(new NotificationService.RaiseCommand(
                    f.userId(), NotificationService.Type.CREDIT_LIMIT, NotificationService.Severity.WARNING,
                    "Credit limit approaching", "Acme is near its limit", "Customer", "c-1"));
            assertThat(raised.type()).isEqualTo("CREDIT_LIMIT");

            assertThat(notificationService.unreadCount()).isEqualTo(1);
            var list = notificationService.list(true, 10);
            assertThat(list).hasSize(1);
            assertThat(list.getFirst().title()).isEqualTo("Credit limit approaching");

            notificationService.markRead(raised.id());
            assertThat(notificationService.unreadCount()).isEqualTo(0);

            notificationService.raise(new NotificationService.RaiseCommand(
                    f.userId(), NotificationService.Type.BACKUP_FAILURE, NotificationService.Severity.CRITICAL,
                    "Backup failed", null, null, null));
            assertThat(notificationService.unreadCount()).isEqualTo(1);
            notificationService.markAllRead();
            assertThat(notificationService.unreadCount()).isEqualTo(0);
        });
    }

    @Test
    void reorder_sweep_raises_one_alert_per_low_variant_and_does_not_duplicate() {
        Fixture f = newFixture("REORD");

        asUser(f.orgId(), f.userId(), () -> {
            // Reorder point 5; only 3 in stock, so this variant is below its point.
            inventoryService.setThresholds(f.shopId(), f.variantId(),
                    new InventoryService.SetThresholdsCommand(
                            new BigDecimal("5"), new BigDecimal("20"), null));
            inventoryService.adjustStock(new InventoryService.AdjustStockCommand(
                    f.shopId(), "Opening stock", null,
                    List.of(new InventoryService.StockAdjustmentLineInput(
                            f.variantId(), new BigDecimal("3.0000"), Money.of("100.0000"), null))), null);

            assertThat(notificationService.runReorderSweep()).isEqualTo(1);

            var alerts = notificationService.list(true, 10);
            assertThat(alerts).hasSize(1);
            assertThat(alerts.getFirst().type()).isEqualTo("REORDER");
            assertThat(alerts.getFirst().referenceType()).isEqualTo("StockBalance");

            // A second sweep must not raise a duplicate while the first is still unread.
            assertThat(notificationService.runReorderSweep()).isEqualTo(0);
            assertThat(notificationService.unreadCount()).isEqualTo(1);
        });
    }

    @Test
    void preferences_are_stored_per_user_type_and_channel() {
        Fixture f = newFixture("PREF");

        asUser(f.orgId(), f.userId(), () -> {
            notificationService.setPreference(NotificationService.Type.REORDER,
                    NotificationService.Channel.EMAIL, true);
            notificationService.setPreference(NotificationService.Type.SHIFT_VARIANCE,
                    NotificationService.Channel.IN_APP, false);

            var prefs = notificationService.getPreferences();
            assertThat(prefs).hasSize(2);
            assertThat(prefs).anyMatch(p -> p.type().equals("REORDER")
                    && p.channel().equals("EMAIL") && p.enabled());
            assertThat(prefs).anyMatch(p -> p.type().equals("SHIFT_VARIANCE")
                    && p.channel().equals("IN_APP") && !p.enabled());
        });
    }
}

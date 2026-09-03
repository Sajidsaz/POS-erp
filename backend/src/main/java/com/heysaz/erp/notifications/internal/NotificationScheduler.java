package com.heysaz.erp.notifications.internal;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.heysaz.erp.notifications.api.NotificationService;
import com.heysaz.erp.platform.tenant.TenantContext;
import com.heysaz.erp.platform.tenant.TenantDirectory;

/**
 * Periodic notification jobs (M5 schedules). Each tenant is swept in turn: the directory
 * lists them (the one cross-tenant read), and the actual work runs through
 * {@link TenantContext#runAsOrg} on the RLS-bound pool, so a failure in one tenant's sweep
 * never touches another's data and never stops the rest.
 */
@Component
class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);

    private final TenantDirectory tenants;
    private final NotificationService notifications;

    NotificationScheduler(TenantDirectory tenants, NotificationService notifications) {
        this.tenants = tenants;
        this.notifications = notifications;
    }

    @Scheduled(
            fixedDelayString = "${erp.notifications.reorder-sweep-interval-ms:300000}",
            initialDelayString = "${erp.notifications.reorder-sweep-initial-delay-ms:300000}")
    public void sweepReorderAlerts() {
        for (UUID orgId : tenants.activeOrganizationIds()) {
            try {
                TenantContext.runAsOrg(orgId, () -> {
                    int raised = notifications.runReorderSweep();
                    if (raised > 0) {
                        log.info("Raised {} reorder alert(s) for org {}", raised, orgId);
                    }
                    return null;
                });
            } catch (Exception e) {
                log.warn("Reorder sweep failed for org {}", orgId, e);
            }
        }
    }
}

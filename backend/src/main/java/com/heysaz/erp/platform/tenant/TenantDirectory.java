package com.heysaz.erp.platform.tenant;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Enumerates the active tenants for platform-level periodic work — the reorder-alert sweep,
 * end-of-day roll-ups, retention jobs.
 *
 * <p>Listing every organization is inherently cross-tenant, so like the outbox worker this
 * reads on the elevated, RLS-bypassing connection (decision D1's named escape hatch). It
 * only ever returns identifiers; a scheduler then does the per-tenant work through
 * {@link TenantContext#runAsOrg} on the ordinary RLS-bound pool, so the actual business
 * reads and writes stay inside row-level security.
 */
@Service
public class TenantDirectory {

    private final JdbcClient elevatedJdbc;

    public TenantDirectory(@Qualifier("elevatedJdbcClient") JdbcClient elevatedJdbc) {
        this.elevatedJdbc = elevatedJdbc;
    }

    public List<UUID> activeOrganizationIds() {
        return elevatedJdbc.sql("SELECT id FROM platform.organization WHERE status = 'ACTIVE'")
                .query(UUID.class)
                .list();
    }
}

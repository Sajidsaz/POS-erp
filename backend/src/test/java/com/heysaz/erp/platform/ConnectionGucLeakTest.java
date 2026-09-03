package com.heysaz.erp.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.heysaz.erp.platform.tenant.TenantContext;
import com.heysaz.erp.support.AbstractIntegrationTest;

/**
 * The test that makes decision D1 trustworthy rather than merely intended.
 *
 * <p>Row-level security is only as strong as the promise that {@code app.org_id} is bound
 * from the credential on every transaction and never survives into the next borrower of a
 * pooled connection. A leak here would not fail loudly — it would quietly serve one
 * tenant's rows to another, which is precisely the failure the policies exist to prevent.
 */
class ConnectionGucLeakTest extends AbstractIntegrationTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Test
    void the_guc_is_bound_inside_a_transaction() {
        UUID orgId = createOrganization("GUC-" + UUID.randomUUID());
        asTenant(orgId, () -> {
            String bound = transactionTemplate.execute(status -> currentOrgSetting());
            assertThat(bound).isEqualTo(orgId.toString());
        });
    }

    /**
     * Repeated so the pool is forced to hand back a connection that a previous tenant
     * already used. With a plain {@code SET} instead of {@code SET LOCAL} this fails on
     * the second iteration.
     */
    @RepeatedTest(5)
    void the_guc_does_not_survive_into_the_next_borrower() {
        UUID orgId = createOrganization("LEAK-" + UUID.randomUUID());

        asTenant(orgId, () -> transactionTemplate.executeWithoutResult(status ->
                assertThat(currentOrgSetting()).isEqualTo(orgId.toString())));

        // Same pool, no principal bound this time.
        assertThat(TenantContext.orgIdOrNull()).isNull();
        String afterRelease = transactionTemplate.execute(status -> currentOrgSetting());
        assertThat(afterRelease).isEmpty();
    }

    @Test
    void a_second_tenant_on_a_recycled_connection_sees_its_own_binding() {
        UUID orgA = createOrganization("A-" + UUID.randomUUID());
        UUID orgB = createOrganization("B-" + UUID.randomUUID());

        asTenant(orgA, () -> transactionTemplate.executeWithoutResult(status ->
                assertThat(currentOrgSetting()).isEqualTo(orgA.toString())));
        asTenant(orgB, () -> transactionTemplate.executeWithoutResult(status ->
                assertThat(currentOrgSetting()).isEqualTo(orgB.toString())));
    }

    @Test
    void the_application_role_cannot_bypass_row_level_security() {
        // DB-011. If erp_app ever acquired BYPASSRLS or became the table owner, every
        // policy in the schema would silently stop applying.
        Boolean bypasses = jdbc.sql("SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user")
                .query(Boolean.class)
                .single();
        assertThat(bypasses).isFalse();
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("erp_app");
    }

    private String currentOrgSetting() {
        return jdbc.sql("SELECT current_setting('app.org_id', true)")
                .query(String.class)
                .optional()
                .orElse("");
    }
}

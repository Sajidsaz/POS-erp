package com.heysaz.erp.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.heysaz.erp.finance.api.ExpenseCategoryService;
import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.support.AbstractIntegrationTest;

/**
 * SEC-015 and acceptance scenario 12, at the layer where the guarantee actually lives.
 *
 * <p>These assertions matter more than the usual "service returns the right list", because
 * they are checking that isolation holds <em>without</em> the query asking for it. None of
 * the SQL in {@code ExpenseCategoryRepository} carries an org predicate; if the policy or
 * the GUC binding regressed, these tests would fail and nothing else would.
 */
class TenantIsolationTest extends AbstractIntegrationTest {

    @Autowired
    ExpenseCategoryService service;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Test
    void a_tenant_reads_only_its_own_rows() {
        UUID orgA = createOrganization("A-" + UUID.randomUUID());
        UUID orgB = createOrganization("B-" + UUID.randomUUID());

        asTenant(orgA, () -> service.create(
                new ExpenseCategoryService.CreateCommand("RENT", "Rent", Money.of(150000)), null));
        asTenant(orgB, () -> service.create(
                new ExpenseCategoryService.CreateCommand("RENT", "Rent B", Money.of(90000)), null));

        asTenant(orgA, () -> {
            List<ExpenseCategoryService.View> rows = service.listActive();
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().name()).isEqualTo("Rent");
        });
        asTenant(orgB, () -> {
            List<ExpenseCategoryService.View> rows = service.listActive();
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().name()).isEqualTo("Rent B");
        });
    }

    @Test
    void the_same_business_code_is_free_in_each_tenant() {
        // DB-003: uniqueness is scoped to the organization, so two tenants both having a
        // category called RENT is normal and must not collide.
        UUID orgA = createOrganization("A-" + UUID.randomUUID());
        UUID orgB = createOrganization("B-" + UUID.randomUUID());

        asTenant(orgA, () -> service.create(
                new ExpenseCategoryService.CreateCommand("UTIL", "Utilities", null), null));
        asTenant(orgB, () -> assertThat(service.create(
                new ExpenseCategoryService.CreateCommand("UTIL", "Utilities", null), null))
                .isNotNull());
    }

    @Test
    void another_tenants_row_is_not_found_rather_than_forbidden() {
        UUID orgA = createOrganization("A-" + UUID.randomUUID());
        UUID orgB = createOrganization("B-" + UUID.randomUUID());

        UUID[] created = new UUID[1];
        asTenant(orgA, () -> created[0] = service.create(
                new ExpenseCategoryService.CreateCommand("FUEL", "Fuel", null), null).id());

        // FR-PERM-001: the response must not disclose that the record exists at all.
        asTenant(orgB, () -> assertThatThrownBy(() -> service.get(created[0]))
                .hasMessageContaining("not found"));
    }

    @Test
    void without_tenant_context_nothing_is_readable() {
        UUID orgA = createOrganization("A-" + UUID.randomUUID());
        asTenant(orgA, () -> service.create(
                new ExpenseCategoryService.CreateCommand("MISC", "Misc", null), null));

        // No principal bound: app.current_org() is NULL, the policy admits nothing.
        // Fail closed is the whole design; an unbound request reads zero rows, never all rows.
        Long visible = transactionTemplate.execute(status ->
                jdbcCount());
        assertThat(visible).isZero();
    }

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private Long jdbcCount() {
        return jdbc.sql("SELECT count(*) FROM app.expense_category").query(Long.class).single();
    }
}

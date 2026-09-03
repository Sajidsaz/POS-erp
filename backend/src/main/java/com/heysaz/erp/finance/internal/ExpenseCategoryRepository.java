package com.heysaz.erp.finance.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.heysaz.erp.finance.domain.ExpenseCategory;
import com.heysaz.erp.platform.money.Money;

/**
 * Note what is absent from every statement here: a {@code WHERE org_id = ?} clause.
 *
 * <p>That is decision D1 doing its job. The policy in V3 filters and constrains rows
 * inside PostgreSQL against the GUC bound by {@link
 * com.heysaz.erp.platform.tenant.TenantAwareDataSource}, so a forgotten predicate cannot
 * leak another tenant's rows. The INSERT still writes {@code org_id} explicitly, and the
 * policy's WITH CHECK rejects it if it disagrees with the caller's tenant.
 */
@Repository
class ExpenseCategoryRepository {

    private final JdbcClient jdbc;

    ExpenseCategoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(ExpenseCategory category) {
        jdbc.sql("""
                INSERT INTO app.expense_category
                    (id, org_id, code, name, monthly_budget, active)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
                .param(category.id())
                .param(category.orgId())
                .param(category.code())
                .param(category.name())
                .param(category.monthlyBudget() == null ? null : category.monthlyBudget().amount())
                .param(category.active())
                .update();
    }

    Optional<ExpenseCategory> findById(UUID id) {
        return jdbc.sql("SELECT * FROM app.expense_category WHERE id = ?")
                .param(id)
                .query(this::map)
                .optional();
    }

    Optional<ExpenseCategory> findByCode(String code) {
        return jdbc.sql("SELECT * FROM app.expense_category WHERE code = ?")
                .param(code)
                .query(this::map)
                .optional();
    }

    List<ExpenseCategory> findActive() {
        return jdbc.sql("SELECT * FROM app.expense_category WHERE active ORDER BY name")
                .query(this::map)
                .list();
    }

    /** Deliberately unfiltered: used only by the isolation test to prove RLS, not by callers. */
    long countAll() {
        return jdbc.sql("SELECT count(*) FROM app.expense_category")
                .query(Long.class)
                .single();
    }

    private ExpenseCategory map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ExpenseCategory(
                rs.getObject("id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                Money.ofNullable(rs.getBigDecimal("monthly_budget")),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}

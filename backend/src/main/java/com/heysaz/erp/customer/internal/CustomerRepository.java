package com.heysaz.erp.customer.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.heysaz.erp.customer.api.CustomerService.CustomerView;
import com.heysaz.erp.customer.api.CustomerService.LedgerEntryView;
import com.heysaz.erp.platform.money.Money;

@Repository
class CustomerRepository {

    private final JdbcClient jdbc;

    CustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insertCustomer(UUID id, UUID orgId, String code, String name, String phone, String email,
                        BigDecimal creditLimit) {
        jdbc.sql("""
                INSERT INTO app.customer (id, org_id, code, name, phone, email, credit_limit)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(code).param(name).param(phone).param(email)
                .param(creditLimit)
                .update();
    }

    Optional<CustomerView> findCustomer(UUID id) {
        return jdbc.sql("""
                SELECT id, code, name, phone, email, credit_limit, credit_balance, active
                FROM app.customer WHERE id = ?
                """)
                .param(id)
                .query(this::mapCustomer)
                .optional();
    }

    List<CustomerView> search(String query, int limit) {
        String like = "%" + (query == null ? "" : query) + "%";
        return jdbc.sql("""
                SELECT id, code, name, phone, email, credit_limit, credit_balance, active
                FROM app.customer
                WHERE name ILIKE ? OR code ILIKE ? OR coalesce(phone, '') ILIKE ?
                ORDER BY name
                LIMIT ?
                """)
                .param(like).param(like).param(like).param(limit)
                .query(this::mapCustomer)
                .list();
    }

    /** The figures a charge or payment needs, taken under a row lock (decision D6). */
    record LockedAccount(UUID id, BigDecimal creditLimit, BigDecimal creditBalance) {
    }

    Optional<LockedAccount> lockCustomer(UUID id) {
        return jdbc.sql("""
                SELECT id, credit_limit, credit_balance FROM app.customer WHERE id = ?
                FOR UPDATE
                """)
                .param(id)
                .query((rs, rowNum) -> new LockedAccount(
                        rs.getObject("id", UUID.class),
                        rs.getBigDecimal("credit_limit"),
                        rs.getBigDecimal("credit_balance")))
                .optional();
    }

    void updateBalance(UUID id, BigDecimal newBalance) {
        jdbc.sql("UPDATE app.customer SET credit_balance = ?, updated_at = now() WHERE id = ?")
                .param(newBalance).param(id)
                .update();
    }

    void updateCreditLimit(UUID id, BigDecimal creditLimit) {
        jdbc.sql("UPDATE app.customer SET credit_limit = ?, updated_at = now() WHERE id = ?")
                .param(creditLimit).param(id)
                .update();
    }

    void insertLedger(UUID id, UUID orgId, UUID customerId, String entryType, BigDecimal amount,
                      String referenceType, String referenceId, BigDecimal balanceAfter, UUID actor) {
        jdbc.sql("""
                INSERT INTO app.customer_ledger
                    (id, org_id, customer_id, entry_type, amount, reference_type, reference_id,
                     balance_after, actor_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(customerId).param(entryType).param(amount)
                .param(referenceType).param(referenceId).param(balanceAfter).param(actor)
                .update();
    }

    List<LedgerEntryView> listLedger(UUID customerId, int limit) {
        return jdbc.sql("""
                SELECT id, entry_type, amount, reference_type, reference_id, balance_after,
                       actor_user_id, created_at
                FROM app.customer_ledger
                WHERE customer_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """)
                .param(customerId).param(limit)
                .query((rs, rowNum) -> new LedgerEntryView(
                        rs.getObject("id", UUID.class),
                        rs.getString("entry_type"),
                        Money.of(rs.getBigDecimal("amount")),
                        rs.getString("reference_type"),
                        rs.getString("reference_id"),
                        Money.of(rs.getBigDecimal("balance_after")),
                        rs.getObject("actor_user_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private CustomerView mapCustomer(ResultSet rs, int rowNum) throws SQLException {
        BigDecimal limit = rs.getBigDecimal("credit_limit");
        BigDecimal balance = rs.getBigDecimal("credit_balance");
        BigDecimal available = limit.subtract(balance).max(BigDecimal.ZERO);
        return new CustomerView(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("phone"),
                rs.getString("email"),
                Money.of(limit),
                Money.of(balance),
                Money.of(available),
                rs.getBoolean("active"));
    }
}

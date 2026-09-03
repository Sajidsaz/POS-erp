package com.heysaz.erp.pos.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.pos.api.ShiftService.ShiftMovementView;

@Repository
class ShiftRepository {

    private final JdbcClient jdbc;

    ShiftRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insertShift(UUID id, UUID orgId, UUID shopId, UUID terminalId, UUID cashierUserId,
                     BigDecimal openingCash, String notes) {
        jdbc.sql("""
                INSERT INTO app.shift
                    (id, org_id, shop_id, terminal_id, cashier_user_id, status, opening_cash, notes)
                VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?)
                """)
                .param(id).param(orgId).param(shopId).param(terminalId).param(cashierUserId)
                .param(openingCash).param(notes)
                .update();
    }

    boolean hasOpenShiftForTerminal(UUID terminalId) {
        return jdbc.sql("SELECT count(*) FROM app.shift WHERE terminal_id = ? AND status = 'OPEN'")
                .param(terminalId).query(Long.class).single() > 0;
    }

    boolean hasOpenShiftForCashier(UUID cashierUserId) {
        return jdbc.sql("SELECT count(*) FROM app.shift WHERE cashier_user_id = ? AND status = 'OPEN'")
                .param(cashierUserId).query(Long.class).single() > 0;
    }

    Optional<UUID> findOpenShiftIdForTerminal(UUID terminalId) {
        return jdbc.sql("SELECT id FROM app.shift WHERE terminal_id = ? AND status = 'OPEN'")
                .param(terminalId).query(UUID.class).optional();
    }

    record ShiftHeader(
            UUID id, UUID shopId, UUID terminalId, UUID cashierUserId, String status,
            BigDecimal openingCash, BigDecimal closingCash, BigDecimal expectedCash,
            BigDecimal cashVariance, Instant openedAt, Instant closedAt, String notes) {
    }

    Optional<ShiftHeader> findHeader(UUID shiftId) {
        return jdbc.sql("""
                SELECT id, shop_id, terminal_id, cashier_user_id, status, opening_cash,
                       closing_cash, expected_cash, cash_variance, opened_at, closed_at, notes
                FROM app.shift WHERE id = ?
                """)
                .param(shiftId)
                .query(this::mapHeader)
                .optional();
    }

    List<ShiftHeader> listForShop(UUID shopId) {
        return jdbc.sql("""
                SELECT id, shop_id, terminal_id, cashier_user_id, status, opening_cash,
                       closing_cash, expected_cash, cash_variance, opened_at, closed_at, notes
                FROM app.shift WHERE shop_id = ? ORDER BY opened_at DESC
                """)
                .param(shopId)
                .query(this::mapHeader)
                .list();
    }

    void insertMovement(UUID id, UUID orgId, UUID shiftId, String movementType,
                        BigDecimal amount, String reason, UUID actorUserId) {
        jdbc.sql("""
                INSERT INTO app.shift_movement
                    (id, org_id, shift_id, movement_type, amount, reason, actor_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(shiftId).param(movementType)
                .param(amount).param(reason).param(actorUserId)
                .update();
    }

    List<ShiftMovementView> findMovements(UUID shiftId) {
        return jdbc.sql("""
                SELECT id, shift_id, movement_type, amount, reason, actor_user_id, occurred_at
                FROM app.shift_movement WHERE shift_id = ? ORDER BY occurred_at
                """)
                .param(shiftId)
                .query((rs, rowNum) -> new ShiftMovementView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("shift_id", UUID.class),
                        rs.getString("movement_type"),
                        Money.of(rs.getBigDecimal("amount")),
                        rs.getString("reason"),
                        rs.getObject("actor_user_id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant()))
                .list();
    }

    void markClosed(UUID shiftId, BigDecimal closingCash, BigDecimal expectedCash,
                    BigDecimal variance, String notes, UUID closedBy) {
        jdbc.sql("""
                UPDATE app.shift
                SET status = 'CLOSED', closing_cash = ?, expected_cash = ?, cash_variance = ?,
                    closed_at = now(), closed_by = ?,
                    notes = coalesce(?, notes)
                WHERE id = ?
                """)
                .param(closingCash).param(expectedCash).param(variance).param(closedBy)
                .param(notes).param(shiftId)
                .update();
    }

    /** All cash-relevant sums for one shift, computed in the database. */
    record CashTotals(
            Money cashSales, Money cardSales, Money otherSales,
            Money changeGiven, Money cashRounding, Money cashRefunds,
            Money cashIn, Money cashOut, Money cashExpenses,
            long salesCount, long returnsCount) {
    }

    CashTotals totalsFor(UUID shiftId) {
        // Payment breakdown across the shift's sales.
        Money[] sales = jdbc.sql("""
                SELECT
                    coalesce(sum(sp.amount) FILTER (WHERE sp.payment_method = 'CASH'), 0) AS cash_sales,
                    coalesce(sum(sp.amount) FILTER (WHERE sp.payment_method = 'CARD'), 0) AS card_sales,
                    coalesce(sum(sp.amount) FILTER (WHERE sp.payment_method NOT IN ('CASH','CARD')), 0) AS other_sales
                FROM app.sale_payment sp
                JOIN app.sale s ON s.id = sp.sale_id
                WHERE s.shift_id = ? AND s.status <> 'VOIDED'
                """)
                .param(shiftId)
                .query((rs, n) -> new Money[] {
                        Money.of(rs.getBigDecimal("cash_sales")),
                        Money.of(rs.getBigDecimal("card_sales")),
                        Money.of(rs.getBigDecimal("other_sales"))})
                .single();

        record SaleAgg(Money change, Money rounding, long count) {
        }
        SaleAgg saleAgg = jdbc.sql("""
                SELECT coalesce(sum(change_given), 0) AS change_given,
                       coalesce(sum(cash_rounding), 0) AS cash_rounding,
                       count(*) AS sales_count
                FROM app.sale WHERE shift_id = ? AND status <> 'VOIDED'
                """)
                .param(shiftId)
                .query((rs, n) -> new SaleAgg(
                        Money.of(rs.getBigDecimal("change_given")),
                        Money.of(rs.getBigDecimal("cash_rounding")),
                        rs.getLong("sales_count")))
                .single();

        record RefundAgg(Money cashRefunds, long count) {
        }
        RefundAgg refundAgg = jdbc.sql("""
                SELECT coalesce(sum(rf.amount) FILTER (WHERE rf.payment_method = 'CASH'), 0) AS cash_refunds,
                       count(DISTINCT r.id) AS returns_count
                FROM app.sale_return r
                LEFT JOIN app.sale_return_refund rf ON rf.return_id = r.id
                WHERE r.shift_id = ?
                """)
                .param(shiftId)
                .query((rs, n) -> new RefundAgg(
                        Money.of(rs.getBigDecimal("cash_refunds")),
                        rs.getLong("returns_count")))
                .single();

        Money[] moves = jdbc.sql("""
                SELECT
                    coalesce(sum(amount) FILTER (WHERE movement_type = 'CASH_IN'), 0) AS cash_in,
                    coalesce(sum(amount) FILTER (WHERE movement_type = 'CASH_OUT'), 0) AS cash_out,
                    coalesce(sum(amount) FILTER (WHERE movement_type = 'EXPENSE'), 0) AS cash_expenses
                FROM app.shift_movement WHERE shift_id = ?
                """)
                .param(shiftId)
                .query((rs, n) -> new Money[] {
                        Money.of(rs.getBigDecimal("cash_in")),
                        Money.of(rs.getBigDecimal("cash_out")),
                        Money.of(rs.getBigDecimal("cash_expenses"))})
                .single();

        return new CashTotals(sales[0], sales[1], sales[2],
                saleAgg.change(), saleAgg.rounding(), refundAgg.cashRefunds(),
                moves[0], moves[1], moves[2], saleAgg.count(), refundAgg.count());
    }

    String shopName(UUID shopId) {
        return jdbc.sql("SELECT name FROM app.shop WHERE id = ?")
                .param(shopId).query(String.class).optional().orElse("");
    }

    String terminalCode(UUID terminalId) {
        return jdbc.sql("SELECT code FROM app.terminal WHERE id = ?")
                .param(terminalId).query(String.class).optional().orElse("");
    }

    String cashierName(UUID userId) {
        return jdbc.sql("SELECT display_name FROM platform.app_user WHERE id = ?")
                .param(userId).query(String.class).optional().orElse("");
    }

    private ShiftHeader mapHeader(ResultSet rs, int rowNum) throws SQLException {
        return new ShiftHeader(
                rs.getObject("id", UUID.class),
                rs.getObject("shop_id", UUID.class),
                rs.getObject("terminal_id", UUID.class),
                rs.getObject("cashier_user_id", UUID.class),
                rs.getString("status"),
                rs.getBigDecimal("opening_cash"),
                rs.getBigDecimal("closing_cash"),
                rs.getBigDecimal("expected_cash"),
                rs.getBigDecimal("cash_variance"),
                rs.getTimestamp("opened_at").toInstant(),
                rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(),
                rs.getString("notes"));
    }
}

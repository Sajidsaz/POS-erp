package com.heysaz.erp.inventory.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.heysaz.erp.inventory.api.StockCountService.CountLineView;
import com.heysaz.erp.inventory.api.StockCountService.StockCountView;
import com.heysaz.erp.inventory.api.StockCountStatus;
import com.heysaz.erp.platform.money.Money;

@Repository
class StockCountRepository {

    private final JdbcClient jdbc;

    StockCountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insertCount(UUID id, UUID orgId, UUID shopId, StockCountStatus status, String notes, UUID createdBy) {
        jdbc.sql("""
                INSERT INTO app.stock_count (id, org_id, shop_id, status, notes, created_by, started_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """)
                .param(id).param(orgId).param(shopId).param(status.name()).param(notes).param(createdBy)
                .update();
    }

    void insertCountLine(UUID id, UUID orgId, UUID countId, UUID variantId, BigDecimal systemQty,
                         BigDecimal countedQty, BigDecimal variance, BigDecimal unitCost, String notes) {
        jdbc.sql("""
                INSERT INTO app.stock_count_line
                    (id, org_id, count_id, variant_id, system_quantity, counted_quantity, variance, unit_cost, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(countId).param(variantId)
                .param(systemQty).param(countedQty).param(variance).param(unitCost).param(notes)
                .update();
    }

    void deleteCountLines(UUID countId) {
        jdbc.sql("DELETE FROM app.stock_count_line WHERE count_id = ?").param(countId).update();
    }

    void markCompleted(UUID countId) {
        jdbc.sql("UPDATE app.stock_count SET status = 'COMPLETED', completed_at = now() WHERE id = ?")
                .param(countId).update();
    }

    void markApproved(UUID countId, UUID approver) {
        jdbc.sql("UPDATE app.stock_count SET status = 'APPROVED', approved_by = ?, approved_at = now() WHERE id = ?")
                .param(approver).param(countId).update();
    }

    void cancelCount(UUID countId, String reason) {
        jdbc.sql("UPDATE app.stock_count SET status = 'CANCELLED', notes = concat_ws(' | Reason: ', notes, ?) WHERE id = ?")
                .param(reason).param(countId).update();
    }

    Optional<StockCountView> findCount(UUID id) {
        return jdbc.sql("""
                SELECT id, shop_id, status, notes, created_by, approved_by,
                       started_at, completed_at, approved_at
                FROM app.stock_count WHERE id = ?
                """)
                .param(id)
                .query(this::mapCountSummary)
                .optional()
                .map(c -> new StockCountView(
                        c.id(), c.shopId(), c.status(), c.notes(), c.createdBy(),
                        c.approvedBy(), c.startedAt(), c.completedAt(), c.approvedAt(),
                        findCountLines(id)));
    }

    List<CountLineView> findCountLines(UUID countId) {
        return jdbc.sql("""
                SELECT l.id, l.variant_id, v.sku AS variant_sku, p.name AS product_name,
                       l.system_quantity, l.counted_quantity, l.variance, l.unit_cost, l.notes
                FROM app.stock_count_line l
                JOIN app.variant v ON v.id = l.variant_id
                JOIN app.product p ON p.id = v.product_id
                WHERE l.count_id = ?
                ORDER BY p.name, v.sku
                """)
                .param(countId)
                .query((rs, rowNum) -> new CountLineView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("variant_id", UUID.class),
                        rs.getString("variant_sku"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("system_quantity"),
                        rs.getBigDecimal("counted_quantity"),
                        rs.getBigDecimal("variance"),
                        Money.ofNullable(rs.getBigDecimal("unit_cost")),
                        rs.getString("notes")))
                .list();
    }

    List<StockCountView> listCounts(UUID shopId) {
        List<StockCountView> list = new ArrayList<>();
        for (CountSummary c : jdbc.sql("""
                SELECT id, shop_id, status, notes, created_by, approved_by,
                       started_at, completed_at, approved_at
                FROM app.stock_count
                WHERE (?::uuid IS NULL OR shop_id = ?)
                ORDER BY started_at DESC
                """)
                .param(shopId).param(shopId)
                .query(this::mapCountSummary).list()) {
            list.add(new StockCountView(
                    c.id(), c.shopId(), c.status(), c.notes(), c.createdBy(),
                    c.approvedBy(), c.startedAt(), c.completedAt(), c.approvedAt(),
                    findCountLines(c.id())));
        }
        return list;
    }

    private record CountSummary(
            UUID id,
            UUID shopId,
            StockCountStatus status,
            String notes,
            UUID createdBy,
            UUID approvedBy,
            Instant startedAt,
            Instant completedAt,
            Instant approvedAt) {
    }

    private CountSummary mapCountSummary(ResultSet rs, int rowNum) throws SQLException {
        return new CountSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("shop_id", UUID.class),
                StockCountStatus.valueOf(rs.getString("status")),
                rs.getString("notes"),
                rs.getObject("created_by", UUID.class),
                rs.getObject("approved_by", UUID.class),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toInstant());
    }
}

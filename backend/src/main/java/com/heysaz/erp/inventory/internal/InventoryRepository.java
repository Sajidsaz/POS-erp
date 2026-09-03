package com.heysaz.erp.inventory.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.heysaz.erp.inventory.api.InventoryService.LowStockAlertView;
import com.heysaz.erp.inventory.api.InventoryService.StockAdjustmentLineView;
import com.heysaz.erp.inventory.api.InventoryService.StockAdjustmentView;
import com.heysaz.erp.inventory.api.InventoryService.StockBalanceView;
import com.heysaz.erp.inventory.api.InventoryService.StockMovementView;
import com.heysaz.erp.inventory.api.MovementType;
import com.heysaz.erp.platform.money.Money;

@Repository
class InventoryRepository {

    private final JdbcClient jdbc;

    InventoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record BalanceRow(
            UUID id,
            UUID shopId,
            UUID variantId,
            BigDecimal quantityOnHand,
            BigDecimal quantityReserved,
            BigDecimal quantityInTransit,
            BigDecimal averageCost,
            BigDecimal reorderPoint,
            BigDecimal reorderQuantity,
            BigDecimal lowStockThreshold) {
    }

    /**
     * Decision D6: Pessimistic locking in deterministic variant_id order to avoid deadlocks.
     */
    List<BalanceRow> lockBalances(UUID shopId, List<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return List.of();
        }
        List<UUID> sorted = new ArrayList<>(variantIds);
        Collections.sort(sorted);

        // Ensure rows exist before locking
        for (UUID variantId : sorted) {
            jdbc.sql("""
                    INSERT INTO app.stock_balance
                        (id, org_id, shop_id, variant_id, quantity_on_hand, quantity_reserved,
                         quantity_in_transit, average_cost)
                    SELECT gen_random_uuid(), s.org_id, ?, ?, 0, 0, 0, coalesce(v.average_cost, 0)
                    FROM app.shop s, app.variant v
                    WHERE s.id = ? AND v.id = ?
                    ON CONFLICT (org_id, shop_id, variant_id) DO NOTHING
                    """)
                    .param(shopId).param(variantId).param(shopId).param(variantId)
                    .update();
        }

        // Lock FOR UPDATE in sorted order
        String placeholders = String.join(",", Collections.nCopies(sorted.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(shopId);
        params.addAll(sorted);

        return jdbc.sql("""
                SELECT id, shop_id, variant_id, quantity_on_hand, quantity_reserved,
                       quantity_in_transit, average_cost, reorder_point, reorder_quantity,
                       low_stock_threshold
                FROM app.stock_balance
                WHERE shop_id = ? AND variant_id IN (%s)
                ORDER BY variant_id
                FOR UPDATE
                """.formatted(placeholders))
                .params(params)
                .query(this::mapBalanceRow)
                .list();
    }

    Optional<BalanceRow> findBalanceRow(UUID shopId, UUID variantId) {
        return jdbc.sql("""
                SELECT id, shop_id, variant_id, quantity_on_hand, quantity_reserved,
                       quantity_in_transit, average_cost, reorder_point, reorder_quantity,
                       low_stock_threshold
                FROM app.stock_balance
                WHERE shop_id = ? AND variant_id = ?
                """)
                .param(shopId).param(variantId)
                .query(this::mapBalanceRow)
                .optional();
    }

    void updateBalance(UUID shopId, UUID variantId, BigDecimal onHand, BigDecimal reserved,
                       BigDecimal inTransit, BigDecimal averageCost) {
        jdbc.sql("""
                UPDATE app.stock_balance
                SET quantity_on_hand = ?,
                    quantity_reserved = ?,
                    quantity_in_transit = ?,
                    average_cost = ?,
                    updated_at = now()
                WHERE shop_id = ? AND variant_id = ?
                """)
                .param(onHand).param(reserved).param(inTransit).param(averageCost)
                .param(shopId).param(variantId)
                .update();
    }

    Optional<StockBalanceView> findBalanceView(UUID shopId, UUID variantId) {
        return jdbc.sql("""
                SELECT b.id, b.shop_id, b.variant_id, v.sku AS variant_sku, p.name AS product_name,
                       b.quantity_on_hand, b.quantity_reserved, b.quantity_in_transit,
                       (b.quantity_on_hand - b.quantity_reserved) AS available_quantity,
                       b.average_cost, b.reorder_point, b.reorder_quantity, b.low_stock_threshold,
                       b.updated_at
                FROM app.stock_balance b
                JOIN app.variant v ON v.id = b.variant_id
                JOIN app.product p ON p.id = v.product_id
                WHERE b.shop_id = ? AND b.variant_id = ?
                """)
                .param(shopId).param(variantId)
                .query(this::mapStockBalanceView)
                .optional();
    }

    List<StockBalanceView> listBalances(UUID shopId, UUID categoryId, String search, Boolean lowStockOnly) {
        StringBuilder sql = new StringBuilder("""
                SELECT b.id, b.shop_id, b.variant_id, v.sku AS variant_sku, p.name AS product_name,
                       b.quantity_on_hand, b.quantity_reserved, b.quantity_in_transit,
                       (b.quantity_on_hand - b.quantity_reserved) AS available_quantity,
                       b.average_cost, b.reorder_point, b.reorder_quantity, b.low_stock_threshold,
                       b.updated_at
                FROM app.stock_balance b
                JOIN app.variant v ON v.id = b.variant_id
                JOIN app.product p ON p.id = v.product_id
                WHERE b.shop_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(shopId);

        if (categoryId != null) {
            sql.append(" AND p.category_id = ?");
            params.add(categoryId);
        }
        if (search != null && !search.isBlank()) {
            sql.append(" AND (p.name ILIKE ? OR v.sku ILIKE ?)");
            String term = "%" + search.trim() + "%";
            params.add(term);
            params.add(term);
        }
        if (Boolean.TRUE.equals(lowStockOnly)) {
            sql.append(" AND b.low_stock_threshold IS NOT NULL AND b.quantity_on_hand <= b.low_stock_threshold");
        }

        sql.append(" ORDER BY p.name, v.sku");
        return jdbc.sql(sql.toString()).params(params).query(this::mapStockBalanceView).list();
    }

    void setThresholds(UUID shopId, UUID variantId, BigDecimal reorderPoint,
                       BigDecimal reorderQuantity, BigDecimal lowStockThreshold) {
        jdbc.sql("""
                UPDATE app.stock_balance
                SET reorder_point = ?,
                    reorder_quantity = ?,
                    low_stock_threshold = ?,
                    updated_at = now()
                WHERE shop_id = ? AND variant_id = ?
                """)
                .param(reorderPoint).param(reorderQuantity).param(lowStockThreshold)
                .param(shopId).param(variantId)
                .update();
    }

    List<LowStockAlertView> getLowStockAlerts(UUID shopId) {
        return jdbc.sql("""
                SELECT b.shop_id, b.variant_id, v.sku AS variant_sku, p.name AS product_name,
                       b.quantity_on_hand, b.low_stock_threshold, b.reorder_point, b.reorder_quantity
                FROM app.stock_balance b
                JOIN app.variant v ON v.id = b.variant_id
                JOIN app.product p ON p.id = v.product_id
                WHERE b.shop_id = ?
                  AND b.low_stock_threshold IS NOT NULL
                  AND b.quantity_on_hand <= b.low_stock_threshold
                ORDER BY p.name, v.sku
                """)
                .param(shopId)
                .query((rs, rowNum) -> new LowStockAlertView(
                        rs.getObject("shop_id", UUID.class),
                        rs.getObject("variant_id", UUID.class),
                        rs.getString("variant_sku"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("quantity_on_hand"),
                        rs.getBigDecimal("low_stock_threshold"),
                        rs.getBigDecimal("reorder_point"),
                        rs.getBigDecimal("reorder_quantity")))
                .list();
    }

    // ----------------------------------------------------------------- Movements

    void insertMovement(UUID id, UUID orgId, UUID shopId, UUID variantId, MovementType type,
                        BigDecimal quantity, BigDecimal unitCost, BigDecimal totalCost,
                        BigDecimal resultingBalance, BigDecimal resultingAverageCost,
                        String refType, String refId, String reason, UUID actor) {
        jdbc.sql("""
                INSERT INTO app.stock_movement
                    (id, org_id, shop_id, variant_id, movement_type, quantity, unit_cost,
                     total_cost, resulting_balance, resulting_average_cost, reference_type,
                     reference_id, reason, actor_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(shopId).param(variantId).param(type.name())
                .param(quantity).param(unitCost).param(totalCost).param(resultingBalance)
                .param(resultingAverageCost).param(refType).param(refId).param(reason).param(actor)
                .update();
    }

    List<StockMovementView> listMovements(UUID shopId, UUID variantId, int limit) {
        return jdbc.sql("""
                SELECT m.id, m.shop_id, m.variant_id, v.sku AS variant_sku, m.movement_type,
                       m.quantity, m.unit_cost, m.total_cost, m.resulting_balance,
                       m.resulting_average_cost, m.reference_type, m.reference_id,
                       m.reason, m.actor_user_id, m.created_at
                FROM app.stock_movement m
                JOIN app.variant v ON v.id = m.variant_id
                WHERE m.shop_id = ? AND (?::uuid IS NULL OR m.variant_id = ?)
                ORDER BY m.created_at DESC
                LIMIT ?
                """)
                .param(shopId).param(variantId).param(variantId).param(limit)
                .query(this::mapStockMovementView)
                .list();
    }

    BigDecimal sumMovements(UUID shopId, UUID variantId) {
        BigDecimal sum = jdbc.sql("""
                SELECT coalesce(sum(quantity), 0)
                FROM app.stock_movement
                WHERE shop_id = ? AND variant_id = ?
                """)
                .param(shopId).param(variantId)
                .query(BigDecimal.class)
                .single();
        return sum == null ? BigDecimal.ZERO : sum;
    }

    // --------------------------------------------------------------- Adjustments

    void insertAdjustment(UUID id, UUID orgId, UUID shopId, String reason, String notes, UUID createdBy) {
        jdbc.sql("""
                INSERT INTO app.stock_adjustment (id, org_id, shop_id, reason, notes, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(shopId).param(reason).param(notes).param(createdBy)
                .update();
    }

    void insertAdjustmentLine(UUID id, UUID orgId, UUID adjustmentId, UUID variantId,
                              BigDecimal delta, BigDecimal unitCost, String reason) {
        jdbc.sql("""
                INSERT INTO app.stock_adjustment_line
                    (id, org_id, adjustment_id, variant_id, quantity_delta, unit_cost, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(adjustmentId).param(variantId)
                .param(delta).param(unitCost).param(reason)
                .update();
    }

    Optional<StockAdjustmentView> findAdjustment(UUID id) {
        return jdbc.sql("""
                SELECT id, shop_id, reason, notes, created_by, created_at
                FROM app.stock_adjustment WHERE id = ?
                """)
                .param(id)
                .query((rs, rowNum) -> new StockAdjustmentView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("shop_id", UUID.class),
                        rs.getString("reason"),
                        rs.getString("notes"),
                        rs.getObject("created_by", UUID.class),
                        rs.getTimestamp("created_at").toInstant(),
                        findAdjustmentLines(id)))
                .optional();
    }

    List<StockAdjustmentLineView> findAdjustmentLines(UUID adjustmentId) {
        return jdbc.sql("""
                SELECT l.id, l.variant_id, v.sku AS variant_sku, l.quantity_delta,
                       l.unit_cost, l.reason
                FROM app.stock_adjustment_line l
                JOIN app.variant v ON v.id = l.variant_id
                WHERE l.adjustment_id = ?
                ORDER BY v.sku
                """)
                .param(adjustmentId)
                .query((rs, rowNum) -> new StockAdjustmentLineView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("variant_id", UUID.class),
                        rs.getString("variant_sku"),
                        rs.getBigDecimal("quantity_delta"),
                        Money.ofNullable(rs.getBigDecimal("unit_cost")),
                        rs.getString("reason")))
                .list();
    }

    boolean isNegativeStockAllowed(UUID shopId) {
        Boolean allowed = jdbc.sql("SELECT allow_negative_stock FROM app.shop WHERE id = ?")
                .param(shopId)
                .query(Boolean.class)
                .optional()
                .orElse(null);
        return Boolean.TRUE.equals(allowed);
    }

    // ----------------------------------------------------------------- Mappers

    private BalanceRow mapBalanceRow(ResultSet rs, int rowNum) throws SQLException {
        return new BalanceRow(
                rs.getObject("id", UUID.class),
                rs.getObject("shop_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getBigDecimal("quantity_on_hand"),
                rs.getBigDecimal("quantity_reserved"),
                rs.getBigDecimal("quantity_in_transit"),
                rs.getBigDecimal("average_cost"),
                rs.getBigDecimal("reorder_point"),
                rs.getBigDecimal("reorder_quantity"),
                rs.getBigDecimal("low_stock_threshold"));
    }

    private StockBalanceView mapStockBalanceView(ResultSet rs, int rowNum) throws SQLException {
        return new StockBalanceView(
                rs.getObject("id", UUID.class),
                rs.getObject("shop_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getString("variant_sku"),
                rs.getString("product_name"),
                rs.getBigDecimal("quantity_on_hand"),
                rs.getBigDecimal("quantity_reserved"),
                rs.getBigDecimal("quantity_in_transit"),
                rs.getBigDecimal("available_quantity"),
                Money.ofNullable(rs.getBigDecimal("average_cost")),
                rs.getBigDecimal("reorder_point"),
                rs.getBigDecimal("reorder_quantity"),
                rs.getBigDecimal("low_stock_threshold"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private StockMovementView mapStockMovementView(ResultSet rs, int rowNum) throws SQLException {
        return new StockMovementView(
                rs.getObject("id", UUID.class),
                rs.getObject("shop_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getString("variant_sku"),
                MovementType.valueOf(rs.getString("movement_type")),
                rs.getBigDecimal("quantity"),
                Money.ofNullable(rs.getBigDecimal("unit_cost")),
                Money.ofNullable(rs.getBigDecimal("total_cost")),
                rs.getBigDecimal("resulting_balance"),
                Money.ofNullable(rs.getBigDecimal("resulting_average_cost")),
                rs.getString("reference_type"),
                rs.getString("reference_id"),
                rs.getString("reason"),
                rs.getObject("actor_user_id", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }
}

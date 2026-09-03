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

import com.heysaz.erp.inventory.api.StockTransferService.StockTransferView;
import com.heysaz.erp.inventory.api.StockTransferService.TransferLineView;
import com.heysaz.erp.inventory.api.TransferStatus;
import com.heysaz.erp.platform.money.Money;

@Repository
class StockTransferRepository {

    private final JdbcClient jdbc;

    StockTransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insertTransfer(UUID id, UUID orgId, String transferNumber, UUID sourceShopId,
                        UUID destShopId, TransferStatus status, String notes, UUID actor) {
        jdbc.sql("""
                INSERT INTO app.stock_transfer
                    (id, org_id, transfer_number, source_shop_id, destination_shop_id,
                     status, notes, dispatched_by, dispatched_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(transferNumber).param(sourceShopId).param(destShopId)
                .param(status.name()).param(notes)
                .param(status == TransferStatus.DISPATCHED ? actor : null)
                .param(status == TransferStatus.DISPATCHED ? java.sql.Timestamp.from(Instant.now()) : null)
                .update();
    }

    void insertTransferLine(UUID id, UUID orgId, UUID transferId, UUID variantId,
                            BigDecimal dispatchedQuantity, BigDecimal unitCost) {
        jdbc.sql("""
                INSERT INTO app.stock_transfer_line
                    (id, org_id, transfer_id, variant_id, dispatched_quantity, received_quantity, unit_cost)
                VALUES (?, ?, ?, ?, ?, 0, ?)
                """)
                .param(id).param(orgId).param(transferId).param(variantId)
                .param(dispatchedQuantity).param(unitCost)
                .update();
    }

    Optional<StockTransferView> findTransfer(UUID id) {
        return jdbc.sql("""
                SELECT id, transfer_number, source_shop_id, destination_shop_id, status,
                       dispatched_by, dispatched_at, received_by, received_at, notes,
                       created_at, updated_at
                FROM app.stock_transfer WHERE id = ?
                """)
                .param(id)
                .query(this::mapTransferSummary)
                .optional()
                .map(t -> new StockTransferView(
                        t.id(), t.transferNumber(), t.sourceShopId(), t.destinationShopId(),
                        t.status(), t.dispatchedBy(), t.dispatchedAt(), t.receivedBy(),
                        t.receivedAt(), t.notes(), t.createdAt(), t.updatedAt(),
                        findTransferLines(id)));
    }

    List<TransferLineView> findTransferLines(UUID transferId) {
        return jdbc.sql("""
                SELECT l.id, l.variant_id, v.sku AS variant_sku, p.name AS product_name,
                       l.dispatched_quantity, l.received_quantity, l.unit_cost, l.discrepancy_reason
                FROM app.stock_transfer_line l
                JOIN app.variant v ON v.id = l.variant_id
                JOIN app.product p ON p.id = v.product_id
                WHERE l.transfer_id = ?
                ORDER BY p.name, v.sku
                """)
                .param(transferId)
                .query((rs, rowNum) -> new TransferLineView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("variant_id", UUID.class),
                        rs.getString("variant_sku"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("dispatched_quantity"),
                        rs.getBigDecimal("received_quantity"),
                        Money.ofNullable(rs.getBigDecimal("unit_cost")),
                        rs.getString("discrepancy_reason")))
                .list();
    }

    void markDispatched(UUID transferId, UUID actor) {
        jdbc.sql("""
                UPDATE app.stock_transfer
                SET status = 'DISPATCHED',
                    dispatched_by = ?,
                    dispatched_at = now(),
                    updated_at = now()
                WHERE id = ?
                """)
                .param(actor).param(transferId)
                .update();
    }

    void updateTransferLineReceived(UUID lineId, BigDecimal receivedQuantity, String discrepancyReason) {
        jdbc.sql("""
                UPDATE app.stock_transfer_line
                SET received_quantity = ?,
                    discrepancy_reason = ?
                WHERE id = ?
                """)
                .param(receivedQuantity).param(discrepancyReason).param(lineId)
                .update();
    }

    void markReceived(UUID transferId, TransferStatus status, UUID actor) {
        jdbc.sql("""
                UPDATE app.stock_transfer
                SET status = ?,
                    received_by = ?,
                    received_at = now(),
                    updated_at = now()
                WHERE id = ?
                """)
                .param(status.name()).param(actor).param(transferId)
                .update();
    }

    void cancelTransfer(UUID transferId, String reason) {
        jdbc.sql("""
                UPDATE app.stock_transfer
                SET status = 'CANCELLED',
                    notes = concat_ws(' | Reason: ', notes, ?),
                    updated_at = now()
                WHERE id = ?
                """)
                .param(reason).param(transferId)
                .update();
    }

    List<StockTransferView> listTransfers(UUID shopId, TransferStatus status) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, transfer_number, source_shop_id, destination_shop_id, status,
                       dispatched_by, dispatched_at, received_by, received_at, notes,
                       created_at, updated_at
                FROM app.stock_transfer
                WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();

        if (shopId != null) {
            sql.append(" AND (source_shop_id = ? OR destination_shop_id = ?)");
            params.add(shopId);
            params.add(shopId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            params.add(status.name());
        }

        sql.append(" ORDER BY created_at DESC");

        List<StockTransferView> list = new ArrayList<>();
        for (TransferSummary t : jdbc.sql(sql.toString()).params(params).query(this::mapTransferSummary).list()) {
            list.add(new StockTransferView(
                    t.id(), t.transferNumber(), t.sourceShopId(), t.destinationShopId(),
                    t.status(), t.dispatchedBy(), t.dispatchedAt(), t.receivedBy(),
                    t.receivedAt(), t.notes(), t.createdAt(), t.updatedAt(),
                    findTransferLines(t.id())));
        }
        return list;
    }

    private record TransferSummary(
            UUID id,
            String transferNumber,
            UUID sourceShopId,
            UUID destinationShopId,
            TransferStatus status,
            UUID dispatchedBy,
            Instant dispatchedAt,
            UUID receivedBy,
            Instant receivedAt,
            String notes,
            Instant createdAt,
            Instant updatedAt) {
    }

    private TransferSummary mapTransferSummary(ResultSet rs, int rowNum) throws SQLException {
        return new TransferSummary(
                rs.getObject("id", UUID.class),
                rs.getString("transfer_number"),
                rs.getObject("source_shop_id", UUID.class),
                rs.getObject("destination_shop_id", UUID.class),
                TransferStatus.valueOf(rs.getString("status")),
                rs.getObject("dispatched_by", UUID.class),
                rs.getTimestamp("dispatched_at") == null ? null : rs.getTimestamp("dispatched_at").toInstant(),
                rs.getObject("received_by", UUID.class),
                rs.getTimestamp("received_at") == null ? null : rs.getTimestamp("received_at").toInstant(),
                rs.getString("notes"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}

package com.heysaz.erp.pos.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.pos.api.PaymentMethod;
import com.heysaz.erp.pos.api.ReturnCondition;
import com.heysaz.erp.pos.api.ReturnService.SaleReturnLineView;
import com.heysaz.erp.pos.api.ReturnService.SaleReturnRefundView;
import com.heysaz.erp.pos.api.ReturnService.SaleReturnView;

@Repository
class ReturnRepository {

    private final JdbcClient jdbc;

    ReturnRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insertReturn(UUID id, UUID orgId, String returnNumber, UUID originalSaleId, UUID shopId,
                      UUID terminalId, UUID shiftId, UUID cashierUserId, UUID approvedBy,
                      BigDecimal refundTotal, String reason) {
        jdbc.sql("""
                INSERT INTO app.sale_return
                    (id, org_id, return_number, original_sale_id, shop_id, terminal_id, shift_id,
                     cashier_user_id, approved_by, refund_total, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(returnNumber).param(originalSaleId).param(shopId)
                .param(terminalId).param(shiftId).param(cashierUserId).param(approvedBy)
                .param(refundTotal).param(reason)
                .update();
    }

    void insertReturnLine(UUID id, UUID orgId, UUID returnId, UUID originalSaleLineId, UUID variantId,
                          BigDecimal quantity, BigDecimal refundAmount, ReturnCondition condition,
                          boolean restocked) {
        jdbc.sql("""
                INSERT INTO app.sale_return_line
                    (id, org_id, return_id, original_sale_line_id, variant_id, quantity,
                     refund_amount, condition, restocked)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(returnId).param(originalSaleLineId).param(variantId)
                .param(quantity).param(refundAmount).param(condition.name()).param(restocked)
                .update();
    }

    void insertReturnRefund(UUID id, UUID orgId, UUID returnId, PaymentMethod method, BigDecimal amount) {
        jdbc.sql("""
                INSERT INTO app.sale_return_refund (id, org_id, return_id, payment_method, amount)
                VALUES (?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(returnId).param(method.name()).param(amount)
                .update();
    }

    Optional<SaleReturnView> findReturn(UUID id) {
        return jdbc.sql("""
                SELECT id, return_number, original_sale_id, shop_id, terminal_id, shift_id,
                       cashier_user_id, approved_by, refund_total, reason, created_at
                FROM app.sale_return WHERE id = ?
                """)
                .param(id)
                .query((rs, rowNum) -> new SaleReturnView(
                        rs.getObject("id", UUID.class),
                        rs.getString("return_number"),
                        rs.getObject("original_sale_id", UUID.class),
                        rs.getObject("shop_id", UUID.class),
                        rs.getObject("terminal_id", UUID.class),
                        rs.getObject("shift_id", UUID.class),
                        rs.getObject("cashier_user_id", UUID.class),
                        rs.getObject("approved_by", UUID.class),
                        Money.of(rs.getBigDecimal("refund_total")),
                        rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant(),
                        findLines(id),
                        findRefunds(id)))
                .optional();
    }

    List<SaleReturnView> listReturns(UUID shopId) {
        List<UUID> ids = jdbc.sql("""
                SELECT id FROM app.sale_return WHERE shop_id = ? ORDER BY created_at DESC
                """)
                .param(shopId)
                .query(UUID.class)
                .list();
        return ids.stream().map(id -> findReturn(id).orElseThrow()).toList();
    }

    private List<SaleReturnLineView> findLines(UUID returnId) {
        return jdbc.sql("""
                SELECT l.id, l.original_sale_line_id, l.variant_id, v.sku AS variant_sku,
                       p.name AS product_name, l.quantity, l.refund_amount, l.condition, l.restocked
                FROM app.sale_return_line l
                JOIN app.variant v ON v.id = l.variant_id
                JOIN app.product p ON p.id = v.product_id
                WHERE l.return_id = ?
                ORDER BY l.id
                """)
                .param(returnId)
                .query((rs, rowNum) -> new SaleReturnLineView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("original_sale_line_id", UUID.class),
                        rs.getObject("variant_id", UUID.class),
                        rs.getString("variant_sku"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("quantity"),
                        Money.of(rs.getBigDecimal("refund_amount")),
                        ReturnCondition.valueOf(rs.getString("condition")),
                        rs.getBoolean("restocked")))
                .list();
    }

    private List<SaleReturnRefundView> findRefunds(UUID returnId) {
        return jdbc.sql("""
                SELECT id, payment_method, amount FROM app.sale_return_refund
                WHERE return_id = ? ORDER BY id
                """)
                .param(returnId)
                .query((rs, rowNum) -> new SaleReturnRefundView(
                        rs.getObject("id", UUID.class),
                        PaymentMethod.valueOf(rs.getString("payment_method")),
                        Money.of(rs.getBigDecimal("amount"))))
                .list();
    }
}

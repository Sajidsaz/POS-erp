package com.heysaz.erp.purchasing.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.purchasing.api.PurchaseOrderStatus;
import com.heysaz.erp.purchasing.api.PurchasingService.GoodsReceiptLineView;
import com.heysaz.erp.purchasing.api.PurchasingService.GoodsReceiptView;
import com.heysaz.erp.purchasing.api.PurchasingService.PurchaseOrderLineView;
import com.heysaz.erp.purchasing.api.PurchasingService.PurchaseOrderView;
import com.heysaz.erp.purchasing.api.PurchasingService.SupplierView;

@Repository
class PurchasingRepository {

    private final JdbcClient jdbc;

    PurchasingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------- Suppliers

    void insertSupplier(UUID id, UUID orgId, String code, String name, String contactName,
                        String phone, String email, String taxRegistrationNo) {
        jdbc.sql("""
                INSERT INTO app.supplier
                    (id, org_id, code, name, contact_name, phone, email, tax_registration_no)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(code).param(name).param(contactName)
                .param(phone).param(email).param(taxRegistrationNo)
                .update();
    }

    List<SupplierView> listSuppliers() {
        return jdbc.sql("""
                SELECT id, code, name, contact_name, phone, email, tax_registration_no, active
                FROM app.supplier ORDER BY name
                """)
                .query((rs, rowNum) -> new SupplierView(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("contact_name"),
                        rs.getString("phone"),
                        rs.getString("email"),
                        rs.getString("tax_registration_no"),
                        rs.getBoolean("active")))
                .list();
    }

    boolean supplierExists(UUID supplierId) {
        return jdbc.sql("SELECT count(*) FROM app.supplier WHERE id = ?")
                .param(supplierId).query(Long.class).single() > 0;
    }

    // -------------------------------------------------------- Purchase orders

    void insertPurchaseOrder(UUID id, UUID orgId, String poNumber, UUID supplierId, UUID shopId,
                             PurchaseOrderStatus status, BigDecimal orderedTotal, String notes,
                             UUID createdBy) {
        jdbc.sql("""
                INSERT INTO app.purchase_order
                    (id, org_id, po_number, supplier_id, shop_id, status, ordered_total, notes, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(poNumber).param(supplierId).param(shopId)
                .param(status.name()).param(orderedTotal).param(notes).param(createdBy)
                .update();
    }

    void insertPurchaseOrderLine(UUID id, UUID orgId, UUID poId, UUID variantId,
                                 BigDecimal orderedQuantity, BigDecimal unitCost) {
        jdbc.sql("""
                INSERT INTO app.purchase_order_line
                    (id, org_id, po_id, variant_id, ordered_quantity, unit_cost)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(poId).param(variantId)
                .param(orderedQuantity).param(unitCost)
                .update();
    }

    void updatePurchaseOrderStatus(UUID poId, PurchaseOrderStatus status) {
        jdbc.sql("UPDATE app.purchase_order SET status = ? WHERE id = ?")
                .param(status.name()).param(poId).update();
    }

    void markPurchaseOrderApproved(UUID poId, UUID approvedBy) {
        jdbc.sql("UPDATE app.purchase_order SET status = 'APPROVED', approved_by = ? WHERE id = ?")
                .param(approvedBy).param(poId).update();
    }

    void incrementPoLineReceived(UUID poLineId, BigDecimal quantity) {
        jdbc.sql("""
                UPDATE app.purchase_order_line
                SET received_quantity = received_quantity + ?
                WHERE id = ?
                """)
                .param(quantity).param(poLineId)
                .update();
    }

    /** The raw PO-line figures a receipt needs to validate against and default its cost from. */
    record PoLineRow(UUID id, UUID poId, UUID variantId, BigDecimal orderedQuantity,
                     BigDecimal unitCost, BigDecimal receivedQuantity) {
    }

    Optional<PoLineRow> findPoLine(UUID poLineId) {
        return jdbc.sql("""
                SELECT id, po_id, variant_id, ordered_quantity, unit_cost, received_quantity
                FROM app.purchase_order_line WHERE id = ?
                """)
                .param(poLineId)
                .query((rs, rowNum) -> new PoLineRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("po_id", UUID.class),
                        rs.getObject("variant_id", UUID.class),
                        rs.getBigDecimal("ordered_quantity"),
                        rs.getBigDecimal("unit_cost"),
                        rs.getBigDecimal("received_quantity")))
                .optional();
    }

    Optional<PurchaseOrderView> findPurchaseOrder(UUID id) {
        return jdbc.sql("""
                SELECT po.id, po.po_number, po.supplier_id, s.name AS supplier_name, po.shop_id,
                       po.status, po.ordered_total, po.notes, po.created_by, po.approved_by, po.created_at
                FROM app.purchase_order po
                JOIN app.supplier s ON s.id = po.supplier_id
                WHERE po.id = ?
                """)
                .param(id)
                .query((rs, rowNum) -> new PurchaseOrderView(
                        rs.getObject("id", UUID.class),
                        rs.getString("po_number"),
                        rs.getObject("supplier_id", UUID.class),
                        rs.getString("supplier_name"),
                        rs.getObject("shop_id", UUID.class),
                        PurchaseOrderStatus.valueOf(rs.getString("status")),
                        Money.of(rs.getBigDecimal("ordered_total")),
                        rs.getString("notes"),
                        rs.getObject("created_by", UUID.class),
                        rs.getObject("approved_by", UUID.class),
                        rs.getTimestamp("created_at").toInstant(),
                        findPurchaseOrderLines(id)))
                .optional();
    }

    private List<PurchaseOrderLineView> findPurchaseOrderLines(UUID poId) {
        return jdbc.sql("""
                SELECT l.id, l.variant_id, v.sku AS variant_sku, p.name AS product_name,
                       l.ordered_quantity, l.unit_cost, l.received_quantity
                FROM app.purchase_order_line l
                JOIN app.variant v ON v.id = l.variant_id
                JOIN app.product p ON p.id = v.product_id
                WHERE l.po_id = ?
                ORDER BY l.id
                """)
                .param(poId)
                .query((rs, rowNum) -> new PurchaseOrderLineView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("variant_id", UUID.class),
                        rs.getString("variant_sku"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("ordered_quantity"),
                        Money.of(rs.getBigDecimal("unit_cost")),
                        rs.getBigDecimal("received_quantity")))
                .list();
    }

    List<PurchaseOrderView> listPurchaseOrders(UUID shopId, PurchaseOrderStatus status) {
        StringBuilder sql = new StringBuilder("SELECT id FROM app.purchase_order WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (shopId != null) {
            sql.append(" AND shop_id = ?");
            params.add(shopId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            params.add(status.name());
        }
        sql.append(" ORDER BY created_at DESC");
        List<UUID> ids = jdbc.sql(sql.toString()).params(params).query(UUID.class).list();
        return ids.stream().map(id -> findPurchaseOrder(id).orElseThrow()).toList();
    }

    // --------------------------------------------------------- Goods receipts

    void insertGoodsReceipt(UUID id, UUID orgId, String grnNumber, UUID poId, UUID supplierId,
                            UUID shopId, String notes, UUID receivedBy) {
        jdbc.sql("""
                INSERT INTO app.goods_receipt
                    (id, org_id, grn_number, po_id, supplier_id, shop_id, notes, received_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(grnNumber).param(poId).param(supplierId)
                .param(shopId).param(notes).param(receivedBy)
                .update();
    }

    void insertGoodsReceiptLine(UUID id, UUID orgId, UUID grnId, UUID poLineId, UUID variantId,
                                BigDecimal receivedQuantity, BigDecimal unitCost) {
        jdbc.sql("""
                INSERT INTO app.goods_receipt_line
                    (id, org_id, grn_id, po_line_id, variant_id, received_quantity, unit_cost)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(grnId).param(poLineId).param(variantId)
                .param(receivedQuantity).param(unitCost)
                .update();
    }

    Optional<GoodsReceiptView> findGoodsReceipt(UUID id) {
        return jdbc.sql("""
                SELECT id, grn_number, po_id, supplier_id, shop_id, notes, received_by, created_at
                FROM app.goods_receipt WHERE id = ?
                """)
                .param(id)
                .query((rs, rowNum) -> new GoodsReceiptView(
                        rs.getObject("id", UUID.class),
                        rs.getString("grn_number"),
                        rs.getObject("po_id", UUID.class),
                        rs.getObject("supplier_id", UUID.class),
                        rs.getObject("shop_id", UUID.class),
                        rs.getString("notes"),
                        rs.getObject("received_by", UUID.class),
                        rs.getTimestamp("created_at").toInstant(),
                        findGoodsReceiptLines(id)))
                .optional();
    }

    private List<GoodsReceiptLineView> findGoodsReceiptLines(UUID grnId) {
        return jdbc.sql("""
                SELECT l.id, l.po_line_id, l.variant_id, v.sku AS variant_sku, p.name AS product_name,
                       l.received_quantity, l.unit_cost
                FROM app.goods_receipt_line l
                JOIN app.variant v ON v.id = l.variant_id
                JOIN app.product p ON p.id = v.product_id
                WHERE l.grn_id = ?
                ORDER BY l.id
                """)
                .param(grnId)
                .query((rs, rowNum) -> new GoodsReceiptLineView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("po_line_id", UUID.class),
                        rs.getObject("variant_id", UUID.class),
                        rs.getString("variant_sku"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("received_quantity"),
                        Money.of(rs.getBigDecimal("unit_cost"))))
                .list();
    }

    List<GoodsReceiptView> listGoodsReceipts(UUID shopId) {
        List<UUID> ids = jdbc.sql("""
                SELECT id FROM app.goods_receipt WHERE shop_id = ? ORDER BY created_at DESC
                """)
                .param(shopId)
                .query(UUID.class)
                .list();
        return ids.stream().map(id -> findGoodsReceipt(id).orElseThrow()).toList();
    }
}

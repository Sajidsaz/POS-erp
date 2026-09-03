package com.heysaz.erp.pos.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.pos.api.PaymentMethod;
import com.heysaz.erp.pos.api.PosService.CartLineInput;
import com.heysaz.erp.pos.api.PosService.HeldCartView;
import com.heysaz.erp.pos.api.PosService.SaleLineView;
import com.heysaz.erp.pos.api.PosService.SalePaymentView;
import com.heysaz.erp.pos.api.PosService.SaleView;

@Repository
class PosRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    PosRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void insertSale(UUID id, UUID orgId, UUID shopId, UUID terminalId, UUID shiftId,
                    UUID cashierUserId, String invoiceNumber, String status,
                    BigDecimal subtotal, BigDecimal discountTotal, BigDecimal taxTotal,
                    BigDecimal grandTotal, BigDecimal cashRounding, BigDecimal totalTendered,
                    BigDecimal changeGiven, UUID customerId, String notes) {
        jdbc.sql("""
                INSERT INTO app.sale
                    (id, org_id, shop_id, terminal_id, shift_id, cashier_user_id, invoice_number,
                     status, subtotal, discount_total, tax_total, grand_total, cash_rounding,
                     total_tendered, change_given, customer_id, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(shopId).param(terminalId).param(shiftId)
                .param(cashierUserId).param(invoiceNumber).param(status).param(subtotal)
                .param(discountTotal).param(taxTotal).param(grandTotal).param(cashRounding)
                .param(totalTendered).param(changeGiven).param(customerId).param(notes)
                .update();
    }

    void insertSaleLine(UUID id, UUID orgId, UUID saleId, UUID variantId, BigDecimal quantity,
                        BigDecimal unitPrice, BigDecimal discountAmount, UUID taxClassId,
                        BigDecimal taxRate, BigDecimal taxAmount, BigDecimal lineTotal,
                        BigDecimal costSnapshot) {
        jdbc.sql("""
                INSERT INTO app.sale_line
                    (id, org_id, sale_id, variant_id, quantity, unit_price, discount_amount,
                     tax_class_id, tax_rate, tax_amount, line_total, cost_snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(saleId).param(variantId).param(quantity)
                .param(unitPrice).param(discountAmount).param(taxClassId).param(taxRate)
                .param(taxAmount).param(lineTotal).param(costSnapshot)
                .update();
    }

    void insertSalePayment(UUID id, UUID orgId, UUID saleId, PaymentMethod method,
                           BigDecimal amount, String reference, BigDecimal tendered, BigDecimal change) {
        jdbc.sql("""
                INSERT INTO app.sale_payment
                    (id, org_id, sale_id, payment_method, amount, reference, tendered_amount, change_amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(saleId).param(method.name()).param(amount)
                .param(reference).param(tendered).param(change)
                .update();
    }

    Optional<SaleView> findSale(UUID id) {
        return jdbc.sql("""
                SELECT id, invoice_number, shop_id, terminal_id, shift_id, cashier_user_id,
                       status, subtotal, discount_total, tax_total, grand_total, cash_rounding,
                       total_tendered, change_given, customer_id, notes, created_at
                FROM app.sale WHERE id = ?
                """)
                .param(id)
                .query(this::mapSaleHeader)
                .optional()
                .map(s -> new SaleView(
                        s.id(), s.invoiceNumber(), s.shopId(), s.terminalId(), s.shiftId(),
                        s.cashierUserId(), s.status(), Money.of(s.subtotal()),
                        Money.of(s.discountTotal()), Money.of(s.taxTotal()), Money.of(s.grandTotal()),
                        Money.of(s.cashRounding()), Money.of(s.totalTendered()),
                        Money.of(s.changeGiven()), s.customerId(), s.notes(), s.createdAt(),
                        findSaleLines(id), findSalePayments(id)));
    }

    Optional<SaleView> findSaleByInvoice(String invoiceNumber) {
        return jdbc.sql("""
                SELECT id, invoice_number, shop_id, terminal_id, shift_id, cashier_user_id,
                       status, subtotal, discount_total, tax_total, grand_total, cash_rounding,
                       total_tendered, change_given, customer_id, notes, created_at
                FROM app.sale WHERE invoice_number = ?
                """)
                .param(invoiceNumber)
                .query(this::mapSaleHeader)
                .optional()
                .map(s -> new SaleView(
                        s.id(), s.invoiceNumber(), s.shopId(), s.terminalId(), s.shiftId(),
                        s.cashierUserId(), s.status(), Money.of(s.subtotal()),
                        Money.of(s.discountTotal()), Money.of(s.taxTotal()), Money.of(s.grandTotal()),
                        Money.of(s.cashRounding()), Money.of(s.totalTendered()),
                        Money.of(s.changeGiven()), s.customerId(), s.notes(), s.createdAt(),
                        findSaleLines(s.id()), findSalePayments(s.id())));
    }

    List<SaleLineView> findSaleLines(UUID saleId) {
        return jdbc.sql("""
                SELECT l.id, l.variant_id, v.sku AS variant_sku, p.name AS product_name,
                       l.quantity, l.unit_price, l.discount_amount, l.tax_rate, l.tax_amount,
                       l.line_total, l.cost_snapshot, l.returned_quantity
                FROM app.sale_line l
                JOIN app.variant v ON v.id = l.variant_id
                JOIN app.product p ON p.id = v.product_id
                WHERE l.sale_id = ?
                ORDER BY l.id
                """)
                .param(saleId)
                .query((rs, rowNum) -> new SaleLineView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("variant_id", UUID.class),
                        rs.getString("variant_sku"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("quantity"),
                        Money.of(rs.getBigDecimal("unit_price")),
                        Money.of(rs.getBigDecimal("discount_amount")),
                        rs.getBigDecimal("tax_rate"),
                        Money.of(rs.getBigDecimal("tax_amount")),
                        Money.of(rs.getBigDecimal("line_total")),
                        Money.of(rs.getBigDecimal("cost_snapshot")),
                        rs.getBigDecimal("returned_quantity")))
                .list();
    }

    List<SalePaymentView> findSalePayments(UUID saleId) {
        return jdbc.sql("""
                SELECT id, payment_method, amount, reference, tendered_amount, change_amount
                FROM app.sale_payment
                WHERE sale_id = ?
                ORDER BY id
                """)
                .param(saleId)
                .query((rs, rowNum) -> new SalePaymentView(
                        rs.getObject("id", UUID.class),
                        PaymentMethod.valueOf(rs.getString("payment_method")),
                        Money.of(rs.getBigDecimal("amount")),
                        rs.getString("reference"),
                        Money.ofNullable(rs.getBigDecimal("tendered_amount")),
                        Money.ofNullable(rs.getBigDecimal("change_amount"))))
                .list();
    }

    void updateSaleStatus(UUID saleId, String status) {
        jdbc.sql("UPDATE app.sale SET status = ? WHERE id = ?").param(status).param(saleId).update();
    }

    void incrementReturnedQuantity(UUID saleLineId, BigDecimal quantity) {
        jdbc.sql("""
                UPDATE app.sale_line
                SET returned_quantity = returned_quantity + ?
                WHERE id = ?
                """)
                .param(quantity).param(saleLineId)
                .update();
    }

    // --------------------------------------------------------------- Held Carts

    void insertHeldCart(UUID id, UUID orgId, UUID shopId, UUID terminalId, UUID cashierUserId,
                        String reference, String payloadJson) {
        jdbc.sql("""
                INSERT INTO app.held_cart
                    (id, org_id, shop_id, terminal_id, cashier_user_id, reference, cart_payload)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """)
                .param(id).param(orgId).param(shopId).param(terminalId).param(cashierUserId)
                .param(reference).param(payloadJson)
                .update();
    }

    Optional<HeldCartView> findHeldCart(UUID id) {
        return jdbc.sql("""
                SELECT id, shop_id, terminal_id, cashier_user_id, reference, cart_payload::text, created_at
                FROM app.held_cart WHERE id = ?
                """)
                .param(id)
                .query((rs, rowNum) -> new HeldCartView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("shop_id", UUID.class),
                        rs.getObject("terminal_id", UUID.class),
                        rs.getObject("cashier_user_id", UUID.class),
                        rs.getString("reference"),
                        parseLinesJson(rs.getString("cart_payload")),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    List<HeldCartView> listHeldCarts(UUID shopId) {
        return jdbc.sql("""
                SELECT id, shop_id, terminal_id, cashier_user_id, reference, cart_payload::text, created_at
                FROM app.held_cart
                WHERE shop_id = ?
                ORDER BY created_at DESC
                """)
                .param(shopId)
                .query((rs, rowNum) -> new HeldCartView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("shop_id", UUID.class),
                        rs.getObject("terminal_id", UUID.class),
                        rs.getObject("cashier_user_id", UUID.class),
                        rs.getString("reference"),
                        parseLinesJson(rs.getString("cart_payload")),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    void deleteHeldCart(UUID id) {
        jdbc.sql("DELETE FROM app.held_cart WHERE id = ?").param(id).update();
    }

    // ------------------------------------------------------------- Receipt Meta

    record ShopReceiptInfo(String name, String address, String taxRegNo, String header, String footer) {
    }

    ShopReceiptInfo findShopReceiptInfo(UUID shopId) {
        return jdbc.sql("""
                SELECT name, concat_ws(', ', address_line1, city) AS address,
                       tax_registration_no, receipt_header, receipt_footer
                FROM app.shop WHERE id = ?
                """)
                .param(shopId)
                .query((rs, rowNum) -> new ShopReceiptInfo(
                        rs.getString("name"),
                        rs.getString("address"),
                        rs.getString("tax_registration_no"),
                        rs.getString("receipt_header"),
                        rs.getString("receipt_footer")))
                .optional()
                .orElse(new ShopReceiptInfo("HeySaz Shop", "", "", "", ""));
    }

    String findTerminalCode(UUID terminalId) {
        return jdbc.sql("SELECT code FROM app.terminal WHERE id = ?")
                .param(terminalId).query(String.class).optional().orElse("T1");
    }

    String findCashierName(UUID userId) {
        return jdbc.sql("SELECT display_name FROM platform.app_user WHERE id = ?")
                .param(userId).query(String.class).optional().orElse("Cashier");
    }

    void insertReceiptReprint(UUID id, UUID orgId, UUID saleId, UUID actorUserId, String reason) {
        jdbc.sql("""
                INSERT INTO app.receipt_reprint_log (id, org_id, sale_id, actor_user_id, reason)
                VALUES (?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(saleId).param(actorUserId).param(reason)
                .update();
    }

    // --------------------------------------------------------------- Shift refs

    Optional<String> findShiftStatus(UUID shiftId) {
        return jdbc.sql("SELECT status FROM app.shift WHERE id = ?")
                .param(shiftId).query(String.class).optional();
    }

    Optional<UUID> findOpenShiftForTerminal(UUID terminalId) {
        return jdbc.sql("SELECT id FROM app.shift WHERE terminal_id = ? AND status = 'OPEN'")
                .param(terminalId).query(UUID.class).optional();
    }

    // -------------------------------------------------------- Pricing metadata

    /** FR-POS-002: selling is refused at a warehouse or a suspended shop (FR-ORG-008). */
    boolean isSellingEnabled(UUID shopId) {
        return jdbc.sql("SELECT selling_enabled AND active FROM app.shop WHERE id = ?")
                .param(shopId).query(Boolean.class).optional().orElse(false);
    }

    UUID shopDefaultTaxClass(UUID shopId) {
        return jdbc.sql("SELECT default_tax_class_id FROM app.shop WHERE id = ?")
                .param(shopId).query(UUID.class).optional().orElse(null);
    }

    /** True when the resolving price list quotes tax-inclusive amounts (decision D4). */
    boolean isPriceListInclusive(UUID priceListId) {
        return jdbc.sql("SELECT tax_inclusive FROM app.price_list WHERE id = ?")
                .param(priceListId).query(Boolean.class).optional().orElse(false);
    }

    /** SKU, product name and tax class for a set of variants, in one round trip. */
    record VariantMeta(UUID variantId, String sku, String productName, UUID taxClassId) {
    }

    Map<UUID, VariantMeta> findVariantMeta(List<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(variantIds.size(), "?"));
        Map<UUID, VariantMeta> out = new java.util.LinkedHashMap<>();
        jdbc.sql("""
                SELECT v.id AS variant_id, v.sku, p.name AS product_name, p.tax_class_id
                FROM app.variant v
                JOIN app.product p ON p.id = v.product_id
                WHERE v.id IN (%s)
                """.formatted(placeholders))
                .params(new ArrayList<Object>(variantIds))
                .query((rs, rowNum) -> new VariantMeta(
                        rs.getObject("variant_id", UUID.class),
                        rs.getString("sku"),
                        rs.getString("product_name"),
                        rs.getObject("tax_class_id", UUID.class)))
                .list()
                .forEach(m -> out.put(m.variantId(), m));
        return out;
    }

    // ------------------------------------------------------------------ Helpers

    private record SaleHeaderRow(
            UUID id, String invoiceNumber, UUID shopId, UUID terminalId, UUID shiftId,
            UUID cashierUserId, String status, BigDecimal subtotal, BigDecimal discountTotal,
            BigDecimal taxTotal, BigDecimal grandTotal, BigDecimal cashRounding,
            BigDecimal totalTendered, BigDecimal changeGiven, UUID customerId, String notes,
            Instant createdAt) {
    }

    private SaleHeaderRow mapSaleHeader(ResultSet rs, int rowNum) throws SQLException {
        return new SaleHeaderRow(
                rs.getObject("id", UUID.class),
                rs.getString("invoice_number"),
                rs.getObject("shop_id", UUID.class),
                rs.getObject("terminal_id", UUID.class),
                rs.getObject("shift_id", UUID.class),
                rs.getObject("cashier_user_id", UUID.class),
                rs.getString("status"),
                rs.getBigDecimal("subtotal"),
                rs.getBigDecimal("discount_total"),
                rs.getBigDecimal("tax_total"),
                rs.getBigDecimal("grand_total"),
                rs.getBigDecimal("cash_rounding"),
                rs.getBigDecimal("total_tendered"),
                rs.getBigDecimal("change_given"),
                rs.getObject("customer_id", UUID.class),
                rs.getString("notes"),
                rs.getTimestamp("created_at").toInstant());
    }

    private List<CartLineInput> parseLinesJson(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<CartLineInput>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}

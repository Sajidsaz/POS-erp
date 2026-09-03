package com.heysaz.erp.reporting.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.reporting.api.ReportingService.InventoryValuationRow;
import com.heysaz.erp.reporting.api.ReportingService.PaymentMixRow;
import com.heysaz.erp.reporting.api.ReportingService.SalesByDayRow;
import com.heysaz.erp.reporting.api.ReportingService.TopProductRow;

@Repository
class ReportingRepository {

    private final JdbcClient jdbc;

    ReportingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record HeaderAgg(long salesCount, BigDecimal gross, BigDecimal tax, BigDecimal discount,
                     BigDecimal rounding) {
    }

    HeaderAgg salesHeaderAgg(UUID shopId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT count(*) AS sales_count,
                       coalesce(sum(grand_total), 0) AS gross,
                       coalesce(sum(tax_total), 0) AS tax,
                       coalesce(sum(discount_total), 0) AS discount,
                       coalesce(sum(cash_rounding), 0) AS rounding
                FROM app.sale
                WHERE (?::uuid IS NULL OR shop_id = ?)
                  AND created_at::date BETWEEN ? AND ?
                  AND status <> 'VOIDED'
                """)
                .param(shopId).param(shopId).param(from).param(to)
                .query((rs, n) -> new HeaderAgg(
                        rs.getLong("sales_count"),
                        rs.getBigDecimal("gross"),
                        rs.getBigDecimal("tax"),
                        rs.getBigDecimal("discount"),
                        rs.getBigDecimal("rounding")))
                .single();
    }

    record LineAgg(BigDecimal cogs, BigDecimal netRevenue) {
    }

    LineAgg salesLineAgg(UUID shopId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT coalesce(sum(l.cost_snapshot * l.quantity), 0) AS cogs,
                       coalesce(sum(l.line_total - l.tax_amount), 0) AS net_revenue
                FROM app.sale_line l
                JOIN app.sale s ON s.id = l.sale_id
                WHERE (?::uuid IS NULL OR s.shop_id = ?)
                  AND s.created_at::date BETWEEN ? AND ?
                  AND s.status <> 'VOIDED'
                """)
                .param(shopId).param(shopId).param(from).param(to)
                .query((rs, n) -> new LineAgg(rs.getBigDecimal("cogs"), rs.getBigDecimal("net_revenue")))
                .single();
    }

    record ReturnAgg(long returnsCount, BigDecimal total) {
    }

    ReturnAgg returnAgg(UUID shopId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT count(*) AS returns_count, coalesce(sum(refund_total), 0) AS total
                FROM app.sale_return
                WHERE (?::uuid IS NULL OR shop_id = ?)
                  AND created_at::date BETWEEN ? AND ?
                """)
                .param(shopId).param(shopId).param(from).param(to)
                .query((rs, n) -> new ReturnAgg(rs.getLong("returns_count"), rs.getBigDecimal("total")))
                .single();
    }

    List<SalesByDayRow> salesByDay(UUID shopId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT created_at::date AS day, count(*) AS sales_count,
                       coalesce(sum(grand_total), 0) AS gross
                FROM app.sale
                WHERE (?::uuid IS NULL OR shop_id = ?)
                  AND created_at::date BETWEEN ? AND ?
                  AND status <> 'VOIDED'
                GROUP BY created_at::date
                ORDER BY created_at::date
                """)
                .param(shopId).param(shopId).param(from).param(to)
                .query((rs, n) -> new SalesByDayRow(
                        rs.getObject("day", LocalDate.class),
                        rs.getLong("sales_count"),
                        Money.of(rs.getBigDecimal("gross"))))
                .list();
    }

    List<TopProductRow> topProducts(UUID shopId, LocalDate from, LocalDate to, int limit) {
        return jdbc.sql("""
                SELECT l.variant_id, v.sku AS variant_sku, p.name AS product_name,
                       sum(l.quantity) AS qty,
                       sum(l.line_total - l.tax_amount) AS revenue,
                       sum((l.line_total - l.tax_amount) - l.cost_snapshot * l.quantity) AS profit
                FROM app.sale_line l
                JOIN app.sale s ON s.id = l.sale_id
                JOIN app.variant v ON v.id = l.variant_id
                JOIN app.product p ON p.id = v.product_id
                WHERE (?::uuid IS NULL OR s.shop_id = ?)
                  AND s.created_at::date BETWEEN ? AND ?
                  AND s.status <> 'VOIDED'
                GROUP BY l.variant_id, v.sku, p.name
                ORDER BY revenue DESC
                LIMIT ?
                """)
                .param(shopId).param(shopId).param(from).param(to).param(limit)
                .query((rs, n) -> new TopProductRow(
                        rs.getObject("variant_id", UUID.class),
                        rs.getString("variant_sku"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("qty"),
                        Money.of(rs.getBigDecimal("revenue")),
                        Money.of(rs.getBigDecimal("profit"))))
                .list();
    }

    List<PaymentMixRow> paymentMix(UUID shopId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT sp.payment_method, count(*) AS cnt, coalesce(sum(sp.amount), 0) AS total
                FROM app.sale_payment sp
                JOIN app.sale s ON s.id = sp.sale_id
                WHERE (?::uuid IS NULL OR s.shop_id = ?)
                  AND s.created_at::date BETWEEN ? AND ?
                  AND s.status <> 'VOIDED'
                GROUP BY sp.payment_method
                ORDER BY total DESC
                """)
                .param(shopId).param(shopId).param(from).param(to)
                .query((rs, n) -> new PaymentMixRow(
                        rs.getString("payment_method"),
                        rs.getLong("cnt"),
                        Money.of(rs.getBigDecimal("total"))))
                .list();
    }

    List<InventoryValuationRow> inventoryValuation(UUID shopId) {
        return jdbc.sql("""
                SELECT b.shop_id, sh.name AS shop_name,
                       coalesce(sum(b.quantity_on_hand * b.average_cost), 0) AS stock_value,
                       count(*) FILTER (WHERE b.quantity_on_hand <> 0) AS variant_count
                FROM app.stock_balance b
                JOIN app.shop sh ON sh.id = b.shop_id
                WHERE (?::uuid IS NULL OR b.shop_id = ?)
                GROUP BY b.shop_id, sh.name
                ORDER BY sh.name
                """)
                .param(shopId).param(shopId)
                .query((rs, n) -> new InventoryValuationRow(
                        rs.getObject("shop_id", UUID.class),
                        rs.getString("shop_name"),
                        Money.of(rs.getBigDecimal("stock_value")),
                        rs.getLong("variant_count")))
                .list();
    }
}

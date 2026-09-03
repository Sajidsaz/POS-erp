package com.heysaz.erp.reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.heysaz.erp.platform.money.Money;

/**
 * Read-only operational and financial reporting (Section 12).
 *
 * <p>Gross profit is read from the cost snapshot each sale line captured at the moment of
 * sale (decision D2 / invariant B7), never recomputed from today's average — so a report
 * over a past period reproduces the margin as it actually was. These are aggregate reads
 * over the same RLS-bound tables the rest of the application uses; a tenant only ever sees
 * its own figures.
 *
 * <p>Day boundaries currently fall on the UTC calendar date; resolving them in each shop's
 * own timezone (DB-005) is a tracked refinement.
 */
public interface ReportingService {

    record SalesSummary(
            LocalDate fromDate,
            LocalDate toDate,
            UUID shopId,
            long salesCount,
            Money grossSales,
            Money totalTax,
            Money totalDiscount,
            Money cashRounding,
            Money costOfGoodsSold,
            Money grossProfit,
            long returnsCount,
            Money returnsTotal,
            Money netSales) {
    }

    record SalesByDayRow(LocalDate date, long salesCount, Money grossSales) {
    }

    record TopProductRow(
            UUID variantId,
            String variantSku,
            String productName,
            BigDecimal quantitySold,
            Money revenue,
            Money grossProfit) {
    }

    record PaymentMixRow(String paymentMethod, long count, Money total) {
    }

    record InventoryValuationRow(UUID shopId, String shopName, Money stockValue, long variantCount) {
    }

    SalesSummary salesSummary(UUID shopId, LocalDate from, LocalDate to);

    List<SalesByDayRow> salesByDay(UUID shopId, LocalDate from, LocalDate to);

    List<TopProductRow> topProducts(UUID shopId, LocalDate from, LocalDate to, int limit);

    List<PaymentMixRow> paymentMix(UUID shopId, LocalDate from, LocalDate to);

    /** Stock valued at moving-average cost, per shop; {@code shopId} null covers every shop. */
    List<InventoryValuationRow> inventoryValuation(UUID shopId);

    /** FR-REP export: sales-by-day as CSV, with a header row. */
    String exportSalesByDayCsv(UUID shopId, LocalDate from, LocalDate to);
}

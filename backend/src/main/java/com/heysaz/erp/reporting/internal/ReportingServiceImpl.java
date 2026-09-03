package com.heysaz.erp.reporting.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;
import com.heysaz.erp.reporting.api.ReportingService;

@Service
class ReportingServiceImpl implements ReportingService {

    private final ReportingRepository repository;

    ReportingServiceImpl(ReportingRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public SalesSummary salesSummary(UUID shopId, LocalDate from, LocalDate to) {
        checkShopAccess(shopId);
        LocalDate[] range = range(from, to);
        ReportingRepository.HeaderAgg header = repository.salesHeaderAgg(shopId, range[0], range[1]);
        ReportingRepository.LineAgg lines = repository.salesLineAgg(shopId, range[0], range[1]);
        ReportingRepository.ReturnAgg returns = repository.returnAgg(shopId, range[0], range[1]);

        Money grossSales = Money.of(header.gross());
        Money cogs = Money.of(lines.cogs());
        Money grossProfit = Money.of(lines.netRevenue()).minus(cogs);
        Money returnsTotal = Money.of(returns.total());

        return new SalesSummary(
                range[0], range[1], shopId,
                header.salesCount(), grossSales, Money.of(header.tax()),
                Money.of(header.discount()), Money.of(header.rounding()),
                cogs, grossProfit, returns.returnsCount(), returnsTotal,
                grossSales.minus(returnsTotal));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SalesByDayRow> salesByDay(UUID shopId, LocalDate from, LocalDate to) {
        checkShopAccess(shopId);
        LocalDate[] range = range(from, to);
        return repository.salesByDay(shopId, range[0], range[1]);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TopProductRow> topProducts(UUID shopId, LocalDate from, LocalDate to, int limit) {
        checkShopAccess(shopId);
        LocalDate[] range = range(from, to);
        return repository.topProducts(shopId, range[0], range[1], Math.clamp(limit, 1, 200));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentMixRow> paymentMix(UUID shopId, LocalDate from, LocalDate to) {
        checkShopAccess(shopId);
        LocalDate[] range = range(from, to);
        return repository.paymentMix(shopId, range[0], range[1]);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InventoryValuationRow> inventoryValuation(UUID shopId) {
        if (shopId != null) {
            checkShopAccess(shopId);
        }
        return repository.inventoryValuation(shopId);
    }

    @Override
    @Transactional(readOnly = true)
    public String exportSalesByDayCsv(UUID shopId, LocalDate from, LocalDate to) {
        List<SalesByDayRow> rows = salesByDay(shopId, from, to);
        StringBuilder csv = new StringBuilder("date,sales_count,gross_sales\n");
        for (SalesByDayRow row : rows) {
            csv.append(row.date()).append(',')
                    .append(row.salesCount()).append(',')
                    .append(row.grossSales()).append('\n');
        }
        return csv.toString();
    }

    /** Defaults an open range to today, and rejects a backwards one. */
    private LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        LocalDate f = from == null ? today : from;
        LocalDate t = to == null ? today : to;
        if (t.isBefore(f)) {
            throw ApiException.conflict("Report end date is before the start date");
        }
        return new LocalDate[] {f, t};
    }

    private void checkShopAccess(UUID shopId) {
        Principal principal = TenantContext.principal().orElse(null);
        if (principal != null && shopId != null && !principal.coversShop(shopId)) {
            throw ApiException.forbidden("Not authorized for shop " + shopId);
        }
    }
}

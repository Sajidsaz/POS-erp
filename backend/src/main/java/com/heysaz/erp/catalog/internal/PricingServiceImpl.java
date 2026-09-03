package com.heysaz.erp.catalog.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heysaz.erp.catalog.api.PricingService;
import com.heysaz.erp.platform.audit.AuditService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.platform.tenant.TenantContext;

@Service
class PricingServiceImpl implements PricingService {

    private final JdbcClient jdbc;
    private final AuditService audit;

    PricingServiceImpl(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Override
    @Transactional
    public TaxClassView createTaxClass(CreateTaxClassCommand command) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO app.tax_class (id, org_id, code, name) VALUES (?, ?, ?, ?)")
                .param(id).param(TenantContext.requireOrgId())
                .param(command.code()).param(command.name())
                .update();
        return new TaxClassView(id, command.code(), command.name(), true);
    }

    @Override
    @Transactional
    public TaxRateView setTaxRate(SetTaxRateCommand command) {
        UUID orgId = TenantContext.requireOrgId();

        // Close the open-ended predecessor at the new start date. Without this the
        // exclusion constraint would simply reject the insert, which is correct but
        // unhelpful — superseding a rate is the normal way rates change.
        jdbc.sql("""
                UPDATE app.tax_rate SET effective_to = ?
                WHERE tax_class_id = ? AND effective_to IS NULL AND effective_from < ?
                """)
                .param(command.effectiveFrom()).param(command.taxClassId())
                .param(command.effectiveFrom())
                .update();

        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO app.tax_rate (id, org_id, tax_class_id, rate, effective_from, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(command.taxClassId()).param(command.rate())
                .param(command.effectiveFrom())
                .param(TenantContext.principal().map(p -> p.userId()).orElse(null))
                .update();

        // SEC-011: a tax rate change moves money, so it is audited like a price override.
        audit.record("tax_rate.set", "TaxClass", command.taxClassId().toString(), null,
                command, AuditService.Outcome.SUCCESS);
        return new TaxRateView(id, command.taxClassId(), command.rate(),
                command.effectiveFrom(), null);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> rateOn(UUID taxClassId, LocalDate asAt) {
        // Half-open range: effective_from inclusive, effective_to exclusive. A rate
        // starting on the day another ends means exactly one applies on that day.
        return jdbc.sql("""
                SELECT rate FROM app.tax_rate
                WHERE tax_class_id = ?
                  AND daterange(effective_from, effective_to, '[)') @> ?::date
                """)
                .param(taxClassId).param(asAt)
                .query(BigDecimal.class)
                .optional();
    }

    @Override
    @Transactional
    public PriceListView createPriceList(CreatePriceListCommand command) {
        UUID id = UUID.randomUUID();
        boolean inclusive = command.taxInclusive() != null && command.taxInclusive();
        jdbc.sql("""
                INSERT INTO app.price_list (id, org_id, code, name, tax_inclusive)
                VALUES (?, ?, ?, ?, ?)
                """)
                .param(id).param(TenantContext.requireOrgId())
                .param(command.code()).param(command.name()).param(inclusive)
                .update();
        return new PriceListView(id, command.code(), command.name(), inclusive, "LKR");
    }

    @Override
    @Transactional
    public void setPrice(SetPriceCommand command) {
        LocalDate from = command.effectiveFrom() == null ? LocalDate.now() : command.effectiveFrom();

        jdbc.sql("""
                UPDATE app.price SET effective_to = ?
                WHERE price_list_id = ? AND variant_id = ?
                  AND effective_to IS NULL AND effective_from < ?
                """)
                .param(from).param(command.priceListId()).param(command.variantId()).param(from)
                .update();

        jdbc.sql("""
                INSERT INTO app.price (id, org_id, price_list_id, variant_id, amount, effective_from)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
                .param(UUID.randomUUID()).param(TenantContext.requireOrgId())
                .param(command.priceListId()).param(command.variantId())
                .param(command.amount().amount()).param(from)
                .update();
    }

    @Override
    @Transactional
    public void assignPriceListToShop(UUID shopId, UUID priceListId) {
        jdbc.sql("""
                INSERT INTO app.shop_price_list (org_id, shop_id, price_list_id)
                VALUES (?, ?, ?)
                ON CONFLICT (shop_id) DO UPDATE SET price_list_id = EXCLUDED.price_list_id
                """)
                .param(TenantContext.requireOrgId()).param(shopId).param(priceListId)
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public ResolvedPrice resolve(UUID variantId, UUID shopId, LocalDate asAt) {
        LocalDate date = asAt == null ? LocalDate.now() : asAt;

        if (shopId != null) {
            Optional<Object[]> listed = jdbc.sql("""
                    SELECT p.amount, p.price_list_id
                    FROM app.shop_price_list spl
                    JOIN app.price p ON p.price_list_id = spl.price_list_id
                    WHERE spl.shop_id = ?
                      AND p.variant_id = ?
                      AND daterange(p.effective_from, p.effective_to, '[)') @> ?::date
                    """)
                    .param(shopId).param(variantId).param(date)
                    .query((rs, rowNum) -> new Object[] {
                            rs.getBigDecimal("amount"), rs.getObject("price_list_id", UUID.class) })
                    .optional();
            if (listed.isPresent()) {
                return new ResolvedPrice(Money.of((BigDecimal) listed.get()[0]),
                        (UUID) listed.get()[1], "SHOP_PRICE_LIST");
            }
        }

        BigDecimal base = jdbc.sql("SELECT base_price FROM app.variant WHERE id = ?")
                .param(variantId)
                .query(BigDecimal.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("Variant"));

        if (base == null) {
            // Better to refuse than to invent a price: a zero here would ring up a free
            // item at the till and look like a discount nobody authorised.
            throw ApiException.conflict("No price is defined for this variant");
        }
        return new ResolvedPrice(Money.of(base), null, "BASE_PRICE");
    }
}

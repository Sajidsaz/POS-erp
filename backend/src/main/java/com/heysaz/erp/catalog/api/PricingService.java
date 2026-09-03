package com.heysaz.erp.catalog.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.heysaz.erp.platform.money.Money;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Price and tax resolution.
 *
 * <p>Both are <em>as-at</em> lookups rather than "current value" lookups, and that is the
 * whole point. FR-PRICE-005 requires a completed transaction to reproduce the rate that
 * applied when it completed, and FR-FIN-008 requires reports not to move when master data
 * changes. A rate or price is therefore never edited in place; it is superseded by a row
 * with a later effective date, and history stays readable.
 */
public interface PricingService {

    record CreateTaxClassCommand(@NotBlank String code, @NotBlank String name) {
    }

    record TaxClassView(UUID id, String code, String name, boolean active) {
    }

    record SetTaxRateCommand(
            @NotNull UUID taxClassId,
            /** 0.1800 is 18%. */
            @NotNull BigDecimal rate,
            @NotNull LocalDate effectiveFrom) {
    }

    record TaxRateView(UUID id, UUID taxClassId, BigDecimal rate,
                       LocalDate effectiveFrom, LocalDate effectiveTo) {
    }

    record CreatePriceListCommand(
            @NotBlank String code,
            @NotBlank String name,
            /** Decision D4: quoting convention only. Stored amounts are always exclusive. */
            Boolean taxInclusive) {
    }

    record PriceListView(UUID id, String code, String name, boolean taxInclusive, String currency) {
    }

    record SetPriceCommand(
            @NotNull UUID priceListId,
            @NotNull UUID variantId,
            @NotNull Money amount,
            LocalDate effectiveFrom) {
    }

    /** What a line should cost, and under which rule it was decided. */
    record ResolvedPrice(Money amount, UUID priceListId, String source) {
    }

    TaxClassView createTaxClass(CreateTaxClassCommand command);

    /**
     * Adds a rate and closes off its predecessor at the same date. Overlapping periods are
     * rejected by the database as well; this keeps the common case from having to be
     * corrected by hand.
     */
    TaxRateView setTaxRate(SetTaxRateCommand command);

    /** The rate in force on {@code asAt}. Absent when the class had no rate then. */
    java.util.Optional<BigDecimal> rateOn(UUID taxClassId, LocalDate asAt);

    PriceListView createPriceList(CreatePriceListCommand command);

    void setPrice(SetPriceCommand command);

    void assignPriceListToShop(UUID shopId, UUID priceListId);

    /**
     * FR-PRICE-007 resolution order. M1 implements the last two legs — shop price list,
     * then the variant's base price; the customer-specific and customer-group legs arrive
     * with customers in M4.
     */
    ResolvedPrice resolve(UUID variantId, UUID shopId, LocalDate asAt);
}

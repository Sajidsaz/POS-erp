package com.heysaz.erp.platform.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * A monetary amount, fixed by Appendix D decision D4.
 *
 * <p>Scale is 4 and rounding is HALF_UP, matching the {@code numeric(19,4)} storage in
 * DB-004. The wrapped {@link BigDecimal} never escapes: callers that want the raw value
 * get it through {@link #amount()} for persistence only, and arithmetic stays here so
 * the rounding rule cannot drift between call sites.
 *
 * <p>Over the wire this is a <em>string</em>, not a JSON number. A JSON number becomes an
 * IEEE-754 double in JavaScript, which silently loses cents on values a retail system
 * reaches within a single day's takings.
 */
public final class Money implements Comparable<Money> {

    public static final int SCALE = 4;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(SCALE, ROUNDING));

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = amount;
    }

    public static Money of(BigDecimal value) {
        Objects.requireNonNull(value, "value");
        return new Money(value.setScale(SCALE, ROUNDING));
    }

    public static Money of(String value) {
        return of(new BigDecimal(value));
    }

    public static Money of(long value) {
        return of(BigDecimal.valueOf(value));
    }

    /** Null-tolerant, for nullable columns. */
    public static Money ofNullable(BigDecimal value) {
        return value == null ? null : of(value);
    }

    public BigDecimal amount() {
        return amount;
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public Money times(BigDecimal multiplier) {
        return of(amount.multiply(multiplier));
    }

    public Money negated() {
        return new Money(amount.negate());
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    /**
     * Presentation rounding to a currency's minor unit — 2 for LKR. Storage keeps 4
     * decimals so that unit prices for fractionally-sold goods stay exact; this is the
     * only place a value is narrowed for display or for a cash tender.
     */
    public Money toMinorUnits(int minorUnitDigits) {
        return new Money(amount.setScale(minorUnitDigits, ROUNDING).setScale(SCALE, ROUNDING));
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Money m && amount.compareTo(m.amount) == 0;
    }

    @Override
    public int hashCode() {
        return amount.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }

    /** Registers the string-on-the-wire representation. Wired in AppConfig. */
    public static SimpleModule jacksonModule() {
        SimpleModule module = new SimpleModule("MoneyModule");
        module.addSerializer(Money.class, new JsonSerializer<Money>() {
            @Override
            public void serialize(Money value, JsonGenerator gen, SerializerProvider serializers)
                    throws java.io.IOException {
                gen.writeString(value.amount.toPlainString());
            }
        });
        module.addDeserializer(Money.class, new JsonDeserializer<Money>() {
            @Override
            public Money deserialize(JsonParser p, DeserializationContext ctxt)
                    throws java.io.IOException {
                String text = p.getValueAsString();
                return text == null || text.isBlank() ? null : Money.of(text);
            }
        });
        return module;
    }
}

package com.heysaz.erp.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heysaz.erp.platform.money.Money;

/** Decision D4. Plain unit tests — no container needed. */
class MoneyTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(Money.jacksonModule());

    @Test
    void amounts_are_stored_at_scale_four() {
        assertThat(Money.of("12.5").amount()).isEqualByComparingTo(new BigDecimal("12.5000"));
        assertThat(Money.of("12.5").amount().scale()).isEqualTo(4);
    }

    @Test
    void rounding_is_half_up() {
        // 0.00005 sits exactly on the boundary; banker's rounding would give 0.0000.
        assertThat(Money.of("0.00005").amount()).isEqualByComparingTo(new BigDecimal("0.0001"));
        assertThat(Money.of("2.00004").amount()).isEqualByComparingTo(new BigDecimal("2.0000"));
    }

    @Test
    void arithmetic_keeps_the_scale() {
        Money total = Money.of("10.0001").plus(Money.of("0.9999"));
        assertThat(total).isEqualTo(Money.of("11.0000"));
        assertThat(total.amount().scale()).isEqualTo(4);
    }

    @Test
    void presentation_narrows_to_the_currency_minor_unit() {
        // LKR has two minor digits. Storage stays at four so unit prices for goods sold
        // by weight do not lose precision before they are ever totalled.
        assertThat(Money.of("1234.5678").toMinorUnits(2)).isEqualTo(Money.of("1234.5700"));
    }

    @Test
    void money_crosses_the_wire_as_a_string() throws Exception {
        record Payload(Money amount) {
        }
        String json = mapper.writeValueAsString(new Payload(Money.of("1234.5600")));

        // A JSON number would become a double in the browser and lose cents on values a
        // day's takings reaches. The quotes are the point of this assertion.
        assertThat(json).isEqualTo("{\"amount\":\"1234.5600\"}");
        assertThat(mapper.readValue(json, Payload.class).amount()).isEqualTo(Money.of("1234.56"));
    }

    @Test
    void equality_ignores_trailing_zero_differences() {
        assertThat(Money.of("5")).isEqualTo(Money.of("5.0000"));
    }
}

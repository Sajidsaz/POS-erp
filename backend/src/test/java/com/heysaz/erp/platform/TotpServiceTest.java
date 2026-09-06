package com.heysaz.erp.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.heysaz.erp.platform.security.TotpService;

/**
 * TOTP correctness against the RFC 6238 Appendix B test vectors (SHA-1 seed
 * "12345678901234567890", truncated to six digits). A pure unit test — no Spring, no DB.
 */
class TotpServiceTest {

    /** Base32 of the ASCII seed "12345678901234567890". */
    private static final String SEED = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private final TotpService totp = new TotpService();

    @Test
    void matches_the_rfc6238_reference_codes() {
        assertThat(totp.currentCode(SEED, 59_000L)).isEqualTo("287082");
        assertThat(totp.currentCode(SEED, 1_111_111_109_000L)).isEqualTo("081804");
        assertThat(totp.currentCode(SEED, 1_234_567_890_000L)).isEqualTo("005924");
        assertThat(totp.currentCode(SEED, 2_000_000_000_000L)).isEqualTo("279037");
    }

    @Test
    void verify_accepts_the_window_and_rejects_outside_it() {
        // A mid-range time (counter 10) so "two steps earlier" doesn't clamp into the window.
        long now = 300_000L;
        assertThat(totp.verify(SEED, totp.currentCode(SEED, now), now, 1)).isTrue();
        assertThat(totp.verify(SEED, "000000", now, 1)).isFalse();

        // One step (30s) earlier is inside a window of 1; two steps (60s) is not.
        assertThat(totp.verify(SEED, totp.currentCode(SEED, now - 30_000L), now, 1)).isTrue();
        assertThat(totp.verify(SEED, totp.currentCode(SEED, now - 60_000L), now, 1)).isFalse();
    }

    @Test
    void a_generated_secret_produces_codes_that_verify() {
        String secret = totp.generateSecret();
        long now = System.currentTimeMillis();
        assertThat(totp.verify(secret, totp.currentCode(secret, now), now, 1)).isTrue();
        assertThat(totp.verify(secret, "123456", now, 0)).isIn(true, false); // sanity: no exception
    }
}

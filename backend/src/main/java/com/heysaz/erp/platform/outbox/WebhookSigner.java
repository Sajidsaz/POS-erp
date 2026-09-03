package com.heysaz.erp.platform.outbox;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * FR-API-002: signs a webhook payload so the receiver can verify it came from us and was
 * not replayed.
 *
 * <p>The signed message is {@code "<timestamp>.<body>"}, HMAC-SHA256 with the subscription
 * secret. The timestamp is inside the signed material, so an attacker who captures a request
 * cannot resend it later with a fresh timestamp — the signature would no longer match. The
 * header format follows the widely-understood Stripe convention, {@code t=<unix>,v1=<hex>},
 * which receivers already have libraries for.
 */
public final class WebhookSigner {

    public static final String SIGNATURE_HEADER = "X-HeySaz-Signature";

    private WebhookSigner() {
    }

    /** The value for {@link #SIGNATURE_HEADER}: {@code t=<epochSeconds>,v1=<hex hmac>}. */
    public static String signatureHeader(String secret, long timestampSeconds, String body) {
        String signed = timestampSeconds + "." + body;
        return "t=" + timestampSeconds + ",v1=" + hmacSha256Hex(secret, signed);
    }

    public static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}

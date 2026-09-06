package com.heysaz.erp.platform.security;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

/**
 * Time-based one-time passwords, RFC 6238 (TOTP over HOTP / RFC 4226), the second factor
 * behind FR-AUTH-005 and FR-AUTH-007.
 *
 * <p>Standard parameters — HMAC-SHA1, a 30-second step, six digits — so any authenticator
 * app (Google Authenticator, 1Password, Authy) works from the {@code otpauth://} URI without
 * the user copying anything by hand. Verification accepts a small window either side of the
 * current step to tolerate clock drift between the phone and the server.
 */
@Service
public class TotpService {

    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int SECRET_BYTES = 20; // 160 bits, the RFC-recommended SHA-1 key size
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A fresh Base32 secret to hand to the authenticator app. */
    public String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /** The {@code otpauth://} URI an authenticator app scans or imports. */
    public String provisioningUri(String issuer, String account, String secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /** The current code for a secret, for a given wall-clock time. */
    public String currentCode(String secret, long epochMillis) {
        return hotp(base32Decode(secret), epochMillis / 1000L / STEP_SECONDS);
    }

    /**
     * True when {@code code} matches the secret within {@code window} steps of {@code now}.
     * A window of 1 accepts the previous, current and next 30-second code.
     */
    public boolean verify(String secret, String code, long epochMillis, int window) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String normalized = code.trim();
        byte[] key = base32Decode(secret);
        long counter = epochMillis / 1000L / STEP_SECONDS;
        for (int i = -window; i <= window; i++) {
            if (constantTimeEquals(hotp(key, counter + i), normalized)) {
                return true;
            }
        }
        return false;
    }

    private static String hotp(byte[] key, long counter) {
        byte[] msg = new byte[8];
        for (int i = 7; i >= 0; i--) {
            msg[i] = (byte) (counter & 0xff);
            counter >>>= 8;
        }
        byte[] hash;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            hash = mac.doFinal(msg);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        int otp = binary % (int) Math.pow(10, DIGITS);
        return String.format("%0" + DIGITS + "d", otp);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    // ------------------------------------------------------------------ Base32

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                sb.append(ALPHABET.charAt((buffer >> bitsLeft) & 0x1f));
            }
        }
        if (bitsLeft > 0) {
            sb.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        return sb.toString();
    }

    static byte[] base32Decode(String secret) {
        String clean = secret.trim().replace("=", "").replace(" ", "").toUpperCase();
        int outLength = clean.length() * 5 / 8;
        byte[] out = new byte[outLength];
        int buffer = 0;
        int bitsLeft = 0;
        int index = 0;
        for (int i = 0; i < clean.length(); i++) {
            int val = ALPHABET.indexOf(clean.charAt(i));
            if (val < 0) {
                throw new IllegalArgumentException("Not a Base32 character: " + clean.charAt(i));
            }
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out[index++] = (byte) ((buffer >> bitsLeft) & 0xff);
            }
        }
        return out;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

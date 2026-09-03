package com.heysaz.erp.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heysaz.erp.platform.tenant.Principal;

/**
 * Issues and verifies the two non-cookie credential types from Appendix D decision D3.
 *
 * <p>Access and refresh tokens are <em>opaque</em> and held server-side. That is the
 * whole reason for the decision: FR-AUTH-003 requires revocation to take effect on the
 * next request, and FR-USER-003 and FR-TERM-004 require it to be immediate. A stateless
 * self-validating token cannot do that without a denylist, which reintroduces the
 * server lookup it was supposed to avoid.
 *
 * <p>Integration tokens are hashed with SHA-256 rather than a password hash. That is
 * deliberate and not a weakening: the secret is 256 bits of {@link SecureRandom}, so
 * there is no dictionary to slow down, and this is a per-request lookup where an
 * adaptive hash would be a self-inflicted latency budget.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private static final String ACCESS_PREFIX = "at:";
    private static final String REFRESH_PREFIX = "rt:";
    private static final String FAMILY_PREFIX = "rtfam:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final JdbcClient elevatedJdbc;
    private final ObjectMapper mapper;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public TokenService(StringRedisTemplate redis,
                        @Qualifier("elevatedJdbcClient") JdbcClient elevatedJdbc,
                        ObjectMapper mapper,
                        @Value("${erp.auth.access-token-ttl:PT15M}") Duration accessTtl,
                        @Value("${erp.auth.refresh-token-ttl:P30D}") Duration refreshTtl) {
        this.redis = redis;
        this.elevatedJdbc = elevatedJdbc;
        this.mapper = mapper;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {
    }

    // ---------------------------------------------------------------- POS tokens

    public TokenPair issue(Principal principal) {
        return issue(principal, UUID.randomUUID().toString());
    }

    private TokenPair issue(Principal principal, String familyId) {
        String accessToken = "hs_at_" + randomSecret();
        String refreshToken = "hs_rt_" + randomSecret();

        redis.opsForValue().set(ACCESS_PREFIX + hash(accessToken), toJson(principal), accessTtl);
        redis.opsForValue().set(REFRESH_PREFIX + hash(refreshToken),
                familyId + "|" + toJson(principal), refreshTtl);
        redis.opsForValue().set(FAMILY_PREFIX + familyId, "active", refreshTtl);

        return new TokenPair(accessToken, refreshToken, accessTtl.toSeconds());
    }

    public Optional<Principal> verifyAccessToken(String token) {
        String json = redis.opsForValue().get(ACCESS_PREFIX + hash(token));
        return Optional.ofNullable(json).map(this::fromJson);
    }

    /**
     * Rotating refresh: the presented token is consumed, and a fresh pair is issued.
     * If a token that was already consumed shows up again, someone has a copy of it, so
     * the entire family is revoked rather than just that one token.
     */
    public Optional<TokenPair> refresh(String refreshToken) {
        String key = REFRESH_PREFIX + hash(refreshToken);
        String stored = redis.opsForValue().getAndDelete(key);
        if (stored == null) {
            return Optional.empty();
        }
        int sep = stored.indexOf('|');
        String familyId = stored.substring(0, sep);
        Principal principal = fromJson(stored.substring(sep + 1));

        if (Boolean.FALSE.equals(redis.hasKey(FAMILY_PREFIX + familyId))) {
            log.warn("Refresh token reuse detected for family {}; family already revoked", familyId);
            return Optional.empty();
        }
        return Optional.of(issue(principal, familyId));
    }

    public void revokeFamily(String familyId) {
        redis.delete(FAMILY_PREFIX + familyId);
    }

    // -------------------------------------------------------- Integration tokens

    /** FR-API-008: no scope means no access, and an expired or revoked token is no token. */
    public Optional<Principal> verifyIntegrationToken(String token) {
        record Row(UUID orgId, String[] scopes, UUID issuedBy, String name) {
        }
        Optional<Row> row = elevatedJdbc.sql("""
                SELECT org_id, scopes, issued_by, name
                FROM platform.integration_token
                WHERE token_hash = ?
                  AND revoked_at IS NULL
                  AND (expires_at IS NULL OR expires_at > now())
                """)
                .param(hash(token))
                .query((rs, rowNum) -> new Row(
                        rs.getObject("org_id", UUID.class),
                        (String[]) rs.getArray("scopes").getArray(),
                        rs.getObject("issued_by", UUID.class),
                        rs.getString("name")))
                .optional();

        row.ifPresent(r -> elevatedJdbc
                .sql("UPDATE platform.integration_token SET last_used_at = now() WHERE token_hash = ?")
                .param(hash(token))
                .update());

        return row.map(r -> new Principal(
                Principal.PrincipalType.INTEGRATION,
                r.issuedBy(), r.orgId(), "integration:" + r.name(),
                Set.of(), Set.of(r.scopes()), null, java.time.Instant.now()));
    }

    // ------------------------------------------------------------------ helpers

    /** The only form in which a secret is ever written down. SEC-010, FR-API-007. */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String toJson(Principal principal) {
        try {
            return mapper.writeValueAsString(principal);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise principal", e);
        }
    }

    private Principal fromJson(String json) {
        try {
            return mapper.readValue(json, Principal.class);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot deserialise principal", e);
        }
    }
}

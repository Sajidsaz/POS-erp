package com.heysaz.erp.platform.security;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.heysaz.erp.platform.tenant.Principal;

/**
 * Makes FR-AUTH-003 hold for authorization as well as authentication.
 *
 * <p>A principal carries the authorities it was issued with, so a role change would
 * otherwise not reach a user until their next login — which for a cashier on a long shift
 * could be a whole day. Rather than re-reading permissions from the database on every
 * request, this records a per-user cut-off in Redis. The filter compares the principal's
 * issue time against it: one Redis read per request, and a revoked or re-permissioned user
 * is stopped on their very next call.
 *
 * <p>The same mechanism serves FR-USER-003 (deactivation) and FR-TERM-004 (a compromised
 * terminal), because all three want the same thing: existing credentials stop working now.
 */
@Service
public class CredentialRevocationService {

    private static final String PREFIX = "credrev:";
    /** Long enough to outlive any refresh token, so a cut-off cannot expire early. */
    private static final Duration RETENTION = Duration.ofDays(45);

    private final StringRedisTemplate redis;

    public CredentialRevocationService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Everything issued to this user before now stops being accepted. */
    public void revokeUserCredentials(UUID userId) {
        redis.opsForValue().set(PREFIX + userId, Long.toString(Instant.now().toEpochMilli()), RETENTION);
    }

    /**
     * True when the principal was issued before the user's cut-off. A principal with no
     * user behind it — an integration token, a platform operator — is never stale by this
     * mechanism; those are revoked at their own source.
     */
    public boolean isStale(Principal principal) {
        if (principal.userId() == null || principal.issuedAt() == null) {
            return false;
        }
        String cutoff = redis.opsForValue().get(PREFIX + principal.userId());
        if (cutoff == null) {
            return false;
        }
        // Issued at or before the cut-off millisecond counts as stale: a role change and a
        // login in the same millisecond should fail closed, not race.
        return principal.issuedAt().toEpochMilli() <= Long.parseLong(cutoff);
    }
}

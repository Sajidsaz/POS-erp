package com.heysaz.erp.platform.tenant;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller, resolved from one of the three credential flows in
 * Appendix D decision D3, and normalised so that nothing downstream needs to know
 * which one was used.
 *
 * <p>SEC-004 / API-STD-008: {@code orgId} always derives from the credential. It is
 * never read from a request body, path or header.
 */
public record Principal(
        PrincipalType type,
        UUID userId,
        UUID orgId,
        String displayName,
        /** Shops this principal may act in. Empty means "not shop-scoped" (FR-ORG-003). */
        Set<UUID> shopScope,
        /** Capability keys from the Section 5.2 matrix, or token scopes for integrations. */
        Set<String> authorities,
        /** Terminal code for POS principals; null otherwise. Used to scope idempotency keys. */
        String terminalCode,
        /**
         * When these authorities were resolved. Compared against the user's revocation
         * cut-off so that a role change takes effect on the next request rather than the
         * next login. See {@code CredentialRevocationService}.
         */
        Instant issuedAt) {

    public enum PrincipalType {
        /** Browser session, admin application. Cookie + CSRF. */
        USER_SESSION,
        /** Tauri POS client. Opaque access token in the OS keychain. */
        POS_TERMINAL,
        /** Machine-to-machine. Scoped bearer token, Section 14.4. */
        INTEGRATION,
        /** Platform operator. Has no tenant context at all (FR-PLAT-001). */
        PLATFORM_OPERATOR,
        /**
         * An internal operation running on behalf of one organization — resolving a user's
         * permissions during login, or provisioning a new tenant's roles. Never reachable
         * from a request; only {@link TenantContext#runAsOrg} produces one.
         */
        SYSTEM
    }

    /**
     * A context-only principal for internal work that knows its organization but has no
     * human behind it. Carries no authorities, so it can bind RLS without being able to
     * pass an authorization check.
     */
    public static Principal system(UUID orgId) {
        return new Principal(PrincipalType.SYSTEM, null, orgId, "system",
                Set.of(), Set.of(), null, Instant.now());
    }

    public boolean isPlatformOperator() {
        return type == PrincipalType.PLATFORM_OPERATOR;
    }

    public boolean hasAuthority(String authority) {
        return authorities.contains(authority);
    }

    /** True when this principal may act in the given shop (FR-ORG-003). */
    public boolean coversShop(UUID shopId) {
        return shopScope.isEmpty() || shopScope.contains(shopId);
    }
}

package com.heysaz.erp.platform.tenant;

import java.util.Optional;
import java.util.UUID;

/**
 * Per-request holder for the authenticated {@link Principal}.
 *
 * <p>This is read by {@link TenantAwareDataSource} when a transaction opens, to bind
 * {@code app.org_id} for row-level security. It is set by the authentication filter
 * and cleared in a finally block on the way out — a leaked value would attach one
 * tenant's identity to another tenant's request, so the clear is not optional.
 */
public final class TenantContext {

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Principal principal) {
        CURRENT.set(principal);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<Principal> principal() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * The organization whose rows this request may touch, or null when there is no
     * tenant context — an unauthenticated request, or a platform operator.
     *
     * <p>Null binds an empty GUC, which makes {@code app.current_org()} return NULL and
     * every policy admit zero rows. Absence of context is never absence of enforcement.
     */
    public static UUID orgIdOrNull() {
        Principal p = CURRENT.get();
        return p == null ? null : p.orgId();
    }

    /** The org id, or a failure if the caller genuinely required tenant context. */
    public static UUID requireOrgId() {
        UUID orgId = orgIdOrNull();
        if (orgId == null) {
            throw new IllegalStateException("No tenant context bound to this request");
        }
        return orgId;
    }

    /**
     * Runs internal work bound to one organization, restoring whatever was bound before.
     *
     * <p>This exists so that operations which legitimately precede a request principal —
     * resolving a user's permissions at login, seeding a new tenant's roles — can still go
     * through the RLS-bound pool instead of reaching for the elevated one. The org id must
     * come from an already-authenticated record; passing a caller-supplied value here would
     * be a tenant-escape hole, which is why nothing on a request path calls it.
     */
    public static <T> T runAsOrg(UUID orgId, java.util.function.Supplier<T> body) {
        Principal previous = CURRENT.get();
        CURRENT.set(Principal.system(orgId));
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static Principal requirePrincipal() {
        Principal p = CURRENT.get();
        if (p == null) {
            throw new IllegalStateException("No authenticated principal bound to this request");
        }
        return p;
    }
}

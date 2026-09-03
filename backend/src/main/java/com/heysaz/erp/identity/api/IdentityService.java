package com.heysaz.erp.identity.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Roles, permission grants and shop scope — the identity module's public surface.
 *
 * <p>Replaces the placeholder grant that M0 shipped in {@code AuthController}. Authorities
 * now come from the Section 5.2 matrix seeded in V5, resolved through the roles a user
 * actually holds.
 */
public interface IdentityService {

    record RoleView(UUID id, String code, String name, boolean isSystem,
                    List<GrantView> grants) {
    }

    record GrantView(String permissionKey, GrantLevel level) {
    }

    /** What a principal is allowed to do, and where. */
    record Resolution(Set<String> authorities, Set<UUID> shopScope) {
    }

    /**
     * Seeds the eight standard roles from the platform templates into a new organization.
     * Idempotent: provisioning an organization twice is a no-op rather than a duplicate
     * set of roles.
     */
    void provisionRoles(UUID orgId);

    /**
     * Resolves authorities and shop scope for a user. Called during authentication, so it
     * runs before any tenant context exists.
     */
    Resolution resolve(UUID userId, UUID orgId);

    List<RoleView> listRoles();

    /**
     * FR-USER-004: the change is audited with its prior and new grant. Assigning or
     * removing a role also invalidates the user's existing sessions and tokens, so the
     * new permissions take effect on their next request rather than their next login.
     */
    void assignRole(UUID userId, UUID roleId);

    void revokeRole(UUID userId, UUID roleId);

    /** FR-ORG-003. A user with an empty scope sees no shop-scoped data at all. */
    void setShopScope(UUID userId, Set<UUID> shopIds);
}

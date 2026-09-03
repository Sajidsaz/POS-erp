package com.heysaz.erp.identity.api;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Section 5.1 legend, as a type.
 *
 * <p>A grant level is stored once per (role, permission) and expanded into the flat
 * authority strings that {@code @PreAuthorize} checks. The expansion lives here rather
 * than at each call site so that "what does APV actually let you do" has one answer.
 */
public enum GrantLevel {

    /** Read and write within the user's assigned shop scope. */
    RW,
    /** Read only. */
    R,
    /** A restricted subset, defined per capability in SRS Section 5.3. */
    LTD,
    /** May approve another user's request, but never their own (FR-PERM-002). */
    APV;

    /**
     * Expands one grant into authorities. Every level implies {@code read}: you cannot
     * approve, or act within a limit, on something you are not allowed to see.
     */
    public Set<String> authoritiesFor(String permissionKey) {
        return switch (this) {
            case RW -> Set.of(permissionKey + ".read", permissionKey + ".write");
            case R -> Set.of(permissionKey + ".read");
            case APV -> Set.of(permissionKey + ".read", permissionKey + ".approve");
            case LTD -> Set.of(permissionKey + ".read", permissionKey + ".limited");
        };
    }

    /**
     * When a user holds several roles, the strongest grant wins rather than the last one
     * read. Union, not override — a Manager who is also a Cashier keeps both.
     */
    public static Set<String> expandAll(java.util.Map<String, Set<GrantLevel>> grants) {
        return grants.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream()
                        .flatMap(level -> level.authoritiesFor(entry.getKey()).stream()))
                .collect(Collectors.toUnmodifiableSet());
    }
}

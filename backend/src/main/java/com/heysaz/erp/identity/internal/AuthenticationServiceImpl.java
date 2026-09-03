package com.heysaz.erp.identity.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.heysaz.erp.identity.api.AuthenticationService;
import com.heysaz.erp.identity.api.IdentityService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.tenant.Principal;

/**
 * Reads {@code platform.app_user} on the elevated connection.
 *
 * <p>This is one of the two justified uses of that pool: a user must be resolvable before
 * we know which organization they belong to, so there is no tenant context to bind yet.
 * Everything that follows — permissions, shop scope — goes back through the RLS-bound pool
 * via {@link IdentityService#resolve}.
 */
@Service
class AuthenticationServiceImpl implements AuthenticationService {

    /** A well-formed bcrypt hash that matches nothing, used to keep timing uniform. */
    private static final String DUMMY_HASH =
            "$2a$12$0000000000000000000000000000000000000000000000000000u";

    private final JdbcClient elevatedJdbc;
    private final PasswordEncoder passwordEncoder;
    private final IdentityService identityService;

    AuthenticationServiceImpl(@Qualifier("elevatedJdbcClient") JdbcClient elevatedJdbc,
                              PasswordEncoder passwordEncoder,
                              IdentityService identityService) {
        this.elevatedJdbc = elevatedJdbc;
        this.passwordEncoder = passwordEncoder;
        this.identityService = identityService;
    }

    private record UserRow(UUID id, UUID orgId, String displayName, String passwordHash,
                           boolean platformOperator, boolean active, String orgStatus) {
    }

    @Override
    public Optional<Principal> authenticate(Credentials credentials) {
        Optional<UserRow> row = elevatedJdbc.sql("""
                SELECT u.id, u.org_id, u.display_name, u.password_hash,
                       u.is_platform_operator, u.active, o.status AS org_status
                FROM platform.app_user u
                LEFT JOIN platform.organization o ON o.id = u.org_id
                WHERE lower(u.email) = lower(?)
                """)
                .param(credentials.email())
                .query((rs, rowNum) -> new UserRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("org_id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("password_hash"),
                        rs.getBoolean("is_platform_operator"),
                        rs.getBoolean("active"),
                        rs.getString("org_status")))
                .optional();

        if (row.isEmpty()) {
            // Still run a hash so a missing account and a wrong password cost the same.
            // Otherwise response latency answers "does this email exist?" for free.
            passwordEncoder.matches(credentials.password(), DUMMY_HASH);
            return Optional.empty();
        }
        UserRow user = row.get();

        // FR-USER-003: a deactivated user cannot authenticate at all.
        if (!user.active() || user.passwordHash() == null
                || !passwordEncoder.matches(credentials.password(), user.passwordHash())) {
            return Optional.empty();
        }

        // FR-PLAT-005: a suspended tenant keeps administrative login but loses the POS.
        boolean pos = credentials.clientType() == ClientType.POS;
        if (!user.platformOperator() && "SUSPENDED".equals(user.orgStatus()) && pos) {
            throw ApiException.forbidden("Organization is suspended; POS operation is unavailable");
        }
        if (!user.platformOperator() && "CLOSED".equals(user.orgStatus())) {
            throw ApiException.forbidden("Organization is closed");
        }

        // A platform operator has no tenant, so there is nothing to resolve against.
        IdentityService.Resolution resolution = user.platformOperator()
                ? new IdentityService.Resolution(Set.of("platform.tenants", "platform.health"), Set.of())
                : identityService.resolve(user.id(), user.orgId());

        Principal.PrincipalType type = user.platformOperator()
                ? Principal.PrincipalType.PLATFORM_OPERATOR
                : (pos ? Principal.PrincipalType.POS_TERMINAL : Principal.PrincipalType.USER_SESSION);

        return Optional.of(new Principal(type, user.id(), user.orgId(), user.displayName(),
                resolution.shopScope(), resolution.authorities(),
                credentials.terminalCode(), Instant.now()));
    }

    @Override
    public void recordLogin(Principal principal) {
        elevatedJdbc.sql("UPDATE platform.app_user SET last_login_at = now() WHERE id = ?")
                .param(principal.userId())
                .update();
    }
}

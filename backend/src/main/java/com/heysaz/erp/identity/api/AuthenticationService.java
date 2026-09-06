package com.heysaz.erp.identity.api;

import java.util.Optional;

import com.heysaz.erp.platform.tenant.Principal;

/**
 * Credential verification, separated from the HTTP layer.
 *
 * <p>It lives behind this interface because authentication reads {@code platform.app_user}
 * on the elevated connection, and a controller holding a {@code JdbcClient} is how
 * transaction boundaries and connection-pool choices go missing. {@code ModuleBoundaryTest}
 * enforces the separation.
 */
public interface AuthenticationService {

    enum ClientType { ADMIN, POS }

    record Credentials(String email, String password, ClientType clientType, String terminalCode,
                       /** Second factor (TOTP or recovery code); null on the first step. */
                       String mfaCode) {
    }

    /**
     * Verifies credentials and resolves the caller's authorities and shop scope.
     * Empty means "not authenticated" — deliberately not distinguishing an unknown account
     * from a wrong password, since only an attacker enumerating accounts benefits from the
     * difference.
     *
     * @throws com.heysaz.erp.platform.error.ApiException when the organization's status
     *         forbids this kind of login (FR-PLAT-005)
     */
    Optional<Principal> authenticate(Credentials credentials);

    /** FR-AUTH-006: last-login metadata, recorded without touching the credential. */
    void recordLogin(Principal principal);
}

package com.heysaz.erp.identity.api;

import java.util.List;
import java.util.UUID;

/**
 * Multi-factor authentication enrollment and verification (FR-AUTH-005, FR-AUTH-007).
 *
 * <p>Enrollment is two steps: {@link #beginEnrollment} issues a secret the authenticator app
 * imports, and {@link #confirmEnrollment} turns MFA on only once the user proves they can
 * generate a valid code — so a mistyped setup can never lock an account out. Confirmation
 * returns single-use recovery codes, shown once. {@link #verify} is what authentication calls
 * for the second factor, accepting a TOTP code or an unused recovery code.
 */
public interface MfaService {

    record EnrollmentView(String secret, String provisioningUri) {
    }

    /** Plaintext recovery codes, returned once at confirmation or regeneration. */
    record RecoveryCodesView(List<String> recoveryCodes) {
    }

    record MfaStatusView(boolean enabled, boolean enrollmentPending, int unusedRecoveryCodes) {
    }

    EnrollmentView beginEnrollment(UUID userId, String accountLabel);

    RecoveryCodesView confirmEnrollment(UUID userId, String code);

    RecoveryCodesView regenerateRecoveryCodes(UUID userId, String code);

    void disable(UUID userId, String code);

    MfaStatusView status(UUID userId);

    boolean isEnabled(UUID userId);

    /** Verifies a TOTP code or consumes an unused recovery code. Used during authentication. */
    boolean verify(UUID userId, String code);

    void setOrgMfaRequired(UUID orgId, boolean required);

    boolean isOrgMfaRequired(UUID orgId);
}

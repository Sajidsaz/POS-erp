package com.heysaz.erp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.heysaz.erp.identity.api.AuthenticationService;
import com.heysaz.erp.identity.api.AuthenticationService.ClientType;
import com.heysaz.erp.identity.api.AuthenticationService.Credentials;
import com.heysaz.erp.identity.api.IdentityService;
import com.heysaz.erp.identity.api.MfaService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.security.TotpService;
import com.heysaz.erp.platform.tenant.TenantContext;
import com.heysaz.erp.support.AbstractIntegrationTest;

/**
 * Milestone 6 — multi-factor authentication (FR-AUTH-005, FR-AUTH-007): enrollment, the
 * second-factor gate at login, single-use recovery codes, and org-policy enforcement.
 */
class MfaAuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired AuthenticationService authService;
    @Autowired MfaService mfaService;
    @Autowired TotpService totp;
    @Autowired IdentityService identityService;
    @Autowired PasswordEncoder passwordEncoder;

    private static final String PASSWORD = "correct horse battery";

    private UUID createUserWithPassword(UUID orgId, String email) {
        UUID id = UUID.randomUUID();
        elevatedJdbc.sql("""
                INSERT INTO platform.app_user (id, org_id, email, password_hash, display_name)
                VALUES (?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(email).param(passwordEncoder.encode(PASSWORD))
                .param("User " + email)
                .update();
        return id;
    }

    private Credentials creds(String email, String mfaCode) {
        return new Credentials(email, PASSWORD, ClientType.ADMIN, null, mfaCode);
    }

    private String enrollAndConfirm(UUID userId) {
        MfaService.EnrollmentView enrollment = mfaService.beginEnrollment(userId, "u@test");
        assertThat(enrollment.provisioningUri()).startsWith("otpauth://totp/");
        String code = totp.currentCode(enrollment.secret(), System.currentTimeMillis());
        mfaService.confirmEnrollment(userId, code);
        return enrollment.secret();
    }

    @Test
    void mfa_gate_requires_a_valid_second_factor_after_the_password() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID orgId = createProvisionedOrganization("MFA-" + suffix);
        String email = "totp-" + suffix + "@heysaz.test";
        UUID userId = createUserWithPassword(orgId, email);
        String secret = enrollAndConfirm(userId);

        // Password alone is no longer enough: the caller is told to supply a code.
        assertThatThrownBy(() -> authService.authenticate(creds(email, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ApiException.ErrorCode.MFA_REQUIRED));

        // A valid TOTP completes the login.
        String code = totp.currentCode(secret, System.currentTimeMillis());
        assertThat(authService.authenticate(creds(email, code)))
                .isPresent()
                .get()
                .satisfies(p -> assertThat(p.userId()).isEqualTo(userId));

        // A wrong code is an ordinary authentication failure.
        assertThat(authService.authenticate(creds(email, "000000"))).isEmpty();
    }

    @Test
    void a_recovery_code_works_once_and_is_then_spent() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID orgId = createProvisionedOrganization("MFR-" + suffix);
        String email = "rec-" + suffix + "@heysaz.test";
        UUID userId = createUserWithPassword(orgId, email);

        MfaService.EnrollmentView enrollment = mfaService.beginEnrollment(userId, email);
        String setupCode = totp.currentCode(enrollment.secret(), System.currentTimeMillis());
        List<String> recovery = mfaService.confirmEnrollment(userId, setupCode).recoveryCodes();
        assertThat(recovery).hasSize(10);

        String oneCode = recovery.getFirst();
        assertThat(authService.authenticate(creds(email, oneCode))).isPresent();
        // The same recovery code cannot be used a second time.
        assertThat(authService.authenticate(creds(email, oneCode))).isEmpty();
    }

    @Test
    void org_policy_makes_mfa_mandatory_for_privileged_accounts() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID orgId = createProvisionedOrganization("MFP-" + suffix);
        String email = "owner-" + suffix + "@heysaz.test";
        UUID userId = createUserWithPassword(orgId, email);

        // Make the user an Owner (holds user management, so it is a privileged account).
        TenantContext.set(principalFor(orgId, userId, "users_roles.write"));
        try {
            var owner = identityService.listRoles().stream()
                    .filter(r -> r.code().equals("OWNER")).findFirst().orElseThrow();
            identityService.assignRole(userId, owner.id());
        } finally {
            TenantContext.clear();
        }

        // Before the policy, an un-enrolled owner can sign in with just a password.
        assertThat(authService.authenticate(creds(email, null))).isPresent();

        mfaService.setOrgMfaRequired(orgId, true);

        // Now the same login is refused until they enrol.
        assertThatThrownBy(() -> authService.authenticate(creds(email, null)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ApiException.ErrorCode.FORBIDDEN);
                    assertThat(ex.getMessage()).contains("multi-factor");
                });

        // After enrolling, a code lets them back in.
        String secret = enrollAndConfirm(userId);
        String code = totp.currentCode(secret, System.currentTimeMillis());
        assertThat(authService.authenticate(creds(email, code))).isPresent();
    }
}

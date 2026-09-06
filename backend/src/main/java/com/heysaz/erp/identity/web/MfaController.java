package com.heysaz.erp.identity.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.heysaz.erp.identity.api.MfaService;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;

import jakarta.validation.constraints.NotBlank;

/**
 * Self-service MFA for the signed-in user, plus the organization policy toggle for
 * administrators (FR-AUTH-007). Enrollment and management always act on the current
 * principal — a user can only set up or remove their own second factor.
 */
@RestController
@RequestMapping("/api/v1/auth/mfa")
class MfaController {

    private final MfaService mfaService;

    MfaController(MfaService mfaService) {
        this.mfaService = mfaService;
    }

    record CodeRequest(@NotBlank String code) {
    }

    record PolicyRequest(boolean required) {
    }

    @PostMapping("/enroll")
    @PreAuthorize("isAuthenticated()")
    MfaService.EnrollmentView enroll() {
        Principal principal = TenantContext.requirePrincipal();
        return mfaService.beginEnrollment(principal.userId(), principal.displayName());
    }

    @PostMapping("/confirm")
    @PreAuthorize("isAuthenticated()")
    MfaService.RecoveryCodesView confirm(@RequestBody CodeRequest request) {
        return mfaService.confirmEnrollment(currentUserId(), request.code());
    }

    @PostMapping("/recovery-codes")
    @PreAuthorize("isAuthenticated()")
    MfaService.RecoveryCodesView regenerate(@RequestBody CodeRequest request) {
        return mfaService.regenerateRecoveryCodes(currentUserId(), request.code());
    }

    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    void disable(@RequestBody CodeRequest request) {
        mfaService.disable(currentUserId(), request.code());
    }

    @GetMapping("/status")
    @PreAuthorize("isAuthenticated()")
    MfaService.MfaStatusView status() {
        return mfaService.status(currentUserId());
    }

    @PutMapping("/policy")
    @PreAuthorize("hasAuthority('users_roles.write')")
    void setPolicy(@RequestBody PolicyRequest request) {
        mfaService.setOrgMfaRequired(TenantContext.requireOrgId(), request.required());
    }

    private java.util.UUID currentUserId() {
        return TenantContext.requirePrincipal().userId();
    }
}

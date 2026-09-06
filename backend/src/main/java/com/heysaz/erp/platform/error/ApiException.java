package com.heysaz.erp.platform.error;

import org.springframework.http.HttpStatus;

/** An error with a machine-readable code, per API-STD-002. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final HttpStatus status;

    public ApiException(ErrorCode code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public ErrorCode code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, what + " not found");
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, message);
    }

    /**
     * FR-API-011: the key was reused with a different body. Distinct from a plain
     * conflict so a client can tell "you already did this" from "you changed your mind
     * mid-retry", which are different bugs on the caller's side.
     */
    public static ApiException idempotencyMismatch() {
        return new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, HttpStatus.CONFLICT,
                "Idempotency key was already used with a different request body");
    }

    /**
     * FR-AUTH-005 / FR-AUTH-007: the password was correct but a second factor is needed.
     * Distinct from a plain authentication failure so the client knows to prompt for a code
     * and resubmit, rather than treating it as a wrong password.
     */
    public static ApiException mfaRequired() {
        return new ApiException(ErrorCode.MFA_REQUIRED, HttpStatus.UNAUTHORIZED,
                "A multi-factor authentication code is required");
    }

    public enum ErrorCode {
        VALIDATION_FAILED,
        UNAUTHENTICATED,
        MFA_REQUIRED,
        FORBIDDEN,
        NOT_FOUND,
        CONFLICT,
        IDEMPOTENCY_KEY_REUSED,
        INTERNAL
    }
}

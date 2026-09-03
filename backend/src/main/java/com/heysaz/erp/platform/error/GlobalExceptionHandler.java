package com.heysaz.erp.platform.error;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The single error envelope required by API-STD-002: a machine-readable code, a
 * human-readable message, and field-level detail where the failure is per-field.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record FieldError(String field, String message) {
    }

    public record ApiError(
            String code,
            String message,
            List<FieldError> details,
            String path,
            Instant timestamp) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException e, HttpServletRequest request) {
        return ResponseEntity.status(e.status())
                .body(new ApiError(e.code().name(), e.getMessage(), List.of(),
                        request.getRequestURI(), Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        List<FieldError> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(new ApiError(ApiException.ErrorCode.VALIDATION_FAILED.name(),
                        "Request validation failed", details,
                        request.getRequestURI(), Instant.now()));
    }

    /**
     * SEC-013: the stack trace goes to the log, never to the client. The response
     * carries nothing that describes internal structure.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(ApiException.ErrorCode.INTERNAL.name(),
                        "Internal error", List.of(), request.getRequestURI(), Instant.now()));
    }
}

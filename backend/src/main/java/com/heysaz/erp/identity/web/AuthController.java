package com.heysaz.erp.identity.web;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.heysaz.erp.identity.api.AuthenticationService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.security.PrincipalResolvingFilter;
import com.heysaz.erp.platform.security.TokenService;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * The three credential flows of Appendix D decision D3, entered from one endpoint.
 *
 * <p>Web concerns only: bind the request, choose between a session cookie and a token pair,
 * shape the response. Credential verification lives in {@code AuthenticationService},
 * because a controller holding a database client is how connection-pool choices and
 * transaction boundaries quietly go wrong.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password,
            AuthenticationService.ClientType clientType,
            /** Required for POS; identifies the terminal for FR-TERM-003 and idempotency scope. */
            String terminalCode,
            /** MFA code (TOTP or recovery); supplied after a first attempt returns MFA_REQUIRED. */
            String mfaCode) {
    }

    public record SessionResponse(UUID userId, UUID orgId, String displayName,
                                  String principalType, Set<String> authorities) {
    }

    public record TokenResponse(String accessToken, String refreshToken, long expiresInSeconds,
                                SessionResponse principal) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    private final AuthenticationService authentication;
    private final TokenService tokenService;
    private final LoginThrottle throttle;

    public AuthController(AuthenticationService authentication, TokenService tokenService,
                          LoginThrottle throttle) {
        this.authentication = authentication;
        this.tokenService = tokenService;
        this.throttle = throttle;
    }

    @PostMapping("/login")
    public Object login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        throttle.check(request.email(), http.getRemoteAddr());

        Principal principal = authentication
                .authenticate(new AuthenticationService.Credentials(
                        request.email(), request.password(),
                        request.clientType(), request.terminalCode(), request.mfaCode()))
                .orElseThrow(() -> {
                    throttle.recordFailure(request.email(), http.getRemoteAddr());
                    return new ApiException(ApiException.ErrorCode.UNAUTHENTICATED,
                            HttpStatus.UNAUTHORIZED, "Invalid credentials");
                });

        throttle.recordSuccess(request.email(), http.getRemoteAddr());
        authentication.recordLogin(principal);

        if (request.clientType() == AuthenticationService.ClientType.POS) {
            TokenService.TokenPair pair = tokenService.issue(principal);
            return new TokenResponse(pair.accessToken(), pair.refreshToken(),
                    pair.expiresInSeconds(), describe(principal));
        }
        // Admin: the credential is the session cookie, and CSRF applies from here on.
        http.getSession(true)
                .setAttribute(PrincipalResolvingFilter.SESSION_PRINCIPAL_ATTRIBUTE, principal);
        return describe(principal);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        TokenService.TokenPair pair = tokenService.refresh(request.refreshToken())
                .orElseThrow(() -> new ApiException(ApiException.ErrorCode.UNAUTHENTICATED,
                        HttpStatus.UNAUTHORIZED, "Refresh token is not valid"));
        return new TokenResponse(pair.accessToken(), pair.refreshToken(),
                pair.expiresInSeconds(), null);
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest http) {
        Optional.ofNullable(http.getSession(false)).ifPresent(session -> session.invalidate());
    }

    @GetMapping("/me")
    public SessionResponse me() {
        return describe(TenantContext.requirePrincipal());
    }

    private SessionResponse describe(Principal principal) {
        return new SessionResponse(principal.userId(), principal.orgId(), principal.displayName(),
                principal.type().name(), principal.authorities());
    }

    /**
     * FR-AUTH-004 and SEC-007: throttled per account and per source address, so that
     * neither spraying one password across many accounts nor many passwords at one
     * account gets a free run.
     */
    @Component
    public static class LoginThrottle {

        private static final int MAX_ATTEMPTS = 10;
        private static final Duration WINDOW = Duration.ofMinutes(15);

        private final StringRedisTemplate redis;

        public LoginThrottle(StringRedisTemplate redis) {
            this.redis = redis;
        }

        public void check(String email, String ip) {
            if (count("login:email:" + email.toLowerCase()) >= MAX_ATTEMPTS
                    || count("login:ip:" + ip) >= MAX_ATTEMPTS) {
                throw new ApiException(ApiException.ErrorCode.FORBIDDEN,
                        HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts; try again later");
            }
        }

        public void recordFailure(String email, String ip) {
            increment("login:email:" + email.toLowerCase());
            increment("login:ip:" + ip);
        }

        public void recordSuccess(String email, String ip) {
            redis.delete("login:email:" + email.toLowerCase());
            redis.delete("login:ip:" + ip);
        }

        private long count(String key) {
            String value = redis.opsForValue().get(key);
            return value == null ? 0 : Long.parseLong(value);
        }

        private void increment(String key) {
            Long value = redis.opsForValue().increment(key);
            if (value != null && value == 1L) {
                redis.expire(key, WINDOW);
            }
        }
    }
}

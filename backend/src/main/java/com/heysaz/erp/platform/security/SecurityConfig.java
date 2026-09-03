package com.heysaz.erp.platform.security;

import java.time.Instant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;

import jakarta.servlet.http.HttpServletRequest;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * FR-AUTH-002: an adaptive hash for passwords. Cost 12 is a deliberate choice over
     * the default 10 — logins are rare relative to API traffic, so the extra work lands
     * where nobody is waiting on it.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * A request authenticating with a bearer token is not cookie-authenticated, so CSRF
     * does not apply to it — the attack needs an ambient credential the browser attaches
     * automatically. SEC-006 scopes the protection to exactly the surface that has one:
     * the admin application's session cookie.
     */
    private static final RequestMatcher BEARER_REQUEST = (HttpServletRequest request) -> {
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith("Bearer ");
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, PrincipalResolvingFilter principalFilter)
            throws Exception {
        http
            .csrf(csrf -> csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .ignoringRequestMatchers(BEARER_REQUEST))
            .sessionManagement(session -> session
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .anyRequest().authenticated())
            .addFilterBefore(principalFilter,
                    org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(handling -> handling
                    .authenticationEntryPoint((request, response, e) ->
                            writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                                    "Authentication required", request.getRequestURI()))
                    .accessDeniedHandler((request, response, e) ->
                            writeError(response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                                    "Not permitted", request.getRequestURI())))
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable());
        return http.build();
    }

    /**
     * Errors from the security layer use the same envelope as everything else
     * (API-STD-002), and disclose nothing about whether the resource exists — which is
     * the behaviour acceptance scenario 11 checks for.
     */
    private static void writeError(jakarta.servlet.http.HttpServletResponse response,
                                   HttpStatus status, String code, String message, String path)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {"code":"%s","message":"%s","details":[],"path":"%s","timestamp":"%s"}"""
                .formatted(code, message, path, Instant.now()));
    }
}

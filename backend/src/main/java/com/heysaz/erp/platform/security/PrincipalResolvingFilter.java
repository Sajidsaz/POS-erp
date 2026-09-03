package com.heysaz.erp.platform.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Resolves the caller into a single {@link Principal}, whichever of the three credential
 * flows they used, and binds it for the request.
 *
 * <p>Order matters only in that the bearer header wins over a cookie: a request carrying
 * an explicit token is asking to be that principal.
 *
 * <p>The {@code finally} block is the important line in this class. {@link TenantContext}
 * is a ThreadLocal on a pooled request thread, so failing to clear it would hand this
 * request's tenant to whoever gets the thread next.
 */
@Component
public class PrincipalResolvingFilter extends OncePerRequestFilter {

    public static final String SESSION_PRINCIPAL_ATTRIBUTE = "erp.principal";

    private final TokenService tokenService;
    private final CredentialRevocationService revocation;

    public PrincipalResolvingFilter(TokenService tokenService,
                                    CredentialRevocationService revocation) {
        this.tokenService = tokenService;
        this.revocation = revocation;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            resolve(request)
                    // A role change, a deactivation or a disabled terminal invalidates
                    // everything issued before it, so a stale principal is simply not
                    // bound and the request continues as anonymous — which the security
                    // chain then refuses.
                    .filter(principal -> !revocation.isStale(principal))
                    .ifPresent(principal -> {
                        TenantContext.set(principal);
                        SecurityContextHolder.getContext()
                                .setAuthentication(authenticationFor(principal));
                    });
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private java.util.Optional<Principal> resolve(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            // The prefix says which store to ask, so a POS token is never checked against
            // the integration table and vice versa.
            if (token.startsWith("hs_at_")) {
                return tokenService.verifyAccessToken(token);
            }
            if (token.startsWith("hs_it_")) {
                return tokenService.verifyIntegrationToken(token);
            }
            return java.util.Optional.empty();
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object stored = session.getAttribute(SESSION_PRINCIPAL_ATTRIBUTE);
            if (stored instanceof Principal principal) {
                return java.util.Optional.of(principal);
            }
        }
        return java.util.Optional.empty();
    }

    private UsernamePasswordAuthenticationToken authenticationFor(Principal principal) {
        List<SimpleGrantedAuthority> authorities = principal.authorities().stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }
}

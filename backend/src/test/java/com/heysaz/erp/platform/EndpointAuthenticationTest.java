package com.heysaz.erp.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.heysaz.erp.support.AbstractIntegrationTest;

/**
 * SEC-015 and acceptance scenario 11, in the form that keeps working as the API grows.
 *
 * <p>Rather than listing endpoints — a list that goes stale the first time someone adds a
 * controller — this walks Spring's own mapping registry. A new endpoint is covered the
 * moment it exists, and an endpoint accidentally left open fails the build rather than
 * waiting to be noticed in production.
 */
class EndpointAuthenticationTest extends AbstractIntegrationTest {

    /** The only endpoints allowed to answer an anonymous caller. */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/actuator/health");

    // Actuator contributes a second RequestMappingHandlerMapping, so this must be
    // qualified or the context fails to start with NoUniqueBeanDefinitionException.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    TestRestTemplate rest;

    @Test
    void every_non_public_endpoint_rejects_an_anonymous_caller() {
        List<String> failures = handlerMapping.getHandlerMethods().entrySet().stream()
                .flatMap(entry -> expand(entry.getKey(), entry.getValue()).stream())
                .filter(call -> !isPublic(call.path()))
                .map(this::checkRejected)
                .filter(java.util.Objects::nonNull)
                .toList();

        assertThat(failures)
                .as("every protected endpoint must enforce authorization server-side (FR-PERM-001)")
                .isEmpty();
    }

    @Test
    void the_endpoint_registry_is_actually_being_walked() {
        // A guard against the test silently passing because it found nothing to check.
        long protectedEndpoints = handlerMapping.getHandlerMethods().entrySet().stream()
                .flatMap(e -> expand(e.getKey(), e.getValue()).stream())
                .filter(call -> !isPublic(call.path()))
                .count();
        assertThat(protectedEndpoints).isGreaterThan(0);
    }

    private record Call(HttpMethod method, String path) {
    }

    private List<Call> expand(RequestMappingInfo info, HandlerMethod handler) {
        Set<String> patterns = info.getPathPatternsCondition() == null
                ? Set.of()
                : info.getPathPatternsCondition().getPatternValues();
        Set<org.springframework.web.bind.annotation.RequestMethod> methods =
                info.getMethodsCondition().getMethods();

        return patterns.stream()
                .filter(p -> p.startsWith("/api/") || p.startsWith("/actuator/"))
                .flatMap(pattern -> (methods.isEmpty()
                        ? Set.of(org.springframework.web.bind.annotation.RequestMethod.GET)
                        : methods).stream()
                        .map(m -> new Call(HttpMethod.valueOf(m.name()), substitute(pattern))))
                .toList();
    }

    /** Path variables get a well-formed but nonexistent value; the call must still be refused. */
    private static String substitute(String pattern) {
        return pattern.replaceAll("\\{[^/}]+}", UUID.randomUUID().toString());
    }

    private static boolean isPublic(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    private String checkRejected(Call call) {
        ResponseEntity<String> response =
                rest.exchange(call.path(), call.method(), null, String.class);
        HttpStatus status = HttpStatus.valueOf(response.getStatusCode().value());

        // 401 is the expected answer. 403 is accepted because CSRF rejects an unauthenticated
        // state-changing call before authentication is even reached, which is also a refusal.
        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            return null;
        }
        return "%s %s answered %s to an anonymous caller".formatted(call.method(), call.path(), status);
    }
}

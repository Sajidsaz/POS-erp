package com.heysaz.erp.support;

import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;

/**
 * TEST-002: integration tests run against a real PostgreSQL.
 *
 * <p>Not a preference. Row-level security, {@code SET LOCAL}, {@code ON CONFLICT DO
 * NOTHING} and {@code FOR UPDATE SKIP LOCKED} are the load-bearing mechanics of this
 * system and none of them exists in an in-memory database, so a test that ran on one
 * would be testing something we do not ship.
 *
 * <p>Containers are started once for the whole suite rather than per class — the JVM
 * tears them down at exit, which is what Ryuk is for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("heysaz")
                    .withUsername("heysaz")
                    .withPassword("heysaz");

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // The application connects as the RLS-bound role, exactly as it does in production.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "erp_app");
        registry.add("spring.datasource.password", () -> "erp_app");

        // Flyway and the elevated pool connect as the owner.
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("erp.datasource.elevated.url", POSTGRES::getJdbcUrl);
        registry.add("erp.datasource.elevated.username", POSTGRES::getUsername);
        registry.add("erp.datasource.elevated.password", POSTGRES::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        // The worker would otherwise race the assertions in outbox tests.
        registry.add("erp.outbox.poll-interval-ms", () -> "600000");
    }

    @Autowired
    @Qualifier("elevatedJdbcClient")
    protected JdbcClient elevatedJdbc;

    /** Creates an organization directly, bypassing tenant context — M1 adds the real API. */
    protected UUID createOrganization(String code) {
        UUID id = UUID.randomUUID();
        elevatedJdbc.sql("""
                INSERT INTO platform.organization (id, code, name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """)
                .param(id).param(code).param("Org " + code)
                .update();
        return id;
    }

    protected Principal principalFor(UUID orgId, String... authorities) {
        return new Principal(Principal.PrincipalType.USER_SESSION, UUID.randomUUID(), orgId,
                "test user", Set.of(), Set.of(authorities), "T1", java.time.Instant.now());
    }

    /**
     * A principal whose {@code userId} is a real {@code platform.app_user} row. Needed
     * wherever the effect writes a foreign key to the acting user — a sale's cashier, a
     * shift's opener — rather than merely reading tenant context.
     */
    protected Principal principalFor(UUID orgId, UUID userId, String... authorities) {
        return new Principal(Principal.PrincipalType.USER_SESSION, userId, orgId,
                "test user", Set.of(), Set.of(authorities), "T1", java.time.Instant.now());
    }

    /** Inserts a real user, bypassing tenant context, so acting-user foreign keys resolve. */
    protected UUID createUser(UUID orgId, String email) {
        UUID id = UUID.randomUUID();
        elevatedJdbc.sql("""
                INSERT INTO platform.app_user (id, org_id, email, display_name)
                VALUES (?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(email).param("User " + email)
                .update();
        return id;
    }

    /** Provisions an organization with the eight standard roles seeded from V5. */
    protected UUID createProvisionedOrganization(String code) {
        UUID orgId = createOrganization(code);
        identityService.provisionRoles(orgId);
        return orgId;
    }

    @Autowired
    protected com.heysaz.erp.identity.api.IdentityService identityService;

    /** Runs a body as if a request from {@code orgId} were in flight. */
    protected void asTenant(UUID orgId, Runnable body) {
        TenantContext.set(principalFor(orgId, "expenses.read", "expenses.write"));
        try {
            body.run();
        } finally {
            TenantContext.clear();
        }
    }
}

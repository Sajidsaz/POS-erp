package com.heysaz.erp.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.heysaz.erp.integration.api.IntegrationService;
import com.heysaz.erp.platform.outbox.OutboxPublisher;
import com.heysaz.erp.platform.outbox.OutboxWorker;
import com.heysaz.erp.platform.outbox.WebhookOutboxDispatcher;
import com.heysaz.erp.platform.outbox.WebhookSigner;
import com.heysaz.erp.platform.tenant.TenantContext;
import com.heysaz.erp.support.AbstractIntegrationTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * FR-API-002 / FR-API-003: the outbox's signed webhook delivery.
 *
 * <p>Drives the dispatcher directly against a real in-JVM HTTP endpoint rather than through
 * a full outbox drain, so the assertions are about one event's delivery and not whatever
 * backlog the rest of the suite has left unpublished.
 */
class WebhookDeliveryIntegrationTest extends AbstractIntegrationTest {

    @Autowired WebhookOutboxDispatcher dispatcher;
    @Autowired OutboxPublisher outboxPublisher;
    @Autowired IntegrationService integrationService;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("transactionManager")
    org.springframework.transaction.PlatformTransactionManager txManager;

    /** A captured inbound webhook request. */
    private record Received(String body, String signature, String eventType, String eventId) {
    }

    private HttpServer startServer(int responseCode, AtomicReference<Received> sink, CountDownLatch latch)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", (HttpExchange exchange) -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            sink.set(new Received(body,
                    exchange.getRequestHeaders().getFirst(WebhookSigner.SIGNATURE_HEADER),
                    exchange.getRequestHeaders().getFirst("X-HeySaz-Event"),
                    exchange.getRequestHeaders().getFirst("X-HeySaz-Event-Id")));
            exchange.sendResponseHeaders(responseCode, -1);
            exchange.close();
            latch.countDown();
        });
        server.start();
        return server;
    }

    private String createSubscription(UUID orgId, UUID userId, String url, List<String> eventTypes) {
        TenantContext.set(principalFor(orgId, userId, "api_tokens.read", "api_tokens.write"));
        try {
            return integrationService.createSubscription(
                    new IntegrationService.CreateSubscriptionCommand(url, eventTypes, "test hook")).secret();
        } finally {
            TenantContext.clear();
        }
    }

    private UUID publishEvent(UUID orgId, UUID userId, String eventType) {
        TenantContext.set(principalFor(orgId, userId, "api_tokens.read"));
        try {
            return new org.springframework.transaction.support.TransactionTemplate(txManager)
                    .execute(status -> outboxPublisher.publish(
                            eventType, "Test", "agg-1", Map.of("hello", "world")));
        } finally {
            TenantContext.clear();
        }
    }

    private OutboxWorker.PendingEvent pending(UUID eventId, UUID orgId, String eventType,
                                              String payload, int attempts) {
        return new OutboxWorker.PendingEvent(eventId, orgId, eventType, "Test", "agg-1", payload, attempts);
    }

    private String deliveryStatus(UUID eventId) {
        return elevatedJdbc.sql("SELECT status FROM app.webhook_delivery WHERE outbox_event_id = ?")
                .param(eventId).query(String.class).single();
    }

    @Test
    void a_committed_event_is_delivered_signed_and_recorded() throws Exception {
        UUID orgId = createProvisionedOrganization("WH-" + UUID.randomUUID().toString().substring(0, 8));
        UUID userId = createUser(orgId, "hook-" + orgId + "@heysaz.test");

        AtomicReference<Received> sink = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        HttpServer server = startServer(200, sink, latch);
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            String secret = createSubscription(orgId, userId, url, List.of("pos.sale_completed"));
            UUID eventId = publishEvent(orgId, userId, "pos.sale_completed");
            String payload = "{\"hello\":\"world\"}";

            dispatcher.dispatch(pending(eventId, orgId, "pos.sale_completed", payload, 0));

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            Received got = sink.get();
            assertThat(got.eventType()).isEqualTo("pos.sale_completed");
            assertThat(got.eventId()).isEqualTo(eventId.toString());
            assertThat(got.body()).isEqualTo(payload);

            // FR-API-002: the signature is HMAC-SHA256 over "<timestamp>.<body>".
            assertThat(got.signature()).startsWith("t=").contains(",v1=");
            String[] parts = got.signature().split(",");
            long ts = Long.parseLong(parts[0].substring("t=".length()));
            String v1 = parts[1].substring("v1=".length());
            assertThat(v1).isEqualTo(WebhookSigner.hmacSha256Hex(secret, ts + "." + payload));

            assertThat(deliveryStatus(eventId)).isEqualTo("DELIVERED");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void a_failing_endpoint_marks_the_delivery_failed_and_signals_a_retry() throws Exception {
        UUID orgId = createProvisionedOrganization("WHF-" + UUID.randomUUID().toString().substring(0, 8));
        UUID userId = createUser(orgId, "hookf-" + orgId + "@heysaz.test");

        AtomicReference<Received> sink = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        HttpServer server = startServer(500, sink, latch);
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            createSubscription(orgId, userId, url, List.of("pos.sale_completed"));
            UUID eventId = publishEvent(orgId, userId, "pos.sale_completed");

            // A 5xx makes the dispatcher throw so the outbox worker will retry the event.
            assertThatThrownBy(() -> dispatcher.dispatch(
                    pending(eventId, orgId, "pos.sale_completed", "{\"hello\":\"world\"}", 0)))
                    .isInstanceOf(WebhookOutboxDispatcher.WebhookDeliveryException.class);

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(deliveryStatus(eventId)).isEqualTo("FAILED");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void the_final_attempt_dead_letters_a_still_failing_delivery() throws Exception {
        UUID orgId = createProvisionedOrganization("WHD-" + UUID.randomUUID().toString().substring(0, 8));
        UUID userId = createUser(orgId, "hookd-" + orgId + "@heysaz.test");

        AtomicReference<Received> sink = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        HttpServer server = startServer(500, sink, latch);
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            createSubscription(orgId, userId, url, List.of("*"));
            UUID eventId = publishEvent(orgId, userId, "pos.sale_completed");

            // attempts = 11 means this is the 12th and final attempt (MAX_ATTEMPTS).
            assertThatThrownBy(() -> dispatcher.dispatch(
                    pending(eventId, orgId, "pos.sale_completed", "{\"hello\":\"world\"}", 11)))
                    .isInstanceOf(WebhookOutboxDispatcher.WebhookDeliveryException.class);

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(deliveryStatus(eventId)).isEqualTo("DEAD");
        } finally {
            server.stop(0);
        }
    }
}

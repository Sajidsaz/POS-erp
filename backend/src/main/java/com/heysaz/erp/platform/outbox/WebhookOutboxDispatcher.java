package com.heysaz.erp.platform.outbox;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.heysaz.erp.platform.outbox.OutboxWorker.OutboxDispatcher;
import com.heysaz.erp.platform.outbox.OutboxWorker.PendingEvent;

/**
 * FR-API-002 / FR-API-003: delivers each committed outbox event to the tenant's active
 * webhook subscriptions, signed, with the outbox worker's backoff behind it.
 *
 * <p>Like {@link OutboxWorker} this spans tenants, so it reads and writes on the elevated,
 * RLS-bypassing connection — the escape hatch named in decision D1, and it runs inside the
 * worker's transaction. Every (subscription, event) pair gets one {@code webhook_delivery}
 * row: a subscriber that already succeeded is marked {@code DELIVERED} and skipped when a
 * later retry re-runs this event for a sibling subscriber that failed, so no endpoint is
 * ever sent the same event twice. If any subscriber fails, this throws so the worker retries
 * the event; on the event's final attempt the still-failed deliveries are moved to the
 * visible {@code DEAD} state (FR-API-003).
 */
@Component
@Primary
public class WebhookOutboxDispatcher implements OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookOutboxDispatcher.class);

    /** Kept in step with {@link OutboxWorker}'s cap so dead-lettering lines up. */
    private static final int MAX_ATTEMPTS = 12;

    private final JdbcClient elevatedJdbc;
    private final HttpClient http;
    private final Duration requestTimeout;

    WebhookOutboxDispatcher(@Qualifier("elevatedJdbcClient") JdbcClient elevatedJdbc,
                            @Value("${erp.webhooks.connect-timeout-ms:3000}") long connectTimeoutMs,
                            @Value("${erp.webhooks.request-timeout-ms:5000}") long requestTimeoutMs) {
        this.elevatedJdbc = elevatedJdbc;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
    }

    private record Subscription(UUID id, String url, String secret) {
    }

    @Override
    public void dispatch(PendingEvent event) {
        List<Subscription> subs = subscriptionsFor(event);
        if (subs.isEmpty()) {
            return; // Nothing subscribed: the event is considered delivered.
        }

        boolean isFinalAttempt = event.attempts() + 1 >= MAX_ATTEMPTS;
        int failures = 0;
        for (Subscription sub : subs) {
            if (!deliverTo(sub, event, isFinalAttempt)) {
                failures++;
            }
        }
        if (failures > 0) {
            throw new WebhookDeliveryException(failures + " of " + subs.size()
                    + " webhook deliveries failed for event " + event.id());
        }
    }

    private boolean deliverTo(Subscription sub, PendingEvent event, boolean isFinalAttempt) {
        // One delivery row per (subscription, event); a retry updates it in place.
        UUID deliveryId = UUID.randomUUID();
        elevatedJdbc.sql("""
                INSERT INTO app.webhook_delivery
                    (id, org_id, subscription_id, outbox_event_id, event_type, url, status)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
                ON CONFLICT (subscription_id, outbox_event_id) DO NOTHING
                """)
                .param(deliveryId).param(event.orgId()).param(sub.id()).param(event.id())
                .param(event.eventType()).param(sub.url())
                .update();

        String status = elevatedJdbc.sql("""
                SELECT status FROM app.webhook_delivery
                WHERE subscription_id = ? AND outbox_event_id = ?
                """)
                .param(sub.id()).param(event.id())
                .query(String.class)
                .single();
        if ("DELIVERED".equals(status)) {
            return true; // A previous attempt already reached this subscriber.
        }

        long timestamp = Instant.now().getEpochSecond();
        String signature = WebhookSigner.signatureHeader(sub.secret(), timestamp, event.payload());
        HttpRequest request = HttpRequest.newBuilder(URI.create(sub.url()))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header(WebhookSigner.SIGNATURE_HEADER, signature)
                .header("X-HeySaz-Event", event.eventType())
                .header("X-HeySaz-Event-Id", event.id().toString())
                .POST(HttpRequest.BodyPublishers.ofString(event.payload()))
                .build();

        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                markDelivered(sub.id(), event.id(), code);
                return true;
            }
            markFailed(sub.id(), event.id(), code, "HTTP " + code, isFinalAttempt);
            return false;
        } catch (Exception e) {
            log.warn("Webhook POST to {} failed for event {}: {}", sub.url(), event.id(), e.toString());
            markFailed(sub.id(), event.id(), null, e.getClass().getSimpleName() + ": " + e.getMessage(),
                    isFinalAttempt);
            return false;
        }
    }

    private List<Subscription> subscriptionsFor(PendingEvent event) {
        return elevatedJdbc.sql("""
                SELECT id, url, secret FROM app.webhook_subscription
                WHERE org_id = ? AND active
                  AND (? = ANY(event_types) OR '*' = ANY(event_types))
                """)
                .param(event.orgId())
                .param(event.eventType())
                .query((rs, rowNum) -> new Subscription(
                        rs.getObject("id", UUID.class),
                        rs.getString("url"),
                        rs.getString("secret")))
                .list();
    }

    private void markDelivered(UUID subscriptionId, UUID eventId, int responseStatus) {
        elevatedJdbc.sql("""
                UPDATE app.webhook_delivery
                SET status = 'DELIVERED', response_status = ?, attempts = attempts + 1,
                    delivered_at = now(), last_error = NULL
                WHERE subscription_id = ? AND outbox_event_id = ?
                """)
                .param(responseStatus).param(subscriptionId).param(eventId)
                .update();
    }

    private void markFailed(UUID subscriptionId, UUID eventId, Integer responseStatus,
                            String error, boolean isFinalAttempt) {
        elevatedJdbc.sql("""
                UPDATE app.webhook_delivery
                SET status = ?, response_status = ?, attempts = attempts + 1, last_error = ?
                WHERE subscription_id = ? AND outbox_event_id = ?
                """)
                .param(isFinalAttempt ? "DEAD" : "FAILED")
                .param(responseStatus)
                .param(error)
                .param(subscriptionId).param(eventId)
                .update();
    }

    public static class WebhookDeliveryException extends RuntimeException {
        WebhookDeliveryException(String message) {
            super(message);
        }
    }
}
